package com.smashprofs.server.network;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class TokenBucketTest {
    @Test public void burstsAreBoundedRefillIsMonotonicAndIdleDoesNotAccumulateUnboundedTokens() {
        var clock=new AtomicLong(0); var bucket=new TokenBucket(60,120,clock::get);
        assertTrue(bucket.allow(120)); assertFalse(bucket.allow(1));
        clock.set(1_000_000_000L); assertTrue(bucket.allow(60)); assertFalse(bucket.allow(1));
        clock.set(0); assertFalse(bucket.allow(1));
        clock.set(100_000_000_000L); assertTrue(bucket.allow(120)); assertFalse(bucket.allow(1));
    }
    @Test public void ordinarySixtyHertzInputDoesNotExhaustTheDefaultInputAllowance() {
        var clock=new AtomicLong(0); var bucket=new TokenBucket(240,480,clock::get);
        for(int i=0;i<6000;i++) { assertTrue(bucket.allow(1)); clock.addAndGet(1_000_000_000L/60); }
    }
}
