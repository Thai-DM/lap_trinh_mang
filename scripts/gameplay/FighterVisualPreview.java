package com.smashprofs.server.game;

import com.badlogic.gdx.*;
import com.badlogic.gdx.backends.lwjgl3.*;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.scenes.scene2d.*;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.ScreenUtils;
import com.smashprofs.common.model.*;
import com.smashprofs.game.Game;
import com.smashprofs.game.Scenes.OnlineHud;
import com.smashprofs.game.Screens.*;
import com.smashprofs.game.network.client.*;
import com.smashprofs.server.network.GameServer;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Real packaged client, GL renderer and TCP. Only fixtures/clock are driven by the test owner. */
public final class FighterVisualPreview extends Game {
    private final String output;private final int count;
    private final ManualScheduledExecutor executor=new ManualScheduledExecutor();
    private final AtomicLong clock=new AtomicLong(10_000_000_000L);
    private final List<LobbyClient> peers=new ArrayList<>();
    private GameServer server;private LobbyClient host;private MatchRuntime runtime;
    private int step,frames;private long started;private boolean jumped,versusCaptured,closed;
    private PlayerId owner;private String oldMatch;
    private Screen observedScreen;private int screenFrames;
    private float[] arenaProjection;private boolean cameraFollowed;
    FighterVisualPreview(String output,int count) { this.output=output;this.count=count; }
    public static void main(String[] args) {
        int width=args.length>2?Integer.parseInt(args[2]):1280;
        var config=new Lwjgl3ApplicationConfiguration();config.setWindowedMode(width,width*9/16);config.setInitialVisible(false);config.disableAudio(true);config.setForegroundFPS(30);
        var preview=new FighterVisualPreview(args[0],Integer.parseInt(args[1]));
        try { new Lwjgl3Application(preview,config); }finally { preview.dispose(); }
    }
    @Override public void create() {
        Game.batch=new SpriteBatch();Game.soundEffects=false;Game.ingameMusic=false;Game.unitTestMode=false;Game.mockMode=false;Game.showFPS=false;
        started=System.nanoTime();server=new GameServer(()->new FixedTickScheduler(executor,java.util.logging.Logger.getLogger("fighter-native")),clock::get);server.start();
        String code=OnlineRoomPresentation.newRoomCode();host=client(code,true);for(int i=1;i<count;i++) { peers.add(client(code,false)); }
        setScreen(new OnlineLobbyScreen(this));host.startAsync();
    }
    private LobbyClient client(String code,boolean create) {
        var c=new LobbyClient(new LobbyConfiguration("127.0.0.1",server.getBoundPort(),code,create?LobbyConfiguration.Mode.CREATE:LobbyConfiguration.Mode.JOIN));c.enableFighter();return c;
    }
    private void capture(String name) {
        if(getScreen() instanceof PlayScreen) { verifyFollowingCamera(); }
        var image=ScreenUtils.getFrameBufferPixmap(0,0,Gdx.graphics.getBackBufferWidth(),Gdx.graphics.getBackBufferHeight());
        PixmapIO.writePNG(Gdx.files.absolute(output+"/"+name+".png"),image,-1,true);image.dispose();System.out.println("FIGHTER VISUAL "+name);
    }
    private void verifyFollowingCamera() {
        try {
            var field=PlayScreen.class.getDeclaredField("onlineScreen");field.setAccessible(true);var screen=field.get(getScreen());
            var cameraField=screen.getClass().getDeclaredField("camera");cameraField.setAccessible(true);
            var viewportField=screen.getClass().getDeclaredField("viewport");viewportField.setAccessible(true);
            var camera=(com.badlogic.gdx.graphics.OrthographicCamera)cameraField.get(screen);
            var viewport=(com.badlogic.gdx.utils.viewport.Viewport)viewportField.get(screen);
            float[] projection={camera.position.x,camera.position.y,viewport.getWorldWidth(),viewport.getWorldHeight()};
            cameraFollowed|=arenaProjection!=null && !Arrays.equals(arenaProjection,projection);
            arenaProjection=projection;
            float bottom=camera.position.y-viewport.getWorldHeight()/2,top=camera.position.y+viewport.getWorldHeight()/2;
            if(bottom<-.4501f) { throw new IllegalStateException("Camera followed a fall below the lava boundary"); }
            var state=host.getMatchSession().getSnapshotBuffer().getLatestSnapshot();
            if(state.gameplay().fighter().player(owner.value()).platformTicks()>0 && top-viewport.getWorldHeight()*.12f<=3.64f) { throw new IllegalStateException("Respawn platform clipped by HUD"); }
            System.out.println("FOLLOW CAMERA PASS "+Arrays.toString(projection));
        } catch(ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private OnlineHud hud() {
        try { var f=PlayScreen.class.getDeclaredField("onlineScreen");f.setAccessible(true);var screen=f.get(getScreen());var h=screen.getClass().getDeclaredField("hud");h.setAccessible(true);return (OnlineHud)h.get(screen); }
        catch(ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private void verifyEquipmentHud(boolean bagVisible,int ammo) {
        try {
            var current=hud();var bag=OnlineHud.class.getDeclaredField("bag");bag.setAccessible(true);
            if(((Actor)bag.get(current)).isVisible()!=bagVisible) { throw new IllegalStateException("Inventory visibility lost on HUD update"); }
            for(String name:List.of("weaponIcon","weaponName","ammo","shieldIcon")) {
                var field=OnlineHud.class.getDeclaredField(name);field.setAccessible(true);var actor=(Actor)field.get(current);
                if(actor.getParent()==null || !actor.isVisible()) { throw new IllegalStateException("Missing equipment HUD: "+name); }
            }
            var field=OnlineHud.class.getDeclaredField("bagWeapon");field.setAccessible(true);
            var label=(com.badlogic.gdx.scenes.scene2d.ui.Label)field.get(current);
            if(!label.getText().toString().contains(Integer.toString(ammo))) { throw new IllegalStateException("Inventory ammo does not match authority"); }
        } catch(ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private void enableCombatDebug() {
        try {
            var field=PlayScreen.class.getDeclaredField("onlineScreen");field.setAccessible(true);var screen=field.get(getScreen());
            var flag=screen.getClass().getDeclaredField("debugCombat");flag.setAccessible(true);flag.setBoolean(screen,true);
        } catch(ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static TextButton find(Group group,String text) {
        for(var actor:group.getChildren()) { if(actor instanceof TextButton b && b.getText().toString().equals(text)) { return b; }if(actor instanceof Group g) { var b=find(g,text);if(b!=null) { return b; } } }return null;
    }
    private void rematchButton() {
        try { var f=OnlineResultScreen.class.getDeclaredField("stage");f.setAccessible(true);var stage=(Stage)f.get(getScreen());var button=find(stage.getRoot(),"ĐẤU LẠI [ENTER]");if(button==null) { throw new IllegalStateException("Missing rematch button"); }button.fire(new ChangeListener.ChangeEvent()); }
        catch(ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    @Override public void render() {
        for(var peer:peers) { if(peer.getSnapshot().phase()==LobbyClient.Phase.WAITING_FOR_START) { peer.markLoaded(); } }
        clock.addAndGet(16_666_667L);executor.runCycle();
        var room=server.getRoomManager().getRoom(host.getSnapshot().roomId());
        if(room!=null && room.getPhase().name().equals("COUNTDOWN") && room.getLoadedPlayerCount()==count && !jumped) { jumped=true;clock.addAndGet(3_000_000_000L);executor.runCycle(); }
        super.render();frames++;
        if(getScreen()!=observedScreen) { observedScreen=getScreen();screenFrames=0; }else { screenFrames++; }
        if(System.nanoTime()-started>45_000_000_000L) { throw new IllegalStateException("Fighter visual timeout step="+step+" host="+host.getSnapshot()); }
        if(getScreen() instanceof OnlineLoadingScreen && screenFrames>2 && !versusCaptured) { capture("versus");versusCaptured=true; }
        if(step==0 && host.getSnapshot().canReady()) { openOnlineLobby(host);for(var peer:peers) { peer.startAsync(); }step=1; }
        else if(step==1 && host.getSettings()!=null && host.getSettings().playerCount()==count && peers.stream().allMatch(p->p.getSnapshot().canReady())) {
            host.ready("Alex");String[] names={"Luca","Maurice","Viktor"};for(int i=0;i<peers.size();i++) { peers.get(i).ready(names[i]); }step=2;
        } else if(step==2 && getScreen() instanceof PlayScreen && screenFrames>3 && host.getMatchSession().getSnapshotBuffer().getLatestSnapshot()!=null) {
            runtime=room.getMatchRuntime();oldMatch=runtime.getMatchId();owner=host.getSnapshot().localPlayerId();var game=runtime.gameplay();
            for(var p:game.state.playersInSlotOrder()) { game.bodies.respawn(p.getPlayerId(),5.12f+p.getSlotIndex()*.30f,.23f);game.fighter.physics.reset(p.getPlayerId()); }
            var target=peers.get(0).getSnapshot().localPlayerId();game.fighter.hit(owner,target,"PREVIEW",20,0,0,1,0,false);
            step=3;frames=0;
        } else if(step==3 && frames>4) {
            var state=host.getMatchSession().getSnapshotBuffer().getLatestSnapshot();
            capture("unarmed-hud");enableCombatDebug();step=4;frames=0;
        } else if(step==4 && frames>4) {
            capture("unarmed-debug");var game=runtime.gameplay();var p=game.bodies.getPosition(owner);
            game.items.drop("LASER",p.xMeters(),p.yMeters(),runtime.getMatchState().getServerTick());step=45;frames=0;
        } else if(step==45 && frames>4 && !host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().items().isEmpty()) {
            capture("weapon-pickup");host.recordFrameInput(0,false,false,false,false,false,true,false);step=46;frames=0;
        } else if(step==46 && frames>4 && host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().player(owner.value()).weapon().equals("LASER")) {
            hud().toggleInventory();step=47;frames=0;
        } else if(step==47 && frames>4) {
            verifyEquipmentHud(true,6);capture("weapon-inventory");hud().toggleInventory();
            host.recordFrameInput(0,false,false,true,false,false);step=48;frames=0;
        } else if(step==48 && frames>4 && host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().player(owner.value()).ammo()==5
                && runtime.gameplay().fighter.fighters.get(owner).attack==null) {
            var shotState=host.getMatchSession().getSnapshotBuffer().getLatestSnapshot();
            if(!shotState.gameplay().fighter().player(owner.value()).attack().equals("NONE")) { throw new IllegalStateException("Gun also started a punch animation"); }
            if(shotState.player(peers.get(0).getSnapshot().localPlayerId().value()).hp()!=56) { throw new IllegalStateException("Gun did not deal projectile-only damage"); }
            verifyEquipmentHud(false,5);capture("weapon-fired");
            host.recordFrameInput(0,false,false,false,false,false,false,false,new FighterInput(false,false,false,false,false,false,true,false));step=49;frames=0;
        } else if(step==49 && frames>4 && !runtime.gameplay().items.itemViews().isEmpty()
                && runtime.gameplay().items.shotViews().stream().noneMatch(s->s.type().equals("THROWN"))) {
            var item=runtime.gameplay().items.itemViews().stream().filter(i->i.type().equals("LASER")).findFirst().orElseThrow();
            runtime.gameplay().bodies.respawn(owner,item.x(),item.y());runtime.gameplay().fighter.physics.reset(owner);
            host.recordFrameInput(0,false,false,false,false,false,true,false);step=52;frames=0;
        } else if(step==52 && frames>4 && host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().player(owner.value()).weapon().equals("LASER")) {
            if(host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().player(owner.value()).ammo()!=5) { throw new IllegalStateException("Throw lost ammo"); }
            host.recordFrameInput(0,false,false,false,false,false,false,true);step=53;frames=0;
        } else if(step==53 && frames>4 && host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().player(owner.value()).weapon().equals("NONE")) {
            if(runtime.gameplay().items.itemViews().stream().noneMatch(i->i.type().equals("LASER"))) { throw new IllegalStateException("Drop lost weapon"); }
            capture("weapon-dropped");hud().toggleHelp();step=44;frames=0;
        } else if(step==44 && frames>4) {
            capture("unarmed-help");hud().toggleHelp();runtime.gameplay().bodies.respawn(owner,2.5f,1.2f);
            runtime.gameplay().fighter.physics.reset(owner);step=41;frames=0;
        } else if(step==41 && frames>2 && runtime.gameplay().bodies.getVelocity(owner).yMetersPerSecond()>0
                && host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().player(owner.value()).hp()<100) {
            step=43;frames=0;
        } else if(step==43 && frames>4) {
            var state=host.getMatchSession().getSnapshotBuffer().getLatestSnapshot();
            if(state.gameplay().player(owner.value()).lives()!=3 || state.player(owner.value()).hp()!=90 || state.player(owner.value()).y()<FighterArena.LAVA_CENTER_Y-.001f) { throw new IllegalStateException("Lava did not rebound with 10 HP damage"); }
            capture("lava-bounce");step=42;frames=0;
        } else if(step==42 && runtime.gameplay().fighter.fighters.get(owner).hitstun==0) {
            host.recordFrameInput(0,false,false,false,false,false,false,false,new FighterInput(false,false,false,false,false,true,false,false));step=5;frames=0;
        } else if(step==5 && frames>4 && host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().fighter().player(owner.value()).action().equals("DODGE")) {
            capture("spot-dodge");host.recordFrameInput(0,false,false,true,false,false);step=51;frames=0;
        } else if(step==51 && frames>4 && host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().fighter().player(owner.value()).detail().gravityCancelled()) {
            capture("gravity-cancel");runtime.gameplay().state.getPlayer(owner).applyDamage(80);runtime.gameplay().fighter.fighters.get(owner).nextLavaDamage=0;
            runtime.gameplay().bodies.respawn(owner,2.5f,.2f);runtime.gameplay().fighter.physics.reset(owner);step=6;frames=0;
        } else if(step==6 && frames>4 && host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().player(owner.value()).status().equals("RESPAWNING")) {
            capture("ko-stock");for(int i=0;i<90;i++) { executor.runCycle(); }step=7;frames=0;
        } else if(step==7 && frames>4 && host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().fighter().player(owner.value()).platformTicks()>0) {
            if(host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().player(owner.value()).hp()!=100) { throw new IllegalStateException("Lava death did not restore HP on respawn"); }
            capture("respawn-platform");host.recordFrameInput(1,false,false,false,false,false);step=8;frames=0;
        } else if(step==8 && frames>5 && host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().fighter().player(owner.value()).platformTicks()==0) {
            if(host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().player(owner.value()).invulnerableTicks()==0) { throw new IllegalStateException("Missing post-platform protection"); }
            capture("respawn-leave");var game=runtime.gameplay();game.bodies.respawn(owner,5.5f,.23f);game.fighter.physics.reset(owner);game.fighter.fighters.get(owner).axis=0;
            for(int stock=0;stock<3;stock++) {
                for(var peer:peers) { game.bodies.respawn(peer.getSnapshot().localPlayerId(),.9f,2); }
                executor.runCycle();for(int i=0;i<(stock<2?92:3);i++) { executor.runCycle(); }
            }
            step=9;frames=0;
        } else if(step==9 && getScreen() instanceof OnlineResultScreen && screenFrames>3) {
            capture("result");for(var peer:peers) { peer.requestRematch(); }rematchButton();jumped=false;step=10;frames=0;
        } else if(step==10 && getScreen() instanceof PlayScreen && screenFrames>3 && room.getMatchRuntime()!=null && !room.getMatchRuntime().getMatchId().equals(oldMatch)) {
            var state=host.getMatchSession().getSnapshotBuffer().getLatestSnapshot();if(state==null) { return; }
            for(var p:state.gameplay().players()) { if(p.lives()!=3 || state.gameplay().fighter().player(p.playerId()).damagePercent()!=0 || !p.weapon().equals("NONE")) { throw new IllegalStateException("Stale rematch state"); } }
            capture("rematch");if(!cameraFollowed) { throw new IllegalStateException("Camera stayed fixed instead of following players"); }
            System.out.println("FIGHTER NATIVE PASS players="+count+" width="+Gdx.graphics.getWidth());Gdx.app.exit();
        }
    }
    @Override public void dispose() { if(closed) { return; }closed=true;for(var peer:peers) { peer.close(); }try { if(Game.batch!=null) { super.dispose(); } }finally { if(server!=null) { server.stop(); } } }
}
