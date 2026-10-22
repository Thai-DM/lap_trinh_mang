package com.smashprofs.server.ranking;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.game.AuthoritativeMatchResult;
import com.smashprofs.server.game.AuthoritativeMatchResult.FinishReason;
import com.smashprofs.server.game.AuthoritativeMatchResult.ParticipantResult;

import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/**
 * Kiểm thử RatingEngine: 2/3/4 người, hòa, không NaN, missing record, rating không âm.
 */
public class RatingEngineTest {

    private final RatingEngine engine = new RatingEngine();

    // -------------------------------------------------------------------------
    // Helper: tạo AuthoritativeMatchResult
    // -------------------------------------------------------------------------

    /** Tạo kết quả trận có winner. */
    private AuthoritativeMatchResult makeResult(String matchId, String winnerId,
                                                 String... playerIds) {
        List<ParticipantResult> parts = new ArrayList<>();
        for (int i = 0; i < playerIds.length; i++) {
            parts.add(new ParticipantResult(
                    new PlayerId(playerIds[i]), i, "char" + i, 100f, !playerIds[i].equals(winnerId)
            ));
        }
        return new AuthoritativeMatchResult(
                matchId, "room1", 1000L,
                new PlayerId(winnerId), FinishReason.LAST_PLAYER_STANDING, parts
        );
    }

    /** Tạo kết quả trận hòa (winnerId = null). */
    private AuthoritativeMatchResult makeDrawResult(String matchId, String... playerIds) {
        List<ParticipantResult> parts = new ArrayList<>();
        for (int i = 0; i < playerIds.length; i++) {
            parts.add(new ParticipantResult(
                    new PlayerId(playerIds[i]), i, "char" + i, 0f, true
            ));
        }
        return new AuthoritativeMatchResult(
                matchId, "room1", 1000L,
                null, FinishReason.ALL_PLAYERS_ELIMINATED, parts
        );
    }

    /** Tạo map record với rating chỉ định. */
    private Map<String, PlayerRecord> records(String... idAndRating) {
        // idAndRating: "p1", "1000", "p2", "1200", ...
        Map<String, PlayerRecord> map = new HashMap<>();
        for (int i = 0; i < idAndRating.length; i += 2) {
            String id = idAndRating[i];
            double rating = Double.parseDouble(idAndRating[i + 1]);
            map.put(id, new PlayerRecord(id, rating, 0, 0, 0, 0));
        }
        return map;
    }

    // -------------------------------------------------------------------------
    // Test 2 người
    // -------------------------------------------------------------------------

    @Test
    public void test2PlayerWinnerGetsRatingIncrease() {
        // Hai người cùng rating 1000: winner phải tăng, loser phải giảm
        var result = makeResult("m1", "p1", "p1", "p2");
        var current = records("p1", "1000", "p2", "1000");

        var updated = engine.compute(result, current);

        assertTrue("Winner phải tăng rating",
                updated.get("p1").rating() > 1000.0);
        assertTrue("Loser phải giảm rating",
                updated.get("p2").rating() < 1000.0);
    }

    @Test
    public void test2PlayerRatingChangeSumIsZero() {
        // Tổng delta phải bằng 0 (zero-sum) khi 2 người cùng rating
        var result = makeResult("m2", "p1", "p1", "p2");
        var current = records("p1", "1000", "p2", "1000");

        var updated = engine.compute(result, current);

        double winnerDelta = updated.get("p1").rating() - 1000.0;
        double loserDelta  = updated.get("p2").rating() - 1000.0;
        assertEquals("Tổng delta phải = 0", 0.0, winnerDelta + loserDelta, 0.01);
    }

    @Test
    public void testWinnerStatsUpdated() {
        var result = makeResult("m3", "p1", "p1", "p2");
        var current = records("p1", "1000", "p2", "1000");

        var updated = engine.compute(result, current);

        assertEquals("wins tăng 1", 1, updated.get("p1").wins());
        assertEquals("totalMatches tăng 1", 1, updated.get("p1").totalMatches());
    }

    @Test
    public void testLoserStatsUpdated() {
        var result = makeResult("m4", "p1", "p1", "p2");
        var current = records("p1", "1000", "p2", "1000");

        var updated = engine.compute(result, current);

        assertEquals("losses tăng 1", 1, updated.get("p2").losses());
        assertEquals("totalMatches tăng 1", 1, updated.get("p2").totalMatches());
    }

    // -------------------------------------------------------------------------
    // Test 3 người
    // -------------------------------------------------------------------------

