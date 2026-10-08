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
    for name in ("debuggable", "testOnly"):
        if app.get(ANDROID + name) not in (None, "false"):
            raise IsolationFailure("DEBUG_ATTRIBUTE")
    forbidden = forbidden_components(source_manifests, application_id)
    activities = []
    for node in app.iter():
        if node.tag not in COMPONENTS:
            continue
        name = node.get(ANDROID + "name")
        if not name:
            raise IsolationFailure("RELEASE_COMPONENT_NAME")
        component = qualified(name, application_id)
        target = node.get(ANDROID + "targetActivity")
        if component in forbidden or (target and qualified(target, application_id) in forbidden):
            raise IsolationFailure("PROTOTYPE_COMPONENT")
        if node.tag == "activity":
            activities.append(component)
    if activities.count(application_id + ".MainActivity") != 1:
        raise IsolationFailure("MAIN_ACTIVITY")
    for path in asset_paths:
        parts = path.split("/")
        if parts[0] in ("abema", "browser-lab"):
            raise IsolationFailure("PROTOTYPE_ASSETS")


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


def fixture(content='<activity android:name=".MainActivity" />', attributes="", package="net.fstab.tachiai"):
    return (f'<manifest xmlns:android="{ANDROID[1:-1]}" xmlns:tools="{TOOLS[1:-1]}" package="{package}">'
            f'<application {attributes}>{content}</application></manifest>').encode()


class IsolationTests(unittest.TestCase):
    sources = [fixture('<activity android:name=".feature.presentation.PrototypeActivity" />'),
               fixture('<activity android:name=".MainActivity" tools:node="remove" />'
                       '<activity android:name=".feature.diagnostic.BrowserLabActivity" />')]

    def check(self, manifest=None, assets=()):
        verify(manifest or fixture(), assets, self.sources, "net.fstab.tachiai")

    def test_release_main_and_existing_guarded_twitch_assets_pass(self):
        self.check(assets=["twitch/session.js", "diagnostics/timing-tools.js"])
        self.check(fixture('<activity android:name="net.fstab.tachiai.MainActivity" />'))

    def test_source_removal_does_not_forbid_release_main(self):
        forbidden = forbidden_components(self.sources, "net.fstab.tachiai")
        self.assertNotIn("net.fstab.tachiai.MainActivity", forbidden)
        self.assertIn("net.fstab.tachiai.feature.diagnostic.BrowserLabActivity", forbidden)

    def test_wrong_application_or_missing_main_refuse(self):
        for manifest in (fixture(package="net.fstab.tachiai.diagnostic"), fixture(content="")):
            with self.assertRaises(IsolationFailure):
                self.check(manifest)

    def test_debug_and_test_only_attributes_refuse(self):
        for attribute in ('android:debuggable="true"', 'android:testOnly="true"', 'android:debuggable="@bool/debug"'):
            with self.assertRaises(IsolationFailure):
                self.check(fixture(attributes=attribute))

    def test_debug_component_or_alias_target_refuse(self):
        for content in ('<activity android:name=".feature.presentation.PrototypeActivity" />',
                        '<service android:name="net.fstab.tachiai.feature.diagnostic.BrowserLabActivity" />',
                        '<activity-alias android:name=".Alias" android:targetActivity=".feature.presentation.PrototypeActivity" />'):
            with self.assertRaises(IsolationFailure):
                self.check(fixture('<activity android:name=".MainActivity" />' + content))

    def test_both_prototype_asset_roots_refuse(self):
        for path in ("abema", "abema/native-bootstrap.js", "browser-lab/index.html"):
            with self.assertRaises(IsolationFailure):
                self.check(assets=[path])

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
        parser.error("provide merged manifest/assets, application ID and source prototype manifests")
    else:
        try:
            verify(bounded_manifest(args.manifest), asset_paths(args.assets),
                   [bounded_manifest(path) for path in args.forbidden_manifest], args.application_id)
            print("RELEASE_ISOLATION_PASSED")
        except (IsolationFailure, OSError):
            print("RELEASE_ISOLATION_FAILED")
            raise SystemExit(1) from None
