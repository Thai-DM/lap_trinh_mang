package com.smashprofs.game.Scenes;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.smashprofs.game.network.client.*;
import java.util.*;

/** Online HUD reads server values only. It has no local HP, countdown, winner or testWin logic. */
public final class OnlineHud implements AutoCloseable {
    private final Stage stage;
    private final BitmapFont font = new BitmapFont();
    private final Map<String, Label> players = new LinkedHashMap<>();
    private final Label status, timer, phaseLabel;
    private final String localId;

    public OnlineHud(SpriteBatch batch, GameStartRoster roster, String localId) {
        this.localId = localId;
        font.getData().setScale(0.95f);
        stage = new Stage(new FitViewport(960, 540), batch);
        var style = new Label.LabelStyle(font, Color.WHITE);

        var root = new Table();
        root.setFillParent(true);
        root.top().pad(12);

        var leftTable = new Table();
        var centerTable = new Table();
        var rightTable = new Table();

        var members = roster.players();
        for (int i = 0; i < members.size(); i++) {
            var member = members.get(i);
            String id = member.playerId().value();
            boolean isLocal = id.equals(localId);
            var label = new Label("Slot " + member.slotIndex() + " | " + member.characterId()
                    + (isLocal ? " (YOU)" : "") + "\nWaiting for state", style);
            label.setColor(isLocal ? Color.CYAN : Color.WHITE);
            players.put(id, label);

            if (i % 2 == 0) {
                label.setAlignment(Align.left);
                leftTable.add(label).left().padBottom(6).row();
            } else {
                label.setAlignment(Align.right);
                rightTable.add(label).right().padBottom(6).row();
            }
        }

        phaseLabel = new Label("BATTLE", style);
        phaseLabel.setColor(Color.GOLD);
        phaseLabel.setAlignment(Align.center);
        timer = new Label("120.0 s", style);
        timer.setAlignment(Align.center);
        centerTable.add(phaseLabel).center().row();
        centerTable.add(timer).center().padTop(2);

        status = new Label("", style);
        status.setAlignment(Align.right);
        rightTable.add(status).right().padTop(4);

        root.add(leftTable).expandX().left().top();
        root.add(centerTable).top().padLeft(10).padRight(10);
        root.add(rightTable).expandX().right().top();

        root.row();
        root.add().colspan(3).expandY();

        var controls = new Label("A/D Move  |  W Jump  |  S Stomp  |  V/Space Melee  |  F Ability  |  Ctrl Block  |  Esc Exit", style);
        controls.setColor(new Color(0.85f, 0.85f, 0.85f, 0.8f));
        controls.setAlignment(Align.center);
        root.row();
        root.add(controls).colspan(3).bottom().padBottom(6);

        stage.addActor(root);
    }

    public void update(StateInterpolator.V1.Frame frame, String connectionStatus, boolean connected) {
        if (frame != null) {
            var latest = frame.authoritative();
            for (var p : latest.players()) {
                var label = players.get(p.playerId());
                if (label != null) {
                    boolean isLocal = p.playerId().equals(localId);
                    String name = "Slot " + p.slotIndex() + " | " + p.characterId() + (isLocal ? " (YOU)" : "");
                    String stateTag = p.dead() ? " [K.O.]" : p.blocking() ? " [BLOCK]" : "";
                    String hp = String.format(Locale.ROOT, "HP: %.1f%s", p.hp(), stateTag);
                    label.setText(name + "\n" + hp);
                    label.setColor(p.dead() ? Color.SALMON : isLocal ? Color.CYAN : Color.WHITE);
                }
            }
            phaseLabel.setText(latest.phase());
            double remSec = Math.max(0, latest.remainingTimeMs() / 1000.0);
            timer.setText(String.format(Locale.ROOT, "%.1f s", remSec));
        }
        status.setText(connectionStatus);
        status.setColor(connected ? Color.CYAN : Color.SALMON);
    }

    public void draw(float delta) { stage.getViewport().apply(); stage.act(delta); stage.draw(); }
    public void resize(int width, int height) { stage.getViewport().update(width, height, true); }
    @Override public void close() { stage.dispose(); font.dispose(); }
}
