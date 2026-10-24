package com.smashprofs.game.network.client;

import org.junit.Test;
import static org.junit.Assert.*;

public class OnlineCameraControllerTest {
    @Test public void cameraFollowsPlayersAndZoomsOutForTheirSeparation() {
        var camera=new OnlineCameraController();var before=camera.update(4,5,.3f,1,16f/9,12,4,0);
        OnlineCameraController.Frame after=before;
        for(int i=0;i<60;i++) { after=camera.update(6,10,1,4,16f/9,12,4,1/60f); }
        assertTrue(after.x()>before.x()+1);assertTrue(after.y()>before.y()+.5f);assertTrue(after.width()>before.width());
    }
    @Test public void followingFallingPlayersNeverPullsTheViewIntoTheEmptyBottom() {
        for(float aspect:new float[]{16f/9,4f/3,21f/9,.75f}) {
            var camera=new OnlineCameraController();
            for(int i=0;i<180;i++) {
                var frame=camera.update(1,11.5f,-5,6-i*.1f,aspect,12,4,1/60f);
                assertTrue(frame.y()-frame.height()/2>=-.4501f);assertEquals(aspect,frame.width()/frame.height(),.0001f);
            }
        }
    }
    @Test public void respawnResetKeepsTheHighPlatformBelowTheHud() {
        var camera=new OnlineCameraController();camera.update(5.2f,6,.2f,.2f,16f/9,12,4,0);camera.reset();
        var frame=camera.update(5.2f,6,.2f,3.45f,16f/9,12,4,1/60f);
        assertTrue(frame.y()+frame.height()*.5f-frame.height()*.12f>3.45f+.32f);
        assertTrue(frame.y()-frame.height()*.5f+frame.height()*.08f<.2f-.0625f);
    }
    @Test public void dampingMatchesAt30_60_120FpsAndResizeKeepsTheNewAspect() {
        OnlineCameraController.Frame reference=null;
        for(int fps:new int[]{30,60,120}) {
            var camera=new OnlineCameraController();camera.update(4,5,1,2,16f/9,12,4,0);
            OnlineCameraController.Frame frame=null;
            for(int i=0;i<fps;i++) { frame=camera.update(2,9,2,6,16f/9,12,4,1f/fps); }
            if(reference!=null) { assertEquals(reference.y(),frame.y(),.0001f);assertEquals(reference.width(),frame.width(),.0001f); }
            reference=frame;
            var portrait=camera.update(2,9,2,6,.75f,12,4,1f/fps);assertEquals(.75f,portrait.width()/portrait.height(),.0001f);
            assertTrue(portrait.y()-portrait.height()/2>=-.4501f);
        }
    }
    @Test public void invalidWindowAspectFallsBackToLandscape() {
        var expected=new OnlineCameraController().update(4,6,.3f,1,16f/9,12,4,0);
        for(float aspect:new float[]{0,-1,Float.NaN,Float.POSITIVE_INFINITY}) {
            assertEquals(expected,new OnlineCameraController().update(4,6,.3f,1,aspect,12,4,0));
        }
    }
}
