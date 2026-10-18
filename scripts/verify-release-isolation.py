#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Check merged release manifest/assets, not complete bytecode isolation."""
import argparse
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
TOOLS = "{http://schemas.android.com/tools}"
MAX_MANIFEST_BYTES = 2 * 1024 * 1024
COMPONENTS = {"activity", "activity-alias", "service", "receiver", "provider"}
REQUIRED_COMPONENTS = {
    "activity": {
        ".MainActivity",
        ".feature.presentation.CachedPrototypeActivity",
        ".feature.presentation.PlaybackRestartActivity",
        ".feature.connections.ProvidersActivity",
        ".feature.connections.ManageStreamsActivity",
        ".feature.connections.ConnectionProfilesActivity",
        ".feature.connections.AbemaAccountConnectionActivity",
        ".feature.connections.AbemaApprovalBrowserActivity",
        ".feature.connections.AbemaShareActivity",
        ".feature.connections.TwitchCatalogConnectionActivity",
    },
    "service": {
        ".provider.abema.AbemaEpisodeMetadataService",
        ".provider.abema.AbemaSeriesMetadataService",
        ".provider.abema.account.AbemaAccountReadinessService",
        ".provider.abema.account.AbemaRenewalBrowserService",
        ".provider.abema.account.AbemaAccountBootstrapService",
        ".provider.abema.account.AbemaEmailGrantHandoffService",
        ".provider.abema.account.AbemaAccountAccessService",
    },
}
REQUIRED_ASSETS = {
    f"abema/{stem}{suffix}"
    for stem in ("native-bootstrap", "guest-bootstrap")
    for suffix in (".html", ".js", "-initialize.js")
}


class IsolationFailure(ValueError):
    pass


def manifest_tree(data):
    try:
        if len(data) > MAX_MANIFEST_BYTES:
            raise IsolationFailure("MANIFEST_SIZE")
        text = data.decode("utf-8")
        if "<!DOCTYPE" in text or "<!ENTITY" in text:
            raise IsolationFailure("MANIFEST_DECLARATION")
        root = ET.fromstring(text)
        if root.tag != "manifest":
            raise IsolationFailure("MANIFEST_ROOT")
        return root
    except (UnicodeError, ET.ParseError):
        raise IsolationFailure("MANIFEST_INVALID") from None


def qualified(name, application_id):
    if name.startswith("."):
        return application_id + name
    if "." not in name:
        return application_id + "." + name
    return name


def forbidden_components(source_manifests, application_id):
    result = set()
    for data in source_manifests:
        for node in manifest_tree(data).iter():
            if node.tag in COMPONENTS and node.get(TOOLS + "node") != "remove":
                name = node.get(ANDROID + "name")
                if not name:
                    raise IsolationFailure("SOURCE_COMPONENT_NAME")
                result.add(qualified(name, application_id))
    if not result:
        raise IsolationFailure("SOURCE_COMPONENTS_MISSING")
    return result


def verify(manifest, asset_paths, source_manifests, application_id):
    root = manifest_tree(manifest)
    if root.get("package") != application_id:
        raise IsolationFailure("APPLICATION_ID")
    applications = root.findall("application")
    if len(applications) != 1:
        raise IsolationFailure("APPLICATION_COUNT")
    app = applications[0]
    if app.get(ANDROID + "enabled") not in (None, "true"):
        raise IsolationFailure("APPLICATION_DISABLED")
    for name in ("debuggable", "testOnly"):
        if app.get(ANDROID + name) not in (None, "false"):
            raise IsolationFailure("DEBUG_ATTRIBUTE")
    forbidden = forbidden_components(source_manifests, application_id)
    present = {}
    for node in app.iter():
        if node.tag not in COMPONENTS:
            continue
        name = node.get(ANDROID + "name")
        if not name:
            raise IsolationFailure("RELEASE_COMPONENT_NAME")
        component = qualified(name, application_id)
        target = node.get(ANDROID + "targetActivity")
        if component in forbidden or (target and qualified(target, application_id) in forbidden):
            raise IsolationFailure("DIAGNOSTIC_COMPONENT")
        present.setdefault((node.tag, component), []).append(node)
    for kind, names in REQUIRED_COMPONENTS.items():
        for name in names:
            nodes = present.get((kind, qualified(name, application_id)), [])
            if len(nodes) != 1 or nodes[0].get(ANDROID + "enabled") not in (None, "true"):
                raise IsolationFailure("PRIMARY_COMPONENT")
    browser = present[("service", application_id + ".provider.abema.account.AbemaRenewalBrowserService")][0]
    if browser.get(ANDROID + "exported") != "false" or browser.get(ANDROID + "process") != ":abema_approval_browser" or \
            browser.find("intent-filter") is not None:
        raise IsolationFailure("RENEWAL_BROWSER_SERVICE")
    main = present[("activity", application_id + ".MainActivity")][0]
    launcher_categories = set()
    for intent in main.findall("intent-filter"):
        if any(action.get(ANDROID + "name") == "android.intent.action.MAIN"
               for action in intent.findall("action")):
            launcher_categories.update(category.get(ANDROID + "name") for category in intent.findall("category"))
    if main.get(ANDROID + "exported") != "true" or not {
        "android.intent.category.LAUNCHER", "android.intent.category.LEANBACK_LAUNCHER",
    }.issubset(launcher_categories):
        raise IsolationFailure("PRIMARY_LAUNCHER")
    assets = set(asset_paths)
    if not REQUIRED_ASSETS.issubset(assets):
        raise IsolationFailure("PRIMARY_ASSETS")
    for path in assets:
        parts = path.split("/")
        if parts[0] == "browser-lab" or (parts[0] == "abema" and len(parts) > 1 and
                (parts[1].startswith("cached-helper") or parts[1] == "opaque-broker.js")):
            raise IsolationFailure("DIAGNOSTIC_ASSETS")


