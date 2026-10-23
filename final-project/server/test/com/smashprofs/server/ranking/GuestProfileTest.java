package com.smashprofs.server.ranking;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.*;
import com.smashprofs.server.game.AuthoritativeMatchResult;
import com.smashprofs.server.game.TcpTestClient;
import com.smashprofs.server.network.GameServer;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.util.List;
import static org.junit.Assert.*;

public class GuestProfileTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private AuthoritativeMatchResult result(String match,String winner,String loser) {
        return new AuthoritativeMatchResult(match,"room",1,new PlayerId(winner),AuthoritativeMatchResult.FinishReason.LAST_PLAYER_STANDING,
                List.of(new AuthoritativeMatchResult.ParticipantResult(new PlayerId(winner),0,"Alex",100,false),
                        new AuthoritativeMatchResult.ParticipantResult(new PlayerId(loser),1,"Leo",0,true)));
    }
    private ProtocolMessage connect(TcpTestClient client,String token,String name) throws Exception {
        var payload=ProtocolV1.object().put("guestProfile",true).put("displayName",name);
        if(token!=null) { payload.put("profileToken",token); }
        return client.request(MessageType.CONNECT,payload);
    }
    @Test public void sqliteResumeAndNamesSurviveRestart() throws Exception {
        String db=temp.newFile().getPath();GuestIdentity original;
        try(var store=new SqliteRankingStore(db)) { original=store.connectGuest(null,"Nguyễn Văn An","first"); }
        try(var store=new SqliteRankingStore(db)) {
            var resumed=store.connectGuest(original.token(),"Tên mới","second");
            assertEquals(original,resumed);assertEquals(original.profileId(),store.rankingProfile("first"));
            assertEquals(original.profileId(),store.rankingProfile("second"));assertEquals("Tên mới",store.displayName(original.profileId()));
        }
    }
    @Test public void disconnectedSessionsAccumulateOnOneProfileAndDuplicateResultsAreIgnored() {
        try(var store=SqliteRankingStore.inMemory()) {
            var one=store.connectGuest(null,"Người chơi","one");var two=store.connectGuest(null,"Đối thủ","two");
            var service=new RankingService(store);service.submitMatchResult(result("match-1","one","two"));
            store.connectGuest(one.token(),"Tên mới","one-new");store.connectGuest(two.token(),"Đối thủ","two-new");
            service.submitMatchResult(result("match-2","one-new","two-new"));service.submitMatchResult(result("match-2","one-new","two-new"));
            var entry=service.getLeaderboardEntries().get(0);assertEquals(one.profileId(),entry.playerId().value());
            assertEquals("Tên mới",entry.displayName());assertEquals(2,entry.wins());assertEquals(2,entry.totalMatches());
            assertEquals(2,store.getLeaderboard().size());assertTrue(entry.rating()>1016);
        }
    }
    @Test public void repeatedTabsCannotRankAgainstTheirOwnProfile() {
        try(var store=SqliteRankingStore.inMemory()) {
            var guest=store.connectGuest(null,"Same","tab-1");store.connectGuest(guest.token(),"Same","tab-2");
            new RankingService(store).submitMatchResult(result("self","tab-1","tab-2"));assertTrue(store.getLeaderboard().isEmpty());
        }
    }
    @Test public void memoryStoreUsesSameCredentialRules() {
        try(var store=new InMemoryRankingStore()) {
            var guest=store.connectGuest(null,"First","a");assertEquals(guest,store.connectGuest(guest.token(),"Renamed","b"));
            assertEquals(guest.profileId(),store.rankingProfile("b"));assertEquals("Renamed",store.displayName(guest.profileId()));
            try { store.connectGuest("0".repeat(64),"Fake","c");fail(); }catch(IllegalArgumentException expected) { }
        }
    }
    @Test(timeout=15000) public void tcpResumesSameProfileWithFreshGameplayIdentityAndPublicName() throws Exception {
        try(var store=SqliteRankingStore.inMemory()) {
            var service=new RankingService(store);var server=GameServer.withRanking(service,null);server.start();
            try {
                ProtocolMessage first,opponent;
                try(var a=new TcpTestClient(server.getBoundPort());var b=new TcpTestClient(server.getBoundPort())) {
                    first=connect(a,null,"Chiến Binh Việt");opponent=connect(b,null,"Other");
                    assertEquals(MessageType.CONNECT_ACK,first.type());
                }
                // The result arrives after the sockets have closed.
                service.submitMatchResult(result("after-close",first.payload().path("playerId").asText(),opponent.payload().path("playerId").asText()));
                try(var a=new TcpTestClient(server.getBoundPort())) {
                    var next=connect(a,first.payload().path("profileToken").asText(),"Chiến Binh Việt");
                    assertEquals(first.payload().path("profileId"),next.payload().path("profileId"));
                    assertNotEquals(first.payload().path("playerId"),next.payload().path("playerId"));
                    var snapshot=a.request(MessageType.GET_LEADERBOARD,ProtocolV1.object());
                    var entries=RankingProtocol.parseSnapshot(snapshot);assertEquals(2,entries.size());
                    assertEquals("Chiến Binh Việt",entries.get(0).displayName());assertEquals(next.payload().path("profileId").asText(),entries.get(0).playerId().value());
                    assertFalse(snapshot.payload().toString().contains(first.payload().path("profileToken").asText()));
                }
            } finally { server.stop(); }
        }
    }
    @Test(timeout=10000) public void invalidTokenDoesNotAuthenticateAndPublicIdDoesNotClaimProfile() throws Exception {
        try(var store=SqliteRankingStore.inMemory()) {
            var server=GameServer.withRanking(new RankingService(store),null);server.start();
            try(var victim=new TcpTestClient(server.getBoundPort());var attacker=new TcpTestClient(server.getBoundPort());var fresh=new TcpTestClient(server.getBoundPort())) {
                var original=connect(victim,null,"Victim");
                var rejected=connect(attacker,"0".repeat(64),"Impostor");assertEquals(MessageType.ERROR,rejected.type());
                assertEquals("INVALID_PROFILE_TOKEN",rejected.payload().path("code").asText());
                var unauthorized=attacker.request(MessageType.GET_LEADERBOARD,ProtocolV1.object());assertEquals(MessageType.ERROR,unauthorized.type());
                var payload=ProtocolV1.object().put("guestProfile",true).put("displayName","Fake").put("profileId",original.payload().path("profileId").asText());
                var own=fresh.request(MessageType.CONNECT,payload);assertEquals(MessageType.CONNECT_ACK,own.type());
                assertNotEquals(original.payload().path("profileId"),own.payload().path("profileId"));
            } finally { server.stop(); }
        }
    }
    @Test(timeout=10000) public void malformedTokenIsRejectedAndLegacyAckStaysCompatible() throws Exception {
        try(var store=SqliteRankingStore.inMemory()) {
            var server=GameServer.withRanking(new RankingService(store),null);server.start();
            try(var a=new TcpTestClient(server.getBoundPort());var b=new TcpTestClient(server.getBoundPort())) {
                assertEquals(MessageType.ERROR,connect(a,"not-a-token","Name").type());
                var legacy=b.request(MessageType.CONNECT,RankingProtocol.connectPayload("old-public-id","Old client"));assertEquals(1,legacy.payload().size());
            } finally { server.stop(); }
        }
    }
}
