#!/usr/bin/env python3
"""Host control for the experimental root backend; never starts on boot."""
import argparse
import json
import re
import shlex
import subprocess
import time
from pathlib import Path

BASE = Path(__file__).resolve().parent
REMOTE = "/data/local/tmp/screen-drift"
SERIAL = "16ffd10d"


def adb(*args, timeout=25):
    result = subprocess.run(["adb", "-s", SERIAL, *args], text=True,
                            capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return result.stdout


def root(command):
    return adb("shell", "su -c " + shlex.quote(command))


def deploy(local, name):
    # adb push is shell, while the private backend directory belongs to root.
    staging = "/data/local/tmp/screen-drift-upload-" + name
    adb("push", str(local), staging)
    root("cp " + shlex.quote(staging) + " " + shlex.quote(REMOTE + "/" + name)
         + " && chmod 600 " + shlex.quote(REMOTE + "/" + name))
    adb("shell", "rm -f " + shlex.quote(staging))


def projection():
    return root("dumpsys SurfaceFlinger | grep -E " + shlex.quote(
        r"^Display [0-9]+ \(physical|^   layerStackSpace=|^   orientedDisplaySpace="))


def invoke(mode, state):
    return ("CLASSPATH=" + REMOTE + "/classes.dex:$SYSTEMSERVERCLASSPATH "
            "app_process /system/bin DriftDaemon " + mode + " " + state["id"]
            + " 1200 2670 " + REMOTE + "/drift.properties")


def session():
    return json.loads(root("cat " + REMOTE + "/session.json"))


def check_idle():
    busy = root("ps -A -o ARGS | grep 'app_process /system/bin DriftDaemon' | grep -v grep || true")
    if busy.strip():
        raise RuntimeError("Existing backend detected: stop it and wait before starting another session")


def start(seconds):
    if not 5 <= seconds <= 600:
        raise ValueError("Duration must be 5..600 seconds")
    if "uid=0" not in root("id"):
        raise RuntimeError("Root not available")
    check_idle()
    before = projection()
    ids = re.findall(r"^Display (\d+) \(physical", before, re.M)
    if len(ids) != 1:
        raise RuntimeError("Expected one physical display")
    for field in ("layerStackSpace", "orientedDisplaySpace"):
        expected = (field + "=ProjectionSpace{bounds=Rect(0, 0, 1200, 2670), "
                    "content=Rect(0, 0, 1200, 2670), orientation=ROTATION_0}")
        if expected not in before:
            raise RuntimeError("Start requires full-frame 1200x2670 portrait baseline")
    awake = root("dumpsys power | grep 'mWakefulness='")
    if "Awake" not in awake:
        raise RuntimeError("Wake and unlock the phone first")
    root("mkdir -p " + REMOTE)
    deploy(BASE / "dist/classes.dex", "classes.dex")
    config = (BASE / "drift.properties").read_text()
    config = re.sub(r"(?m)^seconds=.*$", "seconds=" + str(seconds), config)
    generated = BASE / "build/session.properties"
    generated.write_text(config)
    deploy(generated, "drift.properties")
    state = {"id": ids[0], "baseline": before, "seconds": seconds}
    local = BASE / "build/session.json"
    local.write_text(json.dumps(state, indent=2))
    deploy(local, "session.json")
    # Exact known control files only; never deletes arbitrary module/user data.
    root("rm -f " + REMOTE + "/stop " + REMOTE + "/heartbeat " + REMOTE
         + "/watchdog-ready " + REMOTE + "/worker-pid")
    watchdog = invoke("watchdog", state)
    root("nohup sh -c " + shlex.quote(watchdog) + " >" + REMOTE + "/watchdog.log 2>&1 </dev/null &")
    for _ in range(25):
        if root("test -f " + REMOTE + "/watchdog-ready && echo ready || true").strip() == "ready":
            break
        time.sleep(0.2)
    else:
        root("touch " + REMOTE + "/stop")
        raise RuntimeError("Watchdog not ready; worker not started. Inspect logs.")
    try:
        root("nohup sh -c " + shlex.quote(invoke("run", state)) + " >" + REMOTE + "/worker.log 2>&1 </dev/null &")
        time.sleep(2)
        print(root("cat " + REMOTE + "/worker.log"))
        active = root("test -f " + REMOTE + "/heartbeat && test ! -f " + REMOTE + "/stop && echo active || true")
        if active.strip() != "active":
            raise RuntimeError("Backend did not become active; stopped for safety")
    except BaseException:
        root("touch " + REMOTE + "/stop")
        raise
    print("Session running for at most %ds. Keep portrait; screen off or rotation stops it." % seconds)
    print("Touch alignment is UNVERIFIED. Stop: python3 control.py stop")


def stop():
    state = session()
    root("touch " + REMOTE + "/stop")
    time.sleep(3)
    print(root("cat " + REMOTE + "/worker.log " + REMOTE + "/watchdog.log"))
    current = projection()
    print(current)
    print("Baseline restored:", current == state["baseline"])
    if current != state["baseline"]:
        raise RuntimeError("Projection differs (possibly rotation changed); inspect before another session")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["start", "stop", "status", "restore"])
    parser.add_argument("--seconds", type=int, default=60)
    args = parser.parse_args()
    if args.command == "start":
        start(args.seconds)
    elif args.command == "stop":
        stop()
    elif args.command == "restore":
        check_idle()
        print(root(invoke("restore", session())))
        print(projection())
    else:
        print(root("ps -A -o ARGS | grep 'app_process /system/bin DriftDaemon' | grep -v grep || true"))
        print(root("cat " + REMOTE + "/worker.log " + REMOTE + "/watchdog.log"))
        print(projection())


if __name__ == "__main__":
    main()
