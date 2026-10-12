package com.smashprofs.game.Screens;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.scenes.scene2d.*;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.*;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.smashprofs.game.Game;
import com.smashprofs.game.network.client.WireMatchResult;
import java.util.Locale;

/** Presentation of an already validated immutable v1 result; offline WinScreen stays unchanged. */
final class OnlineResultScreen implements Screen {
    private final Game game;
    private final WireMatchResult result;
    private final String localId;
    private final SpriteBatch batch = new SpriteBatch();
    private final Stage stage = new Stage(new FitViewport(960, 540), batch);
    private final BitmapFont font = new BitmapFont();
    private final OnlineSpriteAssets assets = new OnlineSpriteAssets();
    private Texture cardTexture;
    private boolean disposed;

    OnlineResultScreen(WireMatchResult result, String localId) {
        this(null, result, localId);
    }

    OnlineResultScreen(Game game, WireMatchResult result, String localId) {
        this.game = game;
        this.result = result;
        this.localId = localId;
        font.getData().setScale(0.95f);

        // Root table filling screen with map background
        var root = new Table();
        root.setFillParent(true);
        root.background(new TextureRegionDrawable(assets.texture("winscreen/winbg.png")));

        // Translucent dark card behind result content for high contrast and readability
        var card = new Table();
        var pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixmap.setColor(new Color(0.06f, 0.07f, 0.12f, 0.85f));
        pixmap.fill();
        cardTexture = new Texture(pixmap);
        pixmap.dispose();
        card.background(new TextureRegionDrawable(cardTexture));
        card.pad(16, 32, 16, 32);

        // Trophy icon
        var trophy = new Image(assets.texture("winscreen/pokal.png"));
        card.add(trophy).size(85, 105).padBottom(8);
        card.row();

        // Winner heading
        var winner = result.winner();
        boolean isDraw = result.isDraw();
        boolean isYouWinner = winner != null && winner.playerId().equals(localId);

        String title;
        Color titleColor;
        if (isDraw) {
            title = "DRAW MATCH";
            titleColor = Color.LIGHT_GRAY;
        } else if (isYouWinner) {
            title = "★ VICTORY ★\n" + winner.characterId() + " (YOU)";
            titleColor = Color.GOLD;
        } else {
            title = "WINNER: " + winner.characterId() + " (Slot " + winner.slotIndex() + ")";
            titleColor = Color.CYAN;
        }
        var heading = new Label(title, new Label.LabelStyle(font, titleColor));
        heading.setAlignment(Align.center);
        card.add(heading).padBottom(6);
        card.row();

        // Reason & Room subtitle
        String reasonDesc = switch (result.finishReason()) {
            case "LAST_PLAYER_STANDING" -> "Last Player Standing";
            case "TIME_LIMIT" -> "Time Limit Exceeded";
            case "ALL_PLAYERS_ELIMINATED" -> "Double K.O.";
            default -> result.finishReason();
        };
        var subLabel = new Label("Room: " + result.roomId() + "  |  " + reasonDesc, new Label.LabelStyle(font, Color.GRAY));
        subLabel.setAlignment(Align.center);
        card.add(subLabel).padBottom(12);
        card.row();

        // Scoreboard participants rows
        for (var p : result.participants()) {
            boolean isLocal = p.playerId().equals(localId);
            boolean isPWinner = winner != null && p.playerId().equals(winner.playerId());

            String tag = isPWinner ? " [WINNER]" : p.eliminated() ? " [K.O.]" : " [SURVIVED]";
            String rowText = String.format(Locale.ROOT, "Slot %d | %s%s | HP: %.1f%s",
                    p.slotIndex(), p.characterId(), isLocal ? " (YOU)" : "", p.finalHp(), tag);

            Color rowColor = isPWinner ? Color.GOLD : isLocal ? Color.CYAN : Color.WHITE;
            var rowLabel = new Label(rowText, new Label.LabelStyle(font, rowColor));
            rowLabel.setAlignment(Align.center);
            card.add(rowLabel).padBottom(5);
            card.row();
        }

        // Return to Menu button
        var menuBtn = new Image(assets.texture("winscreen/menuButtonInactive.png"));
        menuBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) { returnToMenu(); }
            @Override public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                menuBtn.setDrawable(new TextureRegionDrawable(assets.texture("winscreen/menuButtonActive.png")));
            }
            @Override public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                menuBtn.setDrawable(new TextureRegionDrawable(assets.texture("winscreen/menuButtonInactive.png")));
            }
        });
        card.add(menuBtn).size(160, 52).padTop(10);
        card.row();

        var tip = new Label("Press Esc or Click button to return to Menu", new Label.LabelStyle(font, Color.LIGHT_GRAY));
        tip.setAlignment(Align.center);
        card.add(tip).padTop(4);

        root.add(card).center();
        stage.addActor(root);
    }

    private void returnToMenu() {
        if (game != null) {
            try { game.getLobbyClient().close(); } catch (Exception ignored) {}
            game.setScreen(new MainMenuScreen(game));
        } else {
            Gdx.app.exit();
        }
    }

    @Override public void show() { Gdx.input.setInputProcessor(stage); }
    @Override public void render(float delta) {
        if (disposed) { return; }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) { returnToMenu(); }
        ScreenUtils.clear(Color.BLACK);
        stage.getViewport().apply();
        stage.act(delta);
        stage.draw();
    }
    @Override public void resize(int width, int height) { stage.getViewport().update(width, height, true); }
    @Override public void pause() { }
    @Override public void resume() { }
    @Override public void hide() { dispose(); }
    @Override public void dispose() {
        if (disposed) { return; } disposed = true;
        if (Gdx.input.getInputProcessor() == stage) { Gdx.input.setInputProcessor(null); }
        if (cardTexture != null) { cardTexture.dispose(); cardTexture = null; }
        stage.dispose(); font.dispose(); assets.close(); batch.dispose();
    }
}
