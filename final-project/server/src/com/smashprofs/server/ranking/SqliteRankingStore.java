package com.smashprofs.server.ranking;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hiện thực {@link RankingStore} lưu trữ bền vững vào cơ sở dữ liệu SQLite qua JDBC.
 *
 * <p>Quy tắc kỹ thuật:
 * <ul>
 *   <li>Sử dụng giao dịch cơ sở dữ liệu (Transaction) để đảm bảo tính toàn vẹn ACID.</li>
 *   <li>Bảng {@code processed_matches}: lưu matchId duy nhất, chống xử lý trùng lặp.</li>
 *   <li>Bảng {@code players}: lưu hồ sơ người chơi (profile_id, rating, wins, losses, draws, total_matches).</li>
 *   <li>Khóa đồng bộ synchronized trên connection để an toàn đa luồng.</li>
 * </ul>
 */
public class SqliteRankingStore implements RankingStore, AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(SqliteRankingStore.class.getName());

    private final Connection connection;

    /**
     * Khởi tạo store với file database SQLite.
     *
     * @param dbPath đường dẫn file SQLite (ví dụ "ranking.db" hoặc "data/ranking.db")
     */
    public SqliteRankingStore(String dbPath) {
        try {
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException ignored) {
                // Sẽ fallback sang DriverManager service discovery nếu driver đã nạp trước
            }
            String url = (dbPath == null || dbPath.isBlank() || dbPath.equals(":memory:"))
                    ? "jdbc:sqlite::memory:"
                    : "jdbc:sqlite:" + dbPath;
            this.connection = DriverManager.getConnection(url);
            initSchema();
        } catch (SQLException e) {
            throw new RuntimeException("Không thể khởi tạo kết nối SQLite: " + dbPath, e);
        }
    }

    /**
     * Tạo store SQLite in-memory (dùng cho test hoặc phiên tạm thời).
     */
    public static SqliteRankingStore inMemory() {
        return new SqliteRankingStore(":memory:");
    }

    /**
     * Tạo các bảng cần thiết nếu chưa tồn tại.
     */
    private synchronized void initSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS processed_matches (
                    match_id TEXT PRIMARY KEY,
                    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS players (
                    profile_id TEXT PRIMARY KEY,
                    rating REAL NOT NULL,
                    wins INTEGER NOT NULL,
                    losses INTEGER NOT NULL,
                    draws INTEGER NOT NULL,
                    total_matches INTEGER NOT NULL
                );
            """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_players_rating ON players (rating DESC);
            """);
        }
    }

    @Override
    public synchronized SaveMatchResult saveMatchResult(String matchId, Map<String, PlayerRecord> updatedRecords) {
        if (matchId == null || matchId.isBlank()) {
            throw new IllegalArgumentException("matchId không được để trống");
        }
        if (updatedRecords == null) {
            updatedRecords = Collections.emptyMap();
        }

        try {
            connection.setAutoCommit(false);
            try {
                // 1. Kiểm tra chống trùng matchId
                try (PreparedStatement check = connection.prepareStatement("SELECT 1 FROM processed_matches WHERE match_id = ?")) {
                    check.setString(1, matchId);
                    try (ResultSet rs = check.executeQuery()) {
                        if (rs.next()) {
                            connection.rollback();
                            return SaveMatchResult.DUPLICATE;
                        }
                    }
                }

                // 2. Ghi nhận matchId đã xử lý
                try (PreparedStatement insertMatch = connection.prepareStatement("INSERT INTO processed_matches (match_id) VALUES (?)")) {
                    insertMatch.setString(1, matchId);
                    insertMatch.executeUpdate();
                }

                // 3. Upsert toàn bộ PlayerRecord mới vào bảng players
                String upsertSql = """
                    INSERT INTO players (profile_id, rating, wins, losses, draws, total_matches)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(profile_id) DO UPDATE SET
                        rating = excluded.rating,
                        wins = excluded.wins,
                        losses = excluded.losses,
                        draws = excluded.draws,
                        total_matches = excluded.total_matches
                """;

                try (PreparedStatement upsert = connection.prepareStatement(upsertSql)) {
                    for (PlayerRecord rec : updatedRecords.values()) {
                        if (rec == null || rec.profileId() == null) continue;
                        upsert.setString(1, rec.profileId());
                        upsert.setDouble(2, rec.rating());
                        upsert.setInt(3, rec.wins());
                        upsert.setInt(4, rec.losses());
                        upsert.setInt(5, rec.draws());
                        upsert.setInt(6, rec.totalMatches());
                        upsert.executeUpdate();
                    }
                }

                connection.commit();
                return SaveMatchResult.OK;
            } catch (SQLException ex) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackErr) {
                    LOGGER.log(Level.WARNING, "Lỗi khi rollback giao dịch SQLite", rollbackErr);
                }
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Lỗi thực thi giao dịch lưu kết quả trận đấu", e);
        }
    }

    @Override
    public synchronized Optional<PlayerRecord> findPlayer(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return Optional.empty();
        }

        String sql = "SELECT profile_id, rating, wins, losses, draws, total_matches FROM players WHERE profile_id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, profileId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new PlayerRecord(
                            rs.getString("profile_id"),
                            rs.getDouble("rating"),
                            rs.getInt("wins"),
                            rs.getInt("losses"),
                            rs.getInt("draws"),
                            rs.getInt("total_matches")
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Lỗi khi tìm người chơi " + profileId, e);
        }
        return Optional.empty();
    }

    @Override
    public synchronized List<PlayerRecord> getLeaderboard() {
        String sql = "SELECT profile_id, rating, wins, losses, draws, total_matches FROM players ORDER BY rating DESC";
        List<PlayerRecord> list = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new PlayerRecord(
                        rs.getString("profile_id"),
                        rs.getDouble("rating"),
                        rs.getInt("wins"),
                        rs.getInt("losses"),
                        rs.getInt("draws"),
                        rs.getInt("total_matches")
                ));
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Lỗi khi lấy bảng xếp hạng SQLite", e);
        }
        return Collections.unmodifiableList(list);
    }

    @Override
    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Lỗi khi đóng kết nối SQLite", e);
        }
    }
}
