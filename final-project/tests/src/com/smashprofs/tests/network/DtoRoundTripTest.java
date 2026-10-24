package com.smashprofs.tests.network;

import com.google.gson.Gson;
import com.smashprofs.common.model.*;
import com.smashprofs.common.protocol.MessageType;
import com.smashprofs.common.protocol.NetworkPacket;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class DtoRoundTripTest {

    private Gson gson;

    @Before
    public void setUp() {
        gson = new Gson();
    }

    @Test
    public void testPlayerIdRoundTrip() {
        PlayerId original = new PlayerId("player-42");
        String json = gson.toJson(original);
        PlayerId restored = gson.fromJson(json, PlayerId.class);

        assertNotNull(restored);
        assertEquals("player-42", restored.value());
        assertEquals(original, restored);
    }

    @Test
    public void testPlayerInputRoundTrip() {
        PlayerId pid = new PlayerId("p1");
        PlayerInput original = new PlayerInput(pid, 105L, 300L, 0.75f, true, false, true, false, true);

        String json = gson.toJson(original);
        PlayerInput restored = gson.fromJson(json, PlayerInput.class);

        assertNotNull(restored);
        assertEquals("p1", restored.playerId().value());
        assertEquals(105L, restored.sequence());
        assertEquals(300L, restored.clientTick());
        assertEquals(0.75f, restored.moveAxis(), 0.0001f);
        assertTrue(restored.jumpPressed());
        assertFalse(restored.stompPressed());
        assertTrue(restored.meleePressed());
        assertFalse(restored.blockHeld());
        assertTrue(restored.abilityPressed());
    }

    @Test
    public void testPlayerStateRoundTrip() {
        PlayerId pid = new PlayerId("p2");
        PlayerState original = new PlayerState(pid, "Maurice", 12.5f, 4.2f, 1.1f, -0.5f, 85, true, false, false, true);

        String json = gson.toJson(original);
        PlayerState restored = gson.fromJson(json, PlayerState.class);

        assertNotNull(restored);
        assertEquals("p2", restored.playerId().value());
        assertEquals("Maurice", restored.characterId());
        assertEquals(12.5f, restored.x(), 0.0001f);
        assertEquals(4.2f, restored.y(), 0.0001f);
        assertEquals(1.1f, restored.velocityX(), 0.0001f);
        assertEquals(-0.5f, restored.velocityY(), 0.0001f);
        assertEquals(85, restored.hp());
        assertTrue(restored.grounded());
        assertFalse(restored.blocking());
        assertFalse(restored.dead());
        assertTrue(restored.facingRight());
    }

    @Test
    public void testGameSnapshotRoundTrip() {
        PlayerState ps1 = new PlayerState(new PlayerId("p1"), "Alex", 10.0f, 20.0f, 0f, 0f, 100, true, false, false, true);
        PlayerState ps2 = new PlayerState(new PlayerId("p2"), "Leo", 30.0f, 20.0f, 0f, 0f, 90, true, false, false, false);
        GameSnapshot original = new GameSnapshot("room-alpha", 500L, "PLAYING", 45000L, List.of(ps1, ps2));

        String json = gson.toJson(original);
        GameSnapshot restored = gson.fromJson(json, GameSnapshot.class);

        assertNotNull(restored);
        assertEquals("room-alpha", restored.roomId());
        assertEquals(500L, restored.serverTick());
        assertEquals("PLAYING", restored.phase());
        assertEquals(45000L, restored.remainingTimeMs());
        assertEquals(2, restored.players().size());
        assertEquals("Alex", restored.players().get(0).characterId());
        assertEquals("Leo", restored.players().get(1).characterId());
    }

    @Test
    public void testMatchResultRoundTrip() {
        MatchResult original = new MatchResult("match-01", "room-alpha", new PlayerId("p1"),
                List.of(new PlayerId("p1"), new PlayerId("p2")), 1728000000000L);

        String json = gson.toJson(original);
        MatchResult restored = gson.fromJson(json, MatchResult.class);

        assertNotNull(restored);
        assertEquals("match-01", restored.matchId());
        assertEquals("p1", restored.winnerId().value());
        assertEquals(2, restored.participantIds().size());
        assertEquals(1728000000000L, restored.finishedAtEpochMs());
    }

    @Test
    public void testRankingEntryRoundTrip() {
        RankingEntry original = new RankingEntry(1, new PlayerId("p1"), "ChampionAlex", 10, 2, 12, 0.833, 1550);

        String json = gson.toJson(original);
        RankingEntry restored = gson.fromJson(json, RankingEntry.class);

        assertNotNull(restored);
        assertEquals(1, restored.rank());
        assertEquals("ChampionAlex", restored.displayName());
        assertEquals(10, restored.wins());
        assertEquals(2, restored.losses());
        assertEquals(12, restored.totalMatches());
        assertEquals(0.833, restored.winRate(), 0.001);
        assertEquals(1550, restored.rating());
    }

    @Test
    public void testNetworkPacketEnvelopeWithPayload() {
        PlayerInput input = new PlayerInput(new PlayerId("p1"), 1L, 10L, 1.0f, true, false, false, false, false);
        NetworkPacket packet = NetworkPacket.of(MessageType.PLAYER_INPUT, "p1", input);

        String packetJson = packet.toJson();
        NetworkPacket restoredPacket = NetworkPacket.fromJson(packetJson);

        assertNotNull(restoredPacket);
        assertEquals(MessageType.PLAYER_INPUT, restoredPacket.getType());
        assertEquals("p1", restoredPacket.getSenderId());

        PlayerInput restoredInput = restoredPacket.parsePayload(PlayerInput.class);
        assertNotNull(restoredInput);
        assertEquals(1L, restoredInput.sequence());
        assertEquals(1.0f, restoredInput.moveAxis(), 0.0001f);
        assertTrue(restoredInput.jumpPressed());
    }
}
