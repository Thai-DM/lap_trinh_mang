package com.smashprofs.server.game;

import com.smashprofs.common.model.PlayerId;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MatchRuntimeTest {
    private final List<MatchRuntime> createdRuntimes = new ArrayList<>();

    @After
    public void closeNativeResources() {
        createdRuntimes.forEach(MatchRuntime::close);
    }

    private MatchRuntime createRuntime(MatchSetup setup) {
        MatchRuntime runtime = new MatchRuntime(setup);
        createdRuntimes.add(runtime);
        return runtime;
    }

    @Test public void closingAnUnfinishedMatchReleasesQueuedInputsAndNativeWorld() {
        int worlds = com.smashprofs.server.game.physics.HeadlessPhysicsWorld.liveWorldCount();
        var runtime = createRuntime(setup()); runtime.start();
        var player = runtime.getParticipants().get(0).playerId();
        runtime.submitInput(new com.smashprofs.common.model.PlayerInput(player, 0, 0, 1, false, false, false, false, false));
        assertEquals(1, runtime.getPendingInputCount());
        runtime.close(); runtime.close();
        assertEquals(0, runtime.getPendingInputCount()); assertNull(runtime.getLatestSnapshot());
        assertEquals(worlds, com.smashprofs.server.game.physics.HeadlessPhysicsWorld.liveWorldCount());
    }

    @Test
    public void runtimeCanBeCreatedFromMatchSetup() {
        MatchRuntime runtime = createRuntime(setup());

        assertFalse(runtime.isStarted());
        assertEquals("match-1", runtime.getMatchId());
        assertEquals("room-1", runtime.getRoomId());
    }

    @Test
    public void runtimePreservesMatchId() {
        MatchSetup setup = setup();
        MatchRuntime runtime = createRuntime(setup);

        assertEquals(setup.matchId(), runtime.getMatchId());
        runtime.start();
        assertEquals(setup.matchId(), runtime.getMatchId());
    }

    @Test
    public void runtimePreservesParticipants() {
        MatchSetup setup = setup();
        MatchRuntime runtime = createRuntime(setup);

        assertSame(setup.participants(), runtime.getParticipants());
        runtime.start();
        assertEquals(setup.participants(), runtime.getParticipants());
    }

    @Test
    public void runtimeStartsSuccessfully() {
        MatchRuntime runtime = createRuntime(setup());

        runtime.start();

        assertTrue(runtime.isStarted());
    }

    @Test
    public void startingRuntimeTwiceIsSafe() {
        MatchSetup setup = setup();
        MatchRuntime runtime = createRuntime(setup);

        runtime.start();
        runtime.start();

        assertTrue(runtime.isStarted());
        assertEquals(setup.matchId(), runtime.getMatchId());
        assertEquals(setup.roomId(), runtime.getRoomId());
        assertSame(setup.participants(), runtime.getParticipants());
    }

    @Test
    public void nullMatchSetupIsRejected() {
        assertThrows(NullPointerException.class, () -> createRuntime(null));
    }

    @Test
    public void runtimePreservesRoomId() {
        MatchSetup setup = setup();
        MatchRuntime runtime = createRuntime(setup);
        runtime.start();

        assertEquals(setup.roomId(), runtime.getRoomId());
    }

    @Test
    public void runtimeParticipantListCannotBeModified() {
        MatchRuntime runtime = createRuntime(setup());

        assertThrows(UnsupportedOperationException.class, () -> runtime.getParticipants().remove(0));
        assertThrows(UnsupportedOperationException.class, () -> runtime.getParticipants().clear());
        assertEquals(2, runtime.getParticipants().size());
    }

    @Test
    public void runtimeOwnsExactlyOneMatchState() {
        MatchRuntime runtime = createRuntime(setup());
        assertNull(runtime.getMatchState());

        runtime.start();
        MatchState state = runtime.getMatchState();

        assertNotNull(state);
        assertTrue(runtime.isStarted());
        assertSame(state, runtime.getMatchState());
        assertEquals(runtime.getMatchId(), state.getMatchId());
        assertEquals(runtime.getRoomId(), state.getRoomId());
    }

    @Test
    public void startingRuntimeTwiceDoesNotResetState() {
        MatchRuntime runtime = createRuntime(setup());
        runtime.start();
        MatchState state = runtime.getMatchState();
        ServerPlayer player = state.getPlayer(new PlayerId("player-0"));

        runtime.start();
        runtime.start();

        assertSame(state, runtime.getMatchState());
        assertSame(player, runtime.getMatchState().getPlayer(new PlayerId("player-0")));
        assertEquals(100.0f, player.getHp(), 0.0f);
        assertEquals(0L, state.getServerTick());
        assertFalse(player.isDead());
    }

    @Test
    public void twoMatchesHaveIndependentStates() {
        MatchRuntime first = createRuntime(setup());
        MatchRuntime second = createRuntime(new MatchSetup("match-2", "room-2", List.of(
                new MatchSetup.Participant(new PlayerId("other-0"), 0, "Leo"),
                new MatchSetup.Participant(new PlayerId("other-1"), 1, "Viktor"))));
        first.start();
        assertFalse(second.isStarted());
        assertNull(second.getMatchState());
        second.start();
        MatchState firstState = first.getMatchState();
        MatchState secondState = second.getMatchState();

        assertNotSame(firstState, secondState);
        assertFalse(firstState.containsPlayer(new PlayerId("other-0")));
        assertFalse(secondState.containsPlayer(new PlayerId("player-0")));
        assertEquals("room-1", firstState.getRoomId());
        assertEquals("room-2", secondState.getRoomId());
        assertEquals(2, firstState.getPlayerCount());
        assertEquals(2, secondState.getPlayerCount());
    }

    @Test
    public void runtimesUsingSameSetupStillOwnSeparatePlayerStates() {
        MatchSetup setup = setup();
        MatchRuntime first = createRuntime(setup);
        MatchRuntime second = createRuntime(setup);
        first.start();
        second.start();

        assertNotSame(first.getMatchState(), second.getMatchState());
        for (MatchSetup.Participant participant : setup.participants()) {
            assertNotSame(first.getMatchState().getPlayer(participant.playerId()),
                    second.getMatchState().getPlayer(participant.playerId()));
        }
    }

    @Test
    public void failedStateInitializationDoesNotMarkRuntimeStarted() {
        MatchSetup invalid = new MatchSetup("match-1", "room-1", List.of(
                new MatchSetup.Participant(new PlayerId("first"), 0, "Alex"),
                new MatchSetup.Participant(new PlayerId("second"), 1, "Unknown")));
        MatchRuntime runtime = createRuntime(invalid);

        assertThrows(IllegalArgumentException.class, runtime::start);

        assertFalse(runtime.isStarted());
        assertNull(runtime.getMatchState());
        assertThrows(IllegalArgumentException.class, runtime::start);
        assertFalse(runtime.isStarted());
        assertNull(runtime.getMatchState());
    }

    private static MatchSetup setup() {
        return new MatchSetup("match-1", "room-1", List.of(
                new MatchSetup.Participant(new PlayerId("player-0"), 0, "Alex"),
                new MatchSetup.Participant(new PlayerId("player-1"), 1, "Luca")));
    }
}
