package com.smashprofs.server.room;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.game.MatchRuntime;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Kiểm tra cleanup World thật nếu boundary đăng ký thất bại sau khi runtime start. */
public class GameRoomPhysicsLifecycleTest {
    @Test
    public void registrationFailureClosesUnpublishedRuntime() {
        ManualClock clock = new ManualClock();
        AtomicReference<MatchRuntime> candidate = new AtomicReference<>();
        RuntimeException failure = new IllegalStateException("test-registration-failure");
        GameRoom room = new GameRoom("room", clock, runtime -> {
            candidate.set(runtime);
            assertTrue(runtime.isStarted());
            throw failure;
        });
        prepare(room, clock);
        assertSame(failure, assertThrows(IllegalStateException.class, room::startMatch));
        assertNotNull(candidate.get());
        assertTrue(candidate.get().isClosed());
        assertThrows(IllegalStateException.class, candidate.get()::tick);
        assertNull(room.getMatchRuntime());
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
    }

    @Test
    public void automaticLifecycleFailureDoesNotPublishPlayingOrRetryWorldCreation() {
        ManualClock clock = new ManualClock();
        AtomicReference<MatchRuntime> candidate = new AtomicReference<>();
        GameRoom room = new GameRoom("room", clock, runtime -> {
            candidate.set(runtime);
            throw new IllegalStateException("test-registration-failure");
        });
        prepare(room, clock);
        assertFalse(room.updateLifecycle());
        assertTrue(candidate.get().isClosed());
        assertEquals(RoomPhase.READY, room.getPhase());
        assertNull(room.getMatchRuntime());
        assertNotNull(room.getLifecycleFailure());
        MatchRuntime first = candidate.get();
        assertFalse(room.updateLifecycle());
        assertSame(first, candidate.get());
    }

    private static void prepare(GameRoom room, ManualClock clock) {
        for (PlayerId player : List.of(new PlayerId("alice"), new PlayerId("bob"))) {
            room.joinPlayer(player);
            room.selectCharacter(player, "Alex");
            room.setReady(player, true);
        }
        assertTrue(room.startCountdown());
        clock.advanceMillis(3000);
        assertTrue(room.updateCountdown());
        assertNotNull(room.prepareMatch());
    }
}
