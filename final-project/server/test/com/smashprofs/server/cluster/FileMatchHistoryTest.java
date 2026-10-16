package com.smashprofs.server.cluster;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.game.AuthoritativeMatchResult;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import static org.junit.Assert.*;

public class FileMatchHistoryTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void independentWritersCannotDuplicateSameMatchId() throws Exception {
        Path directory=temp.newFolder().toPath(); var player=new PlayerId("player");
        var result=new AuthoritativeMatchResult("shared","room",60,player,AuthoritativeMatchResult.FinishReason.TIME_LIMIT,
                List.of(new AuthoritativeMatchResult.ParticipantResult(player,0,"Alex",100,false)));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var tasks=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<16;i++) { tasks.add(pool.submit(() -> { new FileMatchHistory(directory).submitMatchResult(result); return null; })); }
            for(var task:tasks) { task.get(); }
            try(var files=Files.list(directory)) { assertEquals(1,files.count()); }
        } finally { pool.shutdownNow(); }
    }
    @Test public void durableAdapterIsIdempotentAcrossRestartAndDetectsConflictingResults() throws Exception {
        Path directory=temp.newFolder().toPath(); var player=new PlayerId("player");
        var result=new AuthoritativeMatchResult("match_1","room",60,player,AuthoritativeMatchResult.FinishReason.TIME_LIMIT,
                List.of(new AuthoritativeMatchResult.ParticipantResult(player,0,"Alex",100,false)));
        new FileMatchHistory(directory).submitMatchResult(result);
        var restarted=new FileMatchHistory(directory); restarted.submitMatchResult(result);
        try(var files=Files.list(directory)) { assertEquals(1,files.count()); }
        assertEquals(1,restarted.history("player",20).size()); assertTrue(restarted.history("other",20).isEmpty());
        var conflict=new AuthoritativeMatchResult("match_1","room",61,player,result.finishReason(),result.participants());
        try { restarted.submitMatchResult(conflict); fail(); } catch(IOException expected) { }
        assertEquals(60,restarted.history("player",20).get(0).path("finishedAtTick").asInt());
    }
}
