package com.smashprofs.game.Screens;

import com.badlogic.gdx.maps.tiled.*;

/** Render-thread handoff from loading to play; resources are loaded before LOAD_READY. */
public final class OnlineArenaAssets implements AutoCloseable {
    final TiledMap map;
    final OnlineSpriteAssets sprites;
    private boolean closed;
    public OnlineArenaAssets() {
        map=new TmxMapLoader().load("1/Map1New2Remake.tmx");sprites=new OnlineSpriteAssets();
        try { sprites.preload(); }catch(RuntimeException error) { map.dispose();sprites.close();throw error; }
    }
    @Override public void close() { if(!closed) { closed=true;map.dispose();sprites.close(); } }
}
