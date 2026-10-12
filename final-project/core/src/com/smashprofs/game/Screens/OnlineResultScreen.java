package com.smashprofs.game.Screens;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.*;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.smashprofs.game.network.client.WireMatchResult;

/** Presentation of an already validated immutable v1 result; offline WinScreen stays unchanged. */
final class OnlineResultScreen implements Screen {
    private final SpriteBatch batch = new SpriteBatch();
    private final Stage stage = new Stage(new FitViewport(960, 540), batch);
    private final BitmapFont font = new BitmapFont();
    private final OnlineSpriteAssets assets = new OnlineSpriteAssets();
    private boolean disposed;
    OnlineResultScreen(WireMatchResult result, String localId) {
        var style = new Label.LabelStyle(font, Color.WHITE);
        var table = new Table(); table.setFillParent(true); table.pad(20);
        table.background(new TextureRegionDrawable(assets.texture("winscreen/winbg.png")));
        table.add(new Image(assets.texture("winscreen/pokal.png"))).size(75, 90).padBottom(12); table.row();
        var winner = result.winner();
        String title = result.isDraw() ? "DRAW" : "WINNER: " + winner.characterId() + " | slot " + winner.slotIndex()
                + (winner.playerId().equals(localId) ? " | YOU" : "");
        var heading = new Label(title, style); heading.setColor(Color.CYAN);
        table.add(heading).padBottom(10); table.row();
        table.add(new Label(result.roomId() + " | " + result.finishReason(), style)).padBottom(12); table.row();
        for (var p : result.participants()) {
            String text = "Slot " + p.slotIndex() + " | " + p.characterId() + (p.playerId().equals(localId) ? " | YOU" : "")
                    + " | " + Float.toString(p.finalHp()) + " HP" + (p.eliminated() ? " | ELIMINATED" : "")
                    + " | " + p.playerId().substring(0, Math.min(8, p.playerId().length()));
            table.add(new Label(text, style)).padBottom(8); table.row();
        }
        var buttonStyle = new TextButton.TextButtonStyle(); buttonStyle.font = font; buttonStyle.fontColor = Color.WHITE;
        var exit = new TextButton("Exit (Esc)", buttonStyle);
        exit.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, com.badlogic.gdx.scenes.scene2d.Actor actor) { Gdx.app.exit(); }
        });
        table.add(exit).padTop(20); stage.addActor(table);
    }
    @Override public void show() { Gdx.input.setInputProcessor(stage); }
    @Override public void render(float delta) {
        if (disposed) { return; }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) { Gdx.app.exit(); }
        ScreenUtils.clear(Color.BLACK); stage.getViewport().apply(); stage.act(delta); stage.draw();
    }
    @Override public void resize(int width, int height) { stage.getViewport().update(width, height, true); }
    @Override public void pause() { }
    @Override public void resume() { }
    @Override public void hide() { dispose(); }
    @Override public void dispose() {
        if (disposed) { return; } disposed = true;
        if (Gdx.input.getInputProcessor() == stage) { Gdx.input.setInputProcessor(null); }
        stage.dispose(); font.dispose(); assets.close(); batch.dispose();
    }
}
