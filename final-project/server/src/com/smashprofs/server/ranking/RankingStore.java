package com.smashprofs.server.ranking;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Interface lưu trữ hồ sơ người chơi và kiểm soát trùng lặp matchId.
 *
 * <p>Có 2 hiện thực:
 * <ul>
 *   <li>{@link InMemoryRankingStore} — dùng trong unit test, không cần file DB.</li>
 *   <li>{@link SqliteRankingStore} — lưu vào SQLite, bền vững qua các lần restart.</li>
 * </ul>
 */
public interface RankingStore extends AutoCloseable {

    /** Optional guest persistence; legacy ranking stores can remain read/result-only. */
    default GuestIdentity connectGuest(String token,String displayName,String sessionPlayerId) {
        throw new UnsupportedOperationException("Guest profiles unavailable");
    }
    default String rankingProfile(String sessionPlayerId) { return sessionPlayerId; }
    default String displayName(String profileId) { return profileId; }

    /**
     * Trạng thái kết quả sau khi lưu một trận đấu.
     */
    enum SaveMatchResult {
        /** Lưu thành công, dữ liệu đã được ghi vào store. */
        OK,
        /** matchId đã tồn tại — bỏ qua, không ghi đè bất kỳ dữ liệu nào. */
        DUPLICATE
    }

    /**
     * Lưu kết quả trận đấu và cập nhật danh sách PlayerRecord mới.
     *
     * <p>Nếu {@code matchId} đã tồn tại trong store, trả về {@link SaveMatchResult#DUPLICATE}
     * và không thay đổi dữ liệu nào (idempotent guard).
     *
     * @param matchId        ID duy nhất của trận đấu
     * @param updatedRecords map profileId → PlayerRecord mới cần lưu
     * @return {@link SaveMatchResult#OK} hoặc {@link SaveMatchResult#DUPLICATE}
     */
    SaveMatchResult saveMatchResult(String matchId, Map<String, PlayerRecord> updatedRecords);

    /**
     * Lấy PlayerRecord theo profileId.
     *
     * @param profileId ID người chơi cần tìm
     * @return {@link Optional} chứa record nếu tồn tại, hoặc empty nếu chưa có.
     */
    Optional<PlayerRecord> findPlayer(String profileId);

    /**
     * Lấy toàn bộ bảng xếp hạng, sắp xếp theo rating giảm dần.
     *
     * @return danh sách PlayerRecord, không null
     */
    List<PlayerRecord> getLeaderboard();

    /**
     * Giải phóng tài nguyên (kết nối DB, v.v.).
     * Gọi khi không còn dùng store nữa.
     */
    void close();
}
