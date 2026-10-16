package com.smashprofs.server.game;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Queue input có giới hạn theo player; chưa áp dụng input vào state hoặc tạo thread. */
public final class InputQueue {
    private volatile com.smashprofs.server.metrics.PerformanceMetrics metrics = com.smashprofs.server.metrics.PerformanceMetrics.DISABLED;
    public void setPerformanceMetrics(com.smashprofs.server.metrics.PerformanceMetrics metrics) { this.metrics=Objects.requireNonNull(metrics); }
    private static final int DEFAULT_CAPACITY_PER_PLAYER = 32;
    private final int capacityPerPlayer;
    private final Map<PlayerId, PlayerBuffer> buffers = new LinkedHashMap<>();

    public InputQueue(MatchSetup setup) {
        this(setup, DEFAULT_CAPACITY_PER_PLAYER);
    }

    public InputQueue(MatchSetup setup, int capacityPerPlayer) {
        Objects.requireNonNull(setup, "setup không được null");
        if (capacityPerPlayer <= 0) {
            throw new IllegalArgumentException("capacityPerPlayer phải lớn hơn 0");
        }
        this.capacityPerPlayer = capacityPerPlayer;
        for (MatchSetup.Participant participant : setup.participants()) {
            buffers.put(participant.playerId(), new PlayerBuffer(capacityPerPlayer));
        }
    }

    /**
     * Kiểm tra DTO, roster, sequence rồi capacity trong cùng khóa.
     * Input bị từ chối không thay đổi queue hoặc sequence đã chấp nhận.
     * clientTick chỉ là metadata, không dùng làm thời gian chính thức.
     */
    public synchronized OfferResult offer(PlayerInput input) {
        long started=System.nanoTime();
        try {
            var result=validateAndOffer(input);
            if(result!=OfferResult.ACCEPTED) { metrics.inputRejections.increment(); }
            return result;
        } finally { metrics.record(com.smashprofs.server.metrics.PerformanceMetrics.Stage.INPUT_VALIDATION,System.nanoTime()-started); }
    }
    private OfferResult validateAndOffer(PlayerInput input) {
        if (input == null || input.playerId() == null || input.playerId().value() == null
                || input.playerId().value().isBlank() || input.sequence() < 0
                || !Float.isFinite(input.moveAxis()) || input.moveAxis() < -1.0f || input.moveAxis() > 1.0f) {
            return OfferResult.INVALID_INPUT;
        }
        PlayerBuffer buffer = buffers.get(input.playerId());
        if (buffer == null) {
            return OfferResult.UNKNOWN_PLAYER;
        }
        if (input.sequence() <= buffer.lastAcceptedSequence) {
            return OfferResult.DUPLICATE_OR_STALE;
        }
        if (buffer.pending.size() >= capacityPerPlayer) {
            return OfferResult.QUEUE_FULL;
        }
        buffer.acceptedAt[buffer.pending.size()]=System.nanoTime();
        buffer.pending.addLast(input);
        buffer.lastAcceptedSequence = input.sequence();
        return OfferResult.ACCEPTED;
    }

    /**
     * Batch bất biến theo thứ tự roster: tối đa một entry cho mỗi player có input mới.
     * Movement/block/sequence/clientTick lấy từ input mới nhất; các nút nhấn gom bằng OR.
     * Xóa input đã gom, không giữ edge hoặc tự tạo entry cho tick sau.
     */
    public synchronized List<PlayerInput> drainForTick() {
        long started=System.nanoTime();
        try { return drainOwned(started); }
        finally { metrics.record(com.smashprofs.server.metrics.PerformanceMetrics.Stage.INPUT_DEQUEUE,System.nanoTime()-started); }
    }
    private List<PlayerInput> drainOwned(long now) {
        List<PlayerInput> drained = new ArrayList<>();
        for (PlayerBuffer buffer : buffers.values()) {
            PlayerInput latest = buffer.pending.peekLast();
            if (latest == null) {
                continue;
            }
            boolean jump = false;
            boolean stomp = false;
            boolean melee = false;
            boolean ability = false;
            int index=0;
            for (PlayerInput input : buffer.pending) {
                metrics.record(com.smashprofs.server.metrics.PerformanceMetrics.Stage.INPUT_QUEUE_WAIT,now-buffer.acceptedAt[index++]);
                jump |= input.jumpPressed();
                stomp |= input.stompPressed();
                melee |= input.meleePressed();
                ability |= input.abilityPressed();
            }
            drained.add(new PlayerInput(latest.playerId(), latest.sequence(), latest.clientTick(),
                    latest.moveAxis(), jump, stomp, melee, latest.blockHeld(), ability));
            buffer.pending.clear();
        }
        return List.copyOf(drained);
    }

    /** Tổng số input chờ của mọi player, không phải số entry sau khi gom. */
    public synchronized int getPendingCount() {
        int count = 0;
        for (PlayerBuffer buffer : buffers.values()) {
            count += buffer.pending.size();
        }
        return count;
    }

    /** Xóa input chờ nhưng giữ sequence watermark để input cũ không được nhận lại. */
    public synchronized void clear() {
        for (PlayerBuffer buffer : buffers.values()) {
            buffer.pending.clear();
        }
    }

    public enum OfferResult {
        ACCEPTED,
        DUPLICATE_OR_STALE,
        INVALID_INPUT,
        UNKNOWN_PLAYER,
        QUEUE_FULL,
        MATCH_FINISHED, // MatchRuntime reject lifecycle; queue không tự đánh giá kết quả trận.
        PLAYER_INACTIVE
    }

    private static final class PlayerBuffer {
        final long[] acceptedAt;
        PlayerBuffer(int capacity) { acceptedAt=new long[capacity]; }
        private final ArrayDeque<PlayerInput> pending = new ArrayDeque<>();
        private long lastAcceptedSequence = -1L;
    }
}
