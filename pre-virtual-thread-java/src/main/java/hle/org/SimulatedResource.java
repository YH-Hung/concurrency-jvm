package hle.org;

public final class SimulatedResource {
    private final int id;

    SimulatedResource(int id) {
        this.id = id;
    }

    public void perform(int sleepMs, int cpuIterations) throws InterruptedException {
        if (sleepMs > 0) {
            Thread.sleep(sleepMs);
        }
        if (cpuIterations > 0) {
            double sink = 0.0;
            for (int i = 0; i < cpuIterations; i++) {
                sink += Math.sqrt((i + 1) % 1000);
            }
            if (sink == Double.MAX_VALUE) {
                System.out.print("");
            }
        }
    }

    public int getId() {
        return id;
    }
}
