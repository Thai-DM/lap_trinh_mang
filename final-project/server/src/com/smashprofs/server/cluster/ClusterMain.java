package com.smashprofs.server.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.StreamSupport;

/** Entrypoints: control config, worker config, standalone config, load URL clients seconds output. */
public final class ClusterMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) { throw new IllegalArgumentException("control|worker|standalone <config> OR load <url> <clients> <seconds> <output>"); }
        if (args[0].equals("load")) { LoadGenerator.main(Arrays.copyOfRange(args, 1, args.length)); return; }
        Properties config = new Properties(); try (var input = Files.newInputStream(Path.of(args[1]))) { config.load(input); }
        String secret = System.getenv("SMASHPROFS_CLUSTER_SECRET");
        switch (args[0]) {
            case "worker" -> {
                try (var worker = new WorkerRuntime(config, secret)) {
                    Runtime.getRuntime().addShutdownHook(new Thread(worker::close));
                    System.out.println("WORKER_READY " + config.getProperty("worker.id") + " tcp=" + worker.tcpPort() + " pid=" + ProcessHandle.current().pid());
                    worker.await();
                }
            }
            case "standalone" -> {
                var history = new FileMatchHistory(Path.of(config.getProperty("history.dir", "run/standalone-history")));
                var server = new com.smashprofs.server.network.GameServer("127.0.0.1",
                        WorkerRuntime.integer(config, "tcp.port", 9000, 0, 65535),
                        WorkerRuntime.integer(config, "max.connections", 1024, 4, 10000), history);
                server.start(); var done = new CountDownLatch(1);
                try (var admin = new HttpJson(WorkerRuntime.integer(config, "admin.port", 9200, 1, 65535), (path, body, credential) -> {
                    if (path.equals("/metrics")) { return Map.of("workers", List.of(Map.of("workerId", "standalone", "report", StandaloneMetrics.capture(server)))); }
                    HttpJson.requireSecret(credential, secret);
                    if(path.equals("/measurement/reset")) {
                        for(var room:server.getRoomManager().getRooms()) { var runtime=room.getMatchRuntime(); if(runtime!=null) { runtime.resetTickMetrics(); } }
                        server.getMetrics().resetMeasurement(); return Map.of("reset",true);
                    }
                    if(path.equals("/measurement/snapshot")) { return StandaloneMetrics.capture(server); }
                    if (path.equals("/admin/shutdown")) { return new HttpJson.AfterResponse(Map.of("stopping", true), done::countDown); }
                    throw new HttpJson.Failure(404, "UNKNOWN_ENDPOINT");
                })) {
                    Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
                    System.out.println("STANDALONE_READY " + server.getBoundPort()); done.await();
                } finally { server.stop(); }
            }
            case "control" -> {
                double[] weights = Arrays.stream(config.getProperty("allocation.weights", ".20,.25,.20,.10,.15,.10").split(",")).mapToDouble(Double::parseDouble).toArray();
                var allocator = new RoomAllocator(RoomAllocator.Algorithm.valueOf(config.getProperty("allocation.algorithm", "LOAD_AWARE")), weights);
                var control = new ControlPlane(secret, allocator);
                try (var processes = new LocalWorkers(config, control, secret);
                     var http = new HttpJson(WorkerRuntime.integer(config, "http.port", 8080, 1, 65535), (path, body, credential) -> {
                         if (path.equals("/admin/shutdown")) {
                             HttpJson.requireSecret(credential, secret);
                             return new HttpJson.AfterResponse(Map.of("draining", true),
                                     () -> new Thread(processes::close, "cluster-shutdown").start());
                         }
                         return control.handle(path, body, credential);
                     })) {
                    Runtime.getRuntime().addShutdownHook(new Thread(processes::close));
                    processes.startInitial();
                    System.out.println("CONTROL_READY http://127.0.0.1:" + http.port());
                    processes.await();
                }
            }
            default -> throw new IllegalArgumentException("Unknown mode: " + args[0]);
        }
    }

    /** Child JVM manager. All owned Process handles are tracked; no name-based process killing. */
    private static final class LocalWorkers implements AutoScalingController.WorkerProvisioner, AutoCloseable {
        private final Properties config; private final ControlPlane control; private final String secret;
        private final Map<String, Process> children = new LinkedHashMap<>();
        private final Map<String, Integer> adminPorts = new HashMap<>();
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        private final CountDownLatch done = new CountDownLatch(1);
        private final AutoScalingController autoscaler;
        private final int initial; private int serial;
        private boolean closed;
        LocalWorkers(Properties config, ControlPlane control, String secret) {
            this.config=config; this.control=control; this.secret=secret;
            initial = WorkerRuntime.integer(config, "workers.initial", 3, 1, 32);
            int minimum = WorkerRuntime.integer(config, "workers.min", initial, 1, 32);
            int maximum = WorkerRuntime.integer(config, "workers.max", initial, initial, 32);
            autoscaler = new AutoScalingController(minimum, maximum, 30_000);
        }
        void startInitial() throws Exception {
            for (int i=0; i<initial; i++) { start(); }
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    synchronized (this) {
                        if (closed) { return; }
                        JsonNode metrics = HttpJson.JSON.valueToTree(control.metrics());
                        var loads = new ArrayList<AutoScalingController.WorkerLoad>();
                        for (var worker : metrics.path("workers")) {
                            if (worker.path("health").asText().equals("LOST")) { continue; }
                            var c = worker.path("reserved");
                            double utilization = Math.max(c.path("rooms").asDouble() / c.path("maxRooms").asDouble(1),
                                    Math.max(c.path("cpu").asDouble(), Math.max(c.path("tickP99Ms").asDouble()/16.666667, c.path("heapRatio").asDouble())));
                            loads.add(new AutoScalingController.WorkerLoad(worker.path("workerId").asText(), utilization, c.path("rooms").asInt(), worker.path("health").asText().equals("DRAINING")));
                        }
                        children.entrySet().removeIf(e -> !e.getValue().isAlive());
                        if (config.getProperty("autoscaling.enabled", "false").equals("true")) {
                            autoscaler.reconcile(loads, System.currentTimeMillis(), this);
                        }
                    }
                } catch (Exception failure) { System.err.println("Autoscaling failed: " + failure.getClass().getSimpleName()); }
            }, 5, 2, TimeUnit.SECONDS);
        }
        @Override public synchronized void start() throws Exception {
            if (closed) { throw new IllegalStateException("Closed provisioner"); }
            int number = ++serial;
            String id = "worker-" + number;
            int tcp = WorkerRuntime.integer(config, "worker.tcp.base", 9000, 1024, 60000) + number;
            int admin = WorkerRuntime.integer(config, "worker.admin.base", 9100, 1024, 60000) + number;
            Properties worker = new Properties(); worker.putAll(config);
            worker.setProperty("worker.id", id); worker.setProperty("tcp.port", Integer.toString(tcp)); worker.setProperty("admin.port", Integer.toString(admin));
            worker.setProperty("control.url", "http://127.0.0.1:" + config.getProperty("http.port", "8080"));
            Path run = Path.of(config.getProperty("run.dir", "run/cluster")); Files.createDirectories(run);
            Path file = run.resolve(id + ".properties").toAbsolutePath();
            try (var output = Files.newOutputStream(file)) { worker.store(output, "Generated worker configuration; contains no credentials"); }
            String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            var arguments=new ArrayList<String>(List.of(java,"-Xms64m","-Xmx"+config.getProperty("worker.heap","512m"),"-XX:NativeMemoryTracking=summary"));
            for(String key:List.of("sharedSnapshots","indexedRecipients","stableRosters")) {
                arguments.add("-Dsmashprofs.performance."+key+"="+config.getProperty("performance."+key,"true"));
            }
            String jfr=config.getProperty("worker.jfr.dir","");
            if(!jfr.isBlank()) {
                Files.createDirectories(Path.of(jfr));
                arguments.add("-XX:StartFlightRecording=settings=profile,maxsize=16m,dumponexit=true,filename="+Path.of(jfr,id+".jfr"));
            }
            arguments.addAll(List.of("-cp",System.getProperty("java.class.path"),ClusterMain.class.getName(),"worker",file.toString()));
            var command = new ProcessBuilder(arguments);
            command.redirectErrorStream(true); command.redirectOutput(run.resolve(id + ".log").toFile());
            command.environment().put("SMASHPROFS_CLUSTER_SECRET", secret);
            Process child = command.start(); children.put(id, child); adminPorts.put(id, admin);
            // Readiness wait is bounded; workers must have registered before accepting allocations.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (child.isAlive() && System.nanoTime() < deadline) {
                try {
                    JsonNode report = HttpJson.call("http://127.0.0.1:" + admin, "/metrics", Map.of(), null);
                    boolean registered = StreamSupport.stream(
                            HttpJson.JSON.valueToTree(control.metrics()).path("workers").spliterator(), false)
                            .anyMatch(w -> w.path("workerId").asText().equals(id));
                    if (report.path("workerId").asText().equals(id) && registered) { return; }
                }
                catch (java.io.IOException pending) { Thread.sleep(100); }
            }
            child.destroy(); throw new java.io.IOException("Worker failed readiness: " + id + "; see " + run.resolve(id + ".log"));
        }
        @Override public void drain(String id) {
            control.handle("/workers/drain", HttpJson.object().put("workerId", id), secret);
        }
        @Override public synchronized void stopEmpty(String id) throws Exception {
            JsonNode state = HttpJson.JSON.valueToTree(control.metrics());
            var worker = java.util.stream.StreamSupport.stream(state.path("workers").spliterator(), false).filter(w -> w.path("workerId").asText().equals(id)).findFirst().orElseThrow();
            if (worker.path("reserved").path("rooms").asInt() != 0 || !worker.path("health").asText().equals("DRAINING")) { throw new IllegalStateException("Worker still owns rooms"); }
            HttpJson.call("http://127.0.0.1:" + adminPorts.get(id), "/shutdown", Map.of(), secret);
        }
        void await() throws InterruptedException { done.await(); }
        @Override public synchronized void close() {
            if (closed) { return; } closed=true; scheduler.shutdownNow();
            // Supervisor shutdown first drains all owners. Existing matches get a bounded grace window.
            for (String id : children.keySet()) { try { drain(id); } catch (RuntimeException ignored) { /* Startup may fail before registration. */ } }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WorkerRuntime.integer(config, "shutdown.grace.seconds", 10, 0, 180));
            while (System.nanoTime() < deadline && ((Number) control.metrics().get("activeRooms")).longValue() > 0) {
                try { Thread.sleep(200); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
            for (Process process : children.values()) { process.destroy(); }
            for (Process process : children.values()) {
                try { if (!process.waitFor(8, TimeUnit.SECONDS)) { process.destroyForcibly(); } }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
            }
            done.countDown();
        }
    }
}
