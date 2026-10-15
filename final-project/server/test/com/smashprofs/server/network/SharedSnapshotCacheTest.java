package com.smashprofs.server.network;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.JsonFrameCodec;
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
}
