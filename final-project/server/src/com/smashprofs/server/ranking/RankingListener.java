package com.smashprofs.server.ranking;

import com.smashprofs.common.model.RankingEntry;

import java.util.Collections;
import java.util.List;

/**
 * Listener nhận thông báo khi bảng xếp hạng người chơi có sự thay đổi.
 */
public interface RankingListener {

    /**
     * Được gọi khi bảng xếp hạng đã được cập nhật thành công (ví dụ sau khi kết thúc một trận đấu).
     *
     * @param leaderboard danh sách xếp hạng mới nhất (bất biến)
     */
    void onRankingUpdated(List<RankingEntry> leaderboard);

    /**
     * Thông báo cập nhật mặc định khi không truyền danh sách cụ thể.
     */
    default void onRankingUpdated() {
        onRankingUpdated(Collections.emptyList());
    }
}
