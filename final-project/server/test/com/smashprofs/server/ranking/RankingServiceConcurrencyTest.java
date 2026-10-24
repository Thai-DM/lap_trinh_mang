package com.smashprofs.server.ranking;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.game.AuthoritativeMatchResult;
import com.smashprofs.server.game.AuthoritativeMatchResult.FinishReason;
import com.smashprofs.server.game.AuthoritativeMatchResult.ParticipantResult;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Kiểm thử tính an toàn đồng thời (Thread-safety) và Idempotent của {@link RankingService}.
 * Sử dụng {@link ExecutorService} và {@link CountDownLatch} để kích hoạt nhiều luồng cùng lúc.
 */
public class RankingServiceConcurrencyTest {

    private InMemoryRankingStore store;
    private RankingService rankingService;
    private ExecutorService executor;

    @Before
    public void setUp() {
        store = new InMemoryRankingStore();
        rankingService = new RankingService(store);
    }

    @After
    public void tearDown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    private AuthoritativeMatchResult makeResult(String matchId, String winnerId, String... playerIds) {
        List<ParticipantResult> parts = new ArrayList<>();
        for (int i = 0; i < playerIds.length; i++) {
            parts.add(new ParticipantResult(
                    new PlayerId(playerIds[i]), i, "char" + i, 100f, !playerIds[i].equals(winnerId)
            ));
        }
        return new AuthoritativeMatchResult(
                matchId, "room-concurrent", 1000L,
                winnerId != null ? new PlayerId(winnerId) : null,
                winnerId != null ? FinishReason.LAST_PLAYER_STANDING : FinishReason.TIME_LIMIT,
                parts
        );
    }

    /**
     * Kiểm tra nộp trùng matchId từ nhiều luồng cùng một lúc.
     * Kỳ vọng: chỉ có 1 lần nộp được tính điểm, 9 lần còn lại bị nhận diện DUPLICATE.
     */
    @Test
    public void testConcurrentDuplicateSubmissions() throws InterruptedException {
        int threadCount = 10;
        executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);

        AuthoritativeMatchResult sameMatch = makeResult("match-dup-concurrent", "alice", "alice", "bob");

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await(); // Đợi tất cả luồng sẵn sàng rồi cùng chạy
                    rankingService.submitMatchResult(sameMatch);
                } catch (Exception e) {
                    // Không nên xảy ra
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown(); // Bắn pháo hiệu chạy đồng thời
        boolean finished = endGate.await(5, TimeUnit.SECONDS);
        assertTrue("Tất cả các luồng phải hoàn thành trong 5 giây", finished);

        PlayerRecord alice = rankingService.findPlayer("alice").orElseThrow();
        PlayerRecord bob = rankingService.findPlayer("bob").orElseThrow();

        // Điểm chỉ được cộng đúng 1 lần
        assertEquals("Alice chỉ được ghi nhận đúng 1 trận đấu", 1, alice.totalMatches());
        assertEquals("Alice chỉ có 1 trận thắng", 1, alice.wins());
        assertEquals("Bob chỉ được ghi nhận đúng 1 trận đấu", 1, bob.totalMatches());
        assertEquals("Bob chỉ có 1 trận thua", 1, bob.losses());

        // Kiểm tra rating tăng tương đương 1 trận 1v1 Elo chuẩn (1000 -> 1016 khi K=32)
        assertEquals(1016.0, alice.rating(), 0.001);
        assertEquals(984.0, bob.rating(), 0.001);
    }

    /**
     * Kiểm tra nhiều trận đấu khác nhau của cùng 2 người chơi diễn ra đồng thời.
     * Kỳ vọng: cơ chế lock tuần tự ngăn chặn lost update, tổng số trận và rating được tính toán nhất quán.
     */
    @Test
    public void testConcurrentDifferentMatchesForSamePlayers() throws InterruptedException {
        int matchCount = 20;
        executor = Executors.newFixedThreadPool(8);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(matchCount);

        // 10 trận Alice thắng, 10 trận Bob thắng
        for (int i = 0; i < matchCount; i++) {
            final int matchIndex = i;
            final String winnerId = (matchIndex % 2 == 0) ? "alice" : "bob";
            final String matchId = "match-seq-" + matchIndex;

            executor.submit(() -> {
                try {
                    startGate.await();
                    AuthoritativeMatchResult match = makeResult(matchId, winnerId, "alice", "bob");
                    rankingService.submitMatchResult(match);
                } catch (Exception e) {
                    // Không nên có lỗi
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean finished = endGate.await(10, TimeUnit.SECONDS);
        assertTrue("Tất cả trận đấu phải xử lý xong trong 10 giây", finished);

        PlayerRecord alice = rankingService.findPlayer("alice").orElseThrow();
        PlayerRecord bob = rankingService.findPlayer("bob").orElseThrow();

        // Không có trận nào bị lost update
        assertEquals("Alice phải có đủ 20 trận", 20, alice.totalMatches());
        assertEquals("Alice phải thắng đúng 10 trận", 10, alice.wins());
        assertEquals("Alice phải thua đúng 10 trận", 10, alice.losses());

        assertEquals("Bob phải có đủ 20 trận", 20, bob.totalMatches());
        assertEquals("Bob phải thắng đúng 10 trận", 10, bob.wins());
        assertEquals("Bob phải thua đúng 10 trận", 10, bob.losses());
    }

    /**
     * Kiểm tra nộp đồng thời các trận đấu giữa nhiều cặp đấu khác nhau.
     */
    @Test
    public void testConcurrentMatchesAcrossMultiplePairs() throws InterruptedException {
        int pairCount = 5;
        int matchesPerPair = 6;
        int totalMatches = pairCount * matchesPerPair;
        executor = Executors.newFixedThreadPool(10);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(totalMatches);

        for (int p = 0; p < pairCount; p++) {
            String p1 = "player-A-" + p;
            String p2 = "player-B-" + p;
            for (int m = 0; m < matchesPerPair; m++) {
                String matchId = "match-" + p + "-" + m;
                String winner = (m % 2 == 0) ? p1 : p2;
                executor.submit(() -> {
                    try {
                        startGate.await();
                        AuthoritativeMatchResult match = makeResult(matchId, winner, p1, p2);
                        rankingService.submitMatchResult(match);
                    } catch (Exception e) {
                        // Không có lỗi
                    } finally {
                        endGate.countDown();
                    }
                });
            }
        }

        startGate.countDown();
        boolean finished = endGate.await(10, TimeUnit.SECONDS);
        assertTrue("Toàn bộ các trận đấu giữa các cặp phải xong trong 10s", finished);

        // Kiểm tra từng cặp đấu
        for (int p = 0; p < pairCount; p++) {
            String p1 = "player-A-" + p;
            String p2 = "player-B-" + p;
            PlayerRecord rec1 = rankingService.findPlayer(p1).orElseThrow();
            PlayerRecord rec2 = rankingService.findPlayer(p2).orElseThrow();

            assertEquals(matchesPerPair, rec1.totalMatches());
            assertEquals(matchesPerPair, rec2.totalMatches());
        }

        // Leaderboard có đủ tất cả người chơi
        List<PlayerRecord> leaderboard = rankingService.getLeaderboard();
        assertEquals(pairCount * 2, leaderboard.size());
    }
}
