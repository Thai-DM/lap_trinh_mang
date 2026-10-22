package com.smashprofs.server.ranking;

import com.smashprofs.server.game.AuthoritativeMatchResult;
import com.smashprofs.server.game.RankingGateway;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hiện thực {@link RankingGateway} để xử lý kết quả trận đấu an toàn.
 *
 * <p>Đặc tính kỹ thuật:
 * <ul>
 *   <li><b>Idempotent:</b> Chống cộng điểm và cập nhật số liệu 2 lần khi nhận trùng {@code matchId}.</li>
 *   <li><b>Thread-safe:</b> Sử dụng {@link ReentrantLock} để tuần tự hóa quá trình đọc - tính toán - ghi,
 *       ngăn chặn lost update và race condition khi nhiều trận đấu nộp kết quả đồng thời.</li>
 *   <li><b>Resilience:</b> Bắt và ghi log mọi ngoại lệ từ tầng lưu trữ (DB/Store),
 *       tuyệt đối không để ngoại lệ lan ra ngoài làm crash GameLoop.</li>
 * </ul>
 */
public class RankingService implements RankingGateway {

    private static final Logger LOGGER = Logger.getLogger(RankingService.class.getName());

    private final RankingStore store;
    private final RatingEngine ratingEngine;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Khởi tạo RankingService với RatingEngine mặc định.
     *
     * @param store kho lưu trữ ranking (không được null)
     */
    public RankingService(RankingStore store) {
        this(store, new RatingEngine());
    }

    /**
     * Khởi tạo RankingService với store và ratingEngine tùy chỉnh.
     *
     * @param store        kho lưu trữ ranking (không được null)
     * @param ratingEngine bộ tính toán rating (không được null)
     */
    public RankingService(RankingStore store, RatingEngine ratingEngine) {
        this.store = Objects.requireNonNull(store, "store không được null");
        this.ratingEngine = Objects.requireNonNull(ratingEngine, "ratingEngine không được null");
    }

    /**
     * Tiếp nhận và xử lý kết quả trận đấu authoritative.
     *
     * <p>Xử lý tuần tự qua lock để đảm bảo tính nhất quán dữ liệu.
     * Mọi ngoại lệ phát sinh trong quá trình lưu trữ được bắt an toàn để không làm gián đoạn GameLoop.
     *
     * @param result kết quả trận đấu authoritative bất biến
     */
    @Override
    public void submitMatchResult(AuthoritativeMatchResult result) {
        if (result == null) {
            LOGGER.warning("Nhận kết quả trận đấu null; bỏ qua");
            return;
        }
        if (result.matchId() == null || result.matchId().isBlank()) {
            LOGGER.warning("Kết quả trận đấu thiếu matchId hợp lệ; bỏ qua");
            return;
        }

        lock.lock();
        try {
            // Đọc hồ sơ hiện tại của các người chơi tham gia từ store
            Map<String, PlayerRecord> currentRecords = new HashMap<>();
            for (AuthoritativeMatchResult.ParticipantResult p : result.participants()) {
                if (p != null && p.playerId() != null) {
                    String profileId = p.playerId().value();
                    store.findPlayer(profileId).ifPresent(rec -> currentRecords.put(profileId, rec));
                }
            }

            // Tính toán rating mới qua RatingEngine
            Map<String, PlayerRecord> updatedRecords = ratingEngine.compute(result, currentRecords);

            // Lưu kết quả trận đấu và records mới vào store
            RankingStore.SaveMatchResult saveResult = store.saveMatchResult(result.matchId(), updatedRecords);
            if (saveResult == RankingStore.SaveMatchResult.DUPLICATE) {
                LOGGER.info("MatchId " + result.matchId() + " đã được xử lý trước đó; bỏ qua (idempotent)");
            } else {
                LOGGER.info("Xử lý thành công kết quả xếp hạng cho matchId: " + result.matchId());
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Lỗi khi xử lý lưu kết quả trận đấu cho matchId: " + result.matchId(), e);
            // Bắt an toàn, không ném ngoại lệ ra ngoài làm crash GameLoop
        } finally {
            lock.unlock();
        }
    }

    /**
     * Tìm hồ sơ người chơi theo profileId.
     *
     * @param profileId ID người chơi
     * @return {@link Optional} chứa PlayerRecord nếu có
     */
    public Optional<PlayerRecord> findPlayer(String profileId) {
        try {
            return store.findPlayer(profileId);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Lỗi khi tìm người chơi " + profileId, e);
            return Optional.empty();
        }
    }

    /**
     * Lấy toàn bộ bảng xếp hạng hiện tại sắp xếp theo rating giảm dần.
     *
     * @return danh sách người chơi xếp hạng, không bao giờ null
     */
    public List<PlayerRecord> getLeaderboard() {
        try {
            return store.getLeaderboard();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Lỗi khi lấy bảng xếp hạng", e);
            return Collections.emptyList();
        }
    }

    /**
     * Trả về kho lưu trữ RankingStore đang được sử dụng.
     *
     * @return ranking store
     */
    public RankingStore getStore() {
        return store;
    }
}
