package com.smashprofs.game.network.client;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Thread-safe outbound input queue for Super-Smash-Profs client.
 * <p>
 * Key architectural features:
 * 1. Decouples the 60 FPS LibGDX Render Thread from Network Send ticks.
 * 2. Merges Continuous States (moveAxis, blockHeld) taking the most recent sample.
 * 3. Carries forward Discrete Events (jumpPressed, stompPressed, meleePressed, abilityPressed)
 *    using OR-logic across sub-frames so fast button taps are never lost.
 * 4. Generates strictly monotonic sequence numbers for authoritative server validation.
 * 5. Bounded capacity with Drop-Oldest policy to prevent input lag backlog under network congestion.
 * </p>
 */
public class OutboundInputQueue {

    public static final int DEFAULT_CAPACITY = 32;

    private final PlayerId playerId;
    private final int capacity;
    private final AtomicLong sequenceGenerator = new AtomicLong(1);
    private final Queue<PlayerInput> queue;

    // Sub-frame accumulation state
    private float accumulatedMoveAxis = 0.0f;
    private boolean accumulatedJump = false;
    private boolean accumulatedStomp = false;
    private boolean accumulatedMelee = false;
    private boolean accumulatedBlock = false;
    private boolean accumulatedAbility = false;
    private boolean hasPendingFrameInput = false;

    public OutboundInputQueue(PlayerId playerId) {
        this(playerId, DEFAULT_CAPACITY);
    }

    public OutboundInputQueue(PlayerId playerId, int capacity) {
        if (playerId == null) {
            throw new IllegalArgumentException("PlayerId cannot be null");
        }
        this.playerId = playerId;
        this.capacity = Math.max(1, capacity);
        this.queue = new ArrayDeque<>(this.capacity);
    }

    /**
     * Called on each frame by the Render Thread to record keyboard/gamepad state.
     * Takes the latest continuous state (moveAxis, block) and carries forward discrete events (jump, attack, etc.).
     *
     * @param moveAxis Current horizontal axis [-1.0f .. 1.0f].
     * @param jump True if jump key was pressed this frame.
     * @param stomp True if stomp key was pressed this frame.
     * @param melee True if melee attack key was pressed this frame.
     * @param block True if block key is held this frame.
     * @param ability True if special ability key was pressed this frame.
     */
    public synchronized void recordFrameInput(float moveAxis, boolean jump, boolean stomp,
                                              boolean melee, boolean block, boolean ability) {
        // Continuous states: take latest value
        this.accumulatedMoveAxis = moveAxis;
        this.accumulatedBlock = block;

        // Discrete events: OR logic carry-forward
        this.accumulatedJump |= jump;
        this.accumulatedStomp |= stomp;
        this.accumulatedMelee |= melee;
        this.accumulatedAbility |= ability;

        this.hasPendingFrameInput = true;
    }

    /**
     * Packages any accumulated sub-frame input into a complete PlayerInput record.
     * Resets discrete event flags while preserving current continuous state.
     *
     * @param clientTick Current client tick timestamp.
     * @return true if an input was created and added to the queue, false if no input was recorded.
     */
    public synchronized boolean flushAccumulatedInput(long clientTick) {
        if (!hasPendingFrameInput) {
            return false;
        }

        long seq = sequenceGenerator.getAndIncrement();
        PlayerInput input = new PlayerInput(
                playerId,
                seq,
                clientTick,
                accumulatedMoveAxis,
                accumulatedJump,
                accumulatedStomp,
                accumulatedMelee,
                accumulatedBlock,
                accumulatedAbility
        );

        // Reset discrete events for next sampling window
        accumulatedJump = false;
        accumulatedStomp = false;
        accumulatedMelee = false;
        accumulatedAbility = false;
        hasPendingFrameInput = false;

        // Bounded capacity: drop oldest if saturated to avoid input lag accumulation
        if (queue.size() >= capacity) {
            queue.poll();
        }
        return queue.offer(input);
    }

    /**
     * Flushes any accumulated frame input and retrieves the next PlayerInput to send over network.
     *
     * @param clientTick Current client simulation tick.
     * @return The next PlayerInput record, or null if no input is ready.
     */
    public synchronized PlayerInput pollNextInputToSend(long clientTick) {
        if (hasPendingFrameInput) {
            flushAccumulatedInput(clientTick);
        }
        return queue.poll();
    }

    /**
     * Production enqueue transaction. The predicate must only perform a non-blocking enqueue.
     * A rejected head stays in this queue; new frames retain all edges and update held state.
     * Unlike the legacy flush/poll API, retries never drop the head or flush into a full queue.
     * Only a successful enqueue consumes the head and the accumulated frames. No success ACK
     * exists in v1: this guarantees local enqueue retention, not server delivery/retry.
     */
    public synchronized boolean trySendAccumulatedInput(long clientTick, Predicate<PlayerInput> enqueue) {
        if (clientTick < 0) { throw new IllegalArgumentException("Negative clientTick"); }
        if (queue.isEmpty()) { flushAccumulatedInput(clientTick); }
        PlayerInput head = queue.peek();
        if (head == null) { return false; }
        PlayerInput attempt = hasPendingFrameInput ? new PlayerInput(playerId, head.sequence(), clientTick,
                accumulatedMoveAxis, head.jumpPressed() || accumulatedJump,
                head.stompPressed() || accumulatedStomp, head.meleePressed() || accumulatedMelee,
                accumulatedBlock, head.abilityPressed() || accumulatedAbility) : head;
        if (!enqueue.test(attempt)) { return false; }
        queue.poll();
        accumulatedJump = accumulatedStomp = accumulatedMelee = accumulatedAbility = false;
        hasPendingFrameInput = false;
        return true;
    }

    /**
     * Directly enqueues a pre-constructed PlayerInput (e.g. for replays or testing).
     *
     * @param input The PlayerInput to enqueue.
     * @return true if enqueued successfully.
     */
    public synchronized boolean offer(PlayerInput input) {
        if (input == null) {
            return false;
        }
        if (queue.size() >= capacity) {
            queue.poll();
        }
        return queue.offer(input);
    }

    /**
     * Returns total count of queued inputs plus any uncommitted pending frame input.
     */
    public synchronized int size() {
        return queue.size() + (hasPendingFrameInput ? 1 : 0);
    }

    /**
     * Checks if the queue has no pending or queued inputs.
     */
    public synchronized boolean isEmpty() {
        return queue.isEmpty() && !hasPendingFrameInput;
    }

    /**
     * Clears all queued and accumulated inputs.
     */
    public synchronized void clear() {
        queue.clear();
        accumulatedMoveAxis = 0.0f;
        accumulatedJump = false;
        accumulatedStomp = false;
        accumulatedMelee = false;
        accumulatedBlock = false;
        accumulatedAbility = false;
        hasPendingFrameInput = false;
    }

    public PlayerId getPlayerId() {
        return playerId;
    }

    public int getCapacity() {
        return capacity;
    }

    public long getCurrentSequence() {
        return sequenceGenerator.get();
    }
}
