package com.smashprofs.game.network.client;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.net.*;
import java.util.*;

/** Runs before opening the desktop window. Gameplay then uses the existing LobbyClient directly. */
public final class ClusterLobbyConnector {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ClusterLobbyConnector() { }
    public static LobbyClient fromArguments(String[] arguments) throws IOException {
        String control = null, room = null, region = "local"; boolean create = false, join = false,gameplay=false;
        Set<String> seen = new HashSet<>();
        for (String argument : arguments) {
            if (!seen.add(argument.split("=", 2)[0])) { throw new IllegalArgumentException("Duplicate cluster option"); }
            if (argument.startsWith("--control-url=")) { control = argument.substring(14); }
            else if (argument.startsWith("--room=")) { room = argument.substring(7); }
            else if (argument.startsWith("--region=")) { region = argument.substring(9); }
            else if (argument.equals("--create-room")) { create=true; }
            else if (argument.equals("--join-room")) { join=true; }
            else if (argument.equals("--gameplay")) { gameplay=true; }
            else { throw new IllegalArgumentException("Unknown cluster option: " + argument); }
        }
        if (control == null || create == join || (join && room == null)) {
            throw new IllegalArgumentException("Cluster: --control-url=http://127.0.0.1:8080 --create-room OR --join-room --room=ID");
        }
        URI uri = URI.create(control);
        if (!uri.getScheme().equals("http") || !("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()))) {
            throw new IllegalArgumentException("Prototype control URL must use loopback HTTP");
        }
        JsonNode auth = call(control, "/auth/guest", Map.of(), null);
        JsonNode assignment = call(control, create ? "/rooms/create" : "/rooms/join",
                create ? Map.of("region", region,"gameplay",gameplay) : Map.of("roomId", room), auth.path("token").asText());
        String host = assignment.path("host").asText(); int port = assignment.path("port").asInt();
        String assignedRoom = assignment.path("roomId").asText();
        System.out.println("Cluster room: " + assignedRoom + " worker=" + assignment.path("workerId").asText());
        var configuration = new LobbyConfiguration(host, port, assignedRoom, LobbyConfiguration.Mode.JOIN);
        var network = NetworkClient.forCluster(host, port, assignment.path("ticket").asText(), null);
        var lobby=new LobbyClient(configuration, network, s -> { });
        if(assignment.path("gameplay").asBoolean(false)) { lobby.enableGameplay(); }
        return lobby;
    }
    private static JsonNode call(String base, String path, Object body, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(base + path).toURL().openConnection();
        connection.setConnectTimeout(2000); connection.setReadTimeout(3000); connection.setDoOutput(true); connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json"); if (token != null) { connection.setRequestProperty("Authorization", token); }
        try {
            byte[] bytes = JSON.writeValueAsBytes(body); connection.setFixedLengthStreamingMode(bytes.length);
            try (var output = connection.getOutputStream()) { output.write(bytes); }
            if (connection.getResponseCode() != 200) { throw new IOException("Cluster request failed: HTTP " + connection.getResponseCode()); }
            try (var input = connection.getInputStream()) {
                byte[] response = input.readNBytes(16385); if (response.length > 16384) { throw new IOException("Assignment too large"); }
                return JSON.readTree(response);
            }
        } finally { connection.disconnect(); }
    }
}
