package hle.org;

public final class RunResult {
    private final Stats stats;
    private final int activeResources;
    private final int idleResources;

    RunResult(Stats stats, int activeResources, int idleResources) {
        this.stats = stats;
        this.activeResources = activeResources;
        this.idleResources = idleResources;
    }

    public Stats getStats() {
        return stats;
    }

    public int getActiveResources() {
        return activeResources;
    }

    public int getIdleResources() {
        return idleResources;
    }
}
