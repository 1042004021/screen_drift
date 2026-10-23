#!/system/bin/sh
# KernelSU late_start service. No system mounts, injection, or policy changes.
MODDIR=${0%/*}
RUNDIR="$MODDIR/runtime"
mkdir -p "$RUNDIR"
chmod 700 "$RUNDIR"
# Boot ID prevents a stale lock from blocking the next boot.
BOOT_ID=$(cat /proc/sys/kernel/random/boot_id)
LOCK="$RUNDIR/supervisor.$BOOT_ID.lock"
mkdir "$LOCK" 2>/dev/null || exit 0
trap 'touch "$RUNDIR/stop"; rmdir "$LOCK" 2>/dev/null' EXIT
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 3; done
sleep 10
[ -n "$SYSTEMSERVERCLASSPATH" ] || {
    echo "SYSTEMSERVERCLASSPATH missing: refusing to start" > "$RUNDIR/supervisor.log"
    exit 1
}
cp "$MODDIR/drift.properties" "$RUNDIR/drift.properties"
# Reuse finite, watched sessions instead of introducing an unbounded unguarded loop.
sed -i 's/^seconds=.*/seconds=600/' "$RUNDIR/drift.properties"
export CLASSPATH="$MODDIR/classes.dex:$SYSTEMSERVERCLASSPATH"
run_backend() {
    app_process "-Dscreen.drift.home=$RUNDIR" /system/bin DriftDaemon "$1" "$DISPLAY_ID" 1200 2670 "$RUNDIR/drift.properties"
}
while [ -d "$MODDIR" ] && [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ]; do
    sleep 2
    dumpsys power | grep -q 'mWakefulness=Awake' || continue
    # Start only from the exact verified full-frame portrait state; never guess baseline.
    PROJECTION=$(dumpsys SurfaceFlinger | grep -E '^Display [0-9]+ \(physical|^   layerStackSpace=|^   orientedDisplaySpace=')
    [ "$(printf '%s\n' "$PROJECTION" | grep -c '^Display .* (physical')" = 1 ] || continue
    printf '%s\n' "$PROJECTION" | grep -Fq 'layerStackSpace=ProjectionSpace{bounds=Rect(0, 0, 1200, 2670), content=Rect(0, 0, 1200, 2670), orientation=ROTATION_0}' || continue
    printf '%s\n' "$PROJECTION" | grep -Fq 'orientedDisplaySpace=ProjectionSpace{bounds=Rect(0, 0, 1200, 2670), content=Rect(0, 0, 1200, 2670), orientation=ROTATION_0}' || continue
    DISPLAY_ID=$(printf '%s\n' "$PROJECTION" | sed -n 's/^Display \([0-9]*\) (physical.*/\1/p')
    # No concurrent desktop experiment or other copy of this backend.
    ps -A -o ARGS | grep 'app_process.*DriftDaemon' | grep -v grep >/dev/null && continue
    rm -f "$RUNDIR/stop" "$RUNDIR/heartbeat" "$RUNDIR/watchdog-ready" "$RUNDIR/worker-pid"
    : > "$RUNDIR/worker.log"
    run_backend watchdog > "$RUNDIR/watchdog.log" 2>&1 &
    WATCHDOG=$!
    READY=0
    for i in 1 2 3 4 5; do
        [ -f "$RUNDIR/watchdog-ready" ] && { READY=1; break; }
        sleep 1
    done
    if [ "$READY" = 1 ]; then
        run_backend run > "$RUNDIR/worker.log" 2>&1 &
        WORKER=$!
        wait "$WORKER"
        touch "$RUNDIR/stop"
    else
        touch "$RUNDIR/stop"
    fi
    wait "$WATCHDOG"
    # Avoid tight crash/restart loops. Consecutive startup failures are latched off.
    if ! grep -q '^RUNNING ' "$RUNDIR/worker.log" 2>/dev/null; then
        echo 'Backend initialization failed; restarting requires reboot. Inspect worker/watchdog.log.' > "$RUNDIR/supervisor.log"
        break
    fi
    sleep 3
done
