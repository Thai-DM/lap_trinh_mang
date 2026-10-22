package com.smashprofs.server.ranking;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.RankingEntry;
import com.smashprofs.common.protocol.MessageType;
import com.smashprofs.common.protocol.ProtocolMessage;
import com.smashprofs.common.protocol.ProtocolV1;
import com.smashprofs.common.protocol.RankingProtocol;
import com.smashprofs.server.game.AuthoritativeMatchResult;
import com.smashprofs.server.game.FixedTickScheduler;
import com.smashprofs.server.game.TcpTestClient;
import com.smashprofs.server.network.GameServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/**
 * Kiểm thử tích hợp End-to-End Ranking qua kết nối TCP thực tế (Task T11).
 *
 * <p>Kiểm tra toàn bộ chu trình:
 * <ul>
 *   <li>Client kết nối qua TCP (với tuỳ chọn profileId và displayName).</li>
 *   <li>Client gửi yêu cầu GET_LEADERBOARD và nhận RANKING_SNAPSHOT chính xác.</li>
 *   <li>Client tự động được đăng ký nhận thông báo realtime từ LeaderboardBroadcaster.</li>
 *   <li>Trận đấu kết thúc, RatingEngine tính toán Elo và SqliteRankingStore lưu trữ bền vững.</li>
 *   <li>Client đã đăng ký nhận được thông điệp RANKING_UPDATED qua TCP.</li>
 *   <li>Tính bất biến và chống cộng trùng lặp (Idempotent) khi cùng một matchId được nộp lại.</li>
 * </ul>
 */
