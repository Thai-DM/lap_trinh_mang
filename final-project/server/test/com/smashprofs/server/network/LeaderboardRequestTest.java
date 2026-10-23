package com.smashprofs.server.network;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.RankingEntry;
import com.smashprofs.common.protocol.MessageType;
import com.smashprofs.common.protocol.ProtocolMessage;
import com.smashprofs.common.protocol.ProtocolV1;
import com.smashprofs.common.protocol.RankingProtocol;
import com.smashprofs.server.ranking.InMemoryRankingStore;
import com.smashprofs.server.ranking.LeaderboardBroadcaster;
import com.smashprofs.server.ranking.PlayerRecord;
import com.smashprofs.server.ranking.RankingService;
import com.smashprofs.server.room.RoomManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Kiểm thử xử lý thông điệp GET_LEADERBOARD trong ProtocolV1Dispatcher (Task T09).
 */
public class LeaderboardRequestTest {

    private ConnectionManager connections;
    private RoomManager rooms;
    private InMemoryRankingStore store;
    private RankingService rankingService;
    private LeaderboardBroadcaster broadcaster;
    private ProtocolV1Dispatcher dispatcher;

    private FakeSessionChannel channel;
    private ClientSession session;

    @Before
    public void setUp() {
        connections = new ConnectionManager();
        rooms = new RoomManager();
        store = new InMemoryRankingStore();
        rankingService = new RankingService(store);
        broadcaster = new LeaderboardBroadcaster();
        dispatcher = new ProtocolV1Dispatcher(connections, rooms, null, rankingService, broadcaster);

        channel = new FakeSessionChannel();
        session = new ClientSession("session-1", channel);
        connections.register(session);
    }

    @After
    public void tearDown() {
        broadcaster.close();
        store.close();
    }

    private void authenticateSession(ClientSession clientSession) {
        clientSession.bindPlayer(new PlayerId("player-test-1"));
    }

    @Test
    public void testGetLeaderboard_WithoutConnect_ReturnsInvalidSession() {
        ProtocolMessage request = RankingProtocol.createGetLeaderboardMessage("req-unauth");
        boolean accepted = dispatcher.dispatch(session, request);

        assertFalse(accepted);
        assertEquals(1, channel.sentMessages.size());
        ProtocolMessage response = channel.sentMessages.get(0);
        assertEquals(MessageType.ERROR, response.type());
        assertEquals("req-unauth", response.requestId());
        assertEquals("INVALID_SESSION", response.payload().path("code").asText());
    }

    @Test
    public void testGetLeaderboard_EmptyStore_ReturnsEmptySnapshot() {
        authenticateSession(session);

        ProtocolMessage request = RankingProtocol.createGetLeaderboardMessage("req-empty");
        boolean accepted = dispatcher.dispatch(session, request);

        assertTrue(accepted);
        assertEquals(1, channel.sentMessages.size());
        ProtocolMessage response = channel.sentMessages.get(0);
        assertEquals(MessageType.RANKING_SNAPSHOT, response.type());
        assertEquals("req-empty", response.requestId());

        List<RankingEntry> entries = RankingProtocol.parseSnapshot(response);
        assertNotNull(entries);
        assertTrue(entries.isEmpty());
    }

    @Test
    public void testGetLeaderboard_AllEntries_OrderedByRating() {
        authenticateSession(session);

        // Chuẩn bị dữ liệu mẫu trong store
        store.saveMatchResult("m1", Map.of(
                "p-alice", new PlayerRecord("p-alice", 1500.0, 10, 2, 0, 12),
                "p-bob", new PlayerRecord("p-bob", 1350.0, 8, 4, 0, 12),
                "p-charlie", new PlayerRecord("p-charlie", 1100.0, 5, 7, 0, 12)
        ));

        ProtocolMessage request = RankingProtocol.createGetLeaderboardMessage("req-all");
        boolean accepted = dispatcher.dispatch(session, request);

        assertTrue(accepted);
        assertEquals(1, channel.sentMessages.size());
        ProtocolMessage response = channel.sentMessages.get(0);
        assertEquals(MessageType.RANKING_SNAPSHOT, response.type());
        assertEquals("req-all", response.requestId());

        List<RankingEntry> entries = RankingProtocol.parseSnapshot(response);
        assertEquals(3, entries.size());

        // Kiểm tra thứ tự và thông tin từng người
        RankingEntry top1 = entries.get(0);
        assertEquals(1, top1.rank());
        assertEquals("p-alice", top1.playerId().value());
        assertEquals(1500, top1.rating());
        assertEquals(10, top1.wins());
        assertEquals(2, top1.losses());
        assertEquals(12, top1.totalMatches());

        RankingEntry top2 = entries.get(1);
        assertEquals(2, top2.rank());
        assertEquals("p-bob", top2.playerId().value());
        assertEquals(1350, top2.rating());

        RankingEntry top3 = entries.get(2);
        assertEquals(3, top3.rank());
        assertEquals("p-charlie", top3.playerId().value());
        assertEquals(1100, top3.rating());
    }

    @Test
    public void testGetLeaderboard_WithLimit_ReturnsTopN() {
        authenticateSession(session);

        store.saveMatchResult("m1", Map.of(
                "p-alice", new PlayerRecord("p-alice", 1500.0, 10, 2, 0, 12),
                "p-bob", new PlayerRecord("p-bob", 1350.0, 8, 4, 0, 12),
                "p-charlie", new PlayerRecord("p-charlie", 1100.0, 5, 7, 0, 12)
        ));

        ProtocolMessage request = RankingProtocol.createGetLeaderboardMessage("req-limit", 2);
        boolean accepted = dispatcher.dispatch(session, request);

        assertTrue(accepted);
        ProtocolMessage response = channel.sentMessages.get(0);
        assertEquals(MessageType.RANKING_SNAPSHOT, response.type());
        assertEquals("req-limit", response.requestId());

        List<RankingEntry> entries = RankingProtocol.parseSnapshot(response);
        assertEquals(2, entries.size());
        assertEquals("p-alice", entries.get(0).playerId().value());
        assertEquals("p-bob", entries.get(1).playerId().value());
    }

