import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Display;
import android.view.Choreographer;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Experimental standalone root backend. No injection into system_server. */
public final class DriftDaemon implements SensorEventListener {
    private static final String HOME = System.getProperty("screen.drift.home", "/data/local/tmp/screen-drift");
    private final File heartbeat = new File(HOME, "heartbeat");
    private final File stop = new File(HOME, "stop");
    private final File workerPid = new File(HOME, "worker-pid");
    private final DriftPhysics physics = new DriftPhysics();
    private final double[] acceleration = new double[2];
    private final double[] gravity = new double[3];
    private boolean gravityReady;
    private long sensorTime;
    private SensorManager sensors;
    private Display display;
    private PowerManager power;
    private Object token, tx;
    private Method projection, apply, close;
    private int width, height, lastX, lastY;
    private double gain, frequency, damping, limit;
    private double verticalGain;
    private double peakAx, peakAy;
    private int peakX, peakY;
    private long samples;
    private long frames, submissions, submitNanos, maxSubmitNanos, frameNanos, maxFrameNanos;
    private final Rect sourceRect = new Rect();
    private final Rect targetRect = new Rect();
    private int fps, seconds;
    private boolean loopFailed;
    private HandlerThread animationThread;

    private void initialize(long id) throws Exception {
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context context = (Context) at.getMethod("getSystemContext").invoke(thread);
        sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        display = ((DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE)).getDisplay(0);
        power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        Class<?> dc = Class.forName("com.android.server.display.DisplayControl");
        Method load = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
        load.setAccessible(true);
        load.invoke(Runtime.getRuntime(), dc, "android_servers");
        token = dc.getMethod("getPhysicalDisplayToken", long.class).invoke(null, id);
        if (token == null) throw new IllegalStateException("Display token unavailable");
        Class<?> tc = Class.forName("android.view.SurfaceControl$Transaction");
        tx = tc.getConstructor().newInstance();
        projection = tc.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class);
        apply = tc.getMethod("apply", boolean.class);
        close = tc.getMethod("close");
    }

    private void project(int x, int y, boolean restore) throws Exception {
        int rotation = display.getRotation();
        if (!restore && rotation != 0) throw new IllegalStateException("Rotation changed: stopping");
        int w = rotation % 2 == 0 ? width : height;
        int h = rotation % 2 == 0 ? height : width;
        sourceRect.set(0, 0, w, h);
        targetRect.set(x, y, width + x, height + y);
        long start = System.nanoTime();
        projection.invoke(tx, token, rotation, sourceRect, targetRect);
        apply.invoke(tx, restore);
        long cost = System.nanoTime() - start;
        submissions++;
        submitNanos += cost;
        maxSubmitNanos = Math.max(maxSubmitNanos, cost);
        lastX = x;
        lastY = y;
    }

    private static double setting(Properties p, String name, double fallback, double min, double max) {
        double v = Double.parseDouble(p.getProperty(name, Double.toString(fallback)));
        if (!Double.isFinite(v) || v < min || v > max) throw new IllegalArgumentException(name);
        return v;
    }

    private void configure(String path) throws Exception {
        Properties p = new Properties();
        try (java.io.FileInputStream in = new java.io.FileInputStream(path)) { p.load(in); }
        gain = setting(p, "gain", 1800, 0, 6000);
        frequency = setting(p, "frequency", 1.4, 0.5, 5);
        damping = setting(p, "damping", 0.65, 0.2, 2);
        limit = setting(p, "limit", 60, 1, 120);
        verticalGain = setting(p, "verticalGain", 1, 0.1, 4);
        fps = (int) setting(p, "fps", 60, 20, 120);
        seconds = (int) setting(p, "seconds", 60, 5, 600);
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.values.length < 3) return;
        double ax = event.values[0], ay = event.values[1];
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            if (!gravityReady) {
                for (int i = 0; i < 3; i++) gravity[i] = event.values[i];
                gravityReady = true;
            }
            double dt = sensorTime == 0 ? 0.01 : Math.min(0.1, (event.timestamp - sensorTime) * 1e-9);
            double alpha = Math.exp(-dt / 0.35);
            for (int i = 0; i < 3; i++) gravity[i] = alpha * gravity[i] + (1 - alpha) * event.values[i];
            ax -= gravity[0];
            ay -= gravity[1];
        }
        sensorTime = event.timestamp;
        // Android sensor Y points up, display Y points down.
        acceleration[0] = Math.max(-15, Math.min(15, ax));
        acceleration[1] = Math.max(-15, Math.min(15, -ay));
        peakAx = Math.max(peakAx, Math.abs(acceleration[0]));
        peakAy = Math.max(peakAy, Math.abs(acceleration[1]));
        samples++;
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void run() throws Exception {
        try (FileOutputStream out = new FileOutputStream(workerPid)) {
            out.write(Integer.toString(android.os.Process.myPid()).getBytes("UTF-8"));
        }
        Sensor sensor = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        if (sensor == null) sensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        animationThread = new HandlerThread("screen-drift-animation");
        animationThread.start();
        final Handler handler = new Handler(animationThread.getLooper());
        final CountDownLatch finished = new CountDownLatch(1);
        if (sensor == null || !sensors.registerListener(this, sensor, 5000, 0, handler)) {
            throw new IllegalStateException("Sensor registration failed");
        }
        System.out.println("SENSOR " + sensor.getName());
        final long started = android.os.SystemClock.elapsedRealtime();
        handler.post(new Runnable() {
          @Override public void run() {
           final Choreographer choreographer = Choreographer.getInstance();
           choreographer.postFrameCallback(new Choreographer.FrameCallback() {
            long previous;
            long lastAccepted;
            long beat;
            @Override public void doFrame(long frameTimeNanos) {
                try {
                    long now = frameTimeNanos;
                    long elapsed = android.os.SystemClock.elapsedRealtime() - started;
                    if (stop.exists() || elapsed >= seconds * 1000L || !power.isInteractive()
                            || display.getRotation() != 0) {
                        finished.countDown();
                        return;
                    }
                    if (lastAccepted != 0 && now - lastAccepted < 1000000000L / fps - 1000000L) {
                        choreographer.postFrameCallback(this);
                        return;
                    }
                    lastAccepted = now;
                    long interval = previous == 0 ? 0 : now - previous;
                    if (interval > 0) { frameNanos += interval; maxFrameNanos = Math.max(maxFrameNanos, interval); }
                    frames++;
                    double dt = previous == 0 ? 1.0 / fps : interval * 1e-9;
                    previous = now;
                    boolean stale = sensorTime == 0 || android.os.SystemClock.elapsedRealtimeNanos() - sensorTime > 300000000L;
                    physics.step(stale ? 0 : acceleration[0], stale ? 0 : acceleration[1] * verticalGain, dt,
                            gain, frequency, damping, limit);
                    int x = (int) Math.round(physics.x), y = (int) Math.round(physics.y);
                    peakX = Math.max(peakX, Math.abs(x));
                    peakY = Math.max(peakY, Math.abs(y));
                    if (x != lastX || y != lastY) project(x, y, false);
                    if (elapsed - beat >= 200 || beat == 0) {
                        try (FileOutputStream out = new FileOutputStream(heartbeat)) { out.write(1); }
                        beat = elapsed;
                    }
                    choreographer.postFrameCallback(this);
                } catch (Throwable error) {
                    loopFailed = true;
                    error.printStackTrace();
                    finished.countDown();
                }
            }
           });
          }
        });
        System.out.println("RUNNING duration=" + seconds + "s limit=" + limit + "px");
        System.out.flush();
        if (!finished.await(seconds + 3L, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Animation did not finish before deadline");
        }
        if (loopFailed) throw new IllegalStateException("Animation loop failed; see previous exception");
    }

    private void watchdog() throws Exception {
        File ready = new File(HOME, "watchdog-ready");
        try (FileOutputStream out = new FileOutputStream(ready)) { out.write(1); }
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < seconds * 1000L + 5000) {
            long age = System.currentTimeMillis() - heartbeat.lastModified();
            if (stop.exists() || (System.currentTimeMillis() - start > 5000 && age > 2000)) break;
            Thread.sleep(200);
        }
        // Stop the worker before restoring so it cannot overwrite the recovery.
        try (FileOutputStream out = new FileOutputStream(stop)) { out.write(1); }
        Thread.sleep(400);
        // If the worker stopped updating its heartbeat because it hung, do not
        // let it resume later and overwrite the restored projection.
        if (workerPid.exists()) {
            try {
                String pidText = readSmallFile(workerPid).trim();
                int pid = Integer.parseInt(pidText);
                if (pid > 1 && pid != android.os.Process.myPid()) {
                    String cmdline = readSmallFile(new File("/proc/" + pid + "/cmdline"));
                    if (cmdline.contains("DriftDaemon") && cmdline.contains("\u0000run\u0000")) {
                        android.os.Process.killProcess(pid);
                        Thread.sleep(200);
                    }
                }
            } catch (java.io.FileNotFoundException alreadyExited) {
                // Process exited between the pid and cmdline checks.
            }
        }
        project(0, 0, true);
        System.out.println("WATCHDOG_RESTORED");
        ready.delete();
    }

    private static String readSmallFile(File file) throws Exception {
        byte[] buffer = new byte[4096];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int n = in.read(buffer);
            return n < 0 ? "" : new String(buffer, 0, n, "UTF-8");
        }
    }

    public static void main(String[] args) {
        DriftDaemon daemon = new DriftDaemon();
        int status = 1;
        try {
            if (args.length != 5) throw new IllegalArgumentException("mode displayId width height config");
            String mode = args[0];
            if (!mode.equals("run") && !mode.equals("watchdog") && !mode.equals("restore")) {
                throw new IllegalArgumentException("Invalid mode");
            }
            daemon.width = Integer.parseInt(args[2]);
            daemon.height = Integer.parseInt(args[3]);
            if (daemon.width != 1200 || daemon.height != 2670) throw new IllegalArgumentException("Unverified dimensions");
            daemon.configure(args[4]);
            daemon.initialize(Long.parseLong(args[1]));
            if (mode.equals("watchdog")) daemon.watchdog();
            else if (mode.equals("restore")) daemon.project(0, 0, true);
            else daemon.run();
            status = 0;
        } catch (Throwable error) {
            error.printStackTrace();
        } finally {
            if (daemon.sensors != null) daemon.sensors.unregisterListener(daemon);
            if (daemon.animationThread != null) {
                daemon.animationThread.quitSafely();
                try { daemon.animationThread.join(500); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            if (daemon.tx != null) {
                try { daemon.project(0, 0, true); daemon.close.invoke(daemon.tx); }
                catch (Throwable error) { status = 1; error.printStackTrace(); }
            }
            System.out.println("MOTION samples=" + daemon.samples + " peakAx=" + daemon.peakAx
                    + " peakAy=" + daemon.peakAy + " peakXpx=" + daemon.peakX + " peakYpx=" + daemon.peakY);
            System.out.println("PERF frames=" + daemon.frames + " estimatedUpdateHz="
                    + (daemon.frameNanos == 0 ? 0 : (daemon.frames - 1) * 1e9 / daemon.frameNanos)
                    + " maxFrameMs=" + daemon.maxFrameNanos / 1e6 + " submissions=" + daemon.submissions
                    + " avgSubmitMs=" + (daemon.submissions == 0 ? 0 : daemon.submitNanos / 1e6 / daemon.submissions)
                    + " maxSubmitMs=" + daemon.maxSubmitNanos / 1e6);
            System.out.println("EXIT restored-attempted status=" + status);
            System.out.flush();
            System.err.flush();
            // libandroid_servers teardown is broken in the tested ROM; always cleanup first.
            try { Class.forName("android.system.Os").getMethod("_exit", int.class).invoke(null, status); }
            catch (Throwable error) { throw new RuntimeException(error); }
        }
    }
}
