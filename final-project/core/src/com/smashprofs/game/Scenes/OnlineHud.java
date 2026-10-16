package com.smashprofs.game.Scenes;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.smashprofs.game.network.client.*;
import java.util.*;

/** Online HUD reads server values only. It has no local HP, countdown, winner or testWin logic. */
public final class OnlineHud implements AutoCloseable {
    private final Stage stage;
    private final BitmapFont font = new BitmapFont();
    private final Map<String, Label> players = new LinkedHashMap<>();
    private final Label status, timer;
    private final Label spectator;
    private final Label[] feed=new Label[12];
    private final Map<String,Integer> slots=new HashMap<>();
    private final String localId;
    public OnlineHud(SpriteBatch batch, GameStartRoster roster, String localId) {
        this.localId = localId;
        stage = new Stage(new FitViewport(960, 540), batch);
        var style = new Label.LabelStyle(font, Color.WHITE);
        var table = new Table(); table.setFillParent(true); table.top().pad(12);
        for (var member : roster.players()) {
            String id = member.playerId().value();
            slots.put(id,member.slotIndex());
            var label = new Label("Slot " + member.slotIndex() + " | " + member.characterId()
                    + (id.equals(localId) ? " | YOU" : "") + "\nWaiting for state", style);
            label.setColor(id.equals(localId) ? Color.CYAN : Color.WHITE);
            label.setWrap(true);
            players.put(id, label);
            table.add(label).width(920f / roster.players().size()).top();
        }
        timer = new Label("Waiting for authoritative GAME_STATE", style);
        status = new Label("", style); status.setWrap(true);
        table.row(); table.add(timer).colspan(players.size()).padTop(10);
        table.row(); table.add(status).colspan(players.size()).width(920).padTop(6);
        spectator=new Label("",new Label.LabelStyle(font,new Color(1,.3f,.3f,.75f)));
        table.row();table.add(spectator).colspan(players.size()).padTop(6);
        for(int i=0;i<feed.length;i++) { feed[i]=new Label("",style);table.row();table.add(feed[i]).colspan(players.size()).right().padTop(3); }
        table.row(); table.add().colspan(players.size()).expandY();
        var controls = new Label("A/D move  W jump  S stomp  V/Space melee  F ability  Left Ctrl block\nEsc exit  F9 disconnect", style);
        table.row(); table.add(controls).colspan(players.size()).padBottom(5);
        stage.addActor(table);
    }
    public void update(StateInterpolator.V1.Frame frame, String connectionStatus, boolean connected) {
        update(frame,connectionStatus,connected,null);
    }
    public void update(StateInterpolator.V1.Frame frame, String connectionStatus, boolean connected,ClientMatchSession session) {
        if (frame != null) {
            var latest = frame.authoritative();
            for (var p : latest.players()) {
                String extra="";
                if(latest.gameplay()!=null) {
                    var progress=latest.gameplay().player(p.playerId());
                    String ability=progress.status().equals("ACTIVE")?(progress.ultimateTicks()==0?"READY!":(progress.ultimateTicks()+59)/60+"s"):"LOCKED";
                    extra="\nLives: "+progress.lives()+" | "+progress.status()+"\n[F: "+ability+"] | "+progress.weapon()
                            +(progress.shield()?" | SHIELD":"")+(progress.invulnerableTicks()>0?" | INVULNERABLE":"")
                            +(progress.respawnTicks()>0?" | Respawn "+String.format(Locale.ROOT,"%.1fs",progress.respawnTicks()/60f):"");
                    var ping=session==null?null:session.getPings().get(p.playerId());
                    extra+="\nRTT: "+(!connected || ping!=null && !ping.connected()?"disconnected":ping==null || ping.milliseconds()==null?"unknown":String.format(Locale.ROOT,"%.0fms%s",ping.milliseconds(),ping.milliseconds()>100?" (Lag)":""));
                }
                players.get(p.playerId()).setText("Slot " + p.slotIndex() + " | " + p.characterId()
                        + (p.playerId().equals(localId) ? " | YOU" : "") + "\n" + Float.toString(p.hp())
                        + " HP" + (p.dead() ? " | DEAD" : p.blocking() ? " | BLOCK" : "")
                        + extra);
                if(latest.gameplay()!=null) {
                    var progress=latest.gameplay().player(p.playerId());
                    players.get(p.playerId()).setColor(progress.lives()==0?Color.GRAY:progress.ultimateTicks()==0?Color.CYAN:Color.LIGHT_GRAY);
                }
            }
            timer.setText(latest.phase() + " | " + (latest.gameplay()!=null && latest.gameplay().rules().matchSeconds()==0?"INFINITE":String.format(Locale.ROOT, "%.2f s", latest.remainingTimeMs() / 1000.0)));
        }
        spectator.setText(session!=null && session.isSpectating()?"NO LIVES LEFT - SPECTATING":"");
        var entries=session==null?java.util.List.<KillFeedModel.Entry>of():session.getKillFeed().entries();
        for(int i=0;i<feed.length;i++) {
            if(i>=entries.size()) { feed[i].setText("");continue; }
            var entry=entries.get(i);var event=entry.event();
            String victim=event.victim()==null?"":"P"+(slots.getOrDefault(event.victim(),0)+1);
            String killer=event.killer()==null?"":"P"+(slots.getOrDefault(event.killer(),0)+1)+" -> ";
            String text=switch(event.type()) {
                case "PLAYER_FELL"->killer+victim+" fell into lava";
                case "PLAYER_KILLED"->killer+victim+" K.O. ["+event.weapon()+"]";
                case "PLAYER_RESPAWNED"->victim+" respawned";
                case "PLAYER_ELIMINATED"->victim+" eliminated - spectating";
                default->"MATCH END";
            };
            feed[i].setText(text);feed[i].setColor(1,.85f,.5f,entry.alpha());
        }
        status.setText(connectionStatus);
        status.setColor(connected ? Color.WHITE : Color.SALMON);
    }
    public void draw(float delta) { stage.getViewport().apply(); stage.act(delta); stage.draw(); }
    public void resize(int width, int height) { stage.getViewport().update(width, height, true); }
    @Override public void close() { stage.dispose(); font.dispose(); }
}
