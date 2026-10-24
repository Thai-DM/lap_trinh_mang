package com.smashprofs.common.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.RankingEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Xử lý định dạng payload và parse các thông điệp mạng liên quan đến Leaderboard và hồ sơ người chơi (Ranking & Profile).
 */
public final class RankingProtocol {

    public static final String CAPABILITY = "ranking-v1";

    public static final String FIELD_LEADERBOARD = "leaderboard";
    public static final String FIELD_ENTRIES = "entries";
    public static final String FIELD_RANK = "rank";
    public static final String FIELD_PLAYER_ID = "playerId";
    public static final String FIELD_DISPLAY_NAME = "displayName";
    public static final String FIELD_WINS = "wins";
    public static final String FIELD_LOSSES = "losses";
    public static final String FIELD_TOTAL_MATCHES = "totalMatches";
    public static final String FIELD_WIN_RATE = "winRate";
    public static final String FIELD_RATING = "rating";
    public static final String FIELD_PROFILE_ID = "profileId";
    public static final String FIELD_LIMIT = "limit";
    public static final String FIELD_GAMEPLAY = "gameplay";

    /**
     * Thông tin hồ sơ tùy chọn người chơi gửi khi kết nối (CONNECT).
     */
    public record ConnectProfile(String profileId, String displayName) {
        public boolean hasProfileId() {
            return profileId != null && !profileId.isBlank();
        }

        public boolean hasDisplayName() {
            return displayName != null && !displayName.isBlank();
        }
    }

    private RankingProtocol() {
    }

    // ==========================================
    // 1. CONNECT Message Payload & Parser
    // ==========================================

    /**
     * Tạo payload cho message CONNECT với tùy chọn profileId và displayName.
     */
    public static ObjectNode connectPayload(String profileId, String displayName) {
        ObjectNode node = ProtocolV1.object();
        if (profileId != null && !profileId.isBlank()) {
            node.put(FIELD_PROFILE_ID, profileId);
        }
        if (displayName != null && !displayName.isBlank()) {
            node.put(FIELD_DISPLAY_NAME, displayName);
        }
        return node;
    }

    /**
     * Tạo payload cho message CONNECT với tùy chọn profileId, displayName và gameplay capability.
     */
    public static ObjectNode connectPayload(String profileId, String displayName, String gameplay) {
        ObjectNode node = connectPayload(profileId, displayName);
        if (gameplay != null && !gameplay.isBlank()) {
            node.put(FIELD_GAMEPLAY, gameplay);
        }
        return node;
    }

    /**
     * Parse thông tin hồ sơ người chơi từ payload của message CONNECT.
     * Cấm client gửi {@code playerId} hoặc {@code sessionId} (trả về UNAUTHORIZED_PLAYER).
     *
     * @param payload   JSON payload của message CONNECT
     * @param requestId ID của request để phục vụ báo lỗi
     * @return ConnectProfile chứa profileId và displayName (có thể null nếu client không gửi)
     * @throws ProtocolException nếu payload không hợp lệ hoặc vi phạm quy tắc bảo mật identity
     */
    public static ConnectProfile parseConnectProfile(JsonNode payload, String requestId) throws ProtocolException {
        if (payload == null || !payload.isObject()) {
            throw ProtocolV1.invalid(requestId);
        }

        // Cấm client mạo danh identity playerId hoặc sessionId
        if (payload.has("playerId") || payload.has("sessionId")) {
            throw new ProtocolException("UNAUTHORIZED_PLAYER", "Client must not supply player identity", requestId);
        }

        String profileId = null;
        if (payload.has(FIELD_PROFILE_ID)) {
            JsonNode pNode = payload.get(FIELD_PROFILE_ID);
            if (!pNode.isTextual() || pNode.asText().isBlank() || pNode.asText().length() > 64) {
                throw ProtocolV1.invalid(requestId);
            }
            profileId = pNode.asText();
        }

        String displayName = null;
        if (payload.has(FIELD_DISPLAY_NAME)) {
            JsonNode dNode = payload.get(FIELD_DISPLAY_NAME);
            if (!dNode.isTextual() || dNode.asText().isBlank() || dNode.asText().length() > 64) {
                throw ProtocolV1.invalid(requestId);
            }
            displayName = dNode.asText();
        }

        return new ConnectProfile(profileId, displayName);
    }

    /**
     * Trích xuất profileId từ CONNECT payload.
     */
    public static String parseProfileId(JsonNode payload, String requestId) throws ProtocolException {
        return parseConnectProfile(payload, requestId).profileId();
    }

    /**
     * Trích xuất displayName từ CONNECT payload.
     */
    public static String parseDisplayName(JsonNode payload, String requestId) throws ProtocolException {
        return parseConnectProfile(payload, requestId).displayName();
    }

