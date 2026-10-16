package com.smashprofs.server.cluster;

import java.util.*;

/** Infrastructure-neutral decisions; provisioner must refuse stopping a worker with reservations. */
public final class AutoScalingController {
    public interface WorkerProvisioner {
        void start() throws Exception;
        void drain(String workerId) throws Exception;
        void stopEmpty(String workerId) throws Exception;
    }
    public record WorkerLoad(String id, double utilization, int rooms, boolean draining) { }
    private final int minimum, maximum;
    private final long cooldownMillis;
    private long lastAction;
    private int high, low;
    public AutoScalingController(int minimum, int maximum, long cooldownMillis) {
        if (minimum < 1 || maximum < minimum || cooldownMillis < 0) { throw new IllegalArgumentException("Invalid scaling limits"); }
        this.minimum=minimum; this.maximum=maximum; this.cooldownMillis=cooldownMillis;
    }
    public synchronized void reconcile(List<WorkerLoad> workers, long now, WorkerProvisioner provisioner) throws Exception {
        if (now - lastAction < cooldownMillis) { return; }
        for (var worker : workers) {
            if (worker.draining && worker.rooms == 0 && workers.size() > minimum) {
                provisioner.stopEmpty(worker.id); acted(now); return;
            }
        }
        var ready = workers.stream().filter(w -> !w.draining).toList();
        if (ready.size() < minimum && workers.size() < maximum) { provisioner.start(); acted(now); return; }
        double mean = ready.stream().mapToDouble(WorkerLoad::utilization).average().orElse(0);
        high = mean > .70 ? high + 1 : 0; low = mean < .20 ? low + 1 : 0;
        if (high >= 3 && workers.size() < maximum) { provisioner.start(); acted(now); }
        else if (low >= 10 && ready.size() > minimum) {
            var candidate = ready.stream().min(Comparator.comparingInt(WorkerLoad::rooms).thenComparing(WorkerLoad::id)).orElseThrow();
            provisioner.drain(candidate.id); acted(now);
        }
    }
    private void acted(long now) { lastAction=now; high=0; low=0; }
}
