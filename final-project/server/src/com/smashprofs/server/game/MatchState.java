package com.smashprofs.server.game;

import com.smashprofs.common.model.PlayerId;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** State chính thức của trận; chỉ runtime simulation owner tăng tick, chưa có gameplay simulation. */
public final class MatchState {
    private final String matchId;
    private final String roomId;
    private volatile long serverTick = 0L;
    private final Map<PlayerId, ServerPlayer> players;

    public MatchState(MatchSetup setup) {
        Objects.requireNonNull(setup, "setup không được null");
        matchId = setup.matchId();
        roomId = setup.roomId();
        // MatchSetup đã bảo đảm 2–4 participant và không trùng PlayerId/slotIndex.
        Map<PlayerId, ServerPlayer> initialPlayers = new HashMap<>();
        for (MatchSetup.Participant participant : setup.participants()) {
            ServerPlayer player = new ServerPlayer(
                    participant.playerId(), participant.characterId(), participant.slotIndex());
            initialPlayers.put(player.getPlayerId(), player);
        }
        // Mỗi lần khởi tạo tạo player riêng; không dùng lại player state của trận khác.
        players = Map.copyOf(initialPlayers);
    }

    public String getMatchId() {
        return matchId;
    }

    public String getRoomId() {
        return roomId;
    }

    public long getServerTick() {
        return serverTick;
    }

    /** Chỉ MatchRuntime gọi từ simulation owner; không phải public setter cho network/UI. */
    void advanceTick() {
        serverTick += 1L;
    }

    public int getPlayerCount() {
        return players.size();
    }

    public boolean containsPlayer(PlayerId playerId) {
        return players.containsKey(Objects.requireNonNull(playerId, "playerId không được null"));
    }

    /** Danh sách ID dạng set bất biến, không cho phép sửa map nội bộ. */
    public Set<PlayerId> getPlayerIds() {
        return players.keySet();
    }

    /** Trả player chỉ có API đọc, hoặc null nếu ID không ở trong trận. */
    public ServerPlayer getPlayer(PlayerId playerId) {
        return players.get(Objects.requireNonNull(playerId, "playerId không được null"));
    }
}
