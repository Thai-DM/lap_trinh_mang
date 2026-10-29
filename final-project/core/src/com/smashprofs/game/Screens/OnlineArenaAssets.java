package com.smashprofs.game.Screens;

import com.badlogic.gdx.maps.tiled.*;

/** Render-thread handoff from loading to play; resources are loaded before LOAD_READY. */
public final class OnlineArenaAssets implements AutoCloseable {
    final TiledMap map;
    final OnlineSpriteAssets sprites;
    final OnlineBossAssets boss;
    private boolean closed;
    public OnlineArenaAssets() {
        this(false);
    }
    public OnlineArenaAssets(boolean bossMode) {
        String mapRes = System.getProperty("ssprofs.map", "1/Map1New2Remake.tmx");
        map = new TmxMapLoader().load(mapRes);
        sprites = new OnlineSpriteAssets();
        try { sprites.preload(); boss = bossMode ? new OnlineBossAssets() : null; }
        catch (RuntimeException | Error error) { map.dispose(); sprites.close(); throw error; }
    }
    @Override public void close() { if(!closed) { closed=true;map.dispose();sprites.close();if(boss!=null) { boss.close(); } } }
}
