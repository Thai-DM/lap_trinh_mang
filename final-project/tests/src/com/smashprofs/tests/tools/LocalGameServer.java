package com.smashprofs.tests.tools;

import com.smashprofs.server.network.GameServer;
import com.smashprofs.server.ranking.InMemoryRankingStore;
import com.smashprofs.server.ranking.LeaderboardBroadcaster;
import com.smashprofs.server.ranking.PlayerRecord;
import com.smashprofs.server.ranking.RankingService;
import com.smashprofs.server.ranking.RankingStore;
import com.smashprofs.server.ranking.SqliteRankingStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Local manual desktop smoke helper; reuses GameServer's public API with ranking service. */
public final class LocalGameServer {
    private LocalGameServer() { }
    record Configuration(int port, String bindHost) { }

    static Configuration parseArguments(String[] args) {
        if (args.length > 2) { throw new IllegalArgumentException("Usage: LocalGameServer [port] [bindHost]"); }
        int port = args.length == 0 ? 54555 : Integer.parseInt(args[0]);
        String host = args.length < 2 ? "127.0.0.1" : args[1];
        if (port < 0 || port > 65535 || host.isBlank() || !host.equals(host.strip())) {
            throw new IllegalArgumentException("Port must be 0..65535; bindHost must be nonblank without surrounding spaces");
        }
        return new Configuration(port, host);
    }

    private static void seedDefaultRankingsIfEmpty(RankingStore store) {
        if (store.getLeaderboard().isEmpty()) {
            Map<String, PlayerRecord> seed = new HashMap<>();
            seed.put("Alex_Pro", new PlayerRecord("Alex_Pro", 1540.0, 48, 12, 2, 62));
            seed.put("Leo_King", new PlayerRecord("Leo_King", 1420.0, 36, 18, 1, 55));
            seed.put("Luca_Master", new PlayerRecord("Luca_Master", 1310.0, 29, 21, 4, 54));
            seed.put("DragonSlayer", new PlayerRecord("DragonSlayer", 1250.0, 22, 19, 0, 41));
            seed.put("ShadowNinja", new PlayerRecord("ShadowNinja", 1180.0, 18, 20, 3, 41));
            seed.put("Phoenix_VN", new PlayerRecord("Phoenix_VN", 1090.0, 14, 25, 1, 40));
            seed.put("Rookie_01", new PlayerRecord("Rookie_01", 1000.0, 5, 15, 0, 20));
            store.saveMatchResult("seed_init_001", seed);
        }
    }

    private static RankingStore createRankingStore() {
        try {
            var sqlite = new SqliteRankingStore("ranking.db");
            seedDefaultRankingsIfEmpty(sqlite);
            return sqlite;
        } catch (Throwable e) {
            System.err.println("Note: SQLite unavailable (" + e.getMessage() + "), using in-memory ranking store.");
            var mem = new InMemoryRankingStore();
            seedDefaultRankingsIfEmpty(mem);
            return mem;
        }
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        var configuration = parseArguments(args);
        String host = InetAddress.getByName(configuration.bindHost()).getHostAddress();

        final RankingStore rankingStore = createRankingStore();
        var rankingService = new RankingService(rankingStore);
        var broadcaster = new LeaderboardBroadcaster();

        var server = GameServer.withRanking(host, configuration.port(), 8, rankingService, broadcaster);
        var shutdown = new Thread(() -> {
            server.stop();
            rankingStore.close();
        }, "local-server-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            server.start();
            System.out.println("Local GameServer listening on " + host + ":" + server.getBoundPort()
                    + " (configured host=" + configuration.bindHost() + "; type stop + Enter or Ctrl+C to stop)");
            var console = new BufferedReader(new InputStreamReader(System.in));
            String command;
            while ((command = console.readLine()) != null) {
                if ("stop".equalsIgnoreCase(command.strip())) { return; }
            }
            // A background/redirected launch with no stdin must stay alive until process shutdown.
            new CountDownLatch(1).await();
        } finally {
            server.stop();
            rankingStore.close();
            try { Runtime.getRuntime().removeShutdownHook(shutdown); }
            catch (IllegalStateException shuttingDown) { /* The shutdown hook is already running. */ }
        }
    }
}