    // ==========================================
    // 2. Leaderboard Entry Node & Parser
    // ==========================================

    /**
     * Serialize một đối tượng RankingEntry thành JSON ObjectNode.
     */
    public static ObjectNode entryNode(RankingEntry entry) {
        Objects.requireNonNull(entry, "RankingEntry cannot be null");
        ObjectNode item = ProtocolV1.object();
        item.put(FIELD_RANK, entry.rank());
        item.put(FIELD_PLAYER_ID, entry.playerId() != null ? entry.playerId().value() : "");
        item.put(FIELD_DISPLAY_NAME, entry.displayName() != null ? entry.displayName() : "");
        item.put(FIELD_WINS, entry.wins());
        item.put(FIELD_LOSSES, entry.losses());
        item.put(FIELD_TOTAL_MATCHES, entry.totalMatches());
        item.put(FIELD_WIN_RATE, entry.winRate());
        item.put(FIELD_RATING, entry.rating());
        return item;
    }

    /**
     * Parse một JSON ObjectNode thành đối tượng RankingEntry.
     *
     * @param node JSON node của entry
     * @return RankingEntry hợp lệ
     * @throws IllegalArgumentException nếu định dạng node không hợp lệ
     */
    public static RankingEntry parseEntry(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Ranking entry must be a JSON object");
        }

        if (!node.has(FIELD_RANK) || !node.path(FIELD_RANK).isIntegralNumber()
                || !node.path(FIELD_RANK).canConvertToInt() || node.path(FIELD_RANK).intValue() < 1) {
            throw new IllegalArgumentException("Invalid or missing rank");
        }
        int rank = node.path(FIELD_RANK).intValue();

        if (!node.has(FIELD_PLAYER_ID) || !node.path(FIELD_PLAYER_ID).isTextual()
                || node.path(FIELD_PLAYER_ID).asText().isBlank()) {
            throw new IllegalArgumentException("Invalid or missing playerId");
        }
        PlayerId playerId = new PlayerId(node.path(FIELD_PLAYER_ID).asText());

        if (!node.has(FIELD_DISPLAY_NAME) || !node.path(FIELD_DISPLAY_NAME).isTextual()) {
            throw new IllegalArgumentException("Invalid or missing displayName");
        }
        String displayName = node.path(FIELD_DISPLAY_NAME).asText();

        if (!node.has(FIELD_WINS) || !node.path(FIELD_WINS).isIntegralNumber()
                || !node.path(FIELD_WINS).canConvertToInt() || node.path(FIELD_WINS).intValue() < 0) {
            throw new IllegalArgumentException("Invalid or missing wins");
        }
        int wins = node.path(FIELD_WINS).intValue();

        if (!node.has(FIELD_LOSSES) || !node.path(FIELD_LOSSES).isIntegralNumber()
                || !node.path(FIELD_LOSSES).canConvertToInt() || node.path(FIELD_LOSSES).intValue() < 0) {
            throw new IllegalArgumentException("Invalid or missing losses");
        }
        int losses = node.path(FIELD_LOSSES).intValue();

        if (!node.has(FIELD_TOTAL_MATCHES) || !node.path(FIELD_TOTAL_MATCHES).isIntegralNumber()
                || !node.path(FIELD_TOTAL_MATCHES).canConvertToInt() || node.path(FIELD_TOTAL_MATCHES).intValue() < 0) {
            throw new IllegalArgumentException("Invalid or missing totalMatches");
        }
        int totalMatches = node.path(FIELD_TOTAL_MATCHES).intValue();

        if (!node.has(FIELD_WIN_RATE) || !node.path(FIELD_WIN_RATE).isNumber()
                || !Double.isFinite(node.path(FIELD_WIN_RATE).doubleValue())) {
            throw new IllegalArgumentException("Invalid or missing winRate");
        }
        double winRate = node.path(FIELD_WIN_RATE).doubleValue();

        if (!node.has(FIELD_RATING) || !node.path(FIELD_RATING).isIntegralNumber()
                || !node.path(FIELD_RATING).canConvertToInt()) {
            throw new IllegalArgumentException("Invalid or missing rating");
        }
        int rating = node.path(FIELD_RATING).intValue();

