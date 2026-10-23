package com.smashprofs.web;
import com.smashprofs.probe.ProbeGateway;
import com.smashprofs.server.network.GameServer;
import com.smashprofs.server.ranking.*;
import java.nio.file.*;

/** Existing authoritative game/ranking services behind the bounded WebSocket bridge. */
public final class GameGateway {
    public static void main(String[] args) throws Exception {
        int port=args.length>0?Integer.parseInt(args[0]):8090;
        var file=Path.of(args.length>1?args[1]:"build/run/ranking.db").toAbsolutePath();
        Files.createDirectories(file.getParent());
        try(var store=new SqliteRankingStore(file.toString())) {
            var ranking=new RankingService(store);
            try(var broadcast=new LeaderboardBroadcaster(ranking);
                var gateway=new ProbeGateway(System.getProperty("web.bind.host","127.0.0.1"),port,GameServer.withRanking(ranking,broadcast),32,
                    java.util.Arrays.stream(System.getProperty("web.origins","").split(",")).map(String::strip).filter(s->!s.isEmpty()).collect(java.util.stream.Collectors.toSet()))) {
                Runtime.getRuntime().addShutdownHook(new Thread(gateway::close));
                gateway.start();gateway.awaitStop();
            }
        }
    }
}
