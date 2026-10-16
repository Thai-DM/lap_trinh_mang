package com.smashprofs.server.game;

import com.smashprofs.common.model.*;
import com.smashprofs.server.room.RoomManager;
import com.smashprofs.server.game.physics.HeadlessPhysicsWorld;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import static org.junit.Assert.*;

/** Native Worlds and references must be released, even after projectile/combat activity. */
public class RepeatedRoomResourceTest {
    @Test(timeout=30000) public void repeatedProjectileMatchesReleaseAllWorldsRoomsAndPendingInputs() {
        int baseline=HeadlessPhysicsWorld.liveWorldCount(); var clock=new AtomicLong(10_000_000_000L);
        var executor=new ManualScheduledExecutor(); var scheduler=new FixedTickScheduler(executor,Logger.getLogger("repeated-native-lifecycle"));
        var rooms=new RoomManager(scheduler,clock::get); scheduler.setLifecycleUpdate(rooms::updateLifecycles); scheduler.start();
        int spawnedRounds=0;
        try {
            for(int round=0;round<30;round++) {
                var room=rooms.getOrCreateRoom("cycle_"+round); var players=new ArrayList<PlayerId>();
                String[] characters={"Viktor","Maurice","Leo","Alex"};
                for(int i=0;i<4;i++) {
                    var id=new PlayerId("p"+i); players.add(id); room.joinPlayer(id); room.selectCharacter(id,characters[i]); room.setReady(id,true);
                }
                executor.runCycle(); clock.addAndGet(TimeUnit.SECONDS.toNanos(3)); executor.runCycle();
                var runtime=room.getMatchRuntime(); assertNotNull(runtime);
                boolean sawProjectile=false;
                assertEquals(baseline+1,HeadlessPhysicsWorld.liveWorldCount());
                for(int tick=0;tick<180&&!runtime.isFinished();tick++) {
                    for(var player:players) { runtime.submitInput(new PlayerInput(player,tick,tick,0,tick%40==3,false,tick%10==0,false,tick%60==0)); }
                    executor.runCycle(); sawProjectile|=runtime.getActiveProjectileCount()>0;
                }
                if(sawProjectile) { spawnedRounds++; }
                if(!runtime.isFinished()) { clock.addAndGet(TimeUnit.SECONDS.toNanos(120)); executor.runCycle(); }
                assertNotNull(runtime.getMatchResult()); rooms.removeRoom(room.getRoomId());
                assertEquals(0,runtime.getPendingInputCount()); assertNull(runtime.getLatestSnapshot()); assertTrue(runtime.isPhysicsDisposed());
                assertEquals(0,rooms.getRoomCount()); assertEquals(baseline,HeadlessPhysicsWorld.liveWorldCount());
            }
            assertEquals(30,spawnedRounds);
        } finally { scheduler.stop(); rooms.shutdown(); }
    }
}
