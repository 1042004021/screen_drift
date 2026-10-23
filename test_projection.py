"""Bounded physical-display experiment for this device only; no persistent settings."""
import json
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SERIAL = "16ffd10d"


def adb(*args, timeout=20):
    return subprocess.run(["adb", "-s", SERIAL, *args], text=True,
                          capture_output=True, timeout=timeout, check=True).stdout


def snapshot():
    # Filter remotely: don't collect app/window names or full diagnostic dumps.
    return adb("shell", "su -c 'dumpsys SurfaceFlinger | grep -E "
               '"^Display [0-9]+ \\(physical|^   layerStackSpace=|'
               '^   orientedDisplaySpace="' + "'")


def command(mode, display_id):
    return ["adb", "-s", SERIAL, "shell", "su -c '"
            "CLASSPATH=/data/local/tmp/screen-drift-test.dex:$SYSTEMSERVERCLASSPATH "
            "app_process /system/bin ProjectionTest " + mode + " " + display_id + " 1200 2670'"]


def main():
    before = snapshot()
    ids = re.findall(r"^Display (\d+) \(physical", before, re.M)
    if len(ids) != 1:
        raise RuntimeError("Expected exactly one physical display")
    for field in ("layerStackSpace", "orientedDisplaySpace"):
        expected = (field + "=ProjectionSpace{bounds=Rect(0, 0, 1200, 2670), "
                    "content=Rect(0, 0, 1200, 2670), orientation=ROTATION_0}")
        if expected not in before:
            raise RuntimeError("Baseline is not the verified full-frame portrait projection")
    report = {"before": before}
    (ROOT / "build/baseline.json").write_text(json.dumps(report, indent=2))
    watchdog = subprocess.Popen(command("watchdog", ids[0]), text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    # Finite readiness wait, never shift before recovery process is running.
    import select
    if not select.select([watchdog.stdout], [], [], 10)[0]:
        raise RuntimeError("Watchdog did not become ready; no shift performed")
    ready = watchdog.stdout.readline().strip()
    if ready != "WATCHDOG_READY":
        raise RuntimeError("Watchdog setup failed; no shift performed: " + ready)
    print(ready, flush=True)
    worker = subprocess.Popen(command("shift", ids[0]), text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        time.sleep(0.7)
        report["during"] = snapshot()
        out, err = worker.communicate(timeout=12)
        report["worker"] = {"stdout": out, "stderr": err, "returncode": worker.returncode}
    finally:
        out, err = watchdog.communicate(timeout=15)
        report["watchdog"] = {"stdout": ready + "\n" + out, "stderr": err,
                              "returncode": watchdog.returncode}
        report["after"] = snapshot()
        report["projection_restored"] = report["after"] == before
        (ROOT / "build/result.json").write_text(json.dumps(report, indent=2))
        print(json.dumps(report, indent=2))
    if not report["projection_restored"]:
        raise RuntimeError("Projection differs from baseline; inspect immediately")


if __name__ == "__main__":
    main()
