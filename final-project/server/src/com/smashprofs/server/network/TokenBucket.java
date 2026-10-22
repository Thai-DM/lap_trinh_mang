package com.smashprofs.server.network;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Constant memory, monotonic time, bounded burst. No timer/task per connection. */
public final class TokenBucket {
    private final double perSecond,capacity;
    private final LongSupplier clock;
    private double tokens; private long updated;
    public TokenBucket(int perSecond,int burst) { this(perSecond,burst,System::nanoTime); }
    public TokenBucket(int perSecond,int burst,LongSupplier clock) {
        if(perSecond<=0 || burst<=0) { throw new IllegalArgumentException("Positive rate/burst required"); }
        this.perSecond=perSecond;this.capacity=burst;this.tokens=burst;this.clock=Objects.requireNonNull(clock);updated=clock.getAsLong();
    }
    public synchronized boolean allow(int cost) {
        if(cost<0) { throw new IllegalArgumentException("Negative cost"); }
        long now=clock.getAsLong(); long elapsed=now-updated;
        if(elapsed>0) { tokens=Math.min(capacity,tokens+(elapsed/1e9)*perSecond); updated=now; }
        if(cost>tokens) { return false; } tokens-=cost;return true;
    }
}
