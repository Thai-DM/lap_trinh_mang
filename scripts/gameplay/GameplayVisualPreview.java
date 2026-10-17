package com.smashprofs.server.game;

import com.badlogic.gdx.*;
import com.badlogic.gdx.backends.lwjgl3.*;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.smashprofs.game.Game;
import com.smashprofs.game.Scenes.OnlineHud;
import com.smashprofs.game.Screens.*;
import com.smashprofs.game.network.client.*;
import com.smashprofs.game.ui.*;
import com.smashprofs.server.network.GameServer;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Native graphics smoke. The GL thread also owns this manual simulation scheduler. */
public final class GameplayVisualPreview extends Game {
    private final String output;private final int count;
    private final ManualScheduledExecutor executor=new ManualScheduledExecutor();
    private final AtomicLong clock=new AtomicLong(10_000_000_000L);
    private final ArrayList<LobbyClient> peers=new ArrayList<>();
    private GameServer server;private LobbyClient host;private MatchRuntime runtime;
    private int step,frames;private long started;private boolean skippedCountdown;
    GameplayVisualPreview(String output,int count) { this.output=output;this.count=count; }
    public static void main(String[] args) {
        int width=args.length>2?Integer.parseInt(args[2]):1280;
        var config=new Lwjgl3ApplicationConfiguration();config.setWindowedMode(width,width*9/16);config.setInitialVisible(false);config.disableAudio(true);config.setForegroundFPS(30);
        new Lwjgl3Application(new GameplayVisualPreview(args[0],args.length>1?Integer.parseInt(args[1]):2),config);
    }
    @Override public void create() {
        Game.batch=new SpriteBatch();Game.soundEffects=false;Game.ingameMusic=false;Game.unitTestMode=false;Game.mockMode=false;Game.debugMode=false;Game.showFPS=false;
        started=System.nanoTime();server=new GameServer(()->new FixedTickScheduler(executor,java.util.logging.Logger.getLogger("visual-smoke")),clock::get);server.start();
        String room=OnlineRoomPresentation.newRoomCode();
        host=client(room,LobbyConfiguration.Mode.CREATE);for(int i=1;i<count;i++) { peers.add(client(room,LobbyConfiguration.Mode.JOIN)); }
        setScreen(new OnlineLobbyScreen(this));host.startAsync();
    }
    private LobbyClient client(String room,LobbyConfiguration.Mode mode) { var c=new LobbyClient(new LobbyConfiguration("127.0.0.1",server.getBoundPort(),room,mode));c.enableGameplay();return c; }
    private void capture(String name) {
        var pixels=ScreenUtils.getFrameBufferPixmap(0,0,Gdx.graphics.getBackBufferWidth(),Gdx.graphics.getBackBufferHeight());
        PixmapIO.writePNG(Gdx.files.absolute(output+"/"+name+".png"),pixels,-1,true);pixels.dispose();System.out.println("VISUAL "+name);
    }
    private OnlineHud hud() {
        try { var f=PlayScreen.class.getDeclaredField("onlineScreen");f.setAccessible(true);var screen=f.get(getScreen());var h=screen.getClass().getDeclaredField("hud");h.setAccessible(true);return (OnlineHud)h.get(screen); }
        catch(ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    @Override public void render() {
        clock.addAndGet(16_666_667L);executor.runCycle();
        var room=server.getRoomManager().getRoom(host.getSnapshot().roomId());
        if(room!=null && room.getPhase().name().equals("COUNTDOWN") && !skippedCountdown) { skippedCountdown=true;clock.addAndGet(3_000_000_000L);executor.runCycle(); }
        super.render();frames++;
        if(System.nanoTime()-started>30_000_000_000L) { throw new IllegalStateException("Visual smoke timeout step="+step); }
        if(step==0 && host.getSnapshot().canReady()) { openOnlineLobby(host);for(var peer:peers) { peer.startAsync(); }step=1; }
        else if(step==1 && host.getSettings()!=null && host.getSettings().members().size()==count && peers.stream().allMatch(p->p.getSnapshot().canReady())) {
            host.ready("Viktor");for(var peer:peers) { peer.ready("Luca"); }step=2;
        } else if(step==2 && getScreen() instanceof PlayScreen && host.getSnapshot().isMatchStarted() && host.getMatchSession().getSnapshotBuffer().getLatestSnapshot()!=null) {
            runtime=room.getMatchRuntime();var game=runtime.gameplay();var position=game.bodies.getPosition(host.getSnapshot().localPlayerId());long tick=runtime.getMatchState().getServerTick();
            game.items.drop("SHOTGUN",position.xMeters(),position.yMeters(),tick);game.items.drop("SHIELD",position.xMeters(),position.yMeters(),tick);
            game.items.drop("HEALTH",position.xMeters()+.22f,position.yMeters(),tick);
            String[] items={"SHOTGUN","LASER","BAZOOKA","GRENADE","HEALTH","SHIELD"};for(int i=0;i<items.length;i++) { game.items.drop(items[i],4.8f+i*.36f,.48f,tick); }
            step=3;frames=0;
        } else if(step==3 && frames>4) {
            var equipment=host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().player(host.getSnapshot().localPlayerId().value());
            if(equipment.ammo()==6 && equipment.shield()) {
                runtime.gameplay().items.attack(host.getSnapshot().localPlayerId(),runtime.getMatchState().getServerTick());step=4;
            } else { host.recordFrameInput(0,false,false,false,false,false,true,false); }
            frames=0;
        } else if(step==4 && frames>4 && host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().player(host.getSnapshot().localPlayerId().value()).ammo()==5) {
            capture("compact-hud");hud().toggleInventory();step=5;frames=0;
        } else if(step==5 && frames>4) { capture("inventory");hud().toggleInventory();
            runtime.gameplay().bodies.respawn(host.getSnapshot().localPlayerId(),4.9f,.12f);runtime.gameplay().jumps.resetAfterRespawn(host.getSnapshot().localPlayerId());step=6;frames=0;
        } else if(step==6 && frames>4) {
            float hp=runtime.getMatchState().getPlayer(host.getSnapshot().localPlayerId()).getHp();
            if(hp!=90) { throw new IllegalStateException("Lava rebound expected90HP, got"+hp); }
            capture("lava-rebound");runtime.getMatchState().getPlayer(host.getSnapshot().localPlayerId()).applyDamage(100);step=7;frames=0;
        } else if(step==7 && frames>5) {
            capture("respawning");for(int i=0;i<90;i++) { executor.runCycle(); }step=8;frames=0;
        } else if(step==8 && frames>4 && host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().player(host.getSnapshot().localPlayerId().value()).status().equals("ACTIVE")) {
            if(host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().player(host.getSnapshot().localPlayerId().value()).invulnerableTicks()==0) { throw new IllegalStateException("Missing respawn protection"); }
            capture("respawn-protection");setScreen(new Gallery());step=9;frames=0;
        } else if(step==9 && frames>3) { capture("weapon-art");Gdx.app.exit(); }
    }
    private static final class Gallery implements Screen {
        final Stage stage=new Stage(new FitViewport(1280,720));final OnlineUi ui=new OnlineUi();final CombatIcons icons=new CombatIcons();
        Gallery() {
            var root=new Table();root.setFillParent(true);root.pad(30);root.add(ui.heading("Vũ khí và vật phẩm")).colspan(3).padBottom(24);root.row();
            String[] types={"SHOTGUN","LASER","BAZOOKA","GRENADE","HEALTH","SHIELD"};
            for(int i=0;i<types.length;i++) { var card=ui.panel();card.add(new Image(icons.drawable(types[i]))).size(96);card.row();card.add(ui.label(CombatIcons.name(types[i]))).padTop(12);root.add(card).width(280).height(220).pad(10);if(i%3==2) { root.row(); } }stage.addActor(root);
        }
        public void render(float delta) { ScreenUtils.clear(OnlineUi.BACKGROUND);stage.getViewport().apply();stage.act(delta);stage.draw(); }
        public void resize(int w,int h) { stage.getViewport().update(w,h,true); }public void show() { }public void hide() { dispose(); }public void pause() { }public void resume() { }
        public void dispose() { stage.dispose();icons.close();ui.close(); }
    }
    @Override public void dispose() { for(var peer:peers) { peer.close(); }super.dispose();if(server!=null) { server.stop(); } }
}
