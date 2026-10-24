import com.badlogic.gdx.*;
import com.badlogic.gdx.backends.lwjgl3.*;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.ScreenUtils;
import com.smashprofs.game.Game;
import com.smashprofs.game.Screens.*;
import com.smashprofs.game.network.client.*;
import com.smashprofs.server.network.GameServer;

/** Hidden-window native GL smoke; captures only this application's framebuffer. */
public class UiPreview extends Game {
    private GameServer server;
    private LobbyClient host,peer;
    private final java.util.List<LobbyClient> guests=new java.util.ArrayList<>();
    private int step,frames;
    private long started;
    private final String output;
    private final int count;
    UiPreview(String output,int count) { this.output=output;this.count=count; }
    public static void main(String[] args) {
        var config=new Lwjgl3ApplicationConfiguration();config.setWindowedMode(1280,720);
        config.setInitialVisible(false);config.disableAudio(true);config.setForegroundFPS(30);
        new Lwjgl3Application(new UiPreview(args[0],args.length>1?Integer.parseInt(args[1]):2),config);
    }
    @Override public void create() {
        Game.batch=new SpriteBatch();Game.soundEffects=false;Game.ingameMusic=false;Game.unitTestMode=false;Game.mockMode=false;Game.debugMode=false;Game.showFPS=false;
        started=System.nanoTime();setScreen(new OnlineLobbyScreen(this));
    }
    private void capture(String name) {
        var pixels=ScreenUtils.getFrameBufferPixmap(0,0,Gdx.graphics.getBackBufferWidth(),Gdx.graphics.getBackBufferHeight());
        PixmapIO.writePNG(Gdx.files.absolute(output+"/"+name+".png"),pixels,-1,true);pixels.dispose();System.out.println("PREVIEW "+name);
    }
    private com.badlogic.gdx.scenes.scene2d.ui.TextButton find(com.badlogic.gdx.scenes.scene2d.Group group,String text) {
        for(var actor:group.getChildren()) {
            if(actor instanceof com.badlogic.gdx.scenes.scene2d.ui.TextButton button && button.getText().toString().equals(text)) { return button; }
            if(actor instanceof com.badlogic.gdx.scenes.scene2d.Group child) { var match=find(child,text);if(match!=null) { return match; } }
        }
        return null;
    }
    private void click(String text) {
        try {
            var field=OnlineRoomScreen.class.getDeclaredField("stage");field.setAccessible(true);
            var stage=(com.badlogic.gdx.scenes.scene2d.Stage)field.get(getScreen());var button=find(stage.getRoot(),text);
            if(button==null || button.isDisabled()) { throw new IllegalStateException("Unavailable UI action: "+text); }
            var point=stage.stageToScreenCoordinates(button.localToStageCoordinates(new com.badlogic.gdx.math.Vector2(button.getWidth()/2,button.getHeight()/2)));
            stage.touchDown(Math.round(point.x),Math.round(point.y),0,Input.Buttons.LEFT);stage.touchUp(Math.round(point.x),Math.round(point.y),0,Input.Buttons.LEFT);
        } catch(ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    @Override public void render() {
        super.render();frames++;
        if(System.nanoTime()-started>25_000_000_000L) { throw new IllegalStateException("UI preview timed out at step "+step); }
        if(step==0 && frames>=3) {
            capture("connect");server=new GameServer();server.start();
            String room=OnlineRoomPresentation.newRoomCode();
            host=new LobbyClient(new LobbyConfiguration("127.0.0.1",server.getBoundPort(),room,LobbyConfiguration.Mode.CREATE));
            peer=new LobbyClient(new LobbyConfiguration("127.0.0.1",server.getBoundPort(),room,LobbyConfiguration.Mode.JOIN));
            host.enableGameplay();peer.enableGameplay();guests.add(peer);
            for(int i=2;i<count;i++) { var guest=new LobbyClient(new LobbyConfiguration("127.0.0.1",server.getBoundPort(),room,LobbyConfiguration.Mode.JOIN));guest.enableGameplay();guests.add(guest); }
            host.startAsync();step=1;
        } else if(step==1 && host.getSnapshot().canReady()) {
            openOnlineLobby(host);for(var guest:guests) { guest.startAsync(); }step=2;frames=0;
        } else if(step==2 && frames>5 && host.getSettings()!=null && host.getSettings().members().size()==count) {
            capture("room");click("Luca");click("Sẵn sàng  [Enter]");step=3;frames=0;
        } else if(step==3 && frames>8 && host.getSnapshot().phase()==LobbyClient.Phase.WAITING_FOR_START) {
            if(!"Luca".equals(host.getSnapshot().selectedCharacter())) { throw new IllegalStateException("Character click was not applied"); }
            capture("room-ready");click("Đã sẵn sàng  ·  Bấm để hủy");step=8;frames=0;
        } else if(step==8 && frames>3 && host.getSnapshot().canReady()) {
            click("Viktor");click("Sẵn sàng  [Enter]");step=9;frames=0;
        } else if(step==9 && frames>3 && host.getSnapshot().phase()==LobbyClient.Phase.WAITING_FOR_START) {
            if(!"Viktor".equals(host.getSnapshot().selectedCharacter())) { throw new IllegalStateException("Character reselection was not applied"); }
            step=4;frames=0;Gdx.graphics.setWindowedMode(960,540);
        } else if(step==4 && frames>5) {
            capture("room-small");step=5;frames=0;Gdx.graphics.setWindowedMode(1280,720);
            String[] characters={"Viktor","Luca","Leo"};for(int i=0;i<guests.size();i++) { guests.get(i).ready(characters[i]); }
        } else if(step==5 && host.getSnapshot().isMatchStarted()) { step=6;frames=0; }
        else if(step==6 && frames>20) { capture("gameplay");for(var guest:guests) { guest.close(); }step=7;frames=0; }
        else if(step==7 && frames>10 && getScreen().getClass().getSimpleName().equals("OnlineResultScreen")) { capture("result");Gdx.app.exit(); }
    }
    @Override public void dispose() {
        for(var guest:guests) { guest.close(); }super.dispose();if(server!=null) { server.stop(); }
    }
}
