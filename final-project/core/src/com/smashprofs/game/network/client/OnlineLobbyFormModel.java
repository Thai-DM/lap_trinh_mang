package com.smashprofs.game.network.client;

/** Render-thread form state. LobbyClient owns background I/O until handed to Game. */
public final class OnlineLobbyFormModel implements AutoCloseable {
    private LobbyClient pending;
    private String status = "Enter the running server address and a room ID.";
    private boolean error;

    public boolean connect(String host, String port, String room, LobbyConfiguration.Mode mode) {
        if (pending != null) { return false; }
        try {
            String address = host == null ? "" : host.strip();
            if (address.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("Server address cannot contain spaces.");
            }
            int number;
            try { number = Integer.parseInt(port == null ? "" : port.strip()); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("Port must be a number from 1 to 65535."); }
            var configuration = new LobbyConfiguration(address, number, room, mode);
            pending = new LobbyClient(configuration);
            error = false;
            pending.startAsync();
            return true;
        } catch (IllegalArgumentException invalid) {
            status = invalid.getMessage(); error = true;
            return false;
        }
    }

    /** A joined connection transfers exactly once; a failed attempt is closed before retry. */
    public LobbyClient takeJoinedClient() {
        if (pending == null) { return null; }
        var snapshot = pending.getSnapshot();
        status = snapshot.statusText();
        if (snapshot.phase() == LobbyClient.Phase.JOINED) {
            var joined = pending; pending = null; return joined;
        }
        if (snapshot.phase() == LobbyClient.Phase.ERROR || snapshot.phase() == LobbyClient.Phase.DISCONNECTED) {
            pending.close(); pending = null; error = true;
        }
        return null;
    }
    public boolean isConnecting() { return pending != null; }
    public boolean hasError() { return error; }
    public String getStatus() { return pending == null ? status : pending.getSnapshot().statusText(); }
    public void cancel() {
        if (pending != null) { pending.close(); pending = null; }
        status = "Connection cancelled. You can try another server or room."; error = false;
    }
    @Override public void close() { if (pending != null) { pending.close(); pending = null; } }
}
