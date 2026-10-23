package com.smashprofs.server.ranking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hiện thực {@link RankingStore} lưu trong bộ nhớ (không bền vững).
 *
 * <p>Thread-safe nhờ {@link ConcurrentHashMap}. Dùng chủ yếu trong unit test
 * và môi trường dev để tránh phụ thuộc vào file SQLite.
 */
public final class InMemoryRankingStore implements RankingStore {

    /** Lưu hồ sơ người chơi: profileId → PlayerRecord. */
    private final ConcurrentHashMap<String, PlayerRecord> players = new ConcurrentHashMap<>();

    /** Tập hợp các matchId đã được xử lý để chống trùng. */
    private final Set<String> processedMatches = ConcurrentHashMap.newKeySet();
    private final Map<String,String> credentials=new ConcurrentHashMap<>(),names=new ConcurrentHashMap<>(),sessions=new ConcurrentHashMap<>();

    @Override public synchronized GuestIdentity connectGuest(String token,String name,String sessionId) {
        GuestIdentity identity;
        if(token==null) {
            identity=GuestIdentity.create();credentials.put(GuestIdentity.hash(identity.token()),identity.profileId());
        } else {
            String id=credentials.get(GuestIdentity.hash(token));
            if(id==null) { throw new IllegalArgumentException("Unknown guest credential"); }
            identity=new GuestIdentity(id,token);
        }
        names.put(identity.profileId(),name);sessions.put(sessionId,identity.profileId());return identity;
    }
    @Override public String rankingProfile(String sessionId) { return sessions.getOrDefault(sessionId,sessionId); }
    @Override public String displayName(String profileId) { return names.getOrDefault(profileId,profileId); }

    /**
     * {@inheritDoc}
     *
     * <p>Kiểm tra {@code matchId} trong tập đã xử lý trước khi ghi.
     * Nếu trùng → trả {@link SaveMatchResult#DUPLICATE} ngay, không sửa dữ liệu.
     */
    @Override
    public SaveMatchResult saveMatchResult(String matchId, Map<String, PlayerRecord> updatedRecords) {
        // add() trả false nếu đã tồn tại → DUPLICATE
        if (!processedMatches.add(matchId)) {
            return SaveMatchResult.DUPLICATE;
        }
        // Lưu tất cả records vào map
        players.putAll(updatedRecords);
        return SaveMatchResult.OK;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PlayerRecord> findPlayer(String profileId) {
        return Optional.ofNullable(players.get(profileId));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Trả về bản sao danh sách sắp xếp theo rating giảm dần.
     */
    @Override
    public List<PlayerRecord> getLeaderboard() {
        List<PlayerRecord> list = new ArrayList<>(players.values());
        list.sort(Comparator.comparingDouble(PlayerRecord::rating).reversed());
        return Collections.unmodifiableList(list);
    }

    /** Không cần làm gì vì không có tài nguyên ngoài. */
    @Override
    public void close() {
        // Không có kết nối nào cần đóng với InMemory
    }
}
