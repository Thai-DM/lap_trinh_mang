package com.smashprofs.server.game;

import com.smashprofs.common.protocol.*;
import com.smashprofs.server.cluster.FileMatchHistory;
import com.smashprofs.server.network.GameServer;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import static org.junit.Assert.*;

/** Actual TCP/Box2D/completion/publisher/file pipeline; clock advancement avoids a 120s wall wait. */
public class FileHistoryTcpIntegrationTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test(timeout=15000) public void completedMatchPersistsOnceAndWorkerCanServeAnotherRoomAfterCleanup() throws Exception {
        var clock=new AtomicLong(10_000_000_000L); var executor=new ManualScheduledExecutor();
        Path directory=temp.getRoot().toPath(); var history=new FileMatchHistory(directory);
        var server=GameServer.withRankingGateway(() -> new FixedTickScheduler(executor,Logger.getLogger("file-history-e2e")),clock::get,history);
        var clients=new ArrayList<TcpTestClient>();
        try {
            server.start();
            for(int round=0;round<2;round++) {
                String roomId="persist_"+round; var roster=new ArrayList<TcpTestClient>();
                for(int i=0;i<2;i++) {
                    var client=new TcpTestClient(server.getBoundPort()); clients.add(client); roster.add(client); client.connect();
                    if(i==0) { client.request(MessageType.CREATE_ROOM,ProtocolV1.object().put("roomId",roomId)); }
                    client.request(MessageType.JOIN_ROOM,ProtocolV1.object().put("roomId",roomId));
                    client.request(MessageType.PLAYER_READY,ProtocolV1.object().put("ready",true).put("characterId","Alex"));
                }
                executor.runCycle(); clock.addAndGet(TimeUnit.SECONDS.toNanos(3)); executor.runCycle();
                for(var client:roster) { client.awaitType(MessageType.GAME_START); }
                var runtime=server.getRoomManager().getRoom(roomId).getMatchRuntime();
                roster.get(0).inputAndBarrier(0,1,false,false,false); executor.runCycle();
                clock.addAndGet(TimeUnit.SECONDS.toNanos(120)); executor.runCycle();
                var result=runtime.getMatchResult(); assertNotNull(result);
                for(var client:roster) {
                    var end=client.awaitType(MessageType.GAME_END); assertEquals(result.matchId(),end.payload().path("matchId").asText());
                }
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(server.getResultPublisher().getStatus(result.matchId())!=MatchResultPublisher.Status.SUBMITTED && System.nanoTime()<deadline) { Thread.sleep(5); }
                assertEquals(MatchResultPublisher.Status.SUBMITTED,server.getResultPublisher().getStatus(result.matchId()));
                assertTrue(Files.exists(directory.resolve(result.matchId()+".json")));
                assertEquals(1,history.history(roster.get(0).playerId().value(),20).size());
                history.submitMatchResult(result);
                for(var client:roster) { client.close(); }
                server.getRoomManager().removeRoom(roomId); assertTrue(runtime.isPhysicsDisposed());
                assertEquals(0,server.getRoomManager().getRoomCount()); assertTrue(server.isRunning());
            }
            try(var files=Files.list(directory)) { assertEquals(2,files.count()); }
        } finally { server.stop(); for(var client:clients) { client.close(); } }
    }
}
