package com.smashprofs.server.network;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.JsonFrameCodec;
import com.smashprofs.common.protocol.ProtocolV1;
import com.smashprofs.server.game.AuthoritativeGameSnapshot;
import com.smashprofs.server.metrics.PerformanceMetrics;
import org.junit.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class SharedSnapshotCacheTest {
    private GameStateDelivery delivery(String room,long tick) {
        var a=new PlayerId("a"); var b=new PlayerId("b");
        var players=List.of(new AuthoritativeGameSnapshot.PlayerSnapshot(a,0,"Alex",1,2,0,0,100,true,false,false,true),
                new AuthoritativeGameSnapshot.PlayerSnapshot(b,1,"Leo",3,4,0,0,100,true,false,false,true));
        return new GameStateDelivery(new AuthoritativeGameSnapshot("match",room,tick,players,List.of()),"PLAYING",1000,()->true);
    }
    @Test public void concurrentRecipientsEncodeOnceWithIdenticalProtocolV1Bytes() throws Exception {
        var cache=new SharedSnapshotCache(); var metrics=new PerformanceMetrics(); var d=delivery("r",3);
        var expected=new JsonFrameCodec().encode(GameStateMessageMapper.map(d)); var executor=Executors.newFixedThreadPool(4);
        try {
            var futures=new ArrayList<Future<byte[]>>();
            for(int i=0;i<4;i++) { futures.add(executor.submit(()->{
                var frame=cache.frame(d).encode(new JsonTcpMessageCodec(),metrics); var output=new ByteArrayOutputStream(); frame.writeTo(output); return output.toByteArray();
            })); }
            for(var f:futures) { assertArrayEquals(expected,f.get(3,TimeUnit.SECONDS)); }
            assertEquals(1,metrics.snapshotEncodes.sum()); assertEquals(3,metrics.snapshotCacheHits.sum());
        } finally { executor.shutdownNow(); }
    }
    @Test public void oldWriterCannotReplaceNewFrameOrTransmitWrongTick() throws Exception {
        var cache=new SharedSnapshotCache(); var metrics=new PerformanceMetrics(); var older=delivery("r",3); var newer=delivery("r",6);
        var current=cache.frame(newer).encode(new JsonTcpMessageCodec(),metrics);
        var late=cache.frame(older).encode(new JsonTcpMessageCodec(),metrics); var output=new ByteArrayOutputStream(); late.writeTo(output);
        assertEquals(3,new JsonFrameCodec().readNext(new ByteArrayInputStream(output.toByteArray())).payload().path("serverTick").asInt());
        assertSame(current,cache.frame(newer)); assertEquals(1,cache.size());
    }
    @Test public void cacheHasHardRoomBoundAndDiscardsClosedRoomFrames() {
        var cache=new SharedSnapshotCache(); for(int i=0;i<1100;i++) { cache.frame(delivery("r"+i,3)); }
        assertEquals(1024,cache.size()); cache.retainRooms(Set.of("r0")); assertEquals(1,cache.size());
        cache.clear(); cache.frame(delivery("after-close",3)); assertEquals(0,cache.size());
    }
    @Test public void boundedGameplayPayloadKeepsSharedEncodingAndImmutableStats() throws Exception {
        var sample=delivery("r",3);var players=List.of(
                new com.smashprofs.common.model.GameplaySnapshot.Player("a",3,"ACTIVE",0,0,0,"SHOTGUN",true,12,2,1,0,0,"NONE"),
                new com.smashprofs.common.model.GameplaySnapshot.Player("b",3,"ACTIVE",0,0,0,"NONE",false,0,0,0,0,0,"NONE"));
        var items=new ArrayList<com.smashprofs.common.model.GameplaySnapshot.Item>();var shots=new ArrayList<com.smashprofs.common.model.GameplaySnapshot.Shot>();
        for(int i=1;i<=16;i++) { items.add(new com.smashprofs.common.model.GameplaySnapshot.Item(i,"HEALTH",4,2)); }
        for(int i=1;i<=64;i++) { shots.add(new com.smashprofs.common.model.GameplaySnapshot.Shot(i,"GRENADE","a",4,2,1,1)); }
        var extension=new com.smashprofs.common.model.GameplaySnapshot(com.smashprofs.common.model.MatchRules.DEFAULT,3,players,items,shots);
        var state=sample.snapshot();var delivery=new GameStateDelivery(new AuthoritativeGameSnapshot("match","r",3,state.players(),state.projectiles(),extension),"PLAYING",120000,()->true);
        var cache=new SharedSnapshotCache();var metrics=new PerformanceMetrics();byte[] first=null;
        for(int recipient=0;recipient<4;recipient++) {
            var frame=cache.frame(delivery).encode(new JsonTcpMessageCodec(),metrics);var output=new ByteArrayOutputStream();frame.writeTo(output);
            if(first==null) { first=output.toByteArray(); }else { assertArrayEquals(first,output.toByteArray()); }
        }
        assertEquals(1,metrics.snapshotEncodes.sum());assertEquals(3,metrics.snapshotCacheHits.sum());
        assertTrue(first.length<ProtocolV1.MAX_FRAME_BYTES);
        var decoded=new JsonFrameCodec().readNext(new ByteArrayInputStream(first));assertEquals(extension,com.smashprofs.common.protocol.GameplayProtocol.snapshot(decoded.payload().get("gameplay")));
        System.out.println("Bounded gameplay fixture bytes="+first.length+"; items=16; weaponProjectiles=64; encodeCount=1; recipients=4");
    }
}
