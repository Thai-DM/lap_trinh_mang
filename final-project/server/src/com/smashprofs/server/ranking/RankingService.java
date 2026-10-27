package com.smashprofs.server.ranking;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.RankingEntry;
import com.smashprofs.server.game.AuthoritativeMatchResult;
import com.smashprofs.server.game.RankingGateway;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private final List<RankingListener> listeners = new CopyOnWriteArrayList<>();

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
            result=resolveProfiles(result);
            // Several tabs using one guest profile must never produce self-ranked Elo.
            if(result.participants().stream().map(p->p.playerId()).distinct().count()!=result.participants().size()) {
                LOGGER.warning("Ignoring match between duplicate guest profiles");return;
            }
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
                notifyListeners();
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
     * Đăng ký một RankingListener để nhận thông báo khi bảng xếp hạng thay đổi.
     *
     * @param listener listener cần đăng ký
     */
    public void addRankingListener(RankingListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Hủy đăng ký một RankingListener.
     *
     * @param listener listener cần hủy
     */
    public void removeRankingListener(RankingListener listener) {
        listeners.remove(listener);
    }

    /**
     * Bắn thông báo cập nhật bảng xếp hạng tới toàn bộ các listener đã đăng ký.
     */
    private void notifyListeners() {
        if (listeners.isEmpty()) {
            return;
        }
        List<RankingEntry> entries = getLeaderboardEntries();
        for (RankingListener listener : listeners) {
            try {
                listener.onRankingUpdated(entries);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Lỗi khi thông báo tới RankingListener " + listener, e);
            }
        }
    }

    /**
     * Lấy toàn bộ bảng xếp hạng hiện tại dưới dạng danh sách RankingEntry (đã tính rank, winRate, v.v.).
     *
     * @return danh sách RankingEntry bất biến
     */
    public List<RankingEntry> getLeaderboardEntries() {
        return toRankingEntries(getLeaderboard()).stream().map(e->new RankingEntry(e.rank(),e.playerId(),
                store.displayName(e.playerId().value()),e.wins(),e.losses(),e.totalMatches(),e.winRate(),e.rating())).toList();
    }

    AuthoritativeMatchResult resolveProfiles(AuthoritativeMatchResult result) {
        var participants=result.participants().stream().map(p->new AuthoritativeMatchResult.ParticipantResult(
                new PlayerId(store.rankingProfile(p.playerId().value())),p.slotIndex(),p.characterId(),p.finalHp(),p.eliminated(),p.displayName())).toList();
        PlayerId winner=result.winnerId()==null?null:new PlayerId(store.rankingProfile(result.winnerId().value()));
        return new AuthoritativeMatchResult(result.matchId(),result.roomId(),result.finishedAtTick(),winner,result.finishReason(),participants,result.gameplay());
    }

    /**
     * Chuyển đổi danh sách PlayerRecord sang danh sách RankingEntry giao thức mạng.
     *
     * @param records danh sách hồ sơ người chơi (đã sắp xếp theo rating giảm dần)
     * @return danh sách RankingEntry bất biến
     */
    public static List<RankingEntry> toRankingEntries(List<PlayerRecord> records) {
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        List<RankingEntry> entries = new ArrayList<>(records.size());
        int rank = 1;
        for (PlayerRecord rec : records) {
            if (rec == null) continue;
            double winRate = rec.totalMatches() > 0 ? (double) rec.wins() / rec.totalMatches() : 0.0;
            double roundedWinRate = Math.round(winRate * 1000.0) / 1000.0;
            entries.add(new RankingEntry(
                    rank++,
                    new PlayerId(rec.profileId()),
                    rec.profileId(),
                    rec.wins(),
                    rec.losses(),
                    rec.totalMatches(),
                    roundedWinRate,
                    (int) Math.round(rec.rating())
            ));
        }
        return Collections.unmodifiableList(entries);
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