    @Test
    public void testGetLeaderboard_WithLimitLargerThanTotal_ReturnsAll() {
        authenticateSession(session);

        store.saveMatchResult("m1", Map.of(
                "p-alice", new PlayerRecord("p-alice", 1500.0, 10, 2, 0, 12)
        ));

        ProtocolMessage request = RankingProtocol.createGetLeaderboardMessage("req-large-limit", 50);
        boolean accepted = dispatcher.dispatch(session, request);

        assertTrue(accepted);
        List<RankingEntry> entries = RankingProtocol.parseSnapshot(channel.sentMessages.get(0));
        assertEquals(1, entries.size());
        assertEquals("p-alice", entries.get(0).playerId().value());
    }

    @Test
    public void testGetLeaderboard_AutoSubscribesToBroadcaster() {
        authenticateSession(session);
        assertFalse(broadcaster.isSubscribed(session));

        ProtocolMessage request = RankingProtocol.createGetLeaderboardMessage("req-sub");
        boolean accepted = dispatcher.dispatch(session, request);

        assertTrue(accepted);
        // Sau khi gọi GET_LEADERBOARD, session đã được tự động đăng ký vào broadcaster
        assertTrue(broadcaster.isSubscribed(session));
        assertEquals(1, broadcaster.getSubscriberCount());
    }

    @Test
    public void testGetLeaderboard_InvalidLimitZeroOrNegative_ReturnsInvalidPayload() {
        authenticateSession(session);

        ObjectNode payloadZero = ProtocolV1.object().put("limit", 0);
        ProtocolMessage reqZero = new ProtocolMessage(MessageType.GET_LEADERBOARD, "req-zero", payloadZero);
        assertFalse(dispatcher.dispatch(session, reqZero));
        assertEquals("INVALID_PAYLOAD", channel.sentMessages.get(0).payload().path("code").asText());

        channel.sentMessages.clear();
        ObjectNode payloadNegative = ProtocolV1.object().put("limit", -10);
        ProtocolMessage reqNeg = new ProtocolMessage(MessageType.GET_LEADERBOARD, "req-neg", payloadNegative);
        assertFalse(dispatcher.dispatch(session, reqNeg));
        assertEquals("INVALID_PAYLOAD", channel.sentMessages.get(0).payload().path("code").asText());
    }

    @Test
    public void testGetLeaderboard_InvalidLimitNotInteger_ReturnsInvalidPayload() {
        authenticateSession(session);

        ObjectNode payloadString = ProtocolV1.object().put("limit", "ten");
        ProtocolMessage request = new ProtocolMessage(MessageType.GET_LEADERBOARD, "req-invalid-type", payloadString);
        assertFalse(dispatcher.dispatch(session, request));

        ProtocolMessage response = channel.sentMessages.get(0);
        assertEquals(MessageType.ERROR, response.type());
        assertEquals("INVALID_PAYLOAD", response.payload().path("code").asText());
    }

    @Test
    public void testGetLeaderboard_UnauthorizedPlayerField_ReturnsUnauthorizedPlayer() {
        authenticateSession(session);

        ObjectNode payloadWithPlayer = ProtocolV1.object().put("playerId", "attacker");
        ProtocolMessage request = new ProtocolMessage(MessageType.GET_LEADERBOARD, "req-forged", payloadWithPlayer);
        assertFalse(dispatcher.dispatch(session, request));

        ProtocolMessage response = channel.sentMessages.get(0);
        assertEquals(MessageType.ERROR, response.type());
        assertEquals("UNAUTHORIZED_PLAYER", response.payload().path("code").asText());
    }

    @Test
    public void testGetLeaderboard_ExtraUnexpectedFields_ReturnsInvalidPayload() {
        authenticateSession(session);

        ObjectNode payloadExtra = ProtocolV1.object().put("unsupportedParam", true);
        ProtocolMessage request = new ProtocolMessage(MessageType.GET_LEADERBOARD, "req-extra", payloadExtra);
        assertFalse(dispatcher.dispatch(session, request));

        ProtocolMessage response = channel.sentMessages.get(0);
        assertEquals(MessageType.ERROR, response.type());
        assertEquals("INVALID_PAYLOAD", response.payload().path("code").asText());
    }

    @Test
    public void testGetLeaderboard_WithoutRankingService_ReturnsEmptySnapshot() {
        ProtocolV1Dispatcher dispatcherWithoutService = new ProtocolV1Dispatcher(connections, rooms, null, null, null);
        authenticateSession(session);

        ProtocolMessage request = RankingProtocol.createGetLeaderboardMessage("req-no-srv");
        boolean accepted = dispatcherWithoutService.dispatch(session, request);

        assertTrue(accepted);
        ProtocolMessage response = channel.sentMessages.get(0);
        assertEquals(MessageType.RANKING_SNAPSHOT, response.type());
        List<RankingEntry> entries = RankingProtocol.parseSnapshot(response);
        assertTrue(entries.isEmpty());
    }

    /**
     * FakeSessionChannel dùng để thu thập các tin nhắn gửi đi qua socket mà không cần mạng thật.
     */
    private static final class FakeSessionChannel implements SessionChannel {
        private boolean open = true;
        private final List<ProtocolMessage> sentMessages = new ArrayList<>();

        @Override
        public void send(Object message) {
            if (message instanceof ProtocolMessage pm) {
                sentMessages.add(pm);
            }
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }
}
