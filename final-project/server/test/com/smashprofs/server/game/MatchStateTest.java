package com.smashprofs.server.game;

import com.smashprofs.common.model.PlayerId;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MatchStateTest {

    @Test
    public void matchStateCreatedFromSetup() {
        MatchState state = new MatchState(setup(2));

        assertEquals(2, state.getPlayerCount());
        assertTrue(state.containsPlayer(new PlayerId("player-0")));
        assertNotNull(state.getPlayer(new PlayerId("player-0")));
    }

    @Test
    public void matchStatePreservesMatchId() {
        MatchSetup setup = setup(2);

        assertEquals(setup.matchId(), new MatchState(setup).getMatchId());
    }

    @Test
    public void matchStatePreservesRoomId() {
        MatchSetup setup = setup(2);

        assertEquals(setup.roomId(), new MatchState(setup).getRoomId());
    }

    @Test
    public void initialServerTickIsZero() {
        assertEquals(0L, new MatchState(setup(2)).getServerTick());
    }

    @Test
    public void twoPlayersInitializedCorrectly() {
        MatchSetup setup = setup(2);
        MatchState state = new MatchState(setup);

        assertEquals(2, state.getPlayerCount());
        assertParticipantsMatchState(setup, state);
    }

    @Test
    public void fourPlayersInitializedCorrectly() {
        MatchSetup setup = setup(4);
        MatchState state = new MatchState(setup);

        assertEquals(4, state.getPlayerCount());
        assertParticipantsMatchState(setup, state);
    }

    @Test
    public void everyPlayerStartsWith100FloatHP() {
        MatchState state = new MatchState(setup(4));

        for (PlayerId id : state.getPlayerIds()) {
            assertEquals(100.0f, state.getPlayer(id).getHp(), 0.0f);
        }
    }

    @Test
    public void everyPlayerStartsAlive() {
        MatchState state = new MatchState(setup(4));

        for (PlayerId id : state.getPlayerIds()) {
            assertFalse(state.getPlayer(id).isDead());
        }
    }

    @Test
    public void playerCharacterMatchesSetup() {
        MatchSetup setup = setup(4);
        MatchState state = new MatchState(setup);

        for (MatchSetup.Participant participant : setup.participants()) {
            assertEquals(participant.characterId(), state.getPlayer(participant.playerId()).getCharacterId());
        }
    }

    @Test
    public void playerSlotMatchesSetup() {
        MatchSetup setup = new MatchSetup("match-1", "room-1", List.of(
                new MatchSetup.Participant(new PlayerId("first"), 0, "Alex"),
                new MatchSetup.Participant(new PlayerId("second"), 3, "Viktor")));
        MatchState state = new MatchState(setup);

        assertParticipantsMatchState(setup, state);
        assertEquals(3, state.getPlayer(new PlayerId("second")).getSlotIndex());
    }

    @Test
    public void matchStateUsesUniquePlayerIds() {
        MatchState state = new MatchState(setup(4));
        Set<Integer> slots = new HashSet<>();
        Set<PlayerId> ids = new HashSet<>();

        for (PlayerId id : state.getPlayerIds()) {
            ServerPlayer player = state.getPlayer(id);
            assertTrue(ids.add(player.getPlayerId()));
            assertTrue(slots.add(player.getSlotIndex()));
        }
        assertEquals(4, ids.size());
        assertEquals(Set.of(0, 1, 2, 3), slots);
    }

    @Test
    public void externalCodeCannotModifyPlayerCollection() {
        MatchState state = new MatchState(setup(2));
        Set<PlayerId> ids = state.getPlayerIds();

        assertThrows(UnsupportedOperationException.class, () -> ids.add(new PlayerId("new")));
        assertThrows(UnsupportedOperationException.class, () -> ids.remove(new PlayerId("player-0")));
        assertThrows(UnsupportedOperationException.class, ids::clear);
        assertEquals(2, state.getPlayerCount());
        assertTrue(state.containsPlayer(new PlayerId("player-0")));
    }

    @Test
    public void previousTestsStillPass() {
        MatchSetup setup = setup(2);
        MatchRuntime runtime = new MatchRuntime(setup);
        assertFalse(runtime.isStarted());
        assertEquals(setup.matchId(), runtime.getMatchId());
        assertEquals(setup.roomId(), runtime.getRoomId());
        assertSame(setup.participants(), runtime.getParticipants());

        runtime.start();
        runtime.start();

        assertTrue(runtime.isStarted());
        assertSame(setup.participants(), runtime.getParticipants());
        assertEquals(setup.matchId(), runtime.getMatchState().getMatchId());
        assertParticipantsMatchState(setup, runtime.getMatchState());
    }

    @Test
    public void nullSetupIsRejected() {
        assertThrows(NullPointerException.class, () -> new MatchState(null));
    }

    @Test
    public void unknownPlayerIsNotPresent() {
        MatchState state = new MatchState(setup(2));
        PlayerId unknown = new PlayerId("unknown");

        assertFalse(state.containsPlayer(unknown));
        assertNull(state.getPlayer(unknown));
        assertEquals(2, state.getPlayerCount());
    }

    @Test
    public void nullPlayerQueriesAreRejected() {
        MatchState state = new MatchState(setup(2));

        assertThrows(NullPointerException.class, () -> state.containsPlayer(null));
        assertThrows(NullPointerException.class, () -> state.getPlayer(null));
    }

    @Test
    public void invalidCharacterInSetupCannotCreateState() {
        MatchSetup invalid = new MatchSetup("match-1", "room-1", List.of(
                new MatchSetup.Participant(new PlayerId("first"), 0, "Alex"),
                new MatchSetup.Participant(new PlayerId("second"), 1, "Unknown")));

        assertThrows(IllegalArgumentException.class, () -> new MatchState(invalid));
    }

    private static MatchSetup setup(int count) {
        List<String> characters = List.of("Alex", "Luca", "Maurice", "Viktor");
        List<MatchSetup.Participant> participants = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            participants.add(new MatchSetup.Participant(
                    new PlayerId("player-" + index), index, characters.get(index)));
        }
        return new MatchSetup("match-1", "room-1", participants);
    }

    private static void assertParticipantsMatchState(MatchSetup setup, MatchState state) {
        for (MatchSetup.Participant participant : setup.participants()) {
            ServerPlayer player = state.getPlayer(participant.playerId());
            assertNotNull(player);
            assertEquals(participant.playerId(), player.getPlayerId());
            assertEquals(participant.slotIndex(), player.getSlotIndex());
            assertEquals(participant.characterId(), player.getCharacterId());
        }
    }
}
