#!/usr/bin/env python3
"""Offline bridge state-machine tests. No ROS, Bluetooth, wheel, or shell access."""
import importlib.util
import pathlib
import tempfile
import unittest
from unittest.mock import patch

MODULE_PATH = pathlib.Path(__file__).resolve().parents[1] / "pid_bt_bridge.py"
spec = importlib.util.spec_from_file_location("pid_bt_bridge_tested", MODULE_PATH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class BridgeStateTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        root = pathlib.Path(self.tempdir.name)
        (root / "scripts").mkdir()
        (root / "routes").mkdir()
        self.bridge = module.Bridge(root / "scripts", root / "routes")
        self.bridge.ready_state = "READY"
        self.started = []
        def simulated_job(name, args, **kwargs):
            self.started.append((name, args))
            return {"ok": True, "detail": "OFFLINE TEST ONLY"}
        self.patch_job = patch.object(self.bridge, "_run_job", side_effect=simulated_job)
        self.patch_job.start()

    def tearDown(self):
        self.patch_job.stop()
        self.tempdir.cleanup()

    def command(self, command):
        return self.bridge.handle({"command": command})[0]

    def test_stale_session_blocks_drive_start(self):
        self.bridge.drive_phase = "STOP_UNVERIFIED"
        result = self.command("drive_start")
        self.assertFalse(result["ok"])
        self.assertIn("must be stopped and verified", result["detail"])
        self.assertEqual(self.started, [])

    def test_stale_session_allows_stop_retry(self):
        self.bridge.drive_phase = "STOP_UNVERIFIED"
        with patch.object(self.bridge, "_cancel_drive_launch"):
            result = self.command("drive_stop")
        self.assertTrue(result["ok"])
        self.assertEqual(self.bridge.drive_phase, "STOPPING")
        self.assertEqual(self.started[0][0], "drive_stop")

    def test_stale_session_allows_explicit_reconcile_request(self):
        self.bridge.drive_phase = "STOP_UNVERIFIED"
        result = self.command("drive_reconcile")
        self.assertTrue(result["ok"])
        self.assertEqual(self.started[0][0], "drive_reconcile")
        # The ACK cannot clear stale state: only the finished verifier can.
        self.assertEqual(self.bridge.drive_phase, "STOP_UNVERIFIED")

    def test_reconcile_rejected_while_drive_running(self):
        self.bridge.drive_phase = "RUNNING"
        result = self.command("drive_reconcile")
        self.assertFalse(result["ok"])
        self.assertIn("use DRIVE STOP", result["detail"])
        self.assertEqual(self.started, [])

    def test_reconcile_rejected_while_stop_script_running(self):
        self.bridge.drive_phase = "STOP_UNVERIFIED"
        with patch.object(self.bridge, "_running", side_effect=lambda name: name == "drive_stop"):
            result = self.command("drive_reconcile")
        self.assertFalse(result["ok"])
        self.assertEqual(self.started, [])

    def test_readonly_verifier_exists(self):
        helper = MODULE_PATH.parent / "pid_drive_reconcile.sh"
        self.assertTrue(helper.is_file())
        content = helper.read_text(encoding="utf-8")
        self.assertIn("pid_drive_check.py", content)
        self.assertIn("--mode MANUAL --stopped", content)
        self.assertNotIn("rostopic pub", content)

if __name__ == "__main__":
    unittest.main()
