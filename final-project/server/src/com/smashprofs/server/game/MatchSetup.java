package com.smashprofs.server.game;

import com.smashprofs.common.model.PlayerId;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Dữ liệu khởi tạo bất biến của một trận, chưa chứa gameplay state. */
public record MatchSetup(String matchId, String roomId, List<Participant> participants, com.smashprofs.common.model.MatchRules rules) {
    public MatchSetup(String matchId,String roomId,List<Participant> participants) {
        this(matchId,roomId,participants,com.smashprofs.common.model.MatchRules.LEGACY);
    }
    public MatchSetup {
        Objects.requireNonNull(rules,"rules");
        requireNonBlank(matchId, "matchId");
        requireNonBlank(roomId, "roomId");
        participants = List.copyOf(participants);
        if (participants.size() < 2 || participants.size() > rules.maxPlayers()) {
            throw new IllegalArgumentException("Trận cần từ 2 đến 4 participant");
        }
        Set<PlayerId> playerIds = new HashSet<>();
        Set<Integer> slotIndexes = new HashSet<>();
        for (Participant participant : participants) {
            if (!playerIds.add(participant.playerId())) {
                throw new IllegalArgumentException("PlayerId không được trùng trong trận");
            }
            if (!slotIndexes.add(participant.slotIndex())) {
                throw new IllegalArgumentException("slotIndex không được trùng trong trận");
            }
        }
    }

    /** Bản sao player, slot và nhân vật đã được GameRoom xác thực, không liên kết state mutable. */
    public record Participant(PlayerId playerId, int slotIndex, String characterId, String displayName) {
        public Participant(PlayerId playerId, int slotIndex, String characterId) { this(playerId, slotIndex, characterId, null); }
        public Participant {
            Objects.requireNonNull(playerId, "playerId không được null");
            requireNonBlank(playerId.value(), "playerId.value");
            if (slotIndex < 0 || slotIndex > 3) {
                throw new IllegalArgumentException("slotIndex phải nằm trong khoảng 0 đến 3");
            }
            requireNonBlank(characterId, "characterId");
            displayName = com.smashprofs.common.model.DisplayName.sanitize(displayName, characterId);
        }
    }

    /** Giữ nguyên chuỗi hợp lệ, không tự trim hoặc sinh ID trong model dữ liệu. */
    private static void requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " không được null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " không được blank");
        }
    }
}