def bounded_manifest(path):
    with path.open("rb") as stream:
        data = stream.read(MAX_MANIFEST_BYTES + 1)
    if len(data) > MAX_MANIFEST_BYTES:
        raise IsolationFailure("MANIFEST_SIZE")
    return data


def asset_paths(directory):
    if directory.is_symlink() or not directory.is_dir():
        raise IsolationFailure("ASSETS_DIRECTORY")
    result = []
    for path in directory.rglob("*"):
        if path.is_symlink() or len(result) >= 50_000:
            raise IsolationFailure("ASSETS_SHAPE")
        result.append(path.relative_to(directory).as_posix())
    return result


def primary_content():
    launcher = ('<intent-filter><action android:name="android.intent.action.MAIN" />'
                '<category android:name="android.intent.category.LAUNCHER" />'
                '<category android:name="android.intent.category.LEANBACK_LAUNCHER" /></intent-filter>')
    def component(kind, name):
        attributes = ('android:exported="false" android:process=":abema_approval_browser"'
                      if name == ".provider.abema.account.AbemaRenewalBrowserService" else 'android:exported="true"')
        return f'<{kind} android:name="{name}" {attributes}>' + (launcher if name == ".MainActivity" else "") + f'</{kind}>'

    return "".join(component(kind, name) for kind, names in REQUIRED_COMPONENTS.items() for name in sorted(names))


def fixture(content=None, attributes="", package="net.fstab.tachiai"):
    if content is None:
        content = primary_content()
    return (f'<manifest xmlns:android="{ANDROID[1:-1]}" xmlns:tools="{TOOLS[1:-1]}" package="{package}">'
            f'<application {attributes}>{content}</application></manifest>').encode()


