import android.graphics.Rect;
import android.os.IBinder;
import java.lang.reflect.Method;

/** Bounded visual experiment; watchdog must be armed by the host before shift. */
public final class ProjectionTest {
    private static Object token;
    private static Class<?> transaction;

    private static void project(int width, int height, int dx) throws Exception {
        Object tx = transaction.getConstructor().newInstance();
        try {
            transaction.getMethod("setDisplayProjection", IBinder.class, int.class,
                    Rect.class, Rect.class).invoke(tx, token, 0,
                    new Rect(0, 0, width, height), new Rect(dx, 0, width + dx, height));
            transaction.getMethod("apply", boolean.class).invoke(tx, true);
        } finally {
            transaction.getMethod("close").invoke(tx);
        }
    }

    public static void main(String[] args) {
        int status = 1;
        boolean loaded = false;
        try {
            if (args.length != 4) throw new IllegalArgumentException("mode id width height");
            String mode = args[0];
            if (!mode.equals("watchdog") && !mode.equals("shift")) {
                throw new IllegalArgumentException("invalid mode");
            }
            long id = Long.parseLong(args[1]);
            int width = Integer.parseInt(args[2]);
            int height = Integer.parseInt(args[3]);
            if (width != 1200 || height != 2670) throw new IllegalArgumentException("unexpected size");
            Class<?> control = Class.forName("com.android.server.display.DisplayControl");
            Method load = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
            load.setAccessible(true);
            load.invoke(Runtime.getRuntime(), control, "android_servers");
            loaded = true;
            token = control.getMethod("getPhysicalDisplayToken", long.class).invoke(null, id);
            if (token == null) throw new IllegalStateException("no display token");
            transaction = Class.forName("android.view.SurfaceControl$Transaction");
            if (mode.equals("watchdog")) {
                System.out.println("WATCHDOG_READY");
                System.out.flush();
                Thread.sleep(7000);
                project(width, height, 0);
                System.out.println("WATCHDOG_RESTORED");
            } else {
                try {
                    project(width, height, 100);
                    System.out.println("SHIFT_SUBMITTED dx=100 durationMs=4000");
                    System.out.flush();
                    Thread.sleep(4000);
                } finally {
                    project(width, height, 0);
                    System.out.println("MAIN_RESTORED");
                }
            }
            status = 0;
        } catch (Throwable error) {
            error.printStackTrace();
        } finally {
            System.out.flush();
            System.err.flush();
            if (loaded) {
                // Explicit cleanup above happens before bypassing broken native destructors.
                try {
                    Class.forName("android.system.Os").getMethod("_exit", int.class).invoke(null, status);
                } catch (Throwable exitError) {
                    throw new RuntimeException(exitError);
                }
            }
        }
    }
}
