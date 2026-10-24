package com.smashprofs.server.game;
import com.smashprofs.common.model.*;
import com.smashprofs.server.ranking.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Regression for the null winner observed after a real browser Boss match finished. */
public class BossRankingIntegrationTest {
    private final PlayerId a=new PlayerId("boss-rank-a"),b=new PlayerId("boss-rank-b");
    @Test public void teamVictoryRecordsBothWinsWithoutChangingElo() { verify(AuthoritativeMatchResult.FinishReason.BOSS_DEFEATED); }
    @Test public void teamEliminatedRecordsBothLossesWithoutChangingElo() { verify(AuthoritativeMatchResult.FinishReason.TEAM_ELIMINATED); }
    @Test public void timeoutRecordsBothLossesWithoutChangingElo() { verify(AuthoritativeMatchResult.FinishReason.BOSS_TIMEOUT); }
    private void verify(AuthoritativeMatchResult.FinishReason reason) {
        var runtime=new MatchRuntime(new MatchSetup("boss-rating-"+reason,"bossrank",List.of(
            new MatchSetup.Participant(a,0,"Alex"),new MatchSetup.Participant(b,1,"Luca")),MatchRules.BOSS));
        try {
            runtime.start();var game=runtime.gameplay();final AuthoritativeMatchResult result;
            if(reason==AuthoritativeMatchResult.FinishReason.BOSS_DEFEATED) {
                game.boss.damage(a,2000);runtime.tick();result=runtime.getMatchResult();
            } else if(reason==AuthoritativeMatchResult.FinishReason.TEAM_ELIMINATED) {
                game.disconnect(a);game.disconnect(b);runtime.tick();result=runtime.getMatchResult();
            } else { runtime.tick();result=MatchEndEvaluator.evaluate(runtime.getLatestSnapshot(),true); }
            assertEquals(reason,result.finishReason());assertNull(result.winnerId());assertFalse(result.isDraw());
            try(var store=new InMemoryRankingStore()) {
                store.saveMatchResult("seed",Map.of(a.value(),PlayerRecord.initial(a.value()).withRating(1500),b.value(),PlayerRecord.initial(b.value()).withRating(800)));
                var ranking=new RankingService(store);ranking.submitMatchResult(result);ranking.submitMatchResult(result);
                for(var id:List.of(a,b)) {
                    var record=store.findPlayer(id.value()).orElseThrow();
                    assertEquals(id.equals(a)?1500:800,record.rating(),0);
                    assertEquals(1,record.totalMatches());assertEquals(0,record.draws());
                    assertEquals(reason==AuthoritativeMatchResult.FinishReason.BOSS_DEFEATED?1:0,record.wins());
                    assertEquals(reason==AuthoritativeMatchResult.FinishReason.BOSS_DEFEATED?0:1,record.losses());
                }
            }
        } finally { runtime.close(); }
    }
}
