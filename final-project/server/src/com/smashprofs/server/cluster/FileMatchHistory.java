package com.smashprofs.server.cluster;

import com.smashprofs.server.game.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** RankingGateway adapter: one atomic JSON file per MatchId, outside the simulation thread. */
public final class FileMatchHistory implements RankingGateway {
    private final Path directory;
    public FileMatchHistory(Path directory) throws IOException { this.directory = directory; Files.createDirectories(directory); }
    @Override public synchronized void submitMatchResult(AuthoritativeMatchResult result) throws IOException {
        if (!result.matchId().matches("[A-Za-z0-9_-]{1,128}")) { throw new IOException("Invalid match id"); }
        Path target = directory.resolve(result.matchId() + ".json");
        byte[] bytes = HttpJson.JSON.writeValueAsBytes(result);
        if (Files.exists(target)) {
            if (!Arrays.equals(Files.readAllBytes(target), bytes)) { throw new IOException("Conflicting duplicate result"); }
            return;
        }
        Path temp = Files.createTempFile(directory, ".result-", ".tmp");
        try {
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) { channel.write(buffer); } channel.force(true);
            }
            // Atomic create-if-absent, including across worker JVMs. NTFS/ext4 local prototype.
            try { Files.createLink(target, temp); }
            catch (FileAlreadyExistsException duplicate) {
                if (!Arrays.equals(Files.readAllBytes(target), bytes)) { throw new IOException("Conflicting duplicate result", duplicate); }
            }
        } finally { Files.deleteIfExists(temp); }
    }
    public List<com.fasterxml.jackson.databind.JsonNode> history(String playerId, int limit) throws IOException {
        if (limit < 1 || limit > 100) { throw new IllegalArgumentException("limit 1..100"); }
        var result = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted(Comparator.comparingLong(FileMatchHistory::modified).reversed()).toList()) {
                var node = HttpJson.JSON.readTree(Files.readAllBytes(file));
                for (var participant : node.path("participants")) {
                    if (participant.path("playerId").path("value").asText().equals(playerId)) { result.add(node); break; }
                }
                if (result.size() >= limit) { break; }
            }
        }
        return List.copyOf(result);
    }
    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); } catch (IOException failure) { return 0; }
    }
}
