package com.smashprofs.game.Screens;

import com.smashprofs.game.network.client.LobbyClient;
import com.smashprofs.game.network.client.LobbyConfiguration;
import com.smashprofs.game.network.client.OnlineRoomPresentation;
import com.smashprofs.server.network.GameServer;
import org.junit.Test;
import java.util.List;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

public class OnlineLoadingScreenTest {

    private static void until(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 6_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("Loading screen flow timed out");
            }
            Thread.sleep(5);
        }
    }

    @Test
    public void canonicalCharacterStatsMatchReferenceValues() {
        var alex = OnlineLoadingScreen.getStats("Alex");
        assertEquals(7, alex.attack);
        assertEquals(6, alex.defense);
        assertEquals(5, alex.speed);
        assertEquals(5, alex.dexterity);

        var maurice = OnlineLoadingScreen.getStats("Maurice");
        assertEquals(4, maurice.attack);
        assertEquals(4, maurice.defense);
        assertEquals(7, maurice.speed);
        assertEquals(8, maurice.dexterity);

        var luca = OnlineLoadingScreen.getStats("Luca");
        assertEquals(6, luca.attack);
        assertEquals(4, luca.defense);
        assertEquals(8, luca.speed);
        assertEquals(6, luca.dexterity);

        var leo = OnlineLoadingScreen.getStats("Leo");
        assertEquals(6, leo.attack);
        assertEquals(6, leo.defense);
        assertEquals(6, leo.speed);
        assertEquals(6, leo.dexterity);

        var viktor = OnlineLoadingScreen.getStats("Viktor");
        assertEquals(8, viktor.attack);
        assertEquals(7, viktor.defense);
        assertEquals(3, viktor.speed);
        assertEquals(4, viktor.dexterity);

        var fallback = OnlineLoadingScreen.getStats("UnknownHero");
        assertEquals(5, fallback.attack);
        assertEquals(5, fallback.defense);
        assertEquals(5, fallback.speed);
        assertEquals(5, fallback.dexterity);
    }

    @Test
    public void playerCardDataSupportsTwoThreeAndFourPlayers() {
        // 2-player layout data
        var p1 = new OnlineLoadingScreen.PlayerCardData("id1", "Player 1 (Bạn)", "Alex", 0, true, true);
        var p2 = new OnlineLoadingScreen.PlayerCardData("id2", "Player 2", "Maurice", 1, false, false);
        var twoPlayers = List.of(p1, p2);
        assertEquals(2, twoPlayers.size());
        assertEquals("Alex", twoPlayers.get(0).characterName);
        assertEquals(7, twoPlayers.get(0).stats.attack);
        assertTrue(twoPlayers.get(0).isLocal);
        assertTrue(twoPlayers.get(0).isHost);
        assertEquals("Maurice", twoPlayers.get(1).characterName);
        assertEquals(8, twoPlayers.get(1).stats.dexterity);
        assertFalse(twoPlayers.get(1).isLocal);

        // 3-player layout data
        var p3 = new OnlineLoadingScreen.PlayerCardData("id3", "Player 3", "Luca", 2, false, false);
        var threePlayers = List.of(p1, p2, p3);
        assertEquals(3, threePlayers.size());
        assertEquals("Luca", threePlayers.get(2).characterName);
        assertEquals(8, threePlayers.get(2).stats.speed);

        // 4-player layout data
        var p4 = new OnlineLoadingScreen.PlayerCardData("id4", "Player 4", "Viktor", 3, false, false);
        var fourPlayers = List.of(p1, p2, p3, p4);
        assertEquals(4, fourPlayers.size());
        assertEquals("Viktor", fourPlayers.get(3).characterName);
        assertEquals(8, fourPlayers.get(3).stats.attack);
    }

    @Test(timeout = 20000)
    public void bothPlayersReadyTriggersServerCountdownThenMatchStart() throws Exception {
        var server = new GameServer();
        server.start();
        String room = OnlineRoomPresentation.newRoomCode();

        var host = new LobbyClient(new LobbyConfiguration("127.0.0.1", server.getBoundPort(), room, LobbyConfiguration.Mode.CREATE));
        var peer = new LobbyClient(new LobbyConfiguration("127.0.0.1", server.getBoundPort(), room, LobbyConfiguration.Mode.JOIN));
        host.enableGameplay();
        peer.enableGameplay();

        try {
            assertTrue(host.startAsync());
            until(() -> host.getSnapshot().canReady());
            assertTrue(peer.startAsync());
            until(() -> peer.getSnapshot().canReady());

            until(() -> host.getSettings() != null && peer.getSettings() != null
                    && host.getSettings().members().size() == 2 && peer.getSettings().members().size() == 2);

            // Both players choose character and ready up
            assertTrue(host.ready("Alex"));
            assertTrue(peer.ready("Maurice"));

            // Server detects all players ready -> transitions to COUNTDOWN
            until(() -> "COUNTDOWN".equals(host.getSnapshot().serverPhase()) || host.getSnapshot().isMatchStarted());
            until(() -> "COUNTDOWN".equals(peer.getSnapshot().serverPhase()) || peer.getSnapshot().isMatchStarted());

            // After server countdown completes, GAME_START is received -> isMatchStarted becomes true
            until(() -> host.getSnapshot().isMatchStarted() && peer.getSnapshot().isMatchStarted());
            assertNotNull(host.getMatchSession());
            assertNotNull(peer.getMatchSession());
            assertEquals("PLAYING", host.getSnapshot().serverPhase());
            assertEquals("PLAYING", peer.getSnapshot().serverPhase());
        } finally {
            host.closeAndWait(5000);
            peer.closeAndWait(5000);
            server.stop();
        }
    }
}
