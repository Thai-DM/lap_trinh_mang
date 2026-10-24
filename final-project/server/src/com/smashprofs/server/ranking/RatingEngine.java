package com.smashprofs.server.ranking;

import com.smashprofs.server.game.AuthoritativeMatchResult;
import com.smashprofs.server.game.AuthoritativeMatchResult.ParticipantResult;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Tính toán rating mới từ {@link AuthoritativeMatchResult} cho 2–4 người chơi.
 *
 * <p>Quy tắc:
 * <ul>
 *   <li>Hòa ({@code isDraw() == true}): chỉ tăng {@code draws} và {@code totalMatches},
 *       <strong>không đổi rating</strong>.</li>
 *   <li>Có winner: áp dụng {@link RatingPolicy} pairwise cho từng participant.</li>
 *   <li>Player không có record trong map → tự động dùng {@link PlayerRecord#initial}.</li>
 *   <li>Rating không bao giờ âm (tối thiểu 0.0).</li>
 * </ul>
 */
public final class RatingEngine {

    private final RatingPolicy policy;

    /** Tạo engine với EloRatingPolicy mặc định (K=32). */
    public RatingEngine() {
        this(new RatingPolicy.EloRatingPolicy());
    }

    /** Tạo engine với policy tuỳ chỉnh (dùng cho test hoặc thay thế chiến lược). */
    public RatingEngine(RatingPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "RatingPolicy không được null");
    }

    /**
     * Tính toán rating mới từ kết quả trận đấu.
     *
     * @param matchResult    kết quả authoritative bất biến
     * @param currentRecords map profileId → PlayerRecord hiện tại (chỉ đọc, có thể thiếu entry)
     * @return map profileId → PlayerRecord mới (unmodifiable)
     */
    public Map<String, PlayerRecord> compute(
            AuthoritativeMatchResult matchResult,
            Map<String, PlayerRecord> currentRecords
    ) {
        Objects.requireNonNull(matchResult, "matchResult không được null");
        Objects.requireNonNull(currentRecords, "currentRecords không được null");

        List<ParticipantResult> participants = matchResult.participants();
        if (participants.isEmpty()) return Collections.emptyMap();

        Map<String, PlayerRecord> result = new HashMap<>();

        if (matchResult.gameplay()!=null && matchResult.gameplay().rules().boss()) {
            // Co-op has a team outcome and intentionally no individual winner.
            // Record the outcome without applying competitive pairwise Elo to teammates.
            boolean won=matchResult.finishReason()==AuthoritativeMatchResult.FinishReason.BOSS_DEFEATED;
            for(ParticipantResult p:participants) {
                String id=p.playerId().value();var current=getOrInitial(currentRecords,id);
                result.put(id,won?current.afterWin():current.afterLoss());
            }
        } else if (matchResult.isDraw()) {
            // Hòa: chỉ cập nhật stats, rating giữ nguyên
            for (ParticipantResult p : participants) {
                String id = p.playerId().value();
                PlayerRecord current = getOrInitial(currentRecords, id);
                result.put(id, current.afterDraw());
            }
        } else {
            // Có winner: tính delta Elo pairwise cho từng người
            String winnerId = matchResult.winnerId().value();

            // Thu thập rating hiện tại theo slot để tính pairwise
            Map<String, Double> currentRatings = new HashMap<>();
            for (ParticipantResult p : participants) {
                String id = p.playerId().value();
                currentRatings.put(id, getOrInitial(currentRecords, id).rating());
            }

            for (ParticipantResult p : participants) {
                String id = p.playerId().value();
                PlayerRecord current = getOrInitial(currentRecords, id);

                // Danh sách rating đối thủ (tất cả participant còn lại)
                List<Double> opponentRatings = participants.stream()
                        .filter(op -> !op.playerId().value().equals(id))
                        .map(op -> currentRatings.get(op.playerId().value()))
                        .collect(Collectors.toList());

                boolean isWinner = id.equals(winnerId);
                double score = isWinner ? 1.0 : 0.0;

                double delta = policy.computeDelta(current.rating(), opponentRatings, score);
                double newRating = Math.max(0.0, current.rating() + delta);

                PlayerRecord updated = current.withRating(newRating);
                updated = isWinner ? updated.afterWin() : updated.afterLoss();
                result.put(id, updated);
            }
        }

        return Collections.unmodifiableMap(result);
    }

    /** Lấy record hiện tại hoặc tạo mới nếu chưa có. */
    private PlayerRecord getOrInitial(Map<String, PlayerRecord> records, String profileId) {
        return records.getOrDefault(profileId, PlayerRecord.initial(profileId));
    }
}
