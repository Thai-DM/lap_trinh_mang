package com.smashprofs.game.network.client;

import java.util.HashSet;
import java.util.Set;

/** Connection/lobby settings only; no simulation or rendering settings. */
public record LobbyConfiguration(String host, int port, String roomId, Mode mode) {
    public enum Mode { CREATE, JOIN }

    public LobbyConfiguration {
        if (host == null || host.isBlank()) { throw new IllegalArgumentException("Server host is required"); }
        if (port < 1 || port > 65535) { throw new IllegalArgumentException("Server port must be 1..65535"); }
        validateRoomId(roomId);
        if (mode == null) { throw new IllegalArgumentException("Choose create or join mode"); }
    }

    public static void validateRoomId(String roomId) {
        if (roomId == null || !roomId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Room must match [A-Za-z0-9_-]{1,64}");
        }
    }

    /** Null means the existing offline or --mock launcher path. */
    public static LobbyConfiguration fromArguments(String[] arguments) {
        String host = "127.0.0.1", room = null;
        Integer port = null;
        Mode mode = null;
        boolean mock = false, online = false;
        Set<String> seen = new HashSet<>();
        for (String argument : arguments == null ? new String[0] : arguments) {
            String key = argument.split("=", 2)[0];
            if (!seen.add(key)) { throw new IllegalArgumentException("Duplicate option: " + key); }
            if ("--mock".equalsIgnoreCase(argument)) { mock = true; continue; }
            online = true;
            if (argument.startsWith("--server-host=")) { host = argument.substring(14); }
            else if (argument.startsWith("--server-port=")) {
                try { port = Integer.parseInt(argument.substring(14)); }
                catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid server port"); }
            } else if (argument.startsWith("--room=")) { room = argument.substring(7); }
            else if ("--create-room".equals(argument) || "--join-room".equals(argument)) {
                if (mode != null) { throw new IllegalArgumentException("Choose only one of --create-room or --join-room"); }
                mode = "--create-room".equals(argument) ? Mode.CREATE : Mode.JOIN;
            } else { throw new IllegalArgumentException("Unknown option: " + argument); }
        }
        if (!online) { return null; }
        if (mock) { throw new IllegalArgumentException("--mock cannot be combined with online lobby options"); }
        if (port == null) { throw new IllegalArgumentException("--server-port is required for online mode"); }
        return new LobbyConfiguration(host, port, room, mode);
    }
}
