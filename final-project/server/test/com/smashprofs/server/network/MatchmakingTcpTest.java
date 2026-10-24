package com.smashprofs.server.network;

import com.smashprofs.common.model.BossSnapshot;
import com.smashprofs.common.protocol.*;
import org.junit.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Real TCP, production routing and lifecycle. No fake match results or player identities. */
public class MatchmakingTcpTest {
    private GameServer server;
    private final List<Peer> peers=new ArrayList<>();
    @Before public void start() { server=new GameServer("127.0.0.1",0,32);server.start(); }
    @After public void stop() throws Exception { try { for(var peer:peers) { peer.close(); } }finally { server.stop(); } }
    private Peer peer(boolean capability) throws Exception { var p=new Peer(capability);peers.add(p);return p; }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean() && System.nanoTime()<deadline) { Thread.sleep(10); }
        assertTrue("Timed out waiting for server state",condition.getAsBoolean());
    }
    private final class Peer implements AutoCloseable {
        final Socket socket;final JsonFrameCodec codec=new JsonFrameCodec();final String player;
        Peer(boolean capability) throws Exception {
            socket=new Socket("127.0.0.1",server.getBoundPort());socket.setSoTimeout(6000);
            var payload=ProtocolV1.object();if(capability) { payload.put("gameplay",BossSnapshot.CAPABILITY); }
            player=request(MessageType.CONNECT,payload).payload().path("playerId").asText();
        }
        void send(ProtocolMessage message) throws Exception { socket.getOutputStream().write(codec.encode(message)); }
        ProtocolMessage next(MessageType wanted,String request) throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
            while(System.nanoTime()<deadline) {
                var m=codec.readNext(socket.getInputStream());
                assertNotNull(m);
                if(m.type()==MessageType.PING) { send(new ProtocolMessage(MessageType.PONG,m.requestId(),ProtocolV1.object()));continue; }
                if((m.type()==wanted || wanted==null) && (request==null || request.equals(m.requestId()))) { return m; }
            }
            throw new AssertionError("Missing "+wanted);
        }
        ProtocolMessage request(MessageType type,com.fasterxml.jackson.databind.node.ObjectNode payload) throws Exception {
            String id=UUID.randomUUID().toString();send(new ProtocolMessage(type,id,payload));return next(null,id);
        }
        ProtocolMessage find(String mode,String character) throws Exception {
            return request(MessageType.FIND_MATCH,ProtocolV1.object().put("mode",mode).put("characterId",character));
        }
        void loaded(String room) throws Exception { send(new ProtocolMessage(MessageType.LOAD_READY,null,ProtocolV1.object().put("roomId",room))); }
        public void close() throws Exception { socket.close(); }
    }
    @Test public void firstPlayerWaitsAndDuplicateRequestIsIdempotent() throws Exception {
        var a=peer(true);var response=a.find("PVP","Luca");assertEquals(MessageType.ROOM_STATE,response.type());
        String room=response.payload().path("roomId").asText();var state=server.getRoomManager().getRoom(room);
        assertTrue(state.isMatchmaking());assertEquals(1,state.getPlayerCount());assertEquals(2,state.getRules().maxPlayers());
        assertFalse(state.isPlayerReady(new com.smashprofs.common.model.PlayerId(a.player)));assertNull(state.getMatchRuntime());
        assertEquals(room,a.find("PVP","Luca").payload().path("roomId").asText());assertEquals(1,server.getRoomManager().getRoomCount());
    }
    @Test public void twoPlayersStartWithoutRoomCodeOrReadyRequest() throws Exception { starts("PVP",false); }
    @Test public void bossPairsStartWithCooperativeRules() throws Exception { starts("BOSS",true); }
    private void starts(String mode,boolean boss) throws Exception {
        var a=peer(true);var b=peer(true);String room=a.find(mode,"Alex").payload().path("roomId").asText();
        var response=b.find(mode,"Luca");assertEquals(MessageType.ROOM_STATE,response.type());assertEquals(room,response.payload().path("roomId").asText());
        assertEquals(2,response.payload().path("players").size());
        a.request(MessageType.PLAYER_READY,ProtocolV1.object().put("roomId",room).put("characterId","Alex").put("ready",true));
        b.request(MessageType.PLAYER_READY,ProtocolV1.object().put("roomId",room).put("characterId","Luca").put("ready",true));
        a.loaded(room);b.loaded(room);
        var first=a.next(MessageType.GAME_START,null);var second=b.next(MessageType.GAME_START,null);
        assertEquals(first.payload().path("matchId"),second.payload().path("matchId"));
        assertEquals(boss,server.getRoomManager().getRoom(room).getRules().boss());
    }
    @Test public void pvpAndBossNeverShareAQueue() throws Exception {
        String a=peer(true).find("PVP","Alex").payload().path("roomId").asText();
        String b=peer(true).find("BOSS","Alex").payload().path("roomId").asText();assertNotEquals(a,b);
    }
    @Test public void cancelDisconnectRemovesEmptyWaitingRoom() throws Exception {
        var a=peer(true);String room=a.find("PVP","Alex").payload().path("roomId").asText();a.close();
        await(()->server.getRoomManager().getRoomCount()==0);
        assertNotEquals(room,peer(true).find("PVP","Alex").payload().path("roomId").asText());
    }
    @Test public void disconnectedOpponentDuringLoadingCanBeReplaced() throws Exception {
        var a=peer(true);var b=peer(true);String room=a.find("PVP","Alex").payload().path("roomId").asText();b.find("PVP","Luca");b.close();
        await(()->server.getRoomManager().getRoom(room).getPlayerCount()==1);
        assertEquals(room,peer(true).find("PVP","Leo").payload().path("roomId").asText());
        assertEquals(2,server.getRoomManager().getRoom(room).getPlayerCount());
    }
    @Test public void privateRoomsAreExcludedAndManualJoinCannotStealQuickSlot() throws Exception {
        var privatePeer=peer(true);privatePeer.request(MessageType.CREATE_ROOM,ProtocolV1.object().put("roomId","friends").put("gameplay",true));
        privatePeer.request(MessageType.JOIN_ROOM,ProtocolV1.object().put("roomId","friends"));
        String room=peer(true).find("PVP","Alex").payload().path("roomId").asText();assertNotEquals("friends",room);
        var denied=peer(true).request(MessageType.JOIN_ROOM,ProtocolV1.object().put("roomId",room));assertEquals(MessageType.ERROR,denied.type());
        assertEquals(1,server.getRoomManager().getRoom(room).getPlayerCount());
    }
    @Test public void legacyClientsAndInvalidOrSpoofedRequestsCannotEnterQueue() throws Exception {
        assertEquals(MessageType.ERROR,peer(false).find("PVP","Alex").type());
        var a=peer(true);assertEquals(MessageType.ERROR,a.find("invalid","Alex").type());assertEquals(MessageType.ERROR,a.find("PVP","fake").type());
        var response=a.request(MessageType.FIND_MATCH,ProtocolV1.object().put("mode","PVP").put("characterId","Alex").put("playerId","spoof"));
        assertEquals("UNAUTHORIZED_PLAYER",response.payload().path("code").asText());assertEquals(0,server.getRoomManager().getRoomCount());
    }
    @Test public void sixConcurrentRequestsFormThreeDistinctPairs() throws Exception {
        var clients=new ArrayList<Peer>();for(int i=0;i<6;i++) { clients.add(peer(true)); }
        var pool=Executors.newFixedThreadPool(6);
        try {
            var start=new CountDownLatch(1);var requests=new ArrayList<Future<String>>();
            for(var p:clients) { requests.add(pool.submit(()->{ start.await();return p.find("PVP","Alex").payload().path("roomId").asText(); })); }
            start.countDown();var counts=new HashMap<String,Integer>();for(var result:requests) { counts.merge(result.get(8,TimeUnit.SECONDS),1,Integer::sum); }
            assertEquals(3,counts.size());assertTrue(counts.values().stream().allMatch(count->count==2));
            assertTrue(server.getRoomManager().getRooms().stream().allMatch(room->room.getPlayerCount()==2));
        } finally { pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS)); }
    }
}
