#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Provider-free safety tests; never start or reset a real emulator."""

import importlib.util
import hashlib
import io
from pathlib import Path
import subprocess
import tempfile
import tomllib
import unittest
from unittest.mock import Mock, patch
import zipfile


spec = importlib.util.spec_from_file_location(
    "android_dev_device", Path(__file__).resolve().parents[1] / "android-dev-device.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class DeviceTests(unittest.TestCase):
    def setUp(self):
        temporary_root = Path(tempfile.gettempdir()) / "agents"
        temporary_root.mkdir(parents=True, exist_ok=True)
        self.folder = tempfile.TemporaryDirectory(dir=temporary_root)
        self.addCleanup(self.folder.cleanup)
        self.profile = tomllib.loads(module.PROFILE.read_text())
        self.device = module.Device(Path(self.folder.name), self.profile)

    def populate(self):
        device = self.device
        device.avd.mkdir(parents=True)
        (device.avds / "tachiai-dev.ini").write_text(f"path={device.avd}\n")
        (device.avd / "tachiai-profile.toml").write_bytes(module.PROFILE.read_bytes())
        (device.avd / "config.ini").write_text(
            "image.sysdir.1=system-images/android-36/google_apis/x86_64/\n"
            "hw.ramSize=4096\nhw.cpu.ncore=4\nhw.keyboard=yes\n")
        (device.avd / "userdata-qemu.img").write_bytes(b"persistent-state")
        device.tools.parent.mkdir(parents=True)
        (device.tools.parent / "source.properties").write_text("Pkg.Revision=19.0\n")
        for package, revision in self.profile["sdk"]["revisions"].items():
            folder = device.sdk.joinpath(*package.split(";"))
            folder.mkdir(parents=True)
            (folder / "source.properties").write_text(f"Pkg.Revision={revision}\n")

    def test_existing_device_is_preserved_without_any_command(self):
        self.populate()
        with patch.object(self.device, "run") as run:
            self.device.setup(None)
            run.assert_not_called()
        self.assertEqual((self.device.avd / "userdata-qemu.img").read_bytes(), b"persistent-state")

    def test_emulator_reformatted_properties_are_accepted(self):
        self.populate()
        config = self.device.avd / "config.ini"
        config.write_text(config.read_text().replace("=", " = "))
        self.device.verify_avd()

    def test_disabled_keyboard_is_refused(self):
        self.populate()
        config = self.device.avd / "config.ini"
        config.write_text(config.read_text().replace("hw.keyboard=yes", "hw.keyboard=no"))
        with self.assertRaises(ValueError):
            self.device.verify_avd()

    def test_unowned_or_modified_device_refused_before_commands(self):
        self.populate()
        for change in (lambda: (self.device.avd / "config.ini").write_text("hw.ramSize=2\n"),
                       lambda: (self.device.avd / "tachiai-profile.toml").unlink()):
            change()
            with patch.object(self.device, "run") as run, self.assertRaises(ValueError):
                self.device.setup(None)
            run.assert_not_called()

    def test_existing_sdk_revision_mismatch_refused(self):
        self.populate()
        (self.device.sdk / "emulator/source.properties").write_text("Pkg.Revision=999\n")
        with patch.object(self.device, "run") as run, self.assertRaises(ValueError):
            self.device.setup(None)
        run.assert_not_called()

    def test_device_state_cannot_be_inside_repository(self):
        with self.assertRaises(ValueError):
            module.Device(module.ROOT / "device-state", self.profile)

    def test_explicit_adb_serial_and_avd_identity(self):
        result = Mock(stdout="List of devices attached\nphone\tdevice\nemulator-5580\tdevice\n")
        with patch.object(self.device, "run", return_value=result), \
                patch.object(self.device, "adb", side_effect=[Mock(stdout="tachiai-dev\nOK\n"),
                             Mock(stdout=f"{self.device.avd}\nOK\n")]) as adb:
            self.assertTrue(self.device.connected())
            self.assertEqual(adb.call_args_list[0].args, ("emu", "avd", "name"))
            self.assertEqual(adb.call_args_list[1].args, ("emu", "avd", "path"))
        with patch("subprocess.run", return_value=result) as run:
            self.device.adb("shell", "getprop", "ro.product.cpu.abi")
            self.assertEqual(run.call_args.args[0][1:3], ["-s", "emulator-5580"])

    def test_other_avd_on_same_port_refused(self):
        with patch.object(self.device, "run", return_value=Mock(stdout="emulator-5580\tdevice\n")), \
                patch.object(self.device, "adb", return_value=Mock(stdout="someone-else\nOK\n")), \
                self.assertRaises(ValueError):
            self.device.connected()

    def test_phone_alone_is_not_selected(self):
        with patch.object(self.device, "run", return_value=Mock(stdout="phone\tdevice\n")), \
                patch.object(self.device, "adb") as adb:
            self.assertFalse(self.device.connected())
            adb.assert_not_called()

    def test_same_name_from_other_data_directory_is_refused(self):
        with patch.object(self.device, "run", return_value=Mock(stdout="emulator-5580\tdevice\n")), \
                patch.object(self.device, "adb", side_effect=[Mock(stdout="tachiai-dev\nOK\n"),
                             Mock(stdout=f"{self.folder.name}/other/tachiai-dev.avd\nOK\n")]), \
                self.assertRaises(ValueError):
            self.device.connected()

    def test_missing_running_avd_path_is_refused(self):
        with patch.object(self.device, "run", return_value=Mock(stdout="emulator-5580\tdevice\n")), \
                patch.object(self.device, "adb", side_effect=[Mock(stdout="tachiai-dev\nOK\n"),
                             Mock(stdout="KO: unavailable\n")]), self.assertRaises(ValueError):
            self.device.connected()

    def test_offline_emulator_is_not_replaced(self):
        with patch.object(self.device, "run", return_value=Mock(stdout="emulator-5580\toffline\n")), \
                self.assertRaises(ValueError):
            self.device.connected()

    def test_install_verifies_signer_before_preserving_update(self):
        output = "V2 Signer: certificate SHA-256 digest: " + module.CERTIFICATE.lower() + "\n"
        with patch.object(self.device, "ready"), \
                patch.object(self.device, "run", side_effect=[Mock(stdout=output),
                             Mock(stdout="package: name='net.fstab.tachiai' versionCode='1'\n")]), \
                patch.object(self.device, "adb") as adb:
            self.device.install(Path(self.folder.name) / "app.apk")
            self.assertEqual(adb.call_args_list[0].args[:2], ("install", "-r"))
            self.assertEqual(adb.call_args_list[1].args[:3], ("shell", "am", "start"))

    def test_wrong_or_multiple_signers_refuse_install(self):
        for output in ("Signer #1 certificate SHA-256 digest: 00\n",
                       "Signer #1 certificate SHA-256 digest: " + module.CERTIFICATE + "\n"
                       + "Signer #2 certificate SHA-256 digest: " + module.CERTIFICATE + "\n"):
            with patch.object(self.device, "ready"), \
                    patch.object(self.device, "run", return_value=Mock(stdout=output)), \
                    patch.object(self.device, "adb") as adb, self.assertRaises(ValueError):
                self.device.install(Path("app.apk"))
            adb.assert_not_called()

    def test_failed_apk_verification_refuses_install(self):
        with patch.object(self.device, "ready"), \
                patch.object(self.device, "run", side_effect=subprocess.CalledProcessError(1, "apksigner")), \
                patch.object(self.device, "adb") as adb, self.assertRaises(subprocess.CalledProcessError):
            self.device.install(Path("app.apk"))
        adb.assert_not_called()

    def test_wrong_package_refuses_install(self):
        output = "Signer #1 certificate SHA-256 digest: " + module.CERTIFICATE + "\n"
        with patch.object(self.device, "ready"), \
                patch.object(self.device, "run", side_effect=[Mock(stdout=output),
                             Mock(stdout="package: name='another.application' versionCode='1'\n")]), \
                patch.object(self.device, "adb") as adb, self.assertRaises(ValueError):
            self.device.install(Path("app.apk"))
        adb.assert_not_called()

    def test_missing_redirected_or_symlinked_registry_refused(self):
        self.populate()
        index = self.device.avds / "tachiai-dev.ini"
        index.unlink()
        with self.assertRaises(ValueError):
            self.device.verify_avd()
        index.write_text(f"path={self.folder.name}/other.avd\n")
        with self.assertRaises(ValueError):
            self.device.verify_avd()
        index.unlink()
        target = Path(self.folder.name) / "registry.ini"
        target.write_text(f"path={self.device.avd}\n")
        index.symlink_to(target)
        with self.assertRaises(ValueError):
            self.device.verify_avd()

    def test_stop_does_not_require_completed_boot(self):
        self.populate()
        with patch.object(self.device, "connected", return_value=True), \
                patch.object(self.device, "run", return_value=Mock(stdout="")), \
                patch.object(self.device, "ready") as ready, patch.object(self.device, "adb") as adb:
            self.device.stop()
            ready.assert_not_called()
            adb.assert_called_once_with("emu", "kill", timeout=15)

    def test_stop_waits_for_snapshot_shutdown(self):
        self.populate()
        with patch.object(self.device, "connected", return_value=True), \
                patch.object(self.device, "run", side_effect=[Mock(stdout="emulator-5580\tdevice\n"),
                                                              Mock(stdout="phone\tdevice\n")]), \
                patch.object(self.device, "adb") as adb, patch("time.sleep") as sleep:
            self.device.stop()
            adb.assert_called_once_with("emu", "kill", timeout=15)
            sleep.assert_called_once_with(2)

    def test_wrong_identity_never_stopped(self):
        self.populate()
        with patch.object(self.device, "connected", side_effect=ValueError("wrong AVD")), \
                patch.object(self.device, "adb") as adb, self.assertRaises(ValueError):
            self.device.stop()
        adb.assert_not_called()

    def test_new_creation_has_no_force_and_preserves_created_userdata(self):
        def create(*args, **kwargs):
            if "create" in args:
                self.populate()
        with patch.object(self.device, "bootstrap"), patch.object(self.device, "verify_sdk"), \
                patch.object(self.device, "run", side_effect=create) as run:
            self.device.setup(None)
            args = run.call_args_list[-1].args
            self.assertIn("create", args)
            self.assertNotIn("--force", args)
        self.assertEqual((self.device.avd / "userdata-qemu.img").read_bytes(), b"persistent-state")

    def test_start_preserves_state_and_does_not_request_wipe(self):
        self.populate()
        with patch.object(self.device, "connected", return_value=False), \
                patch.object(self.device, "run"), patch.object(self.device, "ready"), \
                patch.object(self.device, "configure"), \
                patch("subprocess.Popen", return_value=Mock(poll=Mock(return_value=None))) as launch:
            self.device.start(True)
            args = launch.call_args.args[0]
            self.assertIn("-no-window", args)
            self.assertEqual(args[args.index("-gpu") + 1], "software")
            self.assertNotIn("-wipe-data", args)
        self.assertEqual((self.device.avd / "userdata-qemu.img").read_bytes(), b"persistent-state")

    def test_failed_start_preserves_state(self):
        self.populate()
        with patch.object(self.device, "connected", return_value=False), \
                patch.object(self.device, "run"), \
                patch("subprocess.Popen", return_value=Mock(poll=Mock(return_value=1))), \
                self.assertRaises(ValueError):
            self.device.start(False)
        self.assertEqual((self.device.avd / "userdata-qemu.img").read_bytes(), b"persistent-state")

    def test_cold_boot_skips_snapshot_without_wiping_data(self):
        self.populate()
        with patch.object(self.device, "connected", return_value=False), \
                patch.object(self.device, "run"), patch.object(self.device, "ready"), \
                patch.object(self.device, "configure"), \
                patch("subprocess.Popen", return_value=Mock(poll=Mock(return_value=None))) as launch:
            self.device.start(False, cold_boot=True)
            args = launch.call_args.args[0]
            self.assertIn("-no-snapshot-load", args)
            self.assertNotIn("-wipe-data", args)
        self.assertEqual((self.device.avd / "userdata-qemu.img").read_bytes(), b"persistent-state")

    def test_software_renderer_override_preserves_device_data(self):
        self.populate()
        with patch.object(self.device, "connected", return_value=False), \
                patch.object(self.device, "run"), patch.object(self.device, "ready"), \
                patch.object(self.device, "configure"), \
                patch("subprocess.Popen", return_value=Mock(poll=Mock(return_value=None))) as launch:
            self.device.start(False, cold_boot=True, gpu="software")
            args = launch.call_args.args[0]
            self.assertEqual(args[args.index("-gpu") + 1], "software")
            self.assertNotIn("-wipe-data", args)
        self.assertEqual((self.device.avd / "userdata-qemu.img").read_bytes(), b"persistent-state")

    def test_unrecognized_renderer_refused_before_start(self):
        with patch.object(self.device, "run") as run, patch("subprocess.Popen") as launch, \
                self.assertRaises(ValueError):
            self.device.start(False, gpu="unknown")
        run.assert_not_called()
        launch.assert_not_called()

    def test_auto_override_and_runtime_change_do_not_recreate_device(self):
        self.populate()
        self.device.profile["runtime"]["gpu"] = "host"
        self.device.verify_avd()
        with patch.object(self.device, "connected", return_value=False), \
                patch.object(self.device, "run"), patch.object(self.device, "ready"), \
                patch.object(self.device, "configure"), \
                patch("subprocess.Popen", return_value=Mock(poll=Mock(return_value=None))) as launch:
            self.device.start(False, gpu="auto")
            args = launch.call_args.args[0]
            self.assertEqual(args[args.index("-gpu") + 1], "auto")
        self.assertEqual((self.device.avd / "userdata-qemu.img").read_bytes(), b"persistent-state")

    def test_default_start_on_running_device_does_not_restart_it(self):
        self.populate()
        with patch.object(self.device, "connected", return_value=True), \
                patch.object(self.device, "configure") as configure, patch("subprocess.Popen") as launch:
            self.device.start(False)
        configure.assert_called_once_with()
        launch.assert_not_called()

    def test_running_device_does_not_silently_ignore_renderer_change(self):
        self.populate()
        with patch.object(self.device, "connected", return_value=True), \
                patch.object(self.device, "configure") as configure, patch("subprocess.Popen") as launch, \
                self.assertRaises(ValueError):
            self.device.start(False, gpu="software")
        configure.assert_not_called()
        launch.assert_not_called()

    def test_preferences_can_change_without_device_recreation(self):
        self.populate()
        self.device.profile["preferences"]["night_mode"] = "no"
        self.device.verify_avd()
        with patch.object(self.device, "ready"), patch.object(self.device, "adb") as adb:
            self.device.configure()
            self.assertEqual(adb.call_args_list[0].args, ("shell", "cmd", "uimode", "night", "no"))
            self.assertEqual(adb.call_args_list[1].args, (
                "shell", "cmd", "overlay", "enable-exclusive", "--user", "0", "--category",
                "com.android.internal.systemui.navbar.threebutton"))
        self.assertEqual((self.device.avd / "userdata-qemu.img").read_bytes(), b"persistent-state")

    def test_bad_preferences_refused_before_any_setting_is_applied(self):
        self.device.profile["preferences"]["navigation"] = "unsupported"
        with patch.object(self.device, "adb") as adb, self.assertRaises(ValueError):
            self.device.configure()
        adb.assert_not_called()

    def test_preferences_require_verified_owned_ready_device(self):
        with patch.object(self.device, "ready", side_effect=ValueError("wrong device")), \
                patch.object(self.device, "adb") as adb, self.assertRaises(ValueError):
            self.device.configure()
        adb.assert_not_called()

    def test_bootstrap_checks_download_before_extracting(self):
        for member, valid_checksum in (("cmdline-tools/bin/sdkmanager", True),
                                       ("cmdline-tools/bin/sdkmanager", False),
                                       ("cmdline-tools/../../escape", True)):
            with self.subTest(member=member, valid_checksum=valid_checksum):
                device = module.Device(Path(self.folder.name) / str(valid_checksum) / member.replace("/", "_"),
                                       tomllib.loads(module.PROFILE.read_text()))
                buffer = io.BytesIO()
                with zipfile.ZipFile(buffer, "w") as archive:
                    archive.writestr(member, "fixture")
                payload = buffer.getvalue()
                device.profile["sdk"]["bootstrap_sha1"] = hashlib.sha1(payload).hexdigest() if valid_checksum else "0" * 40
                def download(url, destination):
                    destination.write_bytes(payload)
                with patch("urllib.request.urlretrieve", side_effect=download):
                    if valid_checksum and ".." not in member:
                        device.bootstrap(None)
                        self.assertTrue((device.tools / "sdkmanager").is_file())
                    else:
                        with self.assertRaises(ValueError):
                            device.bootstrap(None)
                        self.assertFalse(device.tools.exists())


if __name__ == "__main__":
    unittest.main()
