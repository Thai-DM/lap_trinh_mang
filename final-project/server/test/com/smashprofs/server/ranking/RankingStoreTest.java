package com.smashprofs.server.ranking;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Kiểm thử toàn diện cho các hiện thực {@link RankingStore}:
 * {@link InMemoryRankingStore} và {@link SqliteRankingStore}.
 */
public class RankingStoreTest {

    private File tempDbFile;

    @Before
    public void setup() throws IOException {
        tempDbFile = File.createTempFile("smashprofs-test-ranking-", ".db");
    }

    @After
    public void cleanup() {
        if (tempDbFile != null && tempDbFile.exists()) {
            tempDbFile.delete();
        }
    }

    // ==========================================
    // 1. InMemoryRankingStore Tests
    // ==========================================

    @Test
    public void testInMemoryBasicSaveAndFind() {
        RankingStore store = new InMemoryRankingStore();
        testBasicSaveAndFind(store);
    }

    @Test
    public void testInMemoryDuplicateMatchRejected() {
        RankingStore store = new InMemoryRankingStore();
        testDuplicateMatchRejected(store);
    }

    @Test
    public void testInMemoryLeaderboardSorting() {
        RankingStore store = new InMemoryRankingStore();
        testLeaderboardSorting(store);
    }

    // ==========================================
    // 2. SqliteRankingStore Tests (In-Memory mode)
    // ==========================================

    @Test
    public void testSqliteInMemoryBasicSaveAndFind() {
        try (SqliteRankingStore store = SqliteRankingStore.inMemory()) {
            testBasicSaveAndFind(store);
        }
    }

    @Test
    public void testSqliteInMemoryDuplicateMatchRejected() {
        try (SqliteRankingStore store = SqliteRankingStore.inMemory()) {
            testDuplicateMatchRejected(store);
        }
    }

    @Test
    public void testSqliteInMemoryLeaderboardSorting() {
        try (SqliteRankingStore store = SqliteRankingStore.inMemory()) {
            testLeaderboardSorting(store);
        }
    }

    // ==========================================
    // 3. SqliteRankingStore File Persistence Tests
    // ==========================================

    @Test
    public void testSqliteFilePersistenceAcrossRestarts() {
        String dbPath = tempDbFile.getAbsolutePath();

        // 1. Ghi dữ liệu vào file db và đóng lại
        try (SqliteRankingStore store1 = new SqliteRankingStore(dbPath)) {
            var records = Map.of(
                    "p1", new PlayerRecord("p1", 1250.5, 5, 1, 0, 6),
                    "p2", new PlayerRecord("p2", 980.0, 1, 5, 0, 6)
            );
            assertEquals(RankingStore.SaveMatchResult.OK, store1.saveMatchResult("match-01", records));
        }

        // 2. Mở lại file db đó từ instance mới và kiểm tra tính bền vững
        try (SqliteRankingStore store2 = new SqliteRankingStore(dbPath)) {
            Optional<PlayerRecord> p1 = store2.findPlayer("p1");
            assertTrue("p1 phải tồn tại sau khi mở lại DB", p1.isPresent());
            assertEquals(1250.5, p1.get().rating(), 0.001);
            assertEquals(5, p1.get().wins());

            // Thử lưu trùng match-01 → phải trả về DUPLICATE
            var newRecords = Map.of("p1", new PlayerRecord("p1", 1300.0, 6, 1, 0, 7));
            assertEquals(RankingStore.SaveMatchResult.DUPLICATE, store2.saveMatchResult("match-01", newRecords));

            // Dữ liệu p1 không bị thay đổi do bị chặn trùng matchId
            assertEquals(1250.5, store2.findPlayer("p1").orElseThrow().rating(), 0.001);
        }
    }

