package com.smashprofs.server.network;

import com.smashprofs.common.protocol.MessageType;
import com.smashprofs.common.protocol.ProtocolMessage;
import com.smashprofs.common.protocol.ProtocolV1;
import com.smashprofs.server.game.AuthoritativeMatchResult;
import java.util.Objects;

/** Chỉ ánh xạ kết quả đã công bố; không tính lại winner, không đọc World hoặc tạo MatchResult. */
public final class GameEndMessageMapper {
    private GameEndMessageMapper() { }
    public static ProtocolMessage map(AuthoritativeMatchResult result) {
        Objects.requireNonNull(result);
        var payload = ProtocolV1.object().put("matchId", result.matchId()).put("roomId", result.roomId())
                .put("finishedAtTick", result.finishedAtTick()).put("finishReason", result.finishReason().name());
        if (result.winnerId() == null) { payload.putNull("winnerId"); }
        else { payload.put("winnerId", result.winnerId().value()); }
        var participants = payload.putArray("participants");
        result.participants().stream().sorted(java.util.Comparator.comparingInt(AuthoritativeMatchResult.ParticipantResult::slotIndex))
                .forEach(player -> participants.addObject().put("playerId", player.playerId().value())
                        .put("slotIndex", player.slotIndex()).put("characterId", player.characterId())
                        .put("displayName", player.displayName())
                        .put("finalHp", player.finalHp()).put("eliminated", player.eliminated()));
        if(result.gameplay()!=null) { payload.set("gameplay",com.smashprofs.common.protocol.GameplayProtocol.snapshot(result.gameplay())); }
        return new ProtocolMessage(MessageType.GAME_END, null, payload);
    }
}
