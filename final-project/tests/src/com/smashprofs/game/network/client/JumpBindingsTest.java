package com.smashprofs.game.network.client;

import com.badlogic.gdx.*;
import com.smashprofs.game.Helper.KeyBindingConfig;
import com.smashprofs.game.Helper.KeyBindingConfig.Action;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Verify input behavior and upgrades from persisted settings without writing real user preferences. */
public class JumpBindingsTest {
    private KeyBindingConfig config;
    private Application previousApplication;
    private final Map<Action,Integer> previousBindings=new EnumMap<>(Action.class);
    private final Map<String,Integer> stored=new HashMap<>();
    @Before public void setup() {
        previousApplication=Gdx.app;config=KeyBindingConfig.getInstance();
        for(var action:Action.values()) { previousBindings.put(action,config.getKey(action)); }
        Preferences preferences=(Preferences)Proxy.newProxyInstance(Preferences.class.getClassLoader(),new Class<?>[]{Preferences.class},(proxy,method,args)->{
            switch(method.getName()) {
                case "getInteger":return stored.getOrDefault((String)args[0],args.length==2?(Integer)args[1]:0);
                case "putInteger":stored.put((String)args[0],(Integer)args[1]);return proxy;
                case "flush":return null;
                default:throw new UnsupportedOperationException(method.getName());
            }
        });
        Gdx.app=(Application)Proxy.newProxyInstance(Application.class.getClassLoader(),new Class<?>[]{Application.class},(proxy,method,args)->{
            if(method.getName().equals("getPreferences")) { return preferences; }
            throw new UnsupportedOperationException(method.getName());
        });
    }
    @After public void restore() { Gdx.app=previousApplication;previousBindings.forEach(config::setKey); }
    @Test public void downLightKeepsAttackAndDoesNotBecomeADoubleTapDrop() {
        config.load();
        UnarmedControls.sample(UnarmedControls.Preset.B,k->k==Input.Keys.S,k->k==Input.Keys.S);
        UnarmedControls.sample(UnarmedControls.Preset.B,k->false,k->false);
        var attack=UnarmedControls.sample(UnarmedControls.Preset.B,k->k==Input.Keys.S || k==Input.Keys.J,k->k==Input.Keys.S || k==Input.Keys.J);
        assertTrue(attack.lightPressed());assertTrue(attack.fighter().downHeld());assertFalse(attack.dropPressed());assertFalse(attack.jumpPressed());
    }
    @Test public void doubleTapDownWithoutAttackStillRequestsPlatformDrop() {
        config.load();UnarmedControls.sample(UnarmedControls.Preset.B,k->k==Input.Keys.S,k->k==Input.Keys.S);
        UnarmedControls.sample(UnarmedControls.Preset.B,k->false,k->false);
        var drop=UnarmedControls.sample(UnarmedControls.Preset.B,k->k==Input.Keys.S,k->k==Input.Keys.S);
        assertTrue(drop.dropPressed());assertFalse(drop.lightPressed());
    }
    @Test public void defaultSpaceJumpsOnceAndWAimsWithoutJumping() {
        config.load();
        var jump=UnarmedControls.sample(UnarmedControls.Preset.B,k->k==Input.Keys.SPACE,k->k==Input.Keys.SPACE);
        assertTrue(jump.jumpPressed());assertTrue(jump.fighter().jumpHeld());assertFalse(jump.fighter().upHeld());
        var heldJump=UnarmedControls.sample(UnarmedControls.Preset.B,k->k==Input.Keys.SPACE,k->false);
        assertFalse(heldJump.jumpPressed());assertTrue(heldJump.fighter().jumpHeld());
        var aim=UnarmedControls.sample(UnarmedControls.Preset.B,k->k==Input.Keys.W,k->k==Input.Keys.W);
        assertFalse(aim.jumpPressed());assertFalse(aim.fighter().jumpHeld());assertTrue(aim.fighter().upHeld());
        var iKey=UnarmedControls.sample(UnarmedControls.Preset.B,k->k==Input.Keys.I,k->k==Input.Keys.I);
        assertFalse(iKey.jumpPressed());assertFalse(iKey.fighter().jumpHeld());assertFalse(iKey.fighter().upHeld());
        assertTrue(UnarmedControls.hint().contains("Space"));
    }
    @Test public void oldSavedWASDDefaultsMigrateAndKeepOtherCustomKeys() {
        stored.put("UP",Input.Keys.I);stored.put("JUMP",Input.Keys.W);stored.put("DODGE",Input.Keys.X);
        config.load();assertEquals(Input.Keys.SPACE,config.getKey(Action.JUMP));assertEquals(Input.Keys.W,config.getKey(Action.UP));
        assertEquals(Input.Keys.X,config.getKey(Action.DODGE));
        config.load();assertEquals(Input.Keys.SPACE,config.getKey(Action.JUMP));
    }
    @Test public void previouslyCustomizedJumpIsPreserved() {
        stored.put("JUMP",Input.Keys.UP);stored.put("UP",Input.Keys.W);config.load();
        assertEquals(Input.Keys.UP,config.getKey(Action.JUMP));
    }
    @Test public void explicitSpaceBindingSavedAfterUpgradeSurvivesReload() {
        config.load();config.setKey(Action.JUMP,Input.Keys.SPACE);config.setKey(Action.UP,Input.Keys.W);config.save();config.load();
        assertEquals(Input.Keys.SPACE,config.getKey(Action.JUMP));assertEquals(Input.Keys.W,config.getKey(Action.UP));
    }
    @Test public void arrowLayoutIsPreserved() {
        stored.put("LEFT",Input.Keys.LEFT);stored.put("RIGHT",Input.Keys.RIGHT);stored.put("UP",Input.Keys.UP);
        stored.put("DOWN",Input.Keys.DOWN);stored.put("JUMP",Input.Keys.SPACE);config.load();
        assertEquals(Input.Keys.SPACE,config.getKey(Action.JUMP));assertEquals(Input.Keys.UP,config.getKey(Action.UP));
    }
}
