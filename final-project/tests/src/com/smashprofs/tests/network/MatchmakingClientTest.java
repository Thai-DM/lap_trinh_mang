package com.smashprofs.tests.network;

import com.smashprofs.game.network.client.*;
import com.smashprofs.server.network.GameServer;
import org.junit.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Shared desktop/web lobby state against a real authoritative TCP server. */
public class MatchmakingClientTest {
    private GameServer server;
    private final List<LobbyClient> clients=new ArrayList<>();
    @Before public void start() { server=new GameServer();server.start(); }
    @After public void stop() throws Exception { try { for(var c:clients) { c.closeAndWait(3000); } }finally { server.stop(); } }
    private LobbyClient search(boolean boss,String character) {
        var client=new LobbyClient(new LobbyConfiguration("127.0.0.1",server.getBoundPort(),"matchmaking",LobbyConfiguration.Mode.JOIN));
        clients.add(client);client.enableMatchmaking(boss,character);assertTrue(client.startAsync());return client;
    }
    private void await(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(9);
        while(!condition.getAsBoolean() && System.nanoTime()<end) {
            for(var c:clients) { assertNotEquals(c.getSnapshot().toString(),LobbyClient.Phase.ERROR,c.getSnapshot().phase()); }
            Thread.sleep(10);
        }
        assertTrue("Timeout: "+clients.stream().map(c->c.getSnapshot().toString()).toList(),condition.getAsBoolean());
    }
    @Test public void sharedClientUsesAssignedRoomCharacterAndAutoReadinessThroughStart() throws Exception {
        var a=search(false,"Luca");await(()->a.getSnapshot().phase()==LobbyClient.Phase.JOINED);
        assertNotEquals("matchmaking",a.getSnapshot().roomId());assertEquals("Luca",a.getSnapshot().selectedCharacter());
        assertTrue(a.selectCharacter("Alex"));assertEquals("Alex",a.getSnapshot().selectedCharacter());
        var b=search(false,"Viktor");await(()->b.getSnapshot().phase()==LobbyClient.Phase.JOINED && a.getSnapshot().members().size()==2);
        assertEquals(a.getSnapshot().roomId(),b.getSnapshot().roomId());
        assertTrue(a.ready("Alex"));assertTrue(b.ready("Viktor"));
        await(()->a.getSnapshot().phase()==LobbyClient.Phase.WAITING_FOR_START && b.getSnapshot().phase()==LobbyClient.Phase.WAITING_FOR_START);
        a.markLoaded();b.markLoaded();await(()->a.getSnapshot().isMatchStarted() && b.getSnapshot().isMatchStarted());
        assertEquals(a.getMatchSession().getRoster().matchId(),b.getMatchSession().getRoster().matchId());
    }
    @Test public void cancelledSearchLeavesNoGhostAndCanSearchAgain() throws Exception {
        var a=search(true,"Maurice");await(()->a.getSnapshot().phase()==LobbyClient.Phase.JOINED);
        a.closeAndWait(3000);await(()->server.getRoomManager().getRoomCount()==0);
        var b=search(true,"Leo");await(()->b.getSnapshot().phase()==LobbyClient.Phase.JOINED);
        assertEquals(1,b.getSnapshot().members().size());assertTrue(b.getSettings().rules().boss());
    }
}