    @Test
    public void testSqliteUpsertPlayerStatsAcrossMatches() {
        try (SqliteRankingStore store = SqliteRankingStore.inMemory()) {
            // Trận 1
            var match1 = Map.of(
                    "alice", new PlayerRecord("alice", 1016.0, 1, 0, 0, 1),
                    "bob", new PlayerRecord("bob", 984.0, 0, 1, 0, 1)
            );
            assertEquals(RankingStore.SaveMatchResult.OK, store.saveMatchResult("m1", match1));
            assertEquals(1016.0, store.findPlayer("alice").orElseThrow().rating(), 0.001);

            // Trận 2: Alice thắng tiếp, Bob hòa người khác
            var match2 = Map.of(
                    "alice", new PlayerRecord("alice", 1031.5, 2, 0, 0, 2),
                    "bob", new PlayerRecord("bob", 984.0, 0, 1, 1, 2)
            );
            assertEquals(RankingStore.SaveMatchResult.OK, store.saveMatchResult("m2", match2));

            PlayerRecord alice = store.findPlayer("alice").orElseThrow();
            assertEquals(1031.5, alice.rating(), 0.001);
            assertEquals(2, alice.wins());
            assertEquals(2, alice.totalMatches());

            PlayerRecord bob = store.findPlayer("bob").orElseThrow();
            assertEquals(984.0, bob.rating(), 0.001);
            assertEquals(1, bob.draws());
            assertEquals(2, bob.totalMatches());
        }
    }

    // ==========================================
    // Generic Test Helpers
    // ==========================================

    private void testBasicSaveAndFind(RankingStore store) {
        assertFalse(store.findPlayer("unknown").isPresent());

        var records = Map.of(
                "p1", new PlayerRecord("p1", 1100.0, 3, 1, 0, 4),
                "p2", new PlayerRecord("p2", 950.0, 1, 3, 0, 4)
        );

        RankingStore.SaveMatchResult result = store.saveMatchResult("match-abc", records);
        assertEquals(RankingStore.SaveMatchResult.OK, result);

        Optional<PlayerRecord> p1 = store.findPlayer("p1");
        assertTrue(p1.isPresent());
        assertEquals("p1", p1.get().profileId());
        assertEquals(1100.0, p1.get().rating(), 0.001);
        assertEquals(3, p1.get().wins());
        assertEquals(1, p1.get().losses());
        assertEquals(4, p1.get().totalMatches());

        Optional<PlayerRecord> p2 = store.findPlayer("p2");
        assertTrue(p2.isPresent());
        assertEquals(950.0, p2.get().rating(), 0.001);
    }

    private void testDuplicateMatchRejected(RankingStore store) {
        var records1 = Map.of("p1", new PlayerRecord("p1", 1100.0, 1, 0, 0, 1));
        assertEquals(RankingStore.SaveMatchResult.OK, store.saveMatchResult("match-dup", records1));

        // Lưu lại cùng matchId với số liệu khác
        var records2 = Map.of("p1", new PlayerRecord("p1", 1200.0, 2, 0, 0, 2));
        RankingStore.SaveMatchResult dupResult = store.saveMatchResult("match-dup", records2);

        assertEquals(RankingStore.SaveMatchResult.DUPLICATE, dupResult);

        // Đảm bảo số liệu không bị sửa đổi
        assertEquals(1100.0, store.findPlayer("p1").orElseThrow().rating(), 0.001);
        assertEquals(1, store.findPlayer("p1").orElseThrow().wins());
    }

    private void testLeaderboardSorting(RankingStore store) {
        var records = Map.of(
                "mid", new PlayerRecord("mid", 1000.0, 1, 1, 0, 2),
                "high", new PlayerRecord("high", 1500.0, 5, 0, 0, 5),
                "low", new PlayerRecord("low", 800.0, 0, 4, 0, 4)
        );

        store.saveMatchResult("sort-match", records);

        List<PlayerRecord> leaderboard = store.getLeaderboard();
        assertEquals(3, leaderboard.size());
        assertEquals("high", leaderboard.get(0).profileId());
        assertEquals(1500.0, leaderboard.get(0).rating(), 0.001);

        assertEquals("mid", leaderboard.get(1).profileId());
        assertEquals(1000.0, leaderboard.get(1).rating(), 0.001);

        assertEquals("low", leaderboard.get(2).profileId());
        assertEquals(800.0, leaderboard.get(2).rating(), 0.001);
    }
}
