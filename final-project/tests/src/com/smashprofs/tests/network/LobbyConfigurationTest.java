package com.smashprofs.tests.network;

import com.smashprofs.game.network.client.LobbyConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

public class LobbyConfigurationTest {
    @Test public void offlineAndMockRemainExplicitSeparateModes() {
        assertNull(LobbyConfiguration.fromArguments(new String[0]));
        assertNull(LobbyConfiguration.fromArguments(new String[]{"--mock"}));
        assertThrows(IllegalArgumentException.class, () -> LobbyConfiguration.fromArguments(new String[]{
                "--mock", "--server-port=5555", "--room=arena", "--join-room"}));
    }
    @Test public void parsesCreateAndJoinForLocalhostAndLan() {
        var host = LobbyConfiguration.fromArguments(new String[]{"--server-port=5555", "--room=arena_A-1", "--create-room"});
        assertEquals("127.0.0.1", host.host());
        assertEquals(5555, host.port());
        assertEquals("arena_A-1", host.roomId());
        assertEquals(LobbyConfiguration.Mode.CREATE, host.mode());
        var joiner = LobbyConfiguration.fromArguments(new String[]{"--server-host=192.168.1.10", "--server-port=5555", "--room=arena_A-1", "--join-room"});
        assertEquals("192.168.1.10", joiner.host());
        assertEquals(LobbyConfiguration.Mode.JOIN, joiner.mode());
    }
    @Test public void rejectsInvalidOrIncompleteOnlineConfiguration() {
        for (String[] arguments : new String[][]{
                {"--room=arena", "--join-room"},
                {"--server-port=0", "--room=arena", "--join-room"},
                {"--server-port=65536", "--room=arena", "--join-room"},
                {"--server-port=bad", "--room=arena", "--join-room"},
                {"--server-port=5555", "--room=arena"},
                {"--server-port=5555", "--join-room"},
                {"--server-port=5555", "--room=arena", "--join-room", "--create-room"},
                {"--server-host=", "--server-port=5555", "--room=arena", "--join-room"},
                {"--server-port=5555", "--server-port=5556", "--room=arena", "--join-room"}}) {
            assertThrows(IllegalArgumentException.class, () -> LobbyConfiguration.fromArguments(arguments));
        }
    }
    @Test public void validatesRoomIdWithoutTrimmingOrRewriting() {
        LobbyConfiguration.validateRoomId("r".repeat(64));
        for (String room : new String[]{"", " room", "room ", "r".repeat(65), "room/name", "phong-\u00e9"}) {
            assertThrows(IllegalArgumentException.class, () -> LobbyConfiguration.validateRoomId(room));
        }
    }
}
