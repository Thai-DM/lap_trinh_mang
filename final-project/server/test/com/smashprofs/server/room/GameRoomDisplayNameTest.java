package com.smashprofs.server.room;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.game.MatchSetup;
import org.junit.Test;

import static org.junit.Assert.*;

public class GameRoomDisplayNameTest {

    @Test
    public void preparesMatchWithSanitizedDisplayNames() {
        ManualClock clock = new ManualClock();
        GameRoom room = new GameRoom("room-dn", clock);

        PlayerId p1 = new PlayerId("p1");
        PlayerId p2 = new PlayerId("p2");
        PlayerId p3 = new PlayerId("p3");

        assertEquals(GameRoom.JoinResult.JOINED, room.joinPlayer(p1));
        assertEquals(GameRoom.JoinResult.JOINED, room.joinPlayer(p2));
        assertEquals(GameRoom.JoinResult.JOINED, room.joinPlayer(p3));

        room.setDisplayName(p1, "ChampionAlexander"); // > 12 chars -> ChampionAlex
        room.setDisplayName(p2, "   \t\n  ");           // blank -> fallback to characterId "Leo"
        // p3 has no displayName set -> null -> fallback to characterId "Luca"

        assertTrue(room.selectCharacter(p1, "Alex"));
        assertTrue(room.selectCharacter(p2, "Leo"));
        assertTrue(room.selectCharacter(p3, "Luca"));

        assertTrue(room.setReady(p1, true));
        assertTrue(room.setReady(p2, true));
        assertTrue(room.setReady(p3, true));

        assertTrue(room.startCountdown());
        clock.advanceMillis(3000);
        assertTrue(room.updateCountdown());

        MatchSetup setup = room.prepareMatch();
        assertNotNull(setup);
        assertEquals(3, setup.participants().size());

        MatchSetup.Participant part1 = setup.participants().get(0);
        assertEquals("ChampionAlex", part1.displayName());

        MatchSetup.Participant part2 = setup.participants().get(1);
        assertEquals("Leo", part2.displayName());

        MatchSetup.Participant part3 = setup.participants().get(2);
        assertEquals("Luca", part3.displayName());
    }

    @Test
    public void leavingRoomCleansUpDisplayName() {
        ManualClock clock = new ManualClock();
        GameRoom room = new GameRoom("room-dn-leave", clock);
        PlayerId p1 = new PlayerId("p1");

        assertEquals(GameRoom.JoinResult.JOINED, room.joinPlayer(p1));
        room.setDisplayName(p1, "Alice");
        assertTrue(room.leavePlayer(p1));

        // Rejoin: displayName should be cleared and require re-setting or fallback
        assertEquals(GameRoom.JoinResult.JOINED, room.joinPlayer(p1));
        PlayerId p2 = new PlayerId("p2");
        assertEquals(GameRoom.JoinResult.JOINED, room.joinPlayer(p2));

        assertTrue(room.selectCharacter(p1, "Alex"));
        assertTrue(room.selectCharacter(p2, "Leo"));
        assertTrue(room.setReady(p1, true));
        assertTrue(room.setReady(p2, true));

        assertTrue(room.startCountdown());
        clock.advanceMillis(3000);
        assertTrue(room.updateCountdown());

        MatchSetup setup = room.prepareMatch();
        assertNotNull(setup);
        // p1 didn't set displayName after rejoin -> fallback to Alex
        assertEquals("Alex", setup.participants().get(0).displayName());
    }
}
