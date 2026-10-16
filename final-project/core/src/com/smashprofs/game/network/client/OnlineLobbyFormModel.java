package com.smashprofs.game.network.client;

/** Render-thread form state. LobbyClient owns background I/O until handed to Game. */
public final class OnlineLobbyFormModel implements AutoCloseable {
    private LobbyClient pending;
    private String status = "Mở server trước, sau đó tạo phòng hoặc nhập mã để chơi cùng bạn bè.";
    private boolean error;

    public boolean connect(String host, String port, String room, LobbyConfiguration.Mode mode) {
        if (pending != null) { return false; }
        try {
            String address = host == null ? "" : host.strip();
            if (address.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("Địa chỉ máy chủ không được chứa khoảng trắng.");
            }
            int number;
            try { number = Integer.parseInt(port == null ? "" : port.strip()); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("Cổng phải là số từ 1 đến 65535."); }
            var configuration = new LobbyConfiguration(address, number, room, mode);
            pending = new LobbyClient(configuration);
            pending.enableGameplay();
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
            status=OnlineRoomPresentation.error(snapshot.errorCode(),snapshot.statusText());
            pending.close(); pending = null; error = true;
        }
        return null;
    }
    public boolean isConnecting() { return pending != null; }
    public boolean hasError() { return error; }
    public String getStatus() { return pending == null ? status : "Đang kết nối máy chủ và mở phòng…"; }
    public void cancel() {
        if (pending != null) { pending.close(); pending = null; }
        status = "Đã hủy kết nối. Bạn có thể thử lại."; error = false;
    }
    @Override public void close() { if (pending != null) { pending.close(); pending = null; } }
}
