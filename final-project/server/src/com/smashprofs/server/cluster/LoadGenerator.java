package com.smashprofs.server.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.smashprofs.common.model.*;
import com.smashprofs.common.protocol.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real framed v1 TCP bots; HTTP assignment is used only in cluster mode. */
public final class LoadGenerator {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) { throw new IllegalArgumentException("<http://control OR tcp://host:port> <2..1000 clients, even> <seconds> <output.json>"); }
        int clients = Integer.parseInt(args[1]), seconds = Integer.parseInt(args[2]);
        if (clients < 2 || clients > 1000 || clients % 2 != 0 || seconds < 1 || seconds > 600) { throw new IllegalArgumentException("Invalid load limits"); }
        var result = run(args[0], clients, seconds);
        Path output = Path.of(args[3]); if (output.toAbsolutePath().getParent() != null) { Files.createDirectories(output.toAbsolutePath().getParent()); }
        HttpJson.JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
        System.out.println(HttpJson.JSON.writeValueAsString(result));
        if (((Number) result.get("connectionFailures")).intValue() > 0 || ((Number) result.get("protocolErrors")).longValue() > 0
                || ((Number) result.get("unexpectedDisconnects")).longValue() > 0
                || ((Number) result.get("startedClients")).intValue() != clients
                || ((Number) result.get("clientsWithSnapshots")).intValue() != clients) { throw new IOException("Load validation failed; inspect saved output"); }
    }
    public static Map<String, Object> run(String endpoint, int clients, int seconds) throws Exception {
        var profile=LoadWorkload.Profile.valueOf(System.getProperty("smashprofs.load.profile","IDLE"));
        if(profile==LoadWorkload.Profile.CHURN) { return churn(endpoint,clients,seconds); }
        return runMatch(endpoint,clients,seconds,profile);
    }
    private static Map<String,Object> runMatch(String endpoint,int clients,int seconds,LoadWorkload.Profile profile) throws Exception {
        boolean cluster = endpoint.startsWith("http");
        var bots = new ArrayList<Bot>(); var failures = new ArrayList<String>();
        var readers = new ThreadPoolExecutor(0,clients,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r -> {
            var t = new Thread(r, "load-reader"); t.setDaemon(true); return t;
        });
        var sender = Executors.newSingleThreadScheduledExecutor();
        var workerIds = new TreeSet<String>();
        long setupStart = System.nanoTime();
        JsonNode startMetrics = HttpJson.object(), endMetrics = HttpJson.object();
        try {
            for (int base = 0; base < clients; base += 4) {
                int size = Math.min(4, clients - base);
                var roster = new ArrayList<Bot>(); String room = "load_" + UUID.randomUUID().toString().replace("-", "");
                for (int slot = 0; slot < size; slot++) {
                    Bot bot = null;
                    try {
                        String host; int port; JsonNode assignment = null;
                        if (cluster) {
                            var auth = HttpJson.call(endpoint, "/auth/guest", Map.of(), null);
                            String token = auth.path("token").asText();
                            assignment = HttpJson.call(endpoint, slot == 0 ? "/rooms/create" : "/rooms/join",
                                    slot == 0 ? Map.of("region", "local") : Map.of("roomId", room), token);
                            room = assignment.path("roomId").asText(); host = assignment.path("host").asText(); port = assignment.path("port").asInt();
                            workerIds.add(assignment.path("workerId").asText());
                        } else { URI uri = URI.create(endpoint); host = uri.getHost(); port = uri.getPort(); workerIds.add("standalone"); }
                        bot = new Bot(host, port,profile,base+slot); bot.room=room; bots.add(bot);
                        var connect = ProtocolV1.object();
                        if (cluster) { connect.put("capability", "cluster-v1").put("assignment", assignment.path("ticket").asText()); }
                        var ack = bot.request(MessageType.CONNECT, connect); bot.player = new PlayerId(ack.payload().path("playerId").asText());
                        if (!cluster && slot == 0) { bot.request(MessageType.CREATE_ROOM, ProtocolV1.object().put("roomId", room)); }
                        bot.request(MessageType.JOIN_ROOM, ProtocolV1.object().put("roomId", room));
                        roster.add(bot);
                    } catch (Exception failure) {
                        failures.add(failure.getClass().getSimpleName() + ": " + failure.getMessage());
                        if (bot != null) { bot.close(); }
                        // Stop increasing load safely on the first setup failure.
                        break;
                    }
                }
                if (!failures.isEmpty()) { break; }
                String[] characters = {"Alex", "Luca", "Maurice", "Leo"};
                for (int slot = 0; slot < roster.size(); slot++) {
                    var bot = roster.get(slot);
                    bot.request(MessageType.PLAYER_READY, ProtocolV1.object().put("ready", true).put("characterId", characters[slot]));
                    readers.submit(bot::read);
                }
            }
            long setupNanos = System.nanoTime() - setupStart;
            sender.scheduleAtFixedRate(() -> bots.forEach(Bot::input), 0, 50, TimeUnit.MILLISECONDS);
            long warmupStarted = System.nanoTime();
            long warmupDeadline = warmupStarted + TimeUnit.SECONDS.toNanos(10);
            while (bots.stream().anyMatch(b -> !b.everStarted.get()) && System.nanoTime() < warmupDeadline && failures.isEmpty()) {
                new CountDownLatch(1).await(50, TimeUnit.MILLISECONDS);
            }
            int gameplayWarmup=Integer.getInteger("smashprofs.load.warmupSeconds",0);
            if(gameplayWarmup<0 || gameplayWarmup>60) { throw new IllegalArgumentException("warmup seconds 0..60"); }
            new CountDownLatch(1).await(gameplayWarmup,TimeUnit.SECONDS);
            var startupMetrics=sampleEndpoints(false);
            sampleEndpoints(true);
            bots.forEach(b -> b.interArrival.reset());
            long completedBeforeMeasurement=bots.stream().filter(b -> b.ends.sum()>0).count();
            if (cluster) { startMetrics = HttpJson.call(endpoint, "/metrics", Map.of(), null); }
            long[] snapshotsBefore = bots.stream().mapToLong(b -> b.snapshots.sum()).toArray();
            long[] bytesBefore = bots.stream().mapToLong(b -> b.bytes.sum()).toArray();
            long started = System.nanoTime();
            // Wait on a completion signal so callers can interrupt safely.
            new CountDownLatch(1).await(seconds, TimeUnit.SECONDS);
            double elapsed = (System.nanoTime() - started) / 1e9;
            if (cluster) { endMetrics = HttpJson.call(endpoint, "/metrics", Map.of(), null); }
            var measuredMetrics=sampleEndpoints(false);
            long snapshots = 0, bytes = 0;
            for (int i=0; i<bots.size(); i++) { snapshots += bots.get(i).snapshots.sum()-snapshotsBefore[i]; bytes += bots.get(i).bytes.sum()-bytesBefore[i]; }
            var result = new LinkedHashMap<String, Object>();
            result.put("endpoint", endpoint); result.put("requestedClients", clients); result.put("connectedClients", bots.stream().filter(b -> b.player != null).count());
            result.put("startedClients", bots.stream().filter(b -> b.everStarted.get()).count()); result.put("clientsWithSnapshots", bots.stream().filter(b -> b.snapshots.sum() > 0).count());
            result.put("connectionFailures", failures.size()); result.put("failureDetails", failures);
            result.put("protocolErrors", bots.stream().mapToLong(b -> b.errors.sum()).sum());
            result.put("unexpectedDisconnects", bots.stream().mapToLong(b -> b.disconnects.sum()).sum());
            result.put("gameEnds", bots.stream().mapToLong(b -> b.ends.sum()).sum()); result.put("workersUsed", workerIds);
            result.put("setupSeconds", setupNanos / 1e9); result.put("measurementSeconds", elapsed);
            result.put("warmupSeconds", (started - warmupStarted) / 1e9);
            result.put("snapshotDeliveries", snapshots); result.put("snapshotDeliveriesPerSecond", snapshots / elapsed);
            result.put("deliveriesPerClientPerSecond", snapshots / elapsed / Math.max(1, bots.size())); result.put("receivedBytesPerSecond", bytes / elapsed);
            result.put("inputsSent", bots.stream().mapToLong(b -> b.inputs.sum()).sum());
            result.put("startMetrics", startMetrics); result.put("endMetrics", endMetrics);
            result.put("profile",profile.name()); result.put("gameplayWarmupSeconds",gameplayWarmup);
            result.put("completedBeforeMeasurement",completedBeforeMeasurement);
            result.put("startupWorkerMetrics",startupMetrics); result.put("measurementWorkerMetrics",measuredMetrics);
            result.put("expectedSlowDisconnects",bots.stream().mapToLong(b -> b.slowDisconnects.sum()).sum());
            result.put("snapshotInterArrival",bots.stream().map(b -> b.interArrival.snapshot()).toList());
            result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
            result.put("jdk", System.getProperty("java.runtime.version")); result.put("processors", Runtime.getRuntime().availableProcessors());
            result.put("generatorMaxHeapBytes", Runtime.getRuntime().maxMemory());
            return result;
        } finally { sender.shutdownNow(); bots.forEach(Bot::close); readers.shutdownNow(); readers.awaitTermination(5, TimeUnit.SECONDS); }
    }
    private static List<JsonNode> sampleEndpoints(boolean reset) throws IOException {
        String endpoints=System.getProperty("smashprofs.load.metricsEndpoints",""); var reports=new ArrayList<JsonNode>();
        for(String url:endpoints.split(",")) {
            if(url.isBlank()) { continue; }
            reports.add(HttpJson.call(url,reset?"/measurement/reset":"/measurement/snapshot",Map.of(),System.getenv("SMASHPROFS_CLUSTER_SECRET")));
        }
        return reports;
    }
    private static Map<String,Object> churn(String endpoint,int clients,int seconds) throws Exception {
        if(!endpoint.startsWith("http")) { throw new IllegalArgumentException("CHURN requires cluster cleanup lifecycle"); }
        long started=System.nanoTime(); var rounds=new ArrayList<Map<String,Object>>();
        while((System.nanoTime()-started)/1e9<seconds) { rounds.add(runMatch(endpoint,clients,Math.min(3,seconds),LoadWorkload.Profile.MOVEMENT)); }
        var result=new LinkedHashMap<String,Object>(rounds.get(rounds.size()-1)); result.put("profile","CHURN"); result.put("rounds",rounds);
        result.put("connectionFailures",rounds.stream().mapToLong(r -> ((Number)r.get("connectionFailures")).longValue()).sum());
        result.put("protocolErrors",rounds.stream().mapToLong(r -> ((Number)r.get("protocolErrors")).longValue()).sum());
        result.put("unexpectedDisconnects",rounds.stream().mapToLong(r -> ((Number)r.get("unexpectedDisconnects")).longValue()).sum());
        result.put("churnElapsedSeconds",(System.nanoTime()-started)/1e9); return result;
    }
    private static final class Bot implements AutoCloseable {
        final Socket socket = new Socket(); final JsonFrameCodec codec = new JsonFrameCodec();
        final LongAdder snapshots = new LongAdder(), bytes = new LongAdder(), errors = new LongAdder(), ends = new LongAdder(), inputs = new LongAdder(), disconnects = new LongAdder();
        final AtomicBoolean started = new AtomicBoolean(), closed = new AtomicBoolean();
        final AtomicBoolean everStarted = new AtomicBoolean();
        final LoadWorkload.Profile profile; final int index; final boolean slow;
        final LongAdder slowDisconnects=new LongAdder();
        final com.smashprofs.server.metrics.PerformanceMetrics.Histogram interArrival=new com.smashprofs.server.metrics.PerformanceMetrics.Histogram(50_000_000);
        volatile String room; String match; Set<String> roster=Set.of(); long lastTick=-1,lastStateAt; JsonNode finalState;
        volatile PlayerId player; long sequence; int requests;
        Bot(String host, int port,LoadWorkload.Profile profile,int index) throws IOException {
            this.profile=profile;this.index=index;slow=profile==LoadWorkload.Profile.SLOW_CLIENT&&index%4==0;
            try { if(slow) { socket.setReceiveBufferSize(1024); } socket.connect(new InetSocketAddress(host, port), 3000); socket.setSoTimeout(20_000); socket.setTcpNoDelay(true); }
            catch (IOException failure) { socket.close(); throw failure; }
        }
        synchronized void send(ProtocolMessage message) throws IOException {
            byte[] frame = codec.encode(message); socket.getOutputStream().write(frame); socket.getOutputStream().flush();
        }
        ProtocolMessage request(MessageType type, com.fasterxml.jackson.databind.node.ObjectNode payload) throws IOException {
            String id = "load-" + requests++; send(new ProtocolMessage(type, id, payload));
            while (true) {
                var message = receive(); if (message == null) { throw new EOFException(); }
                if (message.type() == MessageType.ERROR) { throw new IOException(message.payload().path("code").asText()); }
                if (id.equals(message.requestId())) { return message; }
            }
        }
        ProtocolMessage receive() throws IOException {
            var message = codec.readNext(socket.getInputStream());
            if (message == null) { return null; }
            bytes.add(codec.encode(message).length);
            switch (message.type()) {
                case PING -> send(new ProtocolMessage(MessageType.PONG, message.requestId(), ProtocolV1.object()));
                case GAME_START -> {
                    if(everStarted.get() || !room.equals(message.payload().path("roomId").asText())) { throw invalid("Unexpected GAME_START ownership"); }
                    match=message.payload().path("matchId").asText(); var ids=new HashSet<String>();
                    for(var participant:message.payload().path("players")) { if(!ids.add(participant.path("playerId").asText())) { throw invalid("Duplicate roster"); } }
                    if(ids.size()<2 || ids.size()>4 || player==null || !ids.contains(player.value())) { throw invalid("Invalid roster"); }
                    roster=Set.copyOf(ids); started.set(true); everStarted.set(true);
                }
                case GAME_STATE -> {
                    if (!message.payload().path("players").isArray() || !message.payload().path("serverTick").isIntegralNumber()) { throw new IOException("Invalid snapshot"); }
                    var p=message.payload(); long tick=p.path("serverTick").asLong();
                    if(!everStarted.get() || !room.equals(p.path("roomId").asText()) || !match.equals(p.path("matchId").asText()) || tick<=lastTick) { throw invalid("Snapshot ownership/sequence"); }
                    var ids=new HashSet<String>(); for(var player:p.path("players")) {
                        if(!ids.add(player.path("playerId").asText())) { throw invalid("Duplicate snapshot participant"); }
                        for(String key:new String[]{"x","y","velocityX","velocityY","hp"}) {
                            if(!player.path(key).isNumber() || !Double.isFinite(player.path(key).asDouble())) { throw invalid("Invalid numeric state"); }
                        }
                    }
                    if(!ids.equals(roster)) { throw invalid("Snapshot roster changed"); } lastTick=tick;
                    long now=System.nanoTime(); if(lastStateAt!=0) { interArrival.record(now-lastStateAt); } lastStateAt=now;
                    snapshots.increment();
                    if (message.payload().path("phase").asText().equals("FINISHED")) { started.set(false); finalState=p; }
                }
                case GAME_END -> {
                    var p=message.payload(); var winner=p.path("winnerId");
                    if(finalState==null || ends.sum()!=0 || !room.equals(p.path("roomId").asText()) || !match.equals(p.path("matchId").asText())
                            || p.path("finishedAtTick").asLong()!=lastTick || (!winner.isNull()&&!roster.contains(winner.asText()))
                            || p.path("participants").size()!=roster.size()) { throw invalid("Invalid GAME_END"); }
                    var ids=new HashSet<String>();
                    for(var participant:p.path("participants")) {
                        String id=participant.path("playerId").asText();
                        if(!ids.add(id) || !roster.contains(id)) { throw invalid("Invalid result roster"); }
                        JsonNode finalPlayer=null;
                        for(var state:finalState.path("players")) { if(id.equals(state.path("playerId").asText())) { finalPlayer=state; break; } }
                        if(finalPlayer==null || !participant.path("finalHp").isNumber()
                                || Double.compare(participant.path("finalHp").asDouble(),finalPlayer.path("hp").asDouble())!=0
                                || participant.path("slotIndex").asInt(-1)!=finalPlayer.path("slotIndex").asInt(-2)
                                || !participant.path("characterId").asText().equals(finalPlayer.path("characterId").asText())
                                || participant.path("eliminated").asBoolean()!=finalPlayer.path("dead").asBoolean()) { throw invalid("Result differs from final state"); }
                    }
                    ends.increment(); started.set(false);
                }
                case ERROR -> errors.increment();
                default -> { }
            }
            return message;
        }
        IOException invalid(String reason) { errors.increment(); return new IOException(reason); }
        void read() {
            try { while (!closed.get()) {
                if(slow) { Thread.sleep(Integer.getInteger("smashprofs.load.slowReadMillis",250)); }
                if(receive()==null) { disconnected(); return; }
            } } catch (IOException failure) { disconnected(); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        void disconnected() { if(!closed.get() && ends.sum()==0) { if(slow) { slowDisconnects.increment(); } else { disconnects.increment(); } started.set(false); } }
        void input() {
            if (!started.get() || closed.get()) { return; }
            try {
                // Stationary valid input keeps benchmark rooms alive and comparable across runs.
                var payload = ProtocolV1.inputPayload(LoadWorkload.input(profile,player,sequence++,index));
                send(new ProtocolMessage(MessageType.PLAYER_INPUT, null, payload)); inputs.increment();
            } catch (IOException failure) { if(slow) { disconnected(); } else { errors.increment(); } close(); }
        }
        @Override public void close() { closed.set(true); try { socket.close(); } catch (IOException ignored) { } }
    }
}
