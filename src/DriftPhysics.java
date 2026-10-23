/** Pure Java spring model, independent of Android for desktop verification. */
public final class DriftPhysics {
    public double x, y;
    private double vx, vy;
    public void step(double ax, double ay, double dt, double gain, double frequency,
                     double damping, double limit) {
        if (!Double.isFinite(ax) || !Double.isFinite(ay) || !Double.isFinite(dt)) {
            reset();
            return;
        }
        dt = Math.max(0, Math.min(dt, 0.05));
        int count = Math.max(1, (int) Math.ceil(dt / 0.004));
        double h = dt / count;
        double omega = 2 * Math.PI * frequency;
        for (int i = 0; i < count; i++) {
            vx += (-gain * ax - omega * omega * x - 2 * damping * omega * vx) * h;
            vy += (-gain * ay - omega * omega * y - 2 * damping * omega * vy) * h;
            x += vx * h;
            y += vy * h;
            if (Math.abs(x) > limit) { x = Math.copySign(limit, x); vx = 0; }
            if (Math.abs(y) > limit) { y = Math.copySign(limit, y); vy = 0; }
        }
    }
    public void reset() { x = y = vx = vy = 0; }
}