        return new RankingEntry(rank, playerId, displayName, wins, losses, totalMatches, winRate, rating);
    }

    // ==========================================
    // 3. RANKING_SNAPSHOT & RANKING_UPDATED Payload & Parser
    // ==========================================

    /**
     * Tạo payload cho message RANKING_SNAPSHOT hoặc RANKING_UPDATED.
     */
    public static ObjectNode snapshotPayload(List<RankingEntry> entries) {
        ObjectNode node = ProtocolV1.object();
        ArrayNode array = node.putArray(FIELD_LEADERBOARD);
        if (entries != null) {
            for (RankingEntry entry : entries) {
                array.add(entryNode(entry));
            }
        }
        return node;
    }

    /**
     * Parse danh sách RankingEntry từ payload JSON của Leaderboard.
     *
     * @param node JSON ObjectNode chứa trường "leaderboard" hoặc ArrayNode trực tiếp
     * @return danh sách RankingEntry bất biến
     * @throws IllegalArgumentException nếu cấu trúc JSON không hợp lệ
     */
    public static List<RankingEntry> parseLeaderboard(JsonNode node) {
        if (node == null) {
            throw new IllegalArgumentException("Node cannot be null");
        }

        JsonNode listNode = node.has(FIELD_LEADERBOARD) ? node.get(FIELD_LEADERBOARD)
                : (node.has(FIELD_ENTRIES) ? node.get(FIELD_ENTRIES) : (node.isArray() ? node : null));

        if (listNode == null || !listNode.isArray()) {
            throw new IllegalArgumentException("Invalid leaderboard node structure");
        }

        List<RankingEntry> result = new ArrayList<>();
        for (JsonNode item : listNode) {
            result.add(parseEntry(item));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Parse danh sách RankingEntry từ ProtocolMessage (RANKING_SNAPSHOT hoặc RANKING_UPDATED).
     */
    public static List<RankingEntry> parseSnapshot(ProtocolMessage message) {
        if (message == null || (message.type() != MessageType.RANKING_SNAPSHOT && message.type() != MessageType.RANKING_UPDATED)) {
            throw new IllegalArgumentException("Message must be RANKING_SNAPSHOT or RANKING_UPDATED");
        }
        return parseLeaderboard(message.payload());
    }

    // ==========================================
    // 4. GET_LEADERBOARD Request Payload & Parser
    // ==========================================

    /**
     * Tạo payload cho message GET_LEADERBOARD (không giới hạn số lượng).
     */
    public static ObjectNode getLeaderboardPayload() {
        return ProtocolV1.object();
    }

    /**
     * Tạo payload cho message GET_LEADERBOARD với số lượng tối đa bản ghi cần lấy.
     */
    public static ObjectNode getLeaderboardPayload(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return ProtocolV1.object().put(FIELD_LIMIT, limit);
    }

    /**
     * Parse số lượng limit tùy chọn từ GET_LEADERBOARD payload.
     *
     * @return limit hợp lệ, hoặc null nếu không chỉ định
     */
    public static Integer parseLimit(JsonNode payload, String requestId) throws ProtocolException {
        if (payload == null || !payload.isObject()) {
            throw ProtocolV1.invalid(requestId);
        }
        if (payload.has(FIELD_LIMIT)) {
            JsonNode limitNode = payload.get(FIELD_LIMIT);
            if (!limitNode.isIntegralNumber() || !limitNode.canConvertToInt() || limitNode.intValue() <= 0) {
                throw ProtocolV1.invalid(requestId);
            }
            return limitNode.intValue();
        }
        return null;
    }

    /**
     * Xác thực thông điệp GET_LEADERBOARD từ client.
     */
    public static void validateGetLeaderboard(ProtocolMessage message) throws ProtocolException {
        if (message == null || message.type() != MessageType.GET_LEADERBOARD) {
            throw ProtocolV1.invalid(message != null ? message.requestId() : null);
        }
        if (message.requestId() == null || message.requestId().isBlank()) {
            throw ProtocolV1.invalid(null);
        }
        ObjectNode payload = message.payload();
        ProtocolV1.fields(payload, message.requestId(), FIELD_LIMIT);
        parseLimit(payload, message.requestId());
    }

    // ==========================================
    // 5. Message Factory Helpers
    // ==========================================

    public static ProtocolMessage createGetLeaderboardMessage(String requestId) {
        return new ProtocolMessage(MessageType.GET_LEADERBOARD, requestId, getLeaderboardPayload());
    }

    public static ProtocolMessage createGetLeaderboardMessage(String requestId, int limit) {
        return new ProtocolMessage(MessageType.GET_LEADERBOARD, requestId, getLeaderboardPayload(limit));
    }

    public static ProtocolMessage createSnapshotMessage(String requestId, List<RankingEntry> entries) {
        return new ProtocolMessage(MessageType.RANKING_SNAPSHOT, requestId, snapshotPayload(entries));
    }

    public static ProtocolMessage createUpdatedMessage(List<RankingEntry> entries) {
        return new ProtocolMessage(MessageType.RANKING_UPDATED, null, snapshotPayload(entries));
    }

    public static ProtocolMessage createConnectMessage(String requestId, String profileId, String displayName) {
        return new ProtocolMessage(MessageType.CONNECT, requestId, connectPayload(profileId, displayName));
    }
}
