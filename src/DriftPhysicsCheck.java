/** Desktop self-check; no Android or device needed. */
public final class DriftPhysicsCheck {
    public static void main(String[] args) {
        DriftPhysics p = new DriftPhysics();
        for (int i = 0; i < 30; i++) p.step(4, -3, 1.0 / 120, 1800, 1.4, 0.65, 70);
        if (!(p.x < 0 && p.y > 0)) throw new AssertionError("response direction");
        for (int i = 0; i < 1200; i++) p.step(0, 0, 1.0 / 120, 1800, 1.4, 0.65, 70);
        if (Math.abs(p.x) + Math.abs(p.y) > 0.001) throw new AssertionError("settling");
        for (int i = 0; i < 10000; i++) {
            p.step(1000, -1000, 0.04, 1800, 1.4, 0.65, 70);
            if (Math.abs(p.x) > 70 || Math.abs(p.y) > 70) throw new AssertionError("limit");
        }
        p.step(Double.NaN, 0, 0.01, 1800, 1.4, 0.65, 70);
        if (p.x != 0 || p.y != 0) throw new AssertionError("invalid input");
        for (int fps : new int[]{20, 60, 120}) {
            p.reset();
            for (int i = 0; i < fps; i++) p.step(2, 0, 1.0 / fps, 1800, 1.4, 0.65, 70);
            boolean rebound = false;
            for (int i = 0; i < fps * 10; i++) {
                p.step(0, 0, 1.0 / fps, 1800, 1.4, 0.65, 70);
                if (p.x > 0.1) rebound = true;
            }
            if (!rebound || Math.abs(p.x) > 0.001) throw new AssertionError("rebound/settle at " + fps);
        }
        System.out.println("PASS direction, settling, limit, invalid input, rebound at 20/60/120fps");
    }
}
