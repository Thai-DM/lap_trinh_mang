package com.smashprofs.server.ranking;

/**
 * Hồ sơ rating bất biến của một người chơi.
 * Mọi cập nhật đều trả về instance mới; không có setter.
 */
public record PlayerRecord(
        String profileId,
        double rating,
        int wins,
        int losses,
        int draws,
        int totalMatches
) {
    /** Rating khởi đầu mặc định theo chuẩn Elo thông thường. */
    public static final double INITIAL_RATING = 1000.0;

    /** Tạo hồ sơ mới chưa có trận nào. */
    public static PlayerRecord initial(String profileId) {
        return new PlayerRecord(profileId, INITIAL_RATING, 0, 0, 0, 0);
    }

    /** Trả về bản sao với rating mới. */
    public PlayerRecord withRating(double newRating) {
        return new PlayerRecord(profileId, newRating, wins, losses, draws, totalMatches);
    }

    /** Trả về bản sao sau một chiến thắng (tăng wins và totalMatches). */
    public PlayerRecord afterWin() {
        return new PlayerRecord(profileId, rating, wins + 1, losses, draws, totalMatches + 1);
    }

    /** Trả về bản sao sau một thất bại (tăng losses và totalMatches). */
    public PlayerRecord afterLoss() {
        return new PlayerRecord(profileId, rating, wins, losses + 1, draws, totalMatches + 1);
    }

    /** Trả về bản sao sau một trận hòa (tăng draws và totalMatches, KHÔNG đổi rating). */
    public PlayerRecord afterDraw() {
        return new PlayerRecord(profileId, rating, wins, losses, draws + 1, totalMatches + 1);
    }
}
