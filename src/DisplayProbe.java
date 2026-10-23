import java.lang.reflect.Method;

/** Read-only inventory: never submits a display transaction. */
public final class DisplayProbe {
    public static void main(String[] args) {
        String[] names = {"android.view.SurfaceControl", "android.view.SurfaceControl$Transaction",
                "com.android.server.display.DisplayControl", "android.view.DisplayControl"};
        for (String name : names) {
            System.out.println("CLASS " + name);
            try {
                Class<?> type = Class.forName(name, false, ClassLoader.getSystemClassLoader());
                for (Method method : type.getDeclaredMethods()) {
                    String n = method.getName();
                    if (n.contains("Display") || n.equals("apply") || n.equals("close")) {
                        System.out.println("  " + method);
                    }
                }
                if (name.equals("com.android.server.display.DisplayControl")
                        && args.length == 1 && args[0].equals("--experimental-load-native")) {
                    Method load = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
                    load.setAccessible(true);
                    load.invoke(Runtime.getRuntime(), type, "android_servers");
                    long[] ids = (long[]) type.getDeclaredMethod("getPhysicalDisplayIds").invoke(null);
                    for (long id : ids) {
                        Object token = type.getDeclaredMethod("getPhysicalDisplayToken", long.class).invoke(null, id);
                        System.out.println("  PHYSICAL_DISPLAY id=" + id + " token=" + token);
                    }
                }
            } catch (Throwable error) {
                System.out.println("  UNAVAILABLE: " + error);
            }
        }
        if (args.length == 1 && args[0].equals("--experimental-load-native")) {
            // Standalone app_process hits a native static-destructor crash on this ROM.
            // No display transactions or resources to restore in this read-only probe.
            System.out.flush();
            try {
                Class.forName("android.system.Os").getMethod("_exit", int.class).invoke(null, 0);
            } catch (Throwable error) {
                throw new RuntimeException(error);
            }
        }
    }
}
