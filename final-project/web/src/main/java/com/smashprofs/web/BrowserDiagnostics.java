package com.smashprofs.web;
import com.smashprofs.game.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.*;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.smashprofs.web.json.*;
import org.teavm.jso.JSBody;
/** Read-only diagnostics for real browser integration checks. No gameplay mutation. */
public final class BrowserDiagnostics {
    private static String observedMatch="";
    private static int jumpInputs;
    private static double jumpBase,jumpRise;
    private static long jumpUntil;
    private static String previousWeapon="";
    private static int previousAmmo=-1,previousLives=-1,gunShots;
    public static void update(Game game) {
        var lobby=game.getLobbyClient();
        if(game.getScreen() instanceof com.smashprofs.game.Screens.OnlineMatchmakingScreen quick) { lobby=quick.getLobbyClient(); }
        var snap=lobby==null?null:lobby.getSnapshot();
        var session=lobby==null?null:lobby.getMatchSession();
        var state=session==null?null:session.getSnapshotBuffer().getLatestSnapshot();
        var local=state==null?null:state.player(session.getLocalPlayerId().value());
        var ui=new ArrayNode();
        if(Gdx.input.getInputProcessor() instanceof Stage stage) { actors(stage.getRoot(),stage,ui); }
        publish(game.getScreen()==null?"":game.getScreen().getClass().getSimpleName(),snap==null?"":snap.phase().name(),
            snap==null?"":snap.roomId(),snap==null||snap.localPlayerId()==null?"":snap.localPlayerId().value(),
            session==null?"":session.getRoster().matchId(),state==null?0:state.players().size(),
            local==null?0:local.x(),local==null?0:local.y(),local==null?0:local.hp(),
            state==null?0:state.serverTick(),state==null||state.gameplay()==null||state.gameplay().boss()==null?-1:state.gameplay().boss().hp(),
            snap==null||snap.errorCode()==null?"":snap.errorCode());
        var info=new ObjectNode().set("ui",ui);
        if(game.getScreen() instanceof com.smashprofs.game.Screens.PlayScreen play && play.playerOne!=null) {
            var position=play.playerOne.getB2dbody().getPosition();
            info.put("offlineX",position.x).put("offlineY",position.y)
                .put("physicsBodies",com.smashprofs.game.Screens.PlayScreen.world.getBodyCount());
        }
        if(session!=null && !observedMatch.equals(session.getRoster().matchId())) {
            observedMatch=session.getRoster().matchId();jumpInputs=0;jumpRise=0;jumpUntil=0;
            previousWeapon="";previousAmmo=-1;previousLives=-1;gunShots=0;
        }
        if(local!=null) {
            int jump=com.smashprofs.game.Helper.KeyBindingConfig.getInstance().getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.JUMP);
            if(Gdx.input.isKeyJustPressed(jump)) { jumpInputs++;jumpBase=local.y();jumpUntil=state.serverTick()+60; }
            if(state.serverTick()<=jumpUntil) { jumpRise=Math.max(jumpRise,local.y()-jumpBase); }
            info.put("jumpInputs",jumpInputs).put("jumpRise",jumpRise);
            var roster=info.putArray("roster");
            for(var p:state.players()) { roster.addObject().put("playerId",p.playerId()).put("x",p.x()).put("y",p.y()).put("hp",p.hp()); }
        }
        if(state!=null && state.gameplay()!=null) {
            var p=state.gameplay().player(session.getLocalPlayerId().value());
            if(p.weapon().equals("LASER") && previousWeapon.equals(p.weapon()) && p.ammo()<previousAmmo && p.lives()==previousLives
                && p.status().equals("ACTIVE") && state.gameplay().fighter()!=null && state.gameplay().fighter().player(p.playerId()).attack().equals("NONE")) { gunShots++; }
            previousWeapon=p.weapon();previousAmmo=p.ammo();previousLives=p.lives();
            info.put("weapon",p.weapon()).put("ammo",p.ammo()).put("lives",p.lives()).put("lifeState",p.status());
            info.put("gunShots",gunShots);
            if(state.gameplay().fighter()!=null) { info.put("attack",state.gameplay().fighter().player(p.playerId()).attack()); }
        }
        extra(info.toString());
    }
    private static void actors(Actor actor,Stage stage,ArrayNode out) {
        if(!actor.isVisible()) { return; }
        if(actor instanceof Button || actor instanceof TextField || actor instanceof SelectBox || actor instanceof Label || actor.getName()!=null) {
            String text=actor instanceof TextButton b?b.getText().toString():actor instanceof TextField f?f.getText():actor instanceof Label l?l.getText().toString():actor instanceof SelectBox s?String.valueOf(s.getSelected()):"";
            var point=stage.stageToScreenCoordinates(actor.localToStageCoordinates(new Vector2(actor.getWidth()/2,actor.getHeight()/2)));
            out.addObject().put("type",actor.getClass().getSimpleName()).put("text",text).put("name",actor.getName()).put("x",point.x).put("y",point.y)
                .put("width",actor.getWidth()).put("height",actor.getHeight()).put("disabled",actor instanceof Button b&&b.isDisabled());
        }
        if(actor instanceof com.badlogic.gdx.scenes.scene2d.ui.List<?> list) {
            for(int i=0;i<list.getItems().size;i++) {
                float padding=list.getStyle().background==null?0:list.getStyle().background.getTopHeight();
                var point=stage.stageToScreenCoordinates(actor.localToStageCoordinates(new Vector2(actor.getWidth()/2,actor.getHeight()-padding-(i+.5f)*list.getItemHeight())));
                out.addObject().put("type","SelectOption").put("text",String.valueOf(list.getItems().get(i))).put("x",point.x).put("y",point.y).put("disabled",false);
            }
        }
        if(actor instanceof Group group) { for(var child:group.getChildren()) { actors(child,stage,out); } }
    }
    @JSBody(params={"text"},script="Object.assign(window.smashprofsWeb,JSON.parse(text));") private static native void extra(String text);
    @JSBody(params={"screen","phase","room","playerId","matchId","players","x","y","hp","tick","bossHp","error"},script="window.smashprofsWeb={screen:screen,phase:phase,room:room,playerId:playerId,matchId:matchId,players:players,x:x,y:y,hp:hp,tick:tick,bossHp:bossHp,error:error};")
    private static native void publish(String screen,String phase,String room,String playerId,String matchId,int players,double x,double y,double hp,double tick,double bossHp,String error);
}
