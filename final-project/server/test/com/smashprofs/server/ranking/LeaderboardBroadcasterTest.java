package com.smashprofs.server.ranking;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.RankingEntry;
import com.smashprofs.common.protocol.MessageType;
import com.smashprofs.common.protocol.ProtocolMessage;
import com.smashprofs.common.protocol.RankingProtocol;
import com.smashprofs.server.game.AuthoritativeMatchResult;
import com.smashprofs.server.network.ClientSession;
import com.smashprofs.server.network.SessionChannel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Kiểm thử toàn diện cho LeaderboardBroadcaster (Task T10).
 */
public class LeaderboardBroadcasterTest {

    private ExecutorService testExecutor;
    private InMemoryRankingStore store;
    private RankingService rankingService;
    private LeaderboardBroadcaster broadcaster;

    private RecordingSessionChannel channel1;
    private RecordingSessionChannel channel2;
    private ClientSession session1;
    private ClientSession session2;

    @Before
    public void setUp() {
        testExecutor = Executors.newFixedThreadPool(2);
        store = new InMemoryRankingStore();
        rankingService = new RankingService(store);
        broadcaster = new LeaderboardBroadcaster(rankingService, testExecutor);

        channel1 = new RecordingSessionChannel();
        channel2 = new RecordingSessionChannel();
        session1 = new ClientSession("sess-1", channel1);
        session2 = new ClientSession("sess-2", channel2);
    }

    @After
    public void tearDown() {
        broadcaster.close();
        testExecutor.shutdownNow();
        store.close();
    }

    @Test
    public void testRegisterAndUnregister() {
        assertEquals(0, broadcaster.getSubscriberCount());
        assertFalse(broadcaster.isSubscribed(session1));

        broadcaster.subscribe(session1);
        assertEquals(1, broadcaster.getSubscriberCount());
        assertTrue(broadcaster.isSubscribed(session1));

        broadcaster.subscribe(session2);
        assertEquals(2, broadcaster.getSubscriberCount());
        assertTrue(broadcaster.isSubscribed(session2));

        broadcaster.unsubscribe(session1);
        assertEquals(1, broadcaster.getSubscriberCount());
        assertFalse(broadcaster.isSubscribed(session1));
        assertTrue(broadcaster.isSubscribed(session2));
    }

    @Test
    public void testRegisterDuplicateSession_Idempotent() {
        broadcaster.register(session1);
        broadcaster.register(session1);
        broadcaster.register(session1);

        assertEquals(1, broadcaster.getSubscriberCount());
        assertTrue(broadcaster.isRegistered(session1));
    }

    @Test
    public void testRegisterClosedSession_Ignored() {
        channel1.close();
        assertFalse(session1.isOpen());

        broadcaster.register(session1);
        assertEquals(0, broadcaster.getSubscriberCount());
        assertFalse(broadcaster.isSubscribed(session1));
    }

    @Test
    public void testBroadcast_SendsUpdatedMessageToAllSubscribers() throws Exception {
        broadcaster.subscribe(session1);
        broadcaster.subscribe(session2);

        List<RankingEntry> entries = List.of(
                new RankingEntry(1, new PlayerId("p1"), "Hero1", 5, 1, 6, 0.833, 1400),
                new RankingEntry(2, new PlayerId("p2"), "Hero2", 1, 5, 6, 0.167, 1000)
        );

        CompletableFuture<Void> future = broadcaster.broadcast(entries);
        future.get(2, TimeUnit.SECONDS);

        // Kiểm tra session1
        assertEquals(1, channel1.sentMessages.size());
        ProtocolMessage msg1 = channel1.sentMessages.get(0);
        assertEquals(MessageType.RANKING_UPDATED, msg1.type());
        assertNull(msg1.requestId());
        List<RankingEntry> parsed1 = RankingProtocol.parseSnapshot(msg1);
        assertEquals(2, parsed1.size());
        assertEquals("p1", parsed1.get(0).playerId().value());
        assertEquals(1400, parsed1.get(0).rating());

        // Kiểm tra session2
        assertEquals(1, channel2.sentMessages.size());
        ProtocolMessage msg2 = channel2.sentMessages.get(0);
        assertEquals(MessageType.RANKING_UPDATED, msg2.type());
        List<RankingEntry> parsed2 = RankingProtocol.parseSnapshot(msg2);
        assertEquals(2, parsed2.size());
        assertEquals("p2", parsed2.get(1).playerId().value());
    }

    @Test
    public void testBroadcast_NonBlockingExecution() throws Exception {
        broadcaster.subscribe(session1);

        CountDownLatch threadCheckLatch = new CountDownLatch(1);
        String callerThreadName = Thread.currentThread().getName();
        final String[] workerThreadName = new String[1];

        // Tạo custom channel ghi lại tên thread gửi
        RecordingSessionChannel threadCheckingChannel = new RecordingSessionChannel() {
            @Override
            public void send(Object message) {
                super.send(message);
                workerThreadName[0] = Thread.currentThread().getName();
                threadCheckLatch.countDown();
            }
        };
        ClientSession threadSession = new ClientSession("sess-thread", threadCheckingChannel);
        broadcaster.subscribe(threadSession);

        CompletableFuture<Void> future = broadcaster.broadcast(Collections.emptyList());
        future.get(2, TimeUnit.SECONDS);
        assertTrue(threadCheckLatch.await(2, TimeUnit.SECONDS));

        // Thread gửi phải là worker thread của executor, khác caller thread
        assertNotNull(workerThreadName[0]);
        assertNotEquals(callerThreadName, workerThreadName[0]);
    }

