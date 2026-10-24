package com.smashprofs.game.Screens;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.utils.*;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.smashprofs.game.Game;
import com.smashprofs.game.network.client.*;
import com.smashprofs.game.ui.OnlineUi;

/** Game mode choice and casual matchmaking pairing; transitions to room character selection once matched. */
public final class OnlineMatchmakingScreen implements Screen {
    private final Game game;
    private final String host, port;
    private final Stage stage = new Stage(new FitViewport(1280, 720));
    private final OnlineUi ui = new OnlineUi();
    private final SelectBox<String> mode, character;
    private final TextButton search, cancel;
    private final Label status, timer, modeDesc;
    private LobbyClient client;
    private float elapsed;
    private boolean disposed;

    public LobbyClient getLobbyClient() { return client; }

    public OnlineMatchmakingScreen(Game game, String host, String port, boolean boss) {
        this.game = game;
        this.host = host;
        this.port = port;

        mode = new SelectBox<>(ui.skin);
        mode.setItems("Đối kháng PvP · 1 đấu 1", "Hợp tác Boss · 2 người");
        mode.setSelectedIndex(boss ? 1 : 0);
        mode.setName("quick-mode");

        // Keep character selectbox hidden for backwards compatibility with tests and scripts
        character = new SelectBox<>(ui.skin);
        character.setItems("Alex", "Maurice", "Luca", "Leo", "Viktor");
        character.setName("quick-character");
        character.setVisible(false);

        modeDesc = ui.small("", OnlineUi.BRAWL_CYAN);
        modeDesc.setWrap(true);
        modeDesc.setAlignment(Align.center);
        updateModeDesc();

        mode.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                updateModeDesc();
            }
        });

        search = ui.comicButton("TÌM TRẬN", "comic-gold", this::search);
        search.setName("find-match");
        cancel = ui.comicButton("HỦY TÌM", "comic-white", this::cancel);
        cancel.setName("cancel-matchmaking");
        cancel.setDisabled(true);

        status = ui.small("Chọn thể loại chơi rồi bấm Tìm trận.", Color.WHITE);
        status.setWrap(true);
        status.setAlignment(Align.center);
        status.setName("matchmaking-status");
        timer = ui.small("", OnlineUi.BRAWL_CYAN);

        var root = new Table();
        root.setFillParent(true);
        root.pad(28);

        var back = ui.comicButton("< VỀ SẢNH", "comic-white", this::back);
        back.setName("matchmaking-back");
        root.add(back).left().height(44).row();

        root.add(ui.comicHeading("TÌM TRẬN NHANH", Color.WHITE)).padTop(14).row();
        root.add(ui.small("Chọn thể loại chơi · Sau khi ghép đủ 2 người sẽ vào phòng chọn nhân vật", OnlineUi.BRAWL_CYAN)).padTop(8).row();

        var panel = new Table();
        panel.setBackground(ui.comicBox(OnlineUi.BRAWL_DARK, OnlineUi.BRAWL_CYAN, 3, 4, 4));
        panel.pad(28);

        var modeIcon = ui.label("⚔️   VS   👹", Color.valueOf("FFE066"));
        modeIcon.setAlignment(Align.center);
        panel.add(modeIcon).padBottom(14).row();

        panel.add(ui.small("CHẾ ĐỘ THI ĐẤU:", OnlineUi.BRAWL_GOLD)).padBottom(8).row();
        panel.add(mode).width(400).height(44).padBottom(10).row();
        panel.add(modeDesc).width(460).height(42).padBottom(18).row();

        var buttons = new Table();
        buttons.add(search).width(200).height(48).padRight(12);
        buttons.add(cancel).width(150).height(48);
        panel.add(buttons).padTop(6).row();

        panel.add(status).width(490).height(55).padTop(14).row();
        panel.add(timer).padTop(4);

        root.add(panel).width(560).padTop(24).expandY().center();
        stage.addActor(root);
    }

    private void updateModeDesc() {
        if (mode.getSelectedIndex() == 1) {
            modeDesc.setText("Hợp tác 2 người chơi · Cùng sinh tồn và tiêu diệt Demon Slime Boss!");
            modeDesc.setColor(Color.valueOf("FF7A85"));
        } else {
            modeDesc.setText("Đấu trường đối kháng 1 vs 1 · 3 mạng · Thi đấu tính điểm Elo xếp hạng!");
            modeDesc.setColor(OnlineUi.BRAWL_CYAN);
        }
    }

    private void search() {
        if (client != null) { return; }
        try {
            var config = new LobbyConfiguration(host.strip(), Integer.parseInt(port.strip()), "matchmaking", LobbyConfiguration.Mode.JOIN);
            client = new LobbyClient(config);
            String chosenChar = character.getSelected() != null ? character.getSelected() : "Alex";
            client.enableMatchmaking(mode.getSelectedIndex() == 1, chosenChar);
            elapsed = 0;
            client.startAsync();
            status.setColor(Color.WHITE);
        } catch (IllegalArgumentException failure) {
            if (client != null) { client.close(); client = null; }
            status.setText("Không thể tìm trận. Kiểm tra địa chỉ và cổng máy chủ.");
            status.setColor(OnlineUi.DANGER);
        }
    }

    private void cancel() {
        if (client != null) { client.close(); client = null; }
        elapsed = 0;
        status.setText("Đã hủy tìm trận. Bạn có thể chọn lại thể loại chơi.");
        status.setColor(Color.WHITE);
    }

    private void back() {
        cancel();
        game.setScreen(new OnlineLobbyScreen(game));
    }

    public void show() { Gdx.input.setInputProcessor(stage); }

    public void render(float delta) {
        if (disposed) { return; }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) { back(); return; }
        if (client != null) {
            elapsed += delta;
            var state = client.getSnapshot();
            if (state.phase() == LobbyClient.Phase.ERROR || state.phase() == LobbyClient.Phase.DISCONNECTED) {
                String error = OnlineRoomPresentation.error(state.errorCode(), state.statusText());
                client.close(); client = null;
                status.setText(error); status.setColor(OnlineUi.DANGER);
            } else if (state.members().size() >= 2) {
                // Đã ghép thành công đủ 2 người! Chuyển ngay vào phòng chọn nhân vật theo format phòng
                var found = client;
                client = null;
                game.openOnlineLobby(found);
                return;
            } else {
                status.setText(state.phase() == LobbyClient.Phase.CONNECTING || state.phase() == LobbyClient.Phase.JOINING
                        ? "Đang kết nối máy chủ…"
                        : (mode.getSelectedIndex() == 1 ? "Đang tìm đồng đội…" : "Đang tìm đối thủ…") + " 1/2\nGhép đủ 2 người sẽ vào phòng chọn nhân vật.");
            }
        }
        boolean searching = client != null;
        search.setDisabled(searching);
        cancel.setDisabled(!searching);
        mode.setDisabled(searching);
        timer.setText(searching ? "Đã tìm: " + (int)elapsed + " giây" : "");
        ScreenUtils.clear(OnlineUi.BRAWL_DEEP);
        stage.getViewport().apply();
        stage.act(delta);
        stage.draw();
    }

    public void resize(int width, int height) { stage.getViewport().update(width, height, true); }
    public void pause() { }
    public void resume() { }
    public void hide() { dispose(); }
    public void dispose() {
        if (disposed) { return; }
        disposed = true;
        if (client != null) { client.close(); client = null; }
        if (Gdx.input.getInputProcessor() == stage) { Gdx.input.setInputProcessor(null); }
        stage.dispose();
        ui.close();
    }
}
