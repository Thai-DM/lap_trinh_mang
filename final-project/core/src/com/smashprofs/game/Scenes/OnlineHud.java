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
    private final String localId;
    public OnlineHud(SpriteBatch batch, GameStartRoster roster, String localId) {
        this.localId = localId;
        stage = new Stage(new FitViewport(960, 540), batch);
        var style = new Label.LabelStyle(font, Color.WHITE);
        var table = new Table(); table.setFillParent(true); table.top().pad(12);
        for (var member : roster.players()) {
            String id = member.playerId().value();
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
        table.row(); table.add().colspan(players.size()).expandY();
        var controls = new Label("A/D move  W jump  S stomp  V/Space melee  F ability  Left Ctrl block\nEsc exit  F9 disconnect", style);
        table.row(); table.add(controls).colspan(players.size()).padBottom(5);
        stage.addActor(table);
    }
    public void update(StateInterpolator.V1.Frame frame, String connectionStatus, boolean connected) {
        if (frame != null) {
            var latest = frame.authoritative();
            for (var p : latest.players()) {
                players.get(p.playerId()).setText("Slot " + p.slotIndex() + " | " + p.characterId()
                        + (p.playerId().equals(localId) ? " | YOU" : "") + "\n" + Float.toString(p.hp())
                        + " HP" + (p.dead() ? " | DEAD" : p.blocking() ? " | BLOCK" : "")
                        + "\n" + p.playerId().substring(0, Math.min(8, p.playerId().length())));
            }
            timer.setText(latest.phase() + " | " + String.format(Locale.ROOT, "%.2f s", latest.remainingTimeMs() / 1000.0));
        }
        status.setText(connectionStatus);
        status.setColor(connected ? Color.WHITE : Color.SALMON);
    }
    public void draw(float delta) { stage.getViewport().apply(); stage.act(delta); stage.draw(); }
    public void resize(int width, int height) { stage.getViewport().update(width, height, true); }
    @Override public void close() { stage.dispose(); font.dispose(); }
}
