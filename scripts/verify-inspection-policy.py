#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Conservative source guard; APK/Gradle checks still establish build behavior.

Recognizes the deliberately small audited central policy, rather than attempting
to interpret arbitrary Kotlin. New ways to change window flags require review.
"""
import argparse
import os
from pathlib import Path
import re
import selectors
import signal
import subprocess
import time
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path("apps/android/app/src")
POLICY = SOURCE / "main/kotlin/net/fstab/tachiai/platform/web/DeveloperAccess.kt"
ANDROID = "{http://schemas.android.com/apk/res/android}"
APK_OUTPUT_LIMIT = 32 * 1024 * 1024
APK_TIMEOUT_SECONDS = 60


class PolicyFailure(ValueError):
    pass


def uncomment(text):
    """Keep quoted strings intact and remove Kotlin/Java comments, including nesting."""
    pattern = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*')
    result = []
    cursor = 0
    while match := pattern.search(text, cursor):
        result.append(text[cursor:match.start()])
        token = match.group()
        cursor = match.end()
        if token == "/*":
            depth = 1
            while depth:
                boundary = re.search(r"/\*|\*/", text[cursor:])
                if boundary is None:
                    raise PolicyFailure("UNTERMINATED_COMMENT")
                cursor += boundary.end()
                depth += 1 if boundary.group() == "/*" else -1
            result.append(" ")
        elif token.startswith("//"):
            result.append(" ")
        else:
            result.append(token)
    result.append(text[cursor:])
    return "".join(result)


def compact(text):
    return re.sub(r"\s+", "", uncomment(text))


def call_arguments(text, name):
    for match in re.finditer(rf"\b{name}\s*\(", text):
        start = cursor = match.end()
        depth = 1
        while cursor < len(text) and depth:
            depth += (text[cursor] == "(") - (text[cursor] == ")")
            cursor += 1
        if depth:
            raise PolicyFailure("UNTERMINATED_FLAG_CALL")
        yield text[start:cursor - 1]


def verify_external_source(text):
    text = uncomment(text)
    if re.search(r"\bsetWebContentsDebuggingEnabled\b", text):
        raise PolicyFailure("DIRECT_WEB_INSPECTION_SETTER")
    for arguments in call_arguments(text, r"(?:addFlags|clearFlags|setFlags)"):
        if "FLAG_SECURE" in arguments:
            raise PolicyFailure("DIRECT_SECURE_FLAG_CALL")
        for literal in re.findall(r"(?<![\w.])(?:0[xX][0-9a-fA-F_]+|[0-9][0-9_]*)(?:[uUlL]*)\b", arguments):
            value = re.sub(r"[uUlL]+$", "", literal).replace("_", "")
            if int(value, 16 if value.lower().startswith("0x") else 10) & 0x2000:
                raise PolicyFailure("LITERAL_SECURE_FLAG_CALL")
    if re.search(r"\.\s*flags\s*(?:[|&^+\-]?=)(?!=)", text):
        raise PolicyFailure("DIRECT_FLAG_ASSIGNMENT")
    # The Twitch screen restores prior protection through the policy, so reading
    # the previous flag is allowed. Aliases/imports/other references are refused.
    text = re.sub(r"window\s*\.\s*attributes\s*\.\s*flags\s+and\s+"
                  r"WindowManager\s*\.\s*LayoutParams\s*\.\s*FLAG_SECURE\s*!=\s*0", "", text)
    if re.search(r"\bFLAG_SECURE\b", text):
        raise PolicyFailure("UNREVIEWED_SECURE_FLAG_REFERENCE")


def verify_policy(text):
    text = compact(text)
    screen = ("funprotectScreen(window:Window,protectedNormally:Boolean=true){"
              "if(protectedNormally&&!BuildConfig.LOCAL_INSPECTION){"
              "window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)"
              "}else{window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}}")
    web = ("funconfigureWebInspection(enabledNormally:Boolean=false){"
           "WebView.setWebContentsDebuggingEnabled(BuildConfig.LOCAL_INSPECTION||enabledNormally)}")
    if screen not in text or web not in text:
        raise PolicyFailure("CENTRAL_POLICY_CHANGED")
    remainder = text.replace(screen, "", 1).replace(web, "", 1)
    verify_external_source(remainder)


def verify_build(text):
    text = compact(text)
    required = (
        'providers.gradleProperty("tachiaiLocalInspection").orNull.let{',
        'require(it==null||it=="true"||it=="false")',
        'it=="true"}',
        'valrunningInCi=listOf("CI","GITHUB_ACTIONS").any{',
        'providers.environmentVariable(it).orNull?.lowercase()!inlistOf(null,"","false","0")',
        'check(!localInspection||!runningInCi)',
        'beforeVariants(selector().withBuildType("inspect")){variant->variant.enable=localInspection',
        'buildConfigField("boolean","LOCAL_INSPECTION","false")',
        'manifestPlaceholders["localInspectionEnabled"]="false"',
        'sourceSets.getByName("inspect"){kotlin.directories+="src/debug/kotlin"',
    )
    if any(fragment not in text for fragment in required):
        raise PolicyFailure("BUILD_GUARD_CHANGED")
    if text.count('buildConfigField("boolean","LOCAL_INSPECTION","true")') != 1:
        raise PolicyFailure("INSPECTION_FIELD_COUNT")


def verify_manifest(text):
    root = ET.fromstring(text)
    components = [node for node in root.iter("activity")
                  if node.get(ANDROID + "name") == ".feature.diagnostic.InspectionControlActivity"]
    if len(components) != 1 or components[0].get(ANDROID + "enabled") != "${localInspectionEnabled}":
        raise PolicyFailure("INSPECTION_ENTRY_GUARD")


def manifest_classes(text):
    """Aliases name no implementation class; their target activity does."""
    root = ET.fromstring(text)
    names = {"net.fstab.tachiai.MainActivity", "net.fstab.tachiai.platform.web.DeveloperAccess"}
    for node in root.iter():
        if node.tag not in {"activity", "activity-alias", "service", "receiver", "provider"}:
            continue
        name = node.get(ANDROID + ("targetActivity" if node.tag == "activity-alias" else "name"))
        if not name:
            raise PolicyFailure("MANIFEST_COMPONENT_NAME")
        name = "net.fstab.tachiai" + name if name.startswith(".") else name
        if "." not in name:
            name = "net.fstab.tachiai." + name
        if name.startswith("net.fstab.tachiai."):
            names.add(name)
    return names


def defined_classes(output):
    """Parse SDK dex packages rows: type/state/defined/referenced/bytes/name.

    Only C d rows establish an implementation; packages, references, member rows
    and similarly named nested classes cannot satisfy a required component.
    Format: https://developer.android.com/tools/apkanalyzer
    """
    if len(output) > APK_OUTPUT_LIMIT:
        raise PolicyFailure("APK_ANALYZER_OUTPUT_LIMIT")
    try:
        text = output.decode("utf-8")
    except UnicodeError:
        raise PolicyFailure("APK_ANALYZER_ENCODING") from None
    result = set()
    for line in text.splitlines():
        match = re.fullmatch(r"\s*([PCMF])\s+([dxkr])\s+\d+\s+\d+\s+\d+\s+(.+?)\s*", line)
        if match is None:
            raise PolicyFailure("APK_ANALYZER_ROW")
        kind, state, name = match.groups()
        if kind == "C" and state == "d":
            if any(character.isspace() for character in name) or name in result:
                raise PolicyFailure("APK_ANALYZER_CLASS")
            result.add(name)
    if not result:
        raise PolicyFailure("APK_ANALYZER_NO_CLASSES")
    return result


def bounded_analyzer(command):
    """Bound both pipes and wall time; never print raw tool output on failures."""
    try:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   start_new_session=True)
    except OSError:
        raise PolicyFailure("APK_ANALYZER_START") from None
    output = bytearray()
    total = 0
    reaped = False
    deadline = time.monotonic() + APK_TIMEOUT_SECONDS
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ, True)
            selector.register(process.stderr, selectors.EVENT_READ, False)
            while selector.get_map():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise PolicyFailure("APK_ANALYZER_TIMEOUT")
                for key, _ in selector.select(remaining):
                    chunk = os.read(key.fd, 65536)
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    total += len(chunk)
                    if total > APK_OUTPUT_LIMIT:
                        raise PolicyFailure("APK_ANALYZER_OUTPUT_LIMIT")
                    if key.data:
                        output.extend(chunk)
            try:
                status = process.wait(timeout=max(0.001, deadline - time.monotonic()))
                reaped = True
            except subprocess.TimeoutExpired:
                raise PolicyFailure("APK_ANALYZER_TIMEOUT") from None
            if status != 0:
                raise PolicyFailure("APK_ANALYZER_EXIT")
    finally:
        # The SDK shell launcher can have a Java child holding the pipes open.
        if not reaped:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        process.wait()
        process.stdout.close()
        process.stderr.close()
    return bytes(output)


def verify_apk(apk, analyzer):
    if not apk.is_file() or not analyzer.is_file():
        raise PolicyFailure("APK_ANALYZER_INPUT")
    expected = manifest_classes((ROOT / SOURCE / "debug/AndroidManifest.xml").read_text())
    actual = defined_classes(bounded_analyzer([str(analyzer.resolve()), "dex", "packages", "--defined-only", str(apk.resolve())]))
    missing = expected - actual
    if missing:
        raise PolicyFailure("APK_COMPONENT_CLASSES_MISSING: " + ", ".join(sorted(missing)))
    print(f"INSPECTION_APK_COMPONENTS_DEFINED: {len(expected)}")


def verify_repository(root):
    verify_policy((root / POLICY).read_text())
    for source in sorted((root / SOURCE).rglob("*")):
        if source.suffix not in (".kt", ".java") or source == root / POLICY:
            continue
        try:
            verify_external_source(source.read_text())
        except PolicyFailure as error:
            raise PolicyFailure(f"{source.relative_to(root)}: {error}") from None
    verify_build((root / "apps/android/app/build.gradle.kts").read_text())
    verify_manifest((root / SOURCE / "debug/AndroidManifest.xml").read_text())
    for workflow in (root / ".github/workflows").glob("*.y*ml"):
        if re.search(r"(?:assemble|bundle|install|test|lint)Inspect\b|-PtachiaiLocalInspection(?:=true)?\b", workflow.read_text()):
            raise PolicyFailure("CI_REQUESTS_INSPECTION")


class InspectionPolicyTests(unittest.TestCase):
    def test_comments_and_nonsecure_operations_are_allowed(self):
        verify_external_source('/* nested /* FLAG_SECURE */ comment */\n'
                               '// WebView.setWebContentsDebuggingEnabled(false)\n'
                               'window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)')

    def test_prior_protection_can_be_read(self):
        verify_external_source('val previous = window.attributes.flags and '
                               'WindowManager.LayoutParams.FLAG_SECURE != 0')

    def test_direct_inspection_toggle_and_method_reference_are_refused(self):
        for source in ('WebView\n.setWebContentsDebuggingEnabled\n(false)',
                       'val setter = WebView::setWebContentsDebuggingEnabled'):
            with self.subTest(source=source), self.assertRaises(PolicyFailure):
                verify_external_source(source)

    def test_secure_writes_and_aliases_are_refused(self):
        for source in ('window.addFlags(\nWindowManager.LayoutParams.FLAG_SECURE)',
                       'window.clearFlags(8192)', 'window.setFlags(0x2080, 0x2080)',
                       'window.addFlags(8_192L)',
                       'window.attributes.flags |= 8192',
                       'window.attributes.flags = value',
                       'import android.view.WindowManager.LayoutParams.FLAG_SECURE as hidden',
                       'val hidden = WindowManager.LayoutParams.FLAG_SECURE'):
            with self.subTest(source=source), self.assertRaises(PolicyFailure):
                verify_external_source(source)

    def test_policy_cannot_disable_capture_or_inspection(self):
        source = (ROOT / POLICY).read_text()
        verify_policy(source)
        for old, new in (('!BuildConfig.LOCAL_INSPECTION', 'BuildConfig.LOCAL_INSPECTION'),
                         ('BuildConfig.LOCAL_INSPECTION || enabledNormally', 'enabledNormally'),
                         ('window.clearFlags', 'window.addFlags')):
            with self.subTest(new=new), self.assertRaises(PolicyFailure):
                verify_policy(source.replace(old, new))

    def test_build_guards_cannot_be_removed(self):
        source = (ROOT / "apps/android/app/build.gradle.kts").read_text()
        verify_build(source)
        for old, new in (('variant.enable = localInspection', 'variant.enable = true'),
                         ('check(!localInspection || !runningInCi)', 'check(true)'),
                         ('it == "true"\n}', 'true\n}'),
                         ('kotlin.directories += "src/debug/kotlin"', 'java.srcDir("src/debug/kotlin")'),
                         ('["localInspectionEnabled"] = "false"', '["localInspectionEnabled"] = "true"')):
            with self.subTest(new=new), self.assertRaises(PolicyFailure):
                verify_build(source.replace(old, new))

    def test_manifest_entry_cannot_be_unconditionally_enabled(self):
        source = (ROOT / SOURCE / "debug/AndroidManifest.xml").read_text()
        verify_manifest(source)
        with self.assertRaises(PolicyFailure):
            verify_manifest(source.replace('${localInspectionEnabled}', 'true'))

    def test_dex_definitions_are_distinct_from_references_packages_and_members(self):
        output = (b"P d 1\t2\t100\tnet.fstab.tachiai.MainActivity\n"
                  b"C r 0 1 40 net.fstab.tachiai.Referenced\n"
                  b"M d 1 1 45 net.fstab.tachiai.MemberOnly void method()\n"
                  b"C d 1 1 85 net.fstab.tachiai.MainActivity$Nested\n"
                  b"  C d 1\t1\t85\tnet.fstab.tachiai.Defined\n")
        self.assertEqual(defined_classes(output), {"net.fstab.tachiai.MainActivity$Nested", "net.fstab.tachiai.Defined"})

    def test_dex_unknown_truncated_duplicate_and_reference_only_output_refused(self):
        for output in (b"", b"C r 0 1 40 net.fstab.tachiai.MainActivity\n",
                       b"C d 1 1 85\n", b"C d 1 1 85 example.Class\nWARNING\n",
                       b"C d 1 1 85 example.Class\nC d 1 1 85 example.Class\n",
                       b"C d 1 1 85 example.\xff\n"):
            with self.subTest(output=output), self.assertRaises(PolicyFailure):
                defined_classes(output)

    def test_manifest_components_include_service_and_alias_target(self):
        names = manifest_classes('<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
                                 '<application><service android:name=".ExampleService" />'
                                 '<activity-alias android:name=".Alias" android:targetActivity=".ActualActivity" />'
                                 '</application></manifest>')
        self.assertEqual(names, {"net.fstab.tachiai.MainActivity", "net.fstab.tachiai.platform.web.DeveloperAccess",
                                 "net.fstab.tachiai.ExampleService", "net.fstab.tachiai.ActualActivity"})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--check", action="store_true", help="also check current sources with --self-test")
    parser.add_argument("--apk", type=Path, help="also verify inspection APK component implementations in DEX")
    parser.add_argument("--apkanalyzer", type=Path, help="SDK command-line apkanalyzer executable (required with --apk)")
    args = parser.parse_args()
    if (args.apk is None) != (args.apkanalyzer is None):
        parser.error("--apk and --apkanalyzer must be provided together")
    if args.self_test:
        result = unittest.TextTestRunner().run(unittest.defaultTestLoader.loadTestsFromTestCase(InspectionPolicyTests))
        if not result.wasSuccessful():
            return 1
    if not args.self_test or args.check or args.apk is not None:
        try:
            verify_repository(ROOT)
            if args.apk is not None:
                verify_apk(args.apk, args.apkanalyzer)
        except (PolicyFailure, ET.ParseError) as error:
            print(f"INSPECTION_POLICY_INVALID: {error}")
            return 1
        print("INSPECTION_POLICY_VALID")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
