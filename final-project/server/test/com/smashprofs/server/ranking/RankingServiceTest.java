package com.smashprofs.server.ranking;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.game.AuthoritativeMatchResult;
import com.smashprofs.server.game.AuthoritativeMatchResult.FinishReason;
import com.smashprofs.server.game.AuthoritativeMatchResult.ParticipantResult;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.*;

/**
 * Kiểm thử đơn vị cho {@link RankingService}.
 * Kiểm tra các tính chất: tính điểm đúng, idempotent chống cộng điểm 2 lần,
 * tự động khởi tạo người chơi mới, và bọc ngoại lệ DB an toàn.
 */
public class RankingServiceTest {

    private InMemoryRankingStore store;
    private RankingService rankingService;

    @Before
    public void setUp() {
        store = new InMemoryRankingStore();
        rankingService = new RankingService(store);
    }

    private AuthoritativeMatchResult makeResult(String matchId, String winnerId, String... playerIds) {
        List<ParticipantResult> parts = new ArrayList<>();
        for (int i = 0; i < playerIds.length; i++) {
            parts.add(new ParticipantResult(
                    new PlayerId(playerIds[i]), i, "char" + i, 100f, !playerIds[i].equals(winnerId)
            ));
        }
        return new AuthoritativeMatchResult(
                matchId, "room-1", 1000L,
                winnerId != null ? new PlayerId(winnerId) : null,
                winnerId != null ? FinishReason.LAST_PLAYER_STANDING : FinishReason.TIME_LIMIT,
                parts
        );
    }

    @Test
    public void testSubmitMatchResult_NormalWin() {
        AuthoritativeMatchResult result = makeResult("match-1", "alice", "alice", "bob");

        rankingService.submitMatchResult(result);

        Optional<PlayerRecord> aliceOpt = rankingService.findPlayer("alice");
        Optional<PlayerRecord> bobOpt = rankingService.findPlayer("bob");

        assertTrue("Alice phải tồn tại trong store", aliceOpt.isPresent());
        assertTrue("Bob phải tồn tại trong store", bobOpt.isPresent());

        PlayerRecord alice = aliceOpt.get();
        PlayerRecord bob = bobOpt.get();

        // Rating ban đầu là 1000, Alice thắng nên rating tăng, Bob thua nên rating giảm
        assertTrue("Rating Alice phải tăng (> 1000)", alice.rating() > 1000.0);
        assertTrue("Rating Bob phải giảm (< 1000)", bob.rating() < 1000.0);
        assertEquals("Alice có 1 trận thắng", 1, alice.wins());
        assertEquals("Alice có tổng cộng 1 trận", 1, alice.totalMatches());
        assertEquals("Bob có 1 trận thua", 1, bob.losses());
        assertEquals("Bob có tổng cộng 1 trận", 1, bob.totalMatches());
    }

    @Test
    public void testSubmitMatchResult_Draw() {
        AuthoritativeMatchResult drawResult = makeResult("match-draw-1", null, "alice", "bob");

        rankingService.submitMatchResult(drawResult);

        PlayerRecord alice = rankingService.findPlayer("alice").orElseThrow();
        PlayerRecord bob = rankingService.findPlayer("bob").orElseThrow();

        // Khi hòa: rating giữ nguyên 1000.0, số trận hòa và tổng trận tăng lên 1
        assertEquals("Rating Alice không đổi khi hòa", 1000.0, alice.rating(), 0.001);
        assertEquals("Alice hòa 1 trận", 1, alice.draws());
        assertEquals("Alice tổng 1 trận", 1, alice.totalMatches());

        assertEquals("Rating Bob không đổi khi hòa", 1000.0, bob.rating(), 0.001);
        assertEquals("Bob hòa 1 trận", 1, bob.draws());
        assertEquals("Bob tổng 1 trận", 1, bob.totalMatches());
    }