    @Test
    public void testBroadcast_CleansUpClosedSession() throws Exception {
        broadcaster.subscribe(session1);
        broadcaster.subscribe(session2);
        assertEquals(2, broadcaster.getSubscriberCount());

        // Đóng session1
        channel1.close();
        assertFalse(session1.isOpen());

        CompletableFuture<Void> future = broadcaster.broadcast(Collections.emptyList());
        future.get(2, TimeUnit.SECONDS);

        // session1 đã bị dọn dẹp, chỉ còn session2
        assertEquals(1, broadcaster.getSubscriberCount());
        assertFalse(broadcaster.isSubscribed(session1));
        assertTrue(broadcaster.isSubscribed(session2));

        // session1 không nhận tin nhắn, session2 nhận được
        assertEquals(0, channel1.sentMessages.size());
        assertEquals(1, channel2.sentMessages.size());
    }

    @Test
    public void testOnRankingUpdated_ImplementsRankingListener() throws Exception {
        broadcaster.subscribe(session1);

        List<RankingEntry> entries = List.of(
                new RankingEntry(1, new PlayerId("p1"), "p1", 1, 0, 1, 1.0, 1200)
        );

        // Gọi trực tiếp qua interface RankingListener
        RankingListener listener = broadcaster;
        listener.onRankingUpdated(entries);

        // Đợi một khoảng ngắn cho background task hoàn tất
        assertEventually(() -> assertEquals(1, channel1.sentMessages.size()), 2000);
        ProtocolMessage msg = channel1.sentMessages.get(0);
        assertEquals(MessageType.RANKING_UPDATED, msg.type());
        assertEquals(1, RankingProtocol.parseSnapshot(msg).size());
    }

    @Test
    public void testIntegration_RankingServiceTriggersBroadcast() throws Exception {
        broadcaster.subscribe(session1);

        AuthoritativeMatchResult match = new AuthoritativeMatchResult(
                "match-e2e-1",
                "room-1",
                120L,
                new PlayerId("winner-1"),
                AuthoritativeMatchResult.FinishReason.LAST_PLAYER_STANDING,
                List.of(
                        new AuthoritativeMatchResult.ParticipantResult(new PlayerId("winner-1"), 0, "Alex", 100f, false),
                        new AuthoritativeMatchResult.ParticipantResult(new PlayerId("loser-1"), 1, "Luca", 0f, true)
                )
        );

        // Nộp kết quả trận đấu authoritative
        rankingService.submitMatchResult(match);

        // Broadcaster phải tự động nhận sự kiện và gửi tin nhắn RANKING_UPDATED tới session1
        assertEventually(() -> assertEquals(1, channel1.sentMessages.size()), 2000);

        ProtocolMessage msg = channel1.sentMessages.get(0);
        assertEquals(MessageType.RANKING_UPDATED, msg.type());
        List<RankingEntry> entries = RankingProtocol.parseSnapshot(msg);
        assertEquals(2, entries.size());
        assertEquals("winner-1", entries.get(0).playerId().value());
    }

    @Test
    public void testDuplicateMatchResult_DoesNotTriggerBroadcast() throws Exception {
        broadcaster.subscribe(session1);

        AuthoritativeMatchResult match = new AuthoritativeMatchResult(
                "match-dup-1",
                "room-1",
                120L,
                new PlayerId("winner-1"),
                AuthoritativeMatchResult.FinishReason.LAST_PLAYER_STANDING,
                List.of(
                        new AuthoritativeMatchResult.ParticipantResult(new PlayerId("winner-1"), 0, "Alex", 100f, false),
                        new AuthoritativeMatchResult.ParticipantResult(new PlayerId("loser-1"), 1, "Luca", 0f, true)
                )
        );

        // Lần 1: Thành công -> kích hoạt broadcast
        rankingService.submitMatchResult(match);
        assertEventually(() -> assertEquals(1, channel1.sentMessages.size()), 2000);

        // Lần 2: Nộp lại cùng matchId -> Bị chặn trùng (DUPLICATE) -> KHÔNG broadcast lại
        rankingService.submitMatchResult(match);
        Thread.sleep(200);

        // Số tin nhắn vẫn là 1, không tăng thêm
        assertEquals(1, channel1.sentMessages.size());
    }

    private void assertEventually(Runnable assertion, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        AssertionError lastError = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                lastError = e;
                Thread.sleep(50);
            }
        }
        if (lastError != null) {
            throw lastError;
        }
    }

    /**
     * SessionChannel giả lập ghi nhận các tin nhắn đã gửi.
     */
    private static class RecordingSessionChannel implements SessionChannel {
        private volatile boolean open = true;
        final List<ProtocolMessage> sentMessages = new CopyOnWriteArrayList<>();

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
