"""Offline guard checks. Never contacts adb."""
import unittest
from unittest.mock import patch
import control


BASELINE = ('Display 123 (physical, "")\n'
            '   layerStackSpace=ProjectionSpace{bounds=Rect(0, 0, 1200, 2670), '
            'content=Rect(0, 0, 1200, 2670), orientation=ROTATION_0}\n'
            '   orientedDisplaySpace=ProjectionSpace{bounds=Rect(0, 0, 1200, 2670), '
            'content=Rect(0, 0, 1200, 2670), orientation=ROTATION_0}\n')


class GuardChecks(unittest.TestCase):
    def test_duration_rejected_before_adb(self):
        with patch.object(control, "adb") as adb:
            for seconds in (0, 4, 601):
                with self.assertRaises(ValueError):
                    control.start(seconds)
            adb.assert_not_called()

    def test_root_required(self):
        with patch.object(control, "root", return_value="uid=2000(shell)"):
            with self.assertRaisesRegex(RuntimeError, "Root"):
                control.start(10)

    def test_nonbaseline_rejected(self):
        with patch.object(control, "root", return_value="uid=0(root)"), \
                patch.object(control, "check_idle"), \
                patch.object(control, "projection", return_value=BASELINE.replace("ROTATION_0", "ROTATION_1")), \
                patch.object(control, "adb") as adb:
            with self.assertRaisesRegex(RuntimeError, "baseline"):
                control.start(10)
            adb.assert_not_called()

    def test_multiple_displays_rejected(self):
        with patch.object(control, "root", return_value="uid=0(root)"), \
                patch.object(control, "check_idle"), \
                patch.object(control, "projection", return_value=BASELINE + 'Display 456 (physical, "")\n'):
            with self.assertRaisesRegex(RuntimeError, "one physical"):
                control.start(10)

    def test_existing_process_rejected(self):
        with patch.object(control, "root", return_value="app_process /system/bin DriftDaemon run"):
            with self.assertRaisesRegex(RuntimeError, "Existing backend"):
                control.check_idle()


if __name__ == "__main__":
    unittest.main()
