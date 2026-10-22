package com.smashprofs.common.protocol;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.RankingEntry;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Kiểm thử toàn diện cho giao thức xếp hạng RankingProtocol (Task T07 & T08).
 */
public class RankingProtocolTest {

    private JsonFrameCodec codec;

    @Before
    public void setUp() {
        codec = new JsonFrameCodec();
    }

    // ==========================================
    // 1. Kiểm thử CONNECT với profileId và displayName
    // ==========================================

    @Test
    public void testConnectPayload_WithProfileIdAndDisplayName() throws Exception {
        ObjectNode payload = RankingProtocol.connectPayload("prof-001", "MasterSmash");
        assertEquals("prof-001", payload.path("profileId").asText());
        assertEquals("MasterSmash", payload.path("displayName").asText());

        RankingProtocol.ConnectProfile profile = RankingProtocol.parseConnectProfile(payload, "req-1");
        assertNotNull(profile);
        assertEquals("prof-001", profile.profileId());
        assertEquals("MasterSmash", profile.displayName());
        assertTrue(profile.hasProfileId());
        assertTrue(profile.hasDisplayName());
    }

    @Test
    public void testConnectPayload_WithOnlyProfileId() throws Exception {
        ObjectNode payload = RankingProtocol.connectPayload("prof-002", null);
        assertTrue(payload.has("profileId"));
        assertFalse(payload.has("displayName"));

        RankingProtocol.ConnectProfile profile = RankingProtocol.parseConnectProfile(payload, "req-2");
        assertEquals("prof-002", profile.profileId());
        assertNull(profile.displayName());
        assertTrue(profile.hasProfileId());
        assertFalse(profile.hasDisplayName());
    }

    @Test
    public void testConnectPayload_WithOnlyDisplayName() throws Exception {
        ObjectNode payload = RankingProtocol.connectPayload(null, "JustAlex");
        assertFalse(payload.has("profileId"));
        assertTrue(payload.has("displayName"));

        RankingProtocol.ConnectProfile profile = RankingProtocol.parseConnectProfile(payload, "req-3");
        assertNull(profile.profileId());
        assertEquals("JustAlex", profile.displayName());
        assertFalse(profile.hasProfileId());
        assertTrue(profile.hasDisplayName());
    }

    @Test
    public void testConnectPayload_EmptyPayload() throws Exception {
        ObjectNode payload = ProtocolV1.object();
        RankingProtocol.ConnectProfile profile = RankingProtocol.parseConnectProfile(payload, "req-4");
        assertNull(profile.profileId());
        assertNull(profile.displayName());
        assertFalse(profile.hasProfileId());
        assertFalse(profile.hasDisplayName());
    }

    @Test
    public void testConnectPayload_WithGameplayCapability() throws Exception {
        ObjectNode payload = RankingProtocol.connectPayload("prof-003", "FighterOne", GameplayProtocol.CAPABILITY);
        assertEquals("prof-003", payload.path("profileId").asText());
        assertEquals("FighterOne", payload.path("displayName").asText());
        assertEquals(GameplayProtocol.CAPABILITY, payload.path("gameplay").asText());

        RankingProtocol.ConnectProfile profile = RankingProtocol.parseConnectProfile(payload, "req-5");
        assertEquals("prof-003", profile.profileId());
        assertEquals("FighterOne", profile.displayName());
    }

