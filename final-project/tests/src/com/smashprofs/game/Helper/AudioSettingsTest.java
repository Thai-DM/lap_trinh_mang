package com.smashprofs.game.Helper;

import com.badlogic.gdx.*;
import com.badlogic.gdx.audio.Sound;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

public class AudioSettingsTest {
    private Application oldApp;private final Map<String,Float> saved=new HashMap<>();
    @Before public void setup() {
        oldApp=Gdx.app;
        Preferences prefs=(Preferences)Proxy.newProxyInstance(Preferences.class.getClassLoader(),new Class<?>[]{Preferences.class},(p,m,a)->{
            switch(m.getName()) {
                case "getFloat":return saved.getOrDefault((String)a[0],(Float)a[1]);
                case "putFloat":saved.put((String)a[0],(Float)a[1]);return p;
                case "flush":return null;
                default:throw new UnsupportedOperationException(m.getName());
            }
        });
        Gdx.app=(Application)Proxy.newProxyInstance(Application.class.getClassLoader(),new Class<?>[]{Application.class},(p,m,a)->{
            if(m.getName().equals("getPreferences")) { return prefs; }throw new UnsupportedOperationException(m.getName());
        });
    }
    @After public void restore() { Gdx.app=oldApp;AudioSettings.get().load(); }
    @Test public void volumePersistsAcrossNewSettingsInstance() {
        var settings=new AudioSettings();settings.set(.5f,.4f,.3f);var reload=new AudioSettings();
        assertEquals(.5f,reload.master(),0);assertEquals(.4f,reload.music(),0);assertEquals(.3f,reload.effects(),0);
        assertEquals(.2f,reload.musicGain(),.0001f);assertEquals(.075f,reload.effectGain(.5f),.0001f);
    }
    @Test public void masterZeroMutesBothChannelsWithoutChangingChannelPreferences() {
        var settings=new AudioSettings();settings.set(0,.7f,.8f);assertEquals(0,settings.musicGain(),0);assertEquals(0,settings.effectGain(1),0);
        settings.set(1,settings.music(),settings.effects());assertEquals(.7f,settings.musicGain(),0);assertEquals(.8f,settings.effectGain(1),0);
    }
    @Test public void malformedValuesAreClampedBeforePersistenceOrPlayback() {
        var settings=new AudioSettings();settings.set(2,-3,Float.NaN);assertEquals(1,settings.master(),0);assertEquals(0,settings.music(),0);assertEquals(0,settings.effects(),0);
        assertEquals(0,settings.effectGain(Float.POSITIVE_INFINITY),0);
    }
    @Test public void onlineEffectsUseSavedGainAndMasterMuteDoesNotPlay() {
        var audio=AudioSettings.get();audio.set(.5f,.4f,.6f);List<Float> calls=new ArrayList<>();
        Sound sound=(Sound)Proxy.newProxyInstance(Sound.class.getClassLoader(),new Class<?>[]{Sound.class},(p,m,a)->{
            if(m.getName().equals("play")) { calls.add((Float)a[0]);return 1L; }return null;
        });
        var before=com.smashprofs.game.Game.soundEffects;com.smashprofs.game.Game.soundEffects=true;
        try {
            SoundManager.getSoundManager_INSTANCE().playEffect(sound,.5f);assertEquals(.15f,calls.get(0),.0001f);
            audio.set(0,.4f,.6f);SoundManager.getSoundManager_INSTANCE().playEffect(sound,1);assertEquals(1,calls.size());
        } finally { com.smashprofs.game.Game.soundEffects=before; }
    }
    @Test public void playingMusicChangesGainImmediatelyAndSameTrackIsReused() {
        var oldAudio=Gdx.audio;var oldFiles=Gdx.files;var oldMusic=com.smashprofs.game.Game.ingameMusic;
        List<Float> volumes=new ArrayList<>();int[] created={0},disposed={0};
        var music=(com.badlogic.gdx.audio.Music)Proxy.newProxyInstance(com.badlogic.gdx.audio.Music.class.getClassLoader(),new Class<?>[]{com.badlogic.gdx.audio.Music.class},(p,m,a)->{
            if(m.getName().equals("setVolume")) { volumes.add((Float)a[0]); }
            if(m.getName().equals("dispose")) { disposed[0]++; }
            return null;
        });
        Gdx.audio=(Audio)Proxy.newProxyInstance(Audio.class.getClassLoader(),new Class<?>[]{Audio.class},(p,m,a)->{
            if(m.getName().equals("newMusic")) { created[0]++;return music; }throw new UnsupportedOperationException(m.getName());
        });
        Gdx.files=(Files)Proxy.newProxyInstance(Files.class.getClassLoader(),new Class<?>[]{Files.class},(p,m,a)->new com.badlogic.gdx.files.FileHandle((String)a[0]));
        var manager=SoundManager.getSoundManager_INSTANCE();com.smashprofs.game.Game.ingameMusic=true;
        try {
            AudioSettings.get().set(.5f,.8f,1);manager.setupMusic("test-music.wav");assertEquals(.4f,volumes.get(0),.0001f);
            AudioSettings.get().set(.2f,.5f,1);manager.applyVolumes();assertEquals(.1f,volumes.get(volumes.size()-1),.0001f);
            manager.setupMusic("test-music.wav");assertEquals(1,created[0]);
        } finally { manager.dispose();Gdx.audio=oldAudio;Gdx.files=oldFiles;com.smashprofs.game.Game.ingameMusic=oldMusic; }
        assertEquals(1,disposed[0]);
    }
}
