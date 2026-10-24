package com.smashprofs.game.Helper;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.audio.Sound;
import com.smashprofs.game.Game;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Handles game sound effects and background music.
 */
public class SoundManager {

    private Music currenMusic;
    private String musicPath;
    private final java.util.Map<String,Sound> effects=new java.util.HashMap<>();

    //Singleton for the sound manager
    private static final SoundManager soundManager_INSTANCE = new SoundManager();

    private static Logger log = LogManager.getLogger(SoundManager.class);


    /**
     * Private constructor to avoid client applications to use constructor
     */
    private SoundManager() {
    }

    public static SoundManager getSoundManager_INSTANCE() {return soundManager_INSTANCE;}


    /**
     * Initial setup of the game music.
     * @param musicPath
     * The path to the music file.
     */
    public void setupMusic(String musicPath) {
        //music from https://www.fesliyanstudios.com/royalty-free-music/download/funny-bit/2399 -> royalty free
        if(currenMusic!=null && musicPath.equals(this.musicPath)) { applyVolumes();return; }
        if(currenMusic!=null) { currenMusic.stop();currenMusic.dispose(); }
        this.musicPath=musicPath;
        currenMusic = Gdx.audio.newMusic(Gdx.files.internal(musicPath));
        System.out.println("SoundManager: setupMusic: musicPath: " + musicPath);
        log.info("SoundManager: setupMusic: musicPath: " + musicPath);


        currenMusic.setLooping(true);applyVolumes();
        if(!Boolean.FALSE.equals(Game.ingameMusic)){
            currenMusic.play();
        }
    }

    /**
     * Play a sound from a specified path.
     * @param soundPath
     * The path of the sound file.
     */
    public void playSound(String soundPath) {
        if(Boolean.FALSE.equals(Game.soundEffects) || AudioSettings.get().effectGain(1)==0) { return; }
        if(!soundPath.endsWith(".wav") && !soundPath.endsWith(".mp3")) { return; }
        playEffect(effects.computeIfAbsent(soundPath,path->Gdx.audio.newSound(Gdx.files.internal(path))),1);
    }
    public void playEffect(Sound sound,float gain) {
        float volume=AudioSettings.get().effectGain(gain);
        if(!Boolean.FALSE.equals(Game.soundEffects) && volume>0) { sound.play(volume); }
    }
    public void applyVolumes() {
        if(currenMusic!=null) { currenMusic.setVolume(Boolean.FALSE.equals(Game.ingameMusic)?0:AudioSettings.get().musicGain()); }
    }
    public void dispose() {
        if(currenMusic!=null) { currenMusic.dispose();currenMusic=null;musicPath=null; }
        effects.values().forEach(Sound::dispose);effects.clear();
    }
}
