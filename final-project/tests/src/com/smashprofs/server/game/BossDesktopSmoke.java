package com.smashprofs.server.game;

import com.badlogic.gdx.*;
import com.badlogic.gdx.backends.lwjgl3.*;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.ScreenUtils;
import com.smashprofs.game.Game;
import com.smashprofs.game.Screens.*;
import com.smashprofs.game.network.client.*;
import com.smashprofs.server.network.GameServer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/** Manual hidden-window GL smoke: real client screens + TCP, simulation fixtures stay outside the protocol. */
public final class BossDesktopSmoke {
    private static void until(BooleanSupplier ready) throws Exception {
        long end=System.nanoTime()+8_000_000_000L;
        while(!ready.getAsBoolean()) { if(System.nanoTime()>end) { throw new IllegalStateException("Desktop boss flow timeout"); }Thread.sleep(2); }
    }
    public static void main(String[] args) throws Exception {
        int count=Integer.parseInt(args[0]),width=Integer.parseInt(args[1]),height=Integer.parseInt(args[2]);
        boolean win=Boolean.parseBoolean(args[3]);Path output=Path.of(args[4]);java.nio.file.Files.createDirectories(output);
        var executor=new ManualScheduledExecutor();var clock=new AtomicLong(10_000_000_000L);
        var server=new GameServer(()->new FixedTickScheduler(executor,Logger.getLogger("boss-desktop")),clock::get);
        var clients=new ArrayList<LobbyClient>();server.start();
        try {
            for(int i=0;i<count;i++) {
                var c=new LobbyClient(new LobbyConfiguration("127.0.0.1",server.getBoundPort(),"boss_visual",i==0?LobbyConfiguration.Mode.CREATE:LobbyConfiguration.Mode.JOIN));
                if(i==0) { c.enableBoss(); }else { c.enableBossSupport(); }
                clients.add(c);c.startAsync();until(()->c.getSnapshot().canReady());
            }
            executor.runCycle();until(()->clients.stream().allMatch(c->c.getSettings()!=null&&c.getSettings().playerCount()==count));
            var host=clients.get(0);var room=server.getRoomManager().getRoom("boss_visual");
            var config=new Lwjgl3ApplicationConfiguration();config.setTitle("Boss smoke");config.setInitialVisible(false);
            config.setWindowedMode(width,height);config.setForegroundFPS(60);config.disableAudio(true);
            new Lwjgl3Application(new ApplicationAdapter() {
                Game game;int frame;MatchRuntime runtime;Throwable failure;
                @Override public void create() {
                    Game.soundEffects=false;Game.ingameMusic=false;Game.debugMode=false;Game.batch=new SpriteBatch();
                    game=Game.forLobby(host);game.setScreen(new OnlineRoomScreen(game));
                }
                private void capture(String name) {
                    var p=ScreenUtils.getFrameBufferPixmap(0,0,Gdx.graphics.getWidth(),Gdx.graphics.getHeight());
                    PixmapIO.writePNG(Gdx.files.absolute(output.resolve(name+".png").toString()),p,-1,true);p.dispose();
                }
                @Override public void render() {
                    try {
                        frame++;clock.addAndGet(16_666_667);executor.runCycle();
                        if(frame==10) { capture("room");for(var c:clients) { c.ready("Alex"); }until(room::canStartMatch);executor.runCycle();game.prepareOnlineAssets();for(var c:clients) { c.markLoaded(); }until(()->room.getLoadedPlayerCount()==count);clock.addAndGet(3_000_000_000L);for(int i=0;i<4;i++) { executor.runCycle(); }until(()->host.getMatchSession()!=null&&host.getMatchSession().getSnapshotBuffer().getLatestSnapshot()!=null);runtime=room.getMatchRuntime();game.setScreen(new PlayScreen(game)); }
                        if(runtime!=null&&!runtime.isFinished()) {
                            var g=runtime.gameplay();
                            for(int i=0;i<clients.size();i++) { var id=clients.get(i).getSnapshot().localPlayerId();g.progress.get(id).invulnerableUntil=g.boss.tick+100;g.state.getPlayer(id).heal(100); }
                            if(frame==20) { for(int i=0;i<clients.size();i++) { g.bodies.respawn(clients.get(i).getSnapshot().localPlayerId(),5.1f+i*.35f,1.3f); }g.boss.until=100000; }
                            if(frame==55) { g.boss.attack="CLEAVE";g.boss.action="TELEGRAPH";g.boss.actionAt=g.boss.tick;g.boss.until=g.boss.tick+80; }
                            if(frame==95) { for(int i=0;i<clients.size();i++) { g.bodies.respawn(clients.get(i).getSnapshot().localPlayerId(),4.25f+i*.12f,1.2f); }g.boss.attack="FIREBALL";g.boss.action="TELEGRAPH";g.boss.actionAt=g.boss.tick;g.boss.until=g.boss.tick+2; }
                            if(frame==120) { for(int i=0;i<clients.size();i++) { g.bodies.respawn(clients.get(i).getSnapshot().localPlayerId(),4.25f+i*.12f,1.2f); }g.boss.damage(clients.get(0).getSnapshot().localPlayerId(),g.boss.maxHp*.51f);g.boss.pattern=1;g.boss.action="IDLE";g.boss.until=g.boss.tick; }
                            if(frame==210) { if(win) { g.boss.damage(host.getSnapshot().localPlayerId(),10000); }else { for(var c:clients) { g.disconnect(c.getSnapshot().localPlayerId()); } } }
                        }
                        game.render();
                        if(frame==40) { capture("boss"); }
                        if(frame==75) { capture("cleave-warning"); }
                        if(frame==105) {
                            if(host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().boss().hazards().stream().noneMatch(h->h.type().equals("FIREBALL"))) { throw new IllegalStateException("Missing fireball visual state"); }
                            capture("fireball");
                        }
                        if(frame==140) { capture("eruption-warning-phase2"); }
                        if(frame==188) {
                            if(host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().boss().hazards().stream().noneMatch(h->h.type().equals("ERUPTION")&&h.active())) { throw new IllegalStateException("Missing active eruption visual state"); }
                            capture("eruption-active");
                        }
                        if(frame==235) { capture(win?"boss-death":"team-eliminated"); }
                        if(frame==395) {
                            if(!(game.getScreen() instanceof OnlineResultScreen) || host.getMatchSession().getResult()==null || host.getMatchSession().getResult().teamWon()!=win
                                    || clients.stream().anyMatch(c->c.getMatchSession().getResult()==null || !c.getMatchSession().getResult().equals(host.getMatchSession().getResult()))) {
                                throw new IllegalStateException("Native screen/result did not reach the expected team outcome");
                            }
                            capture(win?"team-win":"team-loss");System.out.println("PASS boss desktop "+count+" clients "+width+"x"+height+" win="+win);Gdx.app.exit();
                        }
                    } catch(Throwable error) { failure=error;error.printStackTrace();Gdx.app.exit(); }
                }
                @Override public void resize(int w,int h) { if(game!=null) { game.resize(w,h); } }
                @Override public void dispose() { if(game!=null&&game.getScreen()!=null) { game.getScreen().dispose(); }Game.batch.dispose();if(failure!=null) { throw new RuntimeException(failure); } }
            },config);
        } finally { for(var c:clients) { c.closeAndWait(5000); }server.stop(); }
    }
}
