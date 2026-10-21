package com.smashprofs.server.game;

import com.smashprofs.common.model.PlayerId;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MatchSetupTest {

    @Test
    public void setupAndParticipantAreImmutableRecords() {
        assertTrue(MatchSetup.class.isRecord());
        assertTrue(MatchSetup.Participant.class.isRecord());
        MatchSetup setup = new MatchSetup("match-1", "room-1", participants());

        assertEquals("match-1", setup.matchId());
        assertEquals("room-1", setup.roomId());
        assertEquals(new PlayerId("player-0"), setup.participants().get(0).playerId());
        assertEquals(0, setup.participants().get(0).slotIndex());
        assertEquals("Alex", setup.participants().get(0).characterId());
    }

    @Test
    public void participantListIsDefensivelyCopied() {
        List<MatchSetup.Participant> source = new ArrayList<>(participants());
        MatchSetup setup = new MatchSetup("match-1", "room-1", source);

        source.set(0, new MatchSetup.Participant(new PlayerId("replacement"), 0, "Luca"));
        source.clear();

        assertEquals(participants(), setup.participants());
    }

    @Test
    public void participantListCannotBeModified() {
        MatchSetup setup = new MatchSetup("match-1", "room-1", participants());
        MatchSetup.Participant third = new MatchSetup.Participant(new PlayerId("third"), 2, "Leo");

        assertThrows(UnsupportedOperationException.class, () -> setup.participants().add(third));
        assertThrows(UnsupportedOperationException.class, () -> setup.participants().set(0, third));
        assertThrows(UnsupportedOperationException.class, () -> setup.participants().remove(0));
        assertThrows(UnsupportedOperationException.class, () -> setup.participants().clear());
        assertEquals(participants(), setup.participants());
    }

    @Test
    public void participantCountMustBeBetweenTwoAndFour() {
        assertThrows(IllegalArgumentException.class,
                () -> new MatchSetup("match-1", "room-1", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new MatchSetup("match-1", "room-1", List.of(participants().get(0))));
        List<MatchSetup.Participant> five = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            five.add(new MatchSetup.Participant(new PlayerId("player-" + index), index % 4, "Alex"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new MatchSetup("match-1", "room-1", five));
    }

    @Test
    public void duplicatePlayerIdsAreRejected() {
        List<MatchSetup.Participant> duplicates = List.of(
                new MatchSetup.Participant(new PlayerId("same"), 0, "Alex"),
                new MatchSetup.Participant(new PlayerId("same"), 1, "Luca"));

        assertThrows(IllegalArgumentException.class,
                () -> new MatchSetup("match-1", "room-1", duplicates));
    }

    @Test
    public void duplicateSlotIndexesAreRejected() {
        List<MatchSetup.Participant> duplicates = List.of(
                new MatchSetup.Participant(new PlayerId("first"), 0, "Alex"),
                new MatchSetup.Participant(new PlayerId("second"), 0, "Alex"));

        assertThrows(IllegalArgumentException.class,
                () -> new MatchSetup("match-1", "room-1", duplicates));
    }

    @Test
    public void invalidMatchAndRoomIdsAreRejected() {
        assertThrows(NullPointerException.class, () -> new MatchSetup(null, "room-1", participants()));
        assertThrows(NullPointerException.class, () -> new MatchSetup("match-1", null, participants()));
        for (String blank : List.of("", " ", "\t\n")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new MatchSetup(blank, "room-1", participants()));
            assertThrows(IllegalArgumentException.class,
                    () -> new MatchSetup("match-1", blank, participants()));
        }
    }

    @Test
    public void nullParticipantListAndEntriesAreRejected() {
        assertThrows(NullPointerException.class, () -> new MatchSetup("match-1", "room-1", null));
        assertThrows(NullPointerException.class,
                () -> new MatchSetup("match-1", "room-1", Arrays.asList(participants().get(0), null)));
    }

    @Test
    public void participantRequiresValidPlayerId() {
        assertThrows(NullPointerException.class, () -> new MatchSetup.Participant(null, 0, "Alex"));
        assertThrows(NullPointerException.class,
                () -> new MatchSetup.Participant(new PlayerId(null), 0, "Alex"));
        for (String blank : List.of("", " ", "\t\n")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new MatchSetup.Participant(new PlayerId(blank), 0, "Alex"));
        }
    }

    @Test
    public void participantRequiresValidSlotAndCharacterId() {
        PlayerId playerId = new PlayerId("player-0");
        for (int index : new int[]{-1, 4, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new MatchSetup.Participant(playerId, index, "Alex"));
        }
        assertThrows(NullPointerException.class, () -> new MatchSetup.Participant(playerId, 0, null));
        for (String blank : List.of("", " ", "\t\n")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new MatchSetup.Participant(playerId, 0, blank));
        }
    }

    private static List<MatchSetup.Participant> participants() {
        return List.of(
                new MatchSetup.Participant(new PlayerId("player-0"), 0, "Alex"),
                new MatchSetup.Participant(new PlayerId("player-1"), 1, "Luca"));
    }
}
