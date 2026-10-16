package com.smashprofs.game.Screens;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.scenes.scene2d.*;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.smashprofs.game.Game;
import com.smashprofs.game.network.client.*;

/** Create/join UI. All GL operations remain on the render thread. */
public final class OnlineLobbyScreen implements Screen {
    private final Game game;
    private final SpriteBatch batch = new SpriteBatch();
    private final Stage stage = new Stage(new FitViewport(960, 540), batch);
    private final Skin skin = new Skin();
    private final Texture background = new Texture("mainmenu/bgmenu.png");
    private final OnlineLobbyFormModel model = new OnlineLobbyFormModel();
    private final TextField host, port, room;
    private final TextButton create, join, back;
    private final Label status;
    private boolean disposed;

    public OnlineLobbyScreen(Game game) {
        this.game = game;
        var pixel = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixel.setColor(Color.WHITE); pixel.fill();
        skin.add("pixel", new Texture(pixel)); pixel.dispose();
        var font = new BitmapFont(); font.getData().setScale(1.15f); skin.add("body", font);
        var heading = new BitmapFont(); heading.getData().setScale(1.8f); skin.add("heading", heading);
        skin.add("default", new Label.LabelStyle(font, Color.valueOf("DDEAF3")));
        var field = new TextField.TextFieldStyle(); field.font = font; field.fontColor = Color.WHITE;
        field.background = skin.newDrawable("pixel", Color.valueOf("18374C"));
        field.focusedBackground = skin.newDrawable("pixel", Color.valueOf("255B74"));
        field.disabledBackground = skin.newDrawable("pixel", Color.valueOf("142B3A"));
        field.cursor = skin.newDrawable("pixel", Color.CYAN);
        field.selection = skin.newDrawable("pixel", Color.valueOf("417D9C"));
        field.background.setLeftWidth(12); field.background.setRightWidth(12);
        field.focusedBackground.setLeftWidth(12); field.focusedBackground.setRightWidth(12);
        skin.add("default", field);
        var button = new TextButton.TextButtonStyle(); button.font = font; button.fontColor = Color.WHITE;
        button.up = skin.newDrawable("pixel", Color.valueOf("21506A"));
        button.over = skin.newDrawable("pixel", Color.valueOf("347D9D"));
        button.down = skin.newDrawable("pixel", Color.valueOf("16394F"));
        button.disabled = skin.newDrawable("pixel", Color.valueOf("233542")); button.disabledFontColor = Color.GRAY;
        skin.add("default", button);
        host = new TextField("127.0.0.1", skin); host.setMaxLength(253);
        port = new TextField("54555", skin); port.setMaxLength(5);
        port.setTextFieldFilter((textField, character) -> character >= '0' && character <= '9');
        room = new TextField("demo", skin); room.setMaxLength(64);
        create = new TextButton("Create room", skin); join = new TextButton("Join room", skin);
        back = new TextButton("Back to menu", skin);
        status = new Label(model.getStatus(), skin); status.setWrap(true);
        var root = new Table(); root.setFillParent(true);
        var panel = new Table(); panel.pad(20); panel.defaults().left();
        panel.setBackground(skin.newDrawable("pixel", new Color(0.035f, 0.08f, 0.12f, 0.96f)));
        panel.add(new Label("ONLINE MULTIPLAYER", new Label.LabelStyle(heading, Color.CYAN))).colspan(2).padBottom(8); panel.row();
        panel.add(new Label("Create a room or join your friends.", skin)).colspan(2).padBottom(12); panel.row();
        panel.add(new Label("Server IP / hostname", skin)); panel.add(new Label("TCP port", skin)); panel.row();
        panel.add(host).width(330).height(36).padTop(6).padRight(14);
        panel.add(port).width(110).height(36).padTop(6); panel.row();
        panel.add(new Label("Room ID", skin)).colspan(2).padTop(14); panel.row();
        panel.add(room).colspan(2).growX().height(36).padTop(6); panel.row();
        panel.add(new Label("1-64 letters, digits, _ or -. Same room ID on both clients.", skin)).colspan(2).padTop(5); panel.row();
        var actions = new Table(); actions.defaults().height(40).growX();
        actions.add(create).padRight(12); actions.add(join);
        panel.add(actions).colspan(2).growX().padTop(14); panel.row();
        panel.add(status).colspan(2).width(470).height(40).padTop(8); panel.row();
        panel.add(back).colspan(2).growX().height(32).padTop(4);
        root.add(panel); stage.addActor(root);
        create.addListener(action(() -> connect(LobbyConfiguration.Mode.CREATE)));
        join.addListener(action(() -> connect(LobbyConfiguration.Mode.JOIN)));
        back.addListener(action(this::back));
    }
    private ChangeListener action(Runnable callback) {
        return new ChangeListener() { @Override public void changed(ChangeEvent event, Actor actor) { callback.run(); } };
    }
    private void connect(LobbyConfiguration.Mode mode) {
        model.connect(host.getText(), port.getText(), room.getText(), mode); refresh();
    }
    private void back() {
        if (model.isConnecting()) { model.cancel(); refresh(); }
        else { game.setScreen(new MainMenuScreen(game)); }
    }
    private void refresh() {
        boolean busy = model.isConnecting();
        host.setDisabled(busy); port.setDisabled(busy); room.setDisabled(busy);
        create.setDisabled(busy); join.setDisabled(busy);
        back.setText(busy ? "Cancel connection" : "Back to menu");
        status.setText(model.getStatus()); status.setColor(model.hasError() ? Color.SALMON : Color.valueOf("A8C8DD"));
    }
    @Override public void show() { Gdx.input.setInputProcessor(stage); stage.setKeyboardFocus(host); }
    @Override public void render(float delta) {
        if (disposed) { return; }
        var joined = model.takeJoinedClient();
        if (joined != null) { game.openOnlineLobby(joined); return; }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) { back(); if (disposed) { return; } }
        refresh(); ScreenUtils.clear(Color.BLACK); stage.getViewport().apply();
        batch.setProjectionMatrix(stage.getCamera().combined); batch.begin();
        batch.setColor(0.28f, 0.38f, 0.45f, 1); batch.draw(background, 0, 0, 960, 540); batch.setColor(Color.WHITE); batch.end();
        stage.act(delta); stage.draw();
    }
    @Override public void resize(int width, int height) { stage.getViewport().update(width, height, true); }
    @Override public void pause() { }
    @Override public void resume() { }
    @Override public void hide() { dispose(); }
    @Override public void dispose() {
        if (disposed) { return; } disposed = true; model.close();
        if (Gdx.input.getInputProcessor() == stage) { Gdx.input.setInputProcessor(null); }
        stage.dispose(); skin.dispose(); background.dispose(); batch.dispose();
    }
}