    @Test
    public void testSubmitMatchResult_Idempotency_DuplicateMatchId() {
        AuthoritativeMatchResult match1 = makeResult("match-dup", "alice", "alice", "bob");

        // Gửi lần 1
        rankingService.submitMatchResult(match1);

        PlayerRecord aliceAfter1st = rankingService.findPlayer("alice").orElseThrow();
        PlayerRecord bobAfter1st = rankingService.findPlayer("bob").orElseThrow();
        double aliceRating1st = aliceAfter1st.rating();
        double bobRating1st = bobAfter1st.rating();

        // Gửi lại lần 2 cùng matchId
        rankingService.submitMatchResult(match1);

        PlayerRecord aliceAfter2nd = rankingService.findPlayer("alice").orElseThrow();
        PlayerRecord bobAfter2nd = rankingService.findPlayer("bob").orElseThrow();

        // Dữ liệu phải được giữ nguyên, không cộng điểm lần 2
        assertEquals("Rating Alice không đổi sau khi gửi trùng matchId", aliceRating1st, aliceAfter2nd.rating(), 0.0001);
        assertEquals("Số trận Alice không đổi", 1, aliceAfter2nd.totalMatches());
        assertEquals("Số trận thắng Alice không đổi", 1, aliceAfter2nd.wins());

        assertEquals("Rating Bob không đổi sau khi gửi trùng matchId", bobRating1st, bobAfter2nd.rating(), 0.0001);
        assertEquals("Số trận Bob không đổi", 1, bobAfter2nd.totalMatches());
        assertEquals("Số trận thua Bob không đổi", 1, bobAfter2nd.losses());
    }

    @Test
    public void testSubmitMatchResult_NewPlayerAutoCreated() {
        AuthoritativeMatchResult match = makeResult("match-new", "charlie", "charlie", "david");

        assertFalse(store.findPlayer("charlie").isPresent());
        assertFalse(store.findPlayer("david").isPresent());

        rankingService.submitMatchResult(match);

        assertTrue("Charlie mới phải được tạo tự động", store.findPlayer("charlie").isPresent());
        assertTrue("David mới phải được tạo tự động", store.findPlayer("david").isPresent());
    }

    @Test
    public void testSubmitMatchResult_DbExceptionHandledSafely() {
        // Tạo một store ném RuntimeException khi saveMatchResult để mô phỏng lỗi DB
        RankingStore failingStore = new RankingStore() {
            @Override
            public SaveMatchResult saveMatchResult(String matchId, Map<String, PlayerRecord> updatedRecords) {
                throw new RuntimeException("Giả lập lỗi kết nối cơ sở dữ liệu");
            }

            @Override
            public Optional<PlayerRecord> findPlayer(String profileId) {
                return Optional.empty();
            }

            @Override
            public List<PlayerRecord> getLeaderboard() {
                return Collections.emptyList();
            }

            @Override
            public void close() {
            }
        };

        RankingService safeService = new RankingService(failingStore);
        AuthoritativeMatchResult match = makeResult("match-fail", "alice", "alice", "bob");

        // Gọi submitMatchResult không được quăng exception ra ngoài (không làm sập GameLoop)
        try {
            safeService.submitMatchResult(match);
        } catch (Exception e) {
            fail("submitMatchResult không được ném Exception ra ngoài: " + e.getMessage());
        }
    }

    @Test
    public void testSubmitMatchResult_NullAndInvalidSafelyHandled() {
        // submit null result
        rankingService.submitMatchResult(null);

        // submit result với matchId null hoặc rỗng
        AuthoritativeMatchResult emptyMatchIdResult = new AuthoritativeMatchResult(
                "", "room-1", 1000L,
                new PlayerId("alice"), FinishReason.LAST_PLAYER_STANDING,
                List.of(new ParticipantResult(new PlayerId("alice"), 0, "char0", 100f, false))
        );
        rankingService.submitMatchResult(emptyMatchIdResult);

        // Không gây lỗi crash
        assertTrue(rankingService.getLeaderboard().isEmpty());
    }

    @Test
    public void testGetLeaderboard_OrderedByRating() {
        // Trận 1: Alice thắng Bob
        rankingService.submitMatchResult(makeResult("m1", "alice", "alice", "bob"));
        // Trận 2: Alice thắng Charlie
        rankingService.submitMatchResult(makeResult("m2", "alice", "alice", "charlie"));

        List<PlayerRecord> leaderboard = rankingService.getLeaderboard();
        assertEquals(3, leaderboard.size());

        // Alice đứng đầu bảng xếp hạng
        assertEquals("alice", leaderboard.get(0).profileId());
        assertTrue(leaderboard.get(0).rating() > leaderboard.get(1).rating());
    }
}