    @Test
    public void testConnectPayload_RejectsPlayerIdSuppliedByClient() {
        ObjectNode payload = ProtocolV1.object().put("playerId", "forged-id");
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> RankingProtocol.parseConnectProfile(payload, "req-err"));
        assertEquals("UNAUTHORIZED_PLAYER", ex.code());
    }

    @Test
    public void testConnectPayload_RejectsSessionIdSuppliedByClient() {
        ObjectNode payload = ProtocolV1.object().put("sessionId", "session-hack");
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> RankingProtocol.parseConnectProfile(payload, "req-err"));
        assertEquals("UNAUTHORIZED_PLAYER", ex.code());
    }

    @Test
    public void testConnectPayload_RejectsNonStringProfileId() {
        ObjectNode payload = ProtocolV1.object().put("profileId", 12345);
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> RankingProtocol.parseConnectProfile(payload, "req-err"));
        assertEquals("INVALID_PAYLOAD", ex.code());
    }

    @Test
    public void testConnectPayload_RejectsBlankProfileId() {
        ObjectNode payload = ProtocolV1.object().put("profileId", "   ");
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> RankingProtocol.parseConnectProfile(payload, "req-err"));
        assertEquals("INVALID_PAYLOAD", ex.code());
    }

    @Test
    public void testConnectPayload_RejectsOverlengthProfileId() {
        ObjectNode payload = ProtocolV1.object().put("profileId", "a".repeat(65));
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> RankingProtocol.parseConnectProfile(payload, "req-err"));
        assertEquals("INVALID_PAYLOAD", ex.code());
    }

    // ==========================================
    // 2. Kiểm thử RankingEntry serialize & parse
    // ==========================================

    @Test
    public void testRankingEntry_RoundTrip() {
        RankingEntry entry = new RankingEntry(1, new PlayerId("p-alpha"), "ChampionAlex", 15, 3, 18, 0.833, 1600);
        ObjectNode node = RankingProtocol.entryNode(entry);

        assertEquals(1, node.path("rank").asInt());
        assertEquals("p-alpha", node.path("playerId").asText());
        assertEquals("ChampionAlex", node.path("displayName").asText());
        assertEquals(15, node.path("wins").asInt());
        assertEquals(3, node.path("losses").asInt());
        assertEquals(18, node.path("totalMatches").asInt());
        assertEquals(0.833, node.path("winRate").asDouble(), 0.0001);
        assertEquals(1600, node.path("rating").asInt());

        RankingEntry parsed = RankingProtocol.parseEntry(node);
        assertEquals(entry.rank(), parsed.rank());
        assertEquals(entry.playerId(), parsed.playerId());
        assertEquals(entry.displayName(), parsed.displayName());
        assertEquals(entry.wins(), parsed.wins());
        assertEquals(entry.losses(), parsed.losses());
        assertEquals(entry.totalMatches(), parsed.totalMatches());
        assertEquals(entry.winRate(), parsed.winRate(), 0.0001);
        assertEquals(entry.rating(), parsed.rating());
    }

    @Test
    public void testRankingEntry_InvalidFields() {
        RankingEntry valid = new RankingEntry(1, new PlayerId("p1"), "Alex", 1, 0, 1, 1.0, 1000);
        ObjectNode base = RankingProtocol.entryNode(valid);

        // Rank < 1
        ObjectNode invalidRank = base.deepCopy();
        invalidRank.put("rank", 0);
        assertThrows(IllegalArgumentException.class, () -> RankingProtocol.parseEntry(invalidRank));

        // Missing playerId
        ObjectNode missingPlayerId = base.deepCopy();
        missingPlayerId.remove("playerId");
        assertThrows(IllegalArgumentException.class, () -> RankingProtocol.parseEntry(missingPlayerId));

        // Invalid winRate (NaN / not a number)
        ObjectNode invalidWinRate = base.deepCopy();
        invalidWinRate.put("winRate", "not-a-number");
        assertThrows(IllegalArgumentException.class, () -> RankingProtocol.parseEntry(invalidWinRate));

        // Negative wins
        ObjectNode negativeWins = base.deepCopy();
        negativeWins.put("wins", -1);
        assertThrows(IllegalArgumentException.class, () -> RankingProtocol.parseEntry(negativeWins));
    }

    // ==========================================
    // 3. Kiểm thử RANKING_SNAPSHOT & RANKING_UPDATED
    // ==========================================

    @Test
    public void testLeaderboardSnapshot_RoundTrip() {
        List<RankingEntry> entries = List.of(
                new RankingEntry(1, new PlayerId("p1"), "Alice", 10, 2, 12, 0.833, 1500),
                new RankingEntry(2, new PlayerId("p2"), "Bob", 8, 4, 12, 0.667, 1350),
                new RankingEntry(3, new PlayerId("p3"), "Charlie", 5, 7, 12, 0.417, 1100)
        );

        ObjectNode payload = RankingProtocol.snapshotPayload(entries);
        assertTrue(payload.has("leaderboard"));
        assertEquals(3, payload.get("leaderboard").size());

        List<RankingEntry> parsed = RankingProtocol.parseLeaderboard(payload);
        assertEquals(3, parsed.size());
        assertEquals(entries.get(0), parsed.get(0));
        assertEquals(entries.get(1), parsed.get(1));
        assertEquals(entries.get(2), parsed.get(2));
    }

    @Test
    public void testLeaderboardSnapshot_EmptyList() {
        ObjectNode payload = RankingProtocol.snapshotPayload(Collections.emptyList());
        assertTrue(payload.has("leaderboard"));
        assertEquals(0, payload.get("leaderboard").size());

        List<RankingEntry> parsed = RankingProtocol.parseLeaderboard(payload);
        assertTrue(parsed.isEmpty());
    }

    @Test
    public void testParseSnapshot_FromProtocolMessage() {
        List<RankingEntry> entries = List.of(
                new RankingEntry(1, new PlayerId("p1"), "Alice", 10, 0, 10, 1.0, 1600)
        );

        ProtocolMessage snapshotMsg = RankingProtocol.createSnapshotMessage("req-snap", entries);
        assertEquals(MessageType.RANKING_SNAPSHOT, snapshotMsg.type());
        assertEquals("req-snap", snapshotMsg.requestId());

        List<RankingEntry> fromSnapshot = RankingProtocol.parseSnapshot(snapshotMsg);
        assertEquals(1, fromSnapshot.size());
        assertEquals(entries.get(0), fromSnapshot.get(0));

        ProtocolMessage updatedMsg = RankingProtocol.createUpdatedMessage(entries);
        assertEquals(MessageType.RANKING_UPDATED, updatedMsg.type());
        assertNull(updatedMsg.requestId());

        List<RankingEntry> fromUpdated = RankingProtocol.parseSnapshot(updatedMsg);
        assertEquals(1, fromUpdated.size());
        assertEquals(entries.get(0), fromUpdated.get(0));
    }

    @Test
    public void testParseSnapshot_RejectsWrongMessageType() {
        ProtocolMessage ping = new ProtocolMessage(MessageType.PING, "r1", ProtocolV1.object());
        assertThrows(IllegalArgumentException.class, () -> RankingProtocol.parseSnapshot(ping));
    }

    // ==========================================
    // 4. Kiểm thử GET_LEADERBOARD
    // ==========================================

    @Test
    public void testGetLeaderboard_NoLimit() throws Exception {
        ProtocolMessage msg = RankingProtocol.createGetLeaderboardMessage("req-lead-1");
        assertEquals(MessageType.GET_LEADERBOARD, msg.type());
        assertEquals("req-lead-1", msg.requestId());
        assertEquals(0, msg.payload().size());

        RankingProtocol.validateGetLeaderboard(msg);
        assertNull(RankingProtocol.parseLimit(msg.payload(), msg.requestId()));
    }

    @Test
    public void testGetLeaderboard_WithLimit() throws Exception {
        ProtocolMessage msg = RankingProtocol.createGetLeaderboardMessage("req-lead-2", 20);
        assertEquals(MessageType.GET_LEADERBOARD, msg.type());
        assertEquals("req-lead-2", msg.requestId());
        assertEquals(20, msg.payload().path("limit").asInt());

        RankingProtocol.validateGetLeaderboard(msg);
        assertEquals(Integer.valueOf(20), RankingProtocol.parseLimit(msg.payload(), msg.requestId()));
    }

    @Test
    public void testGetLeaderboard_RejectsNegativeOrZeroLimit() {
        assertThrows(IllegalArgumentException.class, () -> RankingProtocol.getLeaderboardPayload(0));
        assertThrows(IllegalArgumentException.class, () -> RankingProtocol.getLeaderboardPayload(-5));

        ObjectNode invalidPayload = ProtocolV1.object().put("limit", -1);
        ProtocolMessage msg = new ProtocolMessage(MessageType.GET_LEADERBOARD, "req-3", invalidPayload);
        assertThrows(ProtocolException.class, () -> RankingProtocol.validateGetLeaderboard(msg));
    }

    @Test
    public void testGetLeaderboard_RejectsExtraFields() {
        ObjectNode invalidPayload = ProtocolV1.object().put("unexpectedField", "value");
        ProtocolMessage msg = new ProtocolMessage(MessageType.GET_LEADERBOARD, "req-4", invalidPayload);
        ProtocolException ex = assertThrows(ProtocolException.class, () -> RankingProtocol.validateGetLeaderboard(msg));
        assertEquals("INVALID_PAYLOAD", ex.code());
    }

    @Test
    public void testGetLeaderboard_RejectsPlayerIdField() {
        ObjectNode invalidPayload = ProtocolV1.object().put("playerId", "fakeId");
        ProtocolMessage msg = new ProtocolMessage(MessageType.GET_LEADERBOARD, "req-5", invalidPayload);
        ProtocolException ex = assertThrows(ProtocolException.class, () -> RankingProtocol.validateGetLeaderboard(msg));
        assertEquals("UNAUTHORIZED_PLAYER", ex.code());
    }

    // ==========================================
    // 5. Kiểm thử Wire Frame Codec (JSON encode/decode)
    // ==========================================

    @Test
    public void testWireCodec_GetLeaderboardMessage() throws IOException, ProtocolException {
        ProtocolMessage original = RankingProtocol.createGetLeaderboardMessage("req-wire-1", 10);
        byte[] bytes = codec.encode(original);

        ProtocolMessage decoded = codec.readNext(new ByteArrayInputStream(bytes));
        assertNotNull(decoded);
        assertEquals(MessageType.GET_LEADERBOARD, decoded.type());
        assertEquals("req-wire-1", decoded.requestId());
        assertEquals(10, decoded.payload().path("limit").asInt());
    }

    @Test
    public void testWireCodec_RankingSnapshotMessage() throws IOException, ProtocolException {
        List<RankingEntry> entries = List.of(
                new RankingEntry(1, new PlayerId("p1"), "TopPlayer", 25, 2, 27, 0.925, 1850)
        );
        ProtocolMessage original = RankingProtocol.createSnapshotMessage("req-wire-2", entries);
        byte[] bytes = codec.encode(original);

        ProtocolMessage decoded = codec.readNext(new ByteArrayInputStream(bytes));
        assertNotNull(decoded);
        assertEquals(MessageType.RANKING_SNAPSHOT, decoded.type());
        assertEquals("req-wire-2", decoded.requestId());

        List<RankingEntry> restored = RankingProtocol.parseSnapshot(decoded);
        assertEquals(1, restored.size());
        assertEquals(entries.get(0), restored.get(0));
    }

    @Test
    public void testWireCodec_RankingUpdatedMessage() throws IOException, ProtocolException {
        List<RankingEntry> entries = List.of(
                new RankingEntry(1, new PlayerId("p1"), "TopPlayer", 26, 2, 28, 0.928, 1865)
        );
        ProtocolMessage original = RankingProtocol.createUpdatedMessage(entries);
        byte[] bytes = codec.encode(original);

        ProtocolMessage decoded = codec.readNext(new ByteArrayInputStream(bytes));
        assertNotNull(decoded);
        assertEquals(MessageType.RANKING_UPDATED, decoded.type());
        assertNull(decoded.requestId());

        List<RankingEntry> restored = RankingProtocol.parseSnapshot(decoded);
        assertEquals(1, restored.size());
        assertEquals(entries.get(0), restored.get(0));
    }

    @Test
    public void testWireCodec_ConnectWithProfile() throws IOException, ProtocolException {
        ProtocolMessage original = RankingProtocol.createConnectMessage("req-connect", "profile-42", "SmashHero");
        byte[] bytes = codec.encode(original);

        ProtocolMessage decoded = codec.readNext(new ByteArrayInputStream(bytes));
        assertNotNull(decoded);
        assertEquals(MessageType.CONNECT, decoded.type());
        assertEquals("req-connect", decoded.requestId());

        RankingProtocol.ConnectProfile profile = RankingProtocol.parseConnectProfile(decoded.payload(), decoded.requestId());
        assertEquals("profile-42", profile.profileId());
        assertEquals("SmashHero", profile.displayName());
    }
}