class IsolationTests(unittest.TestCase):
    sources = [fixture('<activity android:name=".feature.diagnostic.InspectionControlActivity" />'
                       '<activity android:name=".feature.diagnostic.AbemaCachedHelperActivity" />'
                       '<service android:name=".provider.abema.account.AbemaRenewalProbeService" />'),
               fixture('<activity android:name=".MainActivity" tools:node="remove" />'
                       '<activity android:name=".feature.diagnostic.BrowserLabActivity" />')]

    def check(self, manifest=None, assets=None):
        verify(manifest or fixture(), REQUIRED_ASSETS if assets is None else assets,
               self.sources, "net.fstab.tachiai")

    def test_primary_product_and_existing_guarded_twitch_assets_pass(self):
        self.check(assets=REQUIRED_ASSETS | {"twitch/session.js", "diagnostics/timing-tools.js"})
        self.check(fixture(primary_content().replace('".MainActivity"', '"net.fstab.tachiai.MainActivity"')))

    def test_source_removal_does_not_forbid_release_main(self):
        forbidden = forbidden_components(self.sources, "net.fstab.tachiai")
        self.assertNotIn("net.fstab.tachiai.MainActivity", forbidden)
        self.assertIn("net.fstab.tachiai.feature.diagnostic.BrowserLabActivity", forbidden)

    def test_renewal_browser_requires_private_original_process_without_intent_filter(self):
        for field, value in (("exported", "true"), ("exported", None), ("process", None),
                             ("process", ":other_browser"), ("intent-filter", "added")):
            with self.subTest(field=field, value=value):
                tree = manifest_tree(fixture())
                browser = next(node for node in tree.find("application")
                               if node.get(ANDROID + "name") == ".provider.abema.account.AbemaRenewalBrowserService")
                if field == "intent-filter":
                    ET.SubElement(browser, "intent-filter")
                elif value is None:
                    browser.attrib.pop(ANDROID + field)
                else:
                    browser.set(ANDROID + field, value)
                with self.assertRaises(IsolationFailure):
                    self.check(ET.tostring(tree))

    def test_wrong_application_or_empty_product_refuse(self):
        for manifest in (fixture(package="net.fstab.tachiai.diagnostic"), fixture(content="")):
            with self.assertRaises(IsolationFailure):
                self.check(manifest)

    def test_debug_and_test_only_attributes_refuse(self):
        for attribute in ('android:debuggable="true"', 'android:testOnly="true"', 'android:debuggable="@bool/debug"'):
            with self.assertRaises(IsolationFailure):
                self.check(fixture(attributes=attribute))

    def test_disabled_application_refuses(self):
        for value in ("false", "@bool/enabled"):
            with self.assertRaises(IsolationFailure):
                self.check(fixture(attributes=f'android:enabled="{value}"'))

    def test_missing_duplicate_disabled_or_wrong_kind_product_component_refuses(self):
        for kind, names in REQUIRED_COMPONENTS.items():
            for name in names:
                tree = manifest_tree(fixture())
                app = tree.find("application")
                node = next(item for item in app if item.get(ANDROID + "name") == name)
                for mutation in ("missing", "duplicate", "disabled", "wrong-kind"):
                    with self.subTest(name=name, mutation=mutation):
                        altered = ET.fromstring(ET.tostring(tree))
                        application = altered.find("application")
                        component = next(item for item in application if item.get(ANDROID + "name") == name)
                        if mutation == "missing":
                            application.remove(component)
                        elif mutation == "duplicate":
                            application.append(ET.fromstring(ET.tostring(node)))
                        elif mutation == "disabled":
                            component.set(ANDROID + "enabled", "false")
                        else:
                            component.tag = "service" if kind == "activity" else "activity"
                        with self.assertRaises(IsolationFailure):
                            self.check(ET.tostring(altered))

    def test_missing_nonexported_or_wrong_action_launcher_refuses(self):
        for old, new in (
            ('android.intent.category.LAUNCHER', 'android.intent.category.DEFAULT'),
            ('android.intent.category.LEANBACK_LAUNCHER', 'android.intent.category.DEFAULT'),
            ('android.intent.action.MAIN', 'android.intent.action.VIEW'),
            ('android:name=".MainActivity" android:exported="true"',
             'android:name=".MainActivity" android:exported="false"'),
        ):
            with self.subTest(old=old), self.assertRaises(IsolationFailure):
                self.check(fixture(primary_content().replace(old, new)))

    def test_debug_component_or_alias_target_refuse(self):
        for content in ('<activity android:name=".feature.diagnostic.InspectionControlActivity" />',
                        '<activity android:name=".feature.diagnostic.InspectionControlActivity" android:enabled="false" />',
                        '<service android:name="net.fstab.tachiai.feature.diagnostic.BrowserLabActivity" />',
                        '<service android:name=".provider.abema.account.AbemaRenewalProbeService" />',
                        '<activity-alias android:name=".Alias" android:targetActivity=".feature.diagnostic.AbemaCachedHelperActivity" />'):
            with self.assertRaises(IsolationFailure):
                self.check(fixture(primary_content() + content))

    def test_diagnostic_assets_refuse(self):
        for path in ("abema/cached-helper.js", "abema/cached-helper-initialize.js",
                     "abema/cached-helper.html", "abema/opaque-broker.js", "browser-lab",
                     "browser-lab/index.html"):
            with self.assertRaises(IsolationFailure):
                self.check(assets=REQUIRED_ASSETS | {path})

    def test_missing_primary_assets_refuse(self):
        for path in REQUIRED_ASSETS:
            with self.subTest(path=path), self.assertRaises(IsolationFailure):
                self.check(assets=REQUIRED_ASSETS - {path})

    def test_malformed_oversized_or_entity_xml_refuse(self):
        for data in (b"not xml", b"x" * (MAX_MANIFEST_BYTES + 1), b"<!DOCTYPE manifest><manifest />",
                     b"<!ENTITY secret SYSTEM 'file:///unavailable'><manifest />", b"\xff"):
            with self.assertRaises(IsolationFailure):
                manifest_tree(data)

    def test_missing_source_inventory_refuses(self):
        with self.assertRaises(IsolationFailure):
            verify(fixture(), (), [], "net.fstab.tachiai")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--assets", type=Path)
    parser.add_argument("--application-id")
    parser.add_argument("--forbidden-manifest", type=Path, action="append", default=[])
    args = parser.parse_args()
    if args.self_test:
        unittest.main(argv=[__file__])
    elif not all((args.manifest, args.assets, args.application_id, args.forbidden_manifest)):
        parser.error("provide merged manifest/assets, application ID and developer-only source manifests")
    else:
        try:
            verify(bounded_manifest(args.manifest), asset_paths(args.assets),
                   [bounded_manifest(path) for path in args.forbidden_manifest], args.application_id)
            print("RELEASE_ISOLATION_PASSED")
        except (IsolationFailure, OSError):
            print("RELEASE_ISOLATION_FAILED")
            raise SystemExit(1) from None