    @Test
    public void test3PlayerCompute() {
        // p1 thắng, p2 & p3 thua; p1 phải tăng rating, p2 và p3 phải giảm
        var result = makeResult("m5", "p1", "p1", "p2", "p3");
        var current = records("p1", "1000", "p2", "1000", "p3", "1000");

        var updated = engine.compute(result, current);

        assertTrue("Winner tăng rating", updated.get("p1").rating() > 1000.0);
        assertTrue("Loser p2 giảm rating", updated.get("p2").rating() < 1000.0);
        assertTrue("Loser p3 giảm rating", updated.get("p3").rating() < 1000.0);
        assertEquals("p1 wins = 1", 1, updated.get("p1").wins());
        assertEquals("p2 losses = 1", 1, updated.get("p2").losses());
        assertEquals("p3 losses = 1", 1, updated.get("p3").losses());
    }

    // -------------------------------------------------------------------------
    // Test 4 người
    // -------------------------------------------------------------------------

    @Test
    public void test4PlayerCompute() {
        // p1 thắng, p2/p3/p4 thua
        var result = makeResult("m6", "p1", "p1", "p2", "p3", "p4");
        var current = records("p1", "1000", "p2", "1000", "p3", "1000", "p4", "1000");

        var updated = engine.compute(result, current);

        assertTrue("Winner tăng rating", updated.get("p1").rating() > 1000.0);
        assertTrue("p2 giảm rating", updated.get("p2").rating() < 1000.0);
        assertTrue("p3 giảm rating", updated.get("p3").rating() < 1000.0);
        assertTrue("p4 giảm rating", updated.get("p4").rating() < 1000.0);
        assertEquals("Tất cả 4 người đều có totalMatches = 1", 1, updated.get("p1").totalMatches());
        assertEquals("Tất cả 4 người đều có totalMatches = 1", 1, updated.get("p4").totalMatches());
    }

    // -------------------------------------------------------------------------
    // Test hòa
    // -------------------------------------------------------------------------

    @Test
    public void testDrawDoesNotChangeRating() {
        // Hòa: rating phải giữ nguyên
        var result = makeDrawResult("m7", "p1", "p2");
        var current = records("p1", "1200", "p2", "900");

        var updated = engine.compute(result, current);

        assertEquals("p1 rating không đổi khi hòa", 1200.0, updated.get("p1").rating(), 0.0001);
        assertEquals("p2 rating không đổi khi hòa", 900.0, updated.get("p2").rating(), 0.0001);
    }

    @Test
    public void testDrawIncrementsTotalMatches() {
        // Hòa: draws và totalMatches phải tăng
        var result = makeDrawResult("m8", "p1", "p2", "p3");
        var current = records("p1", "1000", "p2", "1000", "p3", "1000");

        var updated = engine.compute(result, current);

        for (String id : List.of("p1", "p2", "p3")) {
            assertEquals(id + " draws tăng 1", 1, updated.get(id).draws());
            assertEquals(id + " totalMatches tăng 1", 1, updated.get(id).totalMatches());
            assertEquals(id + " wins = 0", 0, updated.get(id).wins());
            assertEquals(id + " losses = 0", 0, updated.get(id).losses());
        }
    }

    // -------------------------------------------------------------------------
    // Test NaN, missing record, rating không âm
    // -------------------------------------------------------------------------

    @Test
    public void testNoNaNOutput() {
        // Extreme ratings: 0.0 vs 9999.0 — không NaN, không Infinite
        var result = makeResult("m9", "p1", "p1", "p2");
        var current = records("p1", "0.0", "p2", "9999.0");

        var updated = engine.compute(result, current);

        assertTrue("p1 rating không phải NaN",
                Double.isFinite(updated.get("p1").rating()));
        assertTrue("p2 rating không phải NaN",
                Double.isFinite(updated.get("p2").rating()));
    }

    @Test
    public void testInitialRecordUsedWhenMissing() {
        // Player p2 không có record → phải dùng initial rating 1000
        var result = makeResult("m10", "p1", "p1", "p2");
        var current = new HashMap<String, PlayerRecord>();
        current.put("p1", PlayerRecord.initial("p1"));
        // p2 không được thêm vào map

        var updated = engine.compute(result, current);

        assertNotNull("p2 phải có record mới", updated.get("p2"));
        // p2 thua từ initial rating 1000 → rating giảm xuống dưới 1000
        assertTrue("p2 thiếu record vẫn được xử lý",
                updated.get("p2").rating() < PlayerRecord.INITIAL_RATING);
    }

    @Test
    public void testRatingNotNegative() {
        // Player cực yếu thua trận với nhiều người mạnh hơn → rating >= 0
        var result = makeResult("m11", "p2", "p1", "p2", "p3", "p4");
        var current = records("p1", "1.0", "p2", "3000.0", "p3", "3000.0", "p4", "3000.0");

        var updated = engine.compute(result, current);

        assertTrue("p1 rating không âm", updated.get("p1").rating() >= 0.0);
    }
}
