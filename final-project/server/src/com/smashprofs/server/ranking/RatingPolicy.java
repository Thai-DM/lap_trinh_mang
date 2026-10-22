package com.smashprofs.server.ranking;

import java.util.List;

/**
 * Chiến lược tính delta rating cho một người chơi sau mỗi trận đấu.
 * Tách biệt thuật toán khỏi engine để dễ thay thế/kiểm thử.
 */
public interface RatingPolicy {

    /**
     * Tính delta rating cho một người chơi.
     *
     * @param playerRating     rating hiện tại của người chơi
     * @param opponentRatings  danh sách rating của tất cả đối thủ (1–3 người)
     * @param score            1.0 = thắng, 0.0 = thua, 0.5 = hòa
     * @return delta dương/âm để cộng vào rating; trả về 0.0 nếu kết quả là NaN/Infinite
     */
    double computeDelta(double playerRating, List<Double> opponentRatings, double score);

    // -------------------------------------------------------------------------
    // Implementation mặc định: multi-player Elo đối đầu từng cặp
    // -------------------------------------------------------------------------

    /**
     * Elo đối đầu từng cặp (pairwise Elo) mở rộng cho 2–4 người.
     *
     * <p>Với mỗi đối thủ i:
     * <pre>
     *   E_i = 1 / (1 + 10^((R_opp_i - R_player) / 400))
     * </pre>
     * Tổng delta:
     * <pre>
     *   Δ = K × Σ(S − E_i)
     * </pre>
     * Trong đó K = 32, S = score (1.0/0.0/0.5).
     */
    final class EloRatingPolicy implements RatingPolicy {

        /** Hệ số thay đổi rating K theo chuẩn FIDE cho người chơi phổ thông. */
        public static final double DEFAULT_K = 32.0;

        private final double k;

        public EloRatingPolicy() {
            this(DEFAULT_K);
        }

        public EloRatingPolicy(double k) {
            if (k <= 0) throw new IllegalArgumentException("K phải lớn hơn 0");
            this.k = k;
        }

        @Override
        public double computeDelta(double playerRating, List<Double> opponentRatings, double score) {
            if (opponentRatings == null || opponentRatings.isEmpty()) return 0.0;

            double totalDelta = 0.0;
            for (double oppRating : opponentRatings) {
                // Xác suất kỳ vọng thắng người chơi này
                double expected = 1.0 / (1.0 + Math.pow(10.0, (oppRating - playerRating) / 400.0));
                totalDelta += k * (score - expected);
            }

            // Chống NaN/Infinite: nếu kết quả không hợp lệ trả về 0
            if (!Double.isFinite(totalDelta)) return 0.0;

            // Làm tròn đến 2 chữ số thập phân
            return Math.round(totalDelta * 100.0) / 100.0;
        }
    }
}