public class RankingEndToEndTcpTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File dbFile;
    private SqliteRankingStore store;
    private RatingEngine ratingEngine;
    private RankingService rankingService;
    private LeaderboardBroadcaster broadcaster;
    private GameServer server;
    private final List<TcpTestClient> clients = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        dbFile = temp.newFile("ranking-e2e.db");
        store = new SqliteRankingStore(dbFile.getAbsolutePath());
        ratingEngine = new RatingEngine();
        rankingService = new RankingService(store, ratingEngine);
        broadcaster = new LeaderboardBroadcaster(rankingService);

        server = GameServer.withRanking(rankingService, broadcaster);
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        for (TcpTestClient client : clients) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
        clients.clear();

        if (server != null) {
            try {
                server.stop();
            } catch (Exception ignored) {
            }
        }
        if (broadcaster != null) {
            broadcaster.close();
        }
        if (store != null) {
            store.close();
        }
    }

    private TcpTestClient createClient() throws IOException {
        TcpTestClient client = new TcpTestClient(server.getBoundPort());
        clients.add(client);
        return client;
    }

    /**
     * Kiểm tra client kết nối và gửi yêu cầu GET_LEADERBOARD, nhận về RANKING_SNAPSHOT rỗng ban đầu.
     */
    @Test(timeout = 10000)
    public void testLeaderboardRequestReturnsSnapshotOverTcp() throws Exception {
        TcpTestClient client = createClient();
        client.connect("user-profile-01", "Alice");

        ProtocolMessage request = RankingProtocol.createGetLeaderboardMessage("req-001", 10);
        client.send(request);

        ProtocolMessage snapshot = client.awaitType(MessageType.RANKING_SNAPSHOT);
        assertNotNull("Server phải phản hồi RANKING_SNAPSHOT", snapshot);
        assertEquals("req-001", snapshot.requestId());

        List<RankingEntry> entries = RankingProtocol.parseSnapshot(snapshot);
        assertNotNull(entries);
        assertTrue("Bảng xếp hạng ban đầu phải rỗng", entries.isEmpty());
    }

    /**
     * Kiểm tra toàn bộ luồng: Client query leaderboard → Đăng ký nhận push → Trận kết thúc →
     * RatingEngine tính Elo → SqliteRankingStore lưu DB → Server đẩy RANKING_UPDATED qua TCP →
     * Client query lại nhận snapshot mới nhất.
     */
    @Test(timeout = 15000)
    public void testEndToEndMatchCompletionUpdatesRankingAndBroadcastsOverTcp() throws Exception {
        TcpTestClient client1 = createClient();
        client1.connect("p1", "PlayerOne");

        TcpTestClient client2 = createClient();
        client2.connect("p2", "PlayerTwo");

        // 1. Client 1 gửi GET_LEADERBOARD để đăng ký vào danh sách subscriber của Broadcaster
        client1.send(RankingProtocol.createGetLeaderboardMessage("sub-req", 5));
        ProtocolMessage initialSnapshot = client1.awaitType(MessageType.RANKING_SNAPSHOT);
        assertEquals("sub-req", initialSnapshot.requestId());
        assertTrue(broadcaster.getSubscriberCount() >= 1);

        // 2. Tạo kết quả trận đấu Authoritative giữa client1 (thắng) và client2 (thua)
        var matchResult = new AuthoritativeMatchResult(
                "match-e2e-101",
                "arena-e2e",
                2500L,
                client1.playerId(),
                AuthoritativeMatchResult.FinishReason.LAST_PLAYER_STANDING,
                List.of(
                        new AuthoritativeMatchResult.ParticipantResult(client1.playerId(), 0, "Alex", 75.0f, false),
                        new AuthoritativeMatchResult.ParticipantResult(client2.playerId(), 1, "Luca", 0.0f, true)
                )
        );

        // 3. Nộp kết quả trận đấu vào RankingService
        rankingService.submitMatchResult(matchResult);

        // 4. Client 1 (đã subscribe) phải nhận được RANKING_UPDATED qua TCP một cách tự động
        ProtocolMessage updateMessage = client1.awaitType(MessageType.RANKING_UPDATED);
        assertNotNull("Client phải nhận được RANKING_UPDATED tự động từ server", updateMessage);
        assertEquals(MessageType.RANKING_UPDATED, updateMessage.type());

        List<RankingEntry> updatedEntries = RankingProtocol.parseSnapshot(updateMessage);
        assertEquals(2, updatedEntries.size());

        // Player 1 thắng: rank 1, wins = 1, losses = 0, rating > 1000
        RankingEntry entry1 = updatedEntries.get(0);
        assertEquals(client1.playerId().value(), entry1.playerId().value());
        assertEquals(1, entry1.rank());
        assertEquals(1, entry1.wins());
        assertEquals(0, entry1.losses());
        assertEquals(1, entry1.totalMatches());
        assertTrue("Rating người thắng phải tăng (> 1000): " + entry1.rating(), entry1.rating() > 1000);

        // Player 2 thua: rank 2, wins = 0, losses = 1, rating < 1000
        RankingEntry entry2 = updatedEntries.get(1);
        assertEquals(client2.playerId().value(), entry2.playerId().value());
        assertEquals(2, entry2.rank());
        assertEquals(0, entry2.wins());
        assertEquals(1, entry2.losses());
        assertEquals(1, entry2.totalMatches());
        assertTrue("Rating người thua phải giảm (< 1000): " + entry2.rating(), entry2.rating() < 1000);

        // 5. Kiểm tra dữ liệu bền vững trong SQLite DB
        var p1Record = store.findPlayer(client1.playerId().value());
        assertTrue("Dữ liệu p1 phải được lưu trong SQLite", p1Record.isPresent());
        assertEquals(1, p1Record.get().wins());

        // 6. Client gửi yêu cầu GET_LEADERBOARD mới, nhận về dữ liệu khớp với DB
        client1.send(RankingProtocol.createGetLeaderboardMessage("req-after", 10));
        ProtocolMessage postSnapshot = client1.awaitType(MessageType.RANKING_SNAPSHOT);
        assertEquals("req-after", postSnapshot.requestId());

        List<RankingEntry> postEntries = RankingProtocol.parseSnapshot(postSnapshot);
        assertEquals(2, postEntries.size());
        assertEquals(client1.playerId().value(), postEntries.get(0).playerId().value());
    }

    /**
     * Kiểm tra tính Idempotent: nộp trùng lặp matchId không làm tính lại điểm hoặc crash hệ thống.
     */
    @Test(timeout = 10000)
    public void testIdempotentMatchSubmissionPreventsDuplicateScoreOverTcp() throws Exception {
        TcpTestClient client = createClient();
        client.connect("user-p1", "Gamer1");

        var match = new AuthoritativeMatchResult(
                "match-dup-99",
                "room-arena",
                1200L,
                client.playerId(),
                AuthoritativeMatchResult.FinishReason.LAST_PLAYER_STANDING,
                List.of(
                        new AuthoritativeMatchResult.ParticipantResult(client.playerId(), 0, "Alex", 100.0f, false),
                        new AuthoritativeMatchResult.ParticipantResult(new PlayerId("other-p2"), 1, "Luca", 0.0f, true)
                )
        );

        // Lần 1: Xử lý thành công
        rankingService.submitMatchResult(match);
        double ratingAfterFirst = store.findPlayer(client.playerId().value()).orElseThrow().rating();
        int winsAfterFirst = store.findPlayer(client.playerId().value()).orElseThrow().wins();

        // Lần 2: Nộp lại chính matchId đó
        rankingService.submitMatchResult(match);

        // Điểm số và số trận thắng phải giữ nguyên
        double ratingAfterSecond = store.findPlayer(client.playerId().value()).orElseThrow().rating();
        int winsAfterSecond = store.findPlayer(client.playerId().value()).orElseThrow().wins();

        assertEquals(ratingAfterFirst, ratingAfterSecond, 0.001);
        assertEquals(winsAfterFirst, winsAfterSecond);

        // Client query qua TCP vẫn thấy dữ liệu đúng 1 trận
        client.send(RankingProtocol.createGetLeaderboardMessage("req-dup-check", 10));
        ProtocolMessage snapshot = client.awaitType(MessageType.RANKING_SNAPSHOT);
        List<RankingEntry> entries = RankingProtocol.parseSnapshot(snapshot);
        assertEquals(2, entries.size());
        assertEquals(1, entries.get(0).totalMatches());
    }

    /**
     * Kiểm tra client có thể gửi thông tin tùy chọn profileId và displayName khi CONNECT
     * và server nhận diện identity an toàn không bị can thiệp bởi client.
     */
    @Test(timeout = 10000)
    public void testClientConnectWithProfileIdAndDisplayName() throws Exception {
        TcpTestClient client = createClient();
        client.connect("profile_123", "SuperSmashMaster");

        assertNotNull("Server phải sinh playerId duy nhất", client.playerId());
        assertTrue(client.playerId().value().length() > 0);

        // Sau khi connect, gửi request lấy bảng xếp hạng bình thường
        client.send(RankingProtocol.createGetLeaderboardMessage("req-profile-auth", 5));
        ProtocolMessage response = client.awaitType(MessageType.RANKING_SNAPSHOT);
        assertEquals("req-profile-auth", response.requestId());
        assertEquals(MessageType.RANKING_SNAPSHOT, response.type());
    }
}
