package com.smashprofs.game.Screens;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.smashprofs.game.Game;
import com.smashprofs.game.network.client.*;
import com.smashprofs.game.ui.OnlineUi;
import java.util.ArrayList;

/** Brawlhalla comic-style custom game room connection screen. */
public final class OnlineLobbyScreen implements Screen {
    private final Game game;
    private final Stage stage = new Stage(new FitViewport(1280, 720));
    private final OnlineUi ui = new OnlineUi();
    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final OnlineLobbyFormModel model = new OnlineLobbyFormModel();
    private final TextField address, port, code;
    private final TextButton create, join, back;
    private final SelectBox<String> gameMode;
    private final Label status, serverBadge;
    private final Texture bgTexture;
    private final BitmapFont watermarkFont;
    private final ArrayList<Texture> textures = new ArrayList<>();
    private boolean disposed;

    public OnlineLobbyScreen(Game game) {
        this.game = game;

        // --- Background & Watermark ---
        bgTexture = new Texture(Gdx.files.internal("mainmenu/bgmenu.png"));
        textures.add(bgTexture);

        watermarkFont = new BitmapFont(Gdx.files.internal("ui/online/heading.fnt"));
        watermarkFont.getData().setScale(2.6f);

        // --- Mascot character sprites ---
        var alexPunchTex = new Texture(Gdx.files.internal("Sprites/Alex/alex_punch.png"));
        var leoTex = new Texture(Gdx.files.internal("Sprites/Leo/leo_stand.png"));
        var lucaTex = new Texture(Gdx.files.internal("Sprites/Luca/luca_stand.png"));
        alexPunchTex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        leoTex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        lucaTex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        textures.add(alexPunchTex); textures.add(leoTex); textures.add(lucaTex);

        // Frame 2 of punch (100x100) shows Alex lunging forward with extended fist!
        var alexImg = new Image(new TextureRegion(alexPunchTex, 100, 0, 100, 100));
        var leoImg = new Image(new TextureRegion(leoTex, 0, 0, 100, 100));
        var lucaImg = new Image(new TextureRegion(lucaTex, 0, 0, 100, 100));

        // --- Text Fields with Comic styling ---
        var savedServer=Gdx.app.getPreferences("online-server");
        address = new TextField(System.getProperty("smashprofs.server.host",savedServer.getString("host","127.0.0.1")), ui.skin, "comic");
        address.setMaxLength(253);
        address.setAlignment(Align.center);

        port = new TextField(System.getProperty("smashprofs.server.port",savedServer.getString("port","54555")), ui.skin, "comic");
        port.setMaxLength(5);
        port.setAlignment(Align.center);
        port.setTextFieldFilter((field, c) -> c >= '0' && c <= '9');

        code = new TextField("", ui.skin, "comic");
        code.setMaxLength(64);
        code.setMessageText("MÃ PHÒNG (VD: ABCD)");
        code.setAlignment(Align.center);
        code.setTextFieldFilter((field, c) -> Character.isLetterOrDigit(c) && c < 128 || c == '-' || c == '_');

        gameMode=new SelectBox<>(ui.skin);
        gameMode.setItems("Đối kháng PvP", "Hợp tác đánh Boss");
        // --- Action Buttons ---
        create = ui.comicButton("TẠO TRẬN ĐẤU", "comic-gold", () -> connect(LobbyConfiguration.Mode.CREATE));
        join = ui.comicButton("VÀO PHÒNG", "comic-primary", () -> connect(LobbyConfiguration.Mode.JOIN));
        back = ui.comicButton("<", "comic-white", this::back);

        // --- Custom Nordic diamond texture for title ornaments ---
        var diamondPix = new Pixmap(17, 16, Pixmap.Format.RGBA8888);
        diamondPix.setColor(OnlineUi.BRAWL_CYAN);
        for (int y = 0; y < 16; y++) {
            int dx = y <= 7 ? y : 15 - y;
            diamondPix.drawLine(8 - dx, y, 8 + dx, y);
        }
        var diamondTex = new Texture(diamondPix);
        diamondPix.dispose();
        textures.add(diamondTex);

        status = ui.small("Mở server trước, sau đó tạo phòng hoặc nhập mã để chơi cùng bạn bè.", Color.WHITE);
        serverBadge = ui.small("127.0.0.1:54555", OnlineUi.BRAWL_CYAN);

        // --- Root Layout ---
        var root = new Table();
        root.setFillParent(true);
        root.pad(18, 36, 14, 36);

        // ================= HEADER BAR =================
        var header = new Table();

        // 1. Back button (angled square badge with black arrow)
        var backContainer = new Table();
        backContainer.setTransform(true);
        backContainer.setOrigin(Align.center);
        backContainer.setRotation(-3f);
        backContainer.add(back).size(52, 52);
        header.add(backContainer).size(54, 54).left();

        // 2. Title with Nordic / Viking Wing Ornaments
        var titleBox = new Table();
        var wingsRow = new Table();
        var leftWing = createNordicWing(diamondTex, false);
        var mainTitle = ui.comicHeading("CUSTOM GAME ROOM", Color.WHITE);
        var rightWing = createNordicWing(diamondTex, true);
        wingsRow.add(leftWing).padRight(14);
        wingsRow.add(mainTitle);
        wingsRow.add(rightWing).padLeft(14);

        var sub = ui.small("SẢNH ĐẤU ONLINE  ·  2-4 NGƯỜI CHƠI  ·  MỖI NGƯỜI MỘT MÁY", Color.valueOf("C8E6FF"));
        titleBox.add(wingsRow).row();
        titleBox.add(sub).padTop(4);
        header.add(titleBox).expandX().center();

        // 3. Right placeholder matching back button size to ensure title is mathematically centered
        header.add().size(54, 54).right();

        root.add(header).growX();
        root.row();

        // ================= 3 COMIC ACTION CARDS =================
        var cards = new Table();
        cards.defaults().top();

        // ---------- CARD 1: CREATE ROOM (HERO ACTIVE CARD) ----------
        var card1 = new Table();
        card1.setTransform(true);
        card1.setOrigin(Align.center);
        card1.setRotation(-1.5f);
        card1.setBackground(ui.comicBox(OnlineUi.BRAWL_DARK, Color.valueOf("00FFA2"), 4.5f, 8, 10));
        card1.pad(10, 10, 8, 10);

        // Ribbon Title Badge
        var c1Ribbon = new Table();
        c1Ribbon.setBackground(ui.box("000000", 6));
        var c1RibbonTitle = ui.comicHeading("CREATE ROOM", Color.WHITE);
        c1Ribbon.add(c1RibbonTitle).pad(2, 14, 2, 14);
        card1.add(c1Ribbon).left().padTop(2);
        card1.row();

        // Fighter Mascot Podium with fiery red & crimson flame backdrop
        var aura1 = new Table();
        aura1.setBackground(ui.comicBox(Color.valueOf("A61625"), Color.valueOf("FF3B50"), 3.5f, 4, 4));
        aura1.add(alexImg).size(185, 185).center();
        card1.add(aura1).size(320, 205).center().padTop(6).padBottom(8);
        card1.row();

        // Bottom High-Contrast Neon Mint / Cyan Panel
        var c1Bottom = new Table();
        c1Bottom.setBackground(ui.comicBox(OnlineUi.BRAWL_CYAN, OnlineUi.BRAWL_BORDER, 3.5f, 0, 0));
        c1Bottom.pad(10, 12, 10, 12);
        c1Bottom.add(gameMode).growX().height(34).padBottom(7);c1Bottom.row();
        c1Bottom.add(create).growX().height(52);
        c1Bottom.row();
        var c1Desc = ui.small("Tự sinh mã phòng  ·  Tùy chỉnh luật đấu", Color.valueOf("052B28"));
        c1Bottom.add(c1Desc).padTop(6).center();

        card1.add(c1Bottom).growX().expandY().bottom();

        // ---------- CARD 2: JOIN ROOM ----------
        var card2 = new Table();
        card2.setBackground(ui.comicBox(OnlineUi.BRAWL_BLUE, OnlineUi.BRAWL_BORDER, 4.5f, 8, 10));
        card2.pad(10, 10, 8, 10);

        // Ribbon Title Badge
        var c2Ribbon = new Table();
        c2Ribbon.setBackground(ui.box("000000", 6));
        var c2RibbonTitle = ui.comicHeading("JOIN ROOM", Color.WHITE);
        c2Ribbon.add(c2RibbonTitle).pad(2, 14, 2, 14);
        card2.add(c2Ribbon).left().padTop(2);
        card2.row();

        // Warrior Mascot Podium with electric royal blue aura
        var aura2 = new Table();
        aura2.setBackground(ui.comicBox(Color.valueOf("004AA5"), Color.valueOf("35B4FF"), 3.5f, 4, 4));
        aura2.add(leoImg).size(185, 185).center();
        card2.add(aura2).size(320, 205).center().padTop(6).padBottom(8);
        card2.row();

        // Bottom Deep Royal Blue Panel
        var c2Bottom = new Table();
        c2Bottom.setBackground(ui.comicBox(Color.valueOf("004899"), OnlineUi.BRAWL_BORDER, 3.5f, 0, 0));
        c2Bottom.pad(8, 12, 10, 12);
        c2Bottom.add(code).growX().height(42).padBottom(6);
        c2Bottom.row();
        c2Bottom.add(join).growX().height(48);
        c2Bottom.row();
        var c2Desc = ui.small("Nhập mã 4 ký tự do chủ phòng gửi", Color.valueOf("E0F0FF"));
        c2Bottom.add(c2Desc).padTop(6).center();

        card2.add(c2Bottom).growX().expandY().bottom();

        // ---------- CARD 3: SERVER HOST SETTINGS ----------
        var card3 = new Table();
        card3.setTransform(true);
        card3.setOrigin(Align.center);
        card3.setRotation(1.5f);
        card3.setBackground(ui.comicBox(OnlineUi.BRAWL_DEEP, OnlineUi.BRAWL_BORDER, 4.5f, 8, 10));
        card3.pad(10, 10, 8, 10);

        // Ribbon Title Badge
        var c3Ribbon = new Table();
        c3Ribbon.setBackground(ui.box("000000", 6));
        var c3RibbonTitle = ui.comicHeading("SERVER HOST", Color.WHITE);
        c3Ribbon.add(c3RibbonTitle).pad(2, 14, 2, 14);
        card3.add(c3Ribbon).left().padTop(2);
        card3.row();

        // Tech Mascot Podium with deep navy cyber aura
        var aura3 = new Table();
        aura3.setBackground(ui.comicBox(Color.valueOf("032350"), Color.valueOf("4888D4"), 3.5f, 4, 4));
        aura3.add(lucaImg).size(185, 185).center();
        card3.add(aura3).size(320, 205).center().padTop(6).padBottom(8);
        card3.row();

        // Bottom Deep Navy Panel
        var c3Bottom = new Table();
        c3Bottom.setBackground(ui.comicBox(Color.valueOf("021A3D"), OnlineUi.BRAWL_BORDER, 3.5f, 0, 0));
        c3Bottom.pad(8, 12, 10, 12);

        var ipPortRow = new Table();
        ipPortRow.add(address).growX().height(42).padRight(8);
        ipPortRow.add(port).width(95).height(42);
        c3Bottom.add(ipPortRow).growX().padBottom(6);
        c3Bottom.row();

        var pingBadge = new Table();
        pingBadge.setBackground(ui.comicBox(Color.valueOf("051426"), OnlineUi.BRAWL_BORDER, 2, 1, 1));
        pingBadge.pad(4, 10, 4, 10);
        pingBadge.add(ui.small("MÁY CHỦ: ", Color.valueOf("94B8E6"))).padRight(4);
        pingBadge.add(serverBadge);
        c3Bottom.add(pingBadge).growX().height(38);
        c3Bottom.row();

        var c3Desc = ui.small("Nhập IP server · Tự nhớ sau khi kết nối", Color.valueOf("B8D8FF"));
        c3Bottom.add(c3Desc).padTop(6).center();

        card3.add(c3Bottom).growX().expandY().bottom();

        // Add 3 cards with generous spacing
        cards.add(card1).width(360).height(495).padRight(24);
        cards.add(card2).width(360).height(495).padRight(24);
        cards.add(card3).width(360).height(495);

        root.add(cards).padTop(10);
        root.row();

        // ================= FOOTER =================
        var footer = new Table();
        var hints = ui.small("[ESC] VỀ MENU   ·   [ENTER] VÀO PHÒNG", Color.valueOf("B8D8FF"));
        var version = ui.small("SUPER SMASH PROFS v1.0", OnlineUi.BRAWL_CYAN);

        footer.add(hints).left();
        footer.add(status).expandX().center();
        footer.add(version).right();

        root.add(footer).growX().padTop(10);

        stage.addActor(root);

        address.setTextFieldListener((field, c) -> updateBadge());
        port.setTextFieldListener((field, c) -> updateBadge());
        updateBadge();
    }

    private Table createNordicWing(Texture diamondTex, boolean right) {
        var wing = new Table();
        var line = new Image(ui.skin.newDrawable("pixel", OnlineUi.BRAWL_CYAN));
        var diamondLarge = new Image(diamondTex);
        var diamondSmall = new Image(diamondTex);

        if (right) {
            wing.add(diamondLarge).size(16, 16).padRight(6);
            wing.add(diamondSmall).size(10, 10).padRight(8);
            wing.add(line).width(65).height(3);
        } else {
            wing.add(line).width(65).height(3).padRight(8);
            wing.add(diamondSmall).size(10, 10).padRight(6);
            wing.add(diamondLarge).size(16, 16);
        }
        return wing;
    }

    private void updateBadge() {
        serverBadge.setText(address.getText().strip() + ":" + port.getText().strip());
    }

    private void connect(LobbyConfiguration.Mode mode) {
        String room = mode == LobbyConfiguration.Mode.CREATE ? OnlineRoomPresentation.newRoomCode() : code.getText().strip();
        if (mode == LobbyConfiguration.Mode.JOIN && room.isEmpty()) {
            status.setText("Hãy nhập mã phòng trước khi bấm Vào phòng.");
            status.setColor(OnlineUi.DANGER);
            return;
        }
        model.connect(address.getText(), port.getText(), room, mode,gameMode.getSelectedIndex()==1);
        refresh();
    }

    private void refresh() {
        boolean busy = model.isConnecting();
        address.setDisabled(busy);
        port.setDisabled(busy);
        code.setDisabled(busy);
        create.setDisabled(busy);gameMode.setDisabled(busy);
        join.setDisabled(busy);
        back.setDisabled(busy);
        status.setText(model.getStatus());
        status.setColor(model.hasError() ? OnlineUi.DANGER : Color.WHITE);
    }

    private void back() {
        if (model.isConnecting()) {
            model.cancel();
            refresh();
        } else {
            game.setScreen(new MainMenuScreen(game));
        }
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(stage);
        stage.setKeyboardFocus(code);
    }

    @Override
    public void render(float delta) {
        if (disposed) { return; }
        var client = model.takeJoinedClient();
        if (client != null) {
            Gdx.app.getPreferences("online-server").putString("host",address.getText().strip())
                    .putString("port",port.getText().strip()).flush();
            game.openOnlineLobby(client);
            return;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            back();
            if (disposed) { return; }
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER) && !model.isConnecting()) {
            connect(LobbyConfiguration.Mode.JOIN);
        }
        if (model.isConnecting() || model.hasError()) {
            refresh();
        }

        // 1. Clear with vibrant Brawlhalla Royal Blue
        ScreenUtils.clear(Color.valueOf("0060CF"));

        // 2. Draw Textured Background with atmospheric blue tint + Giant Watermark
        batch.setProjectionMatrix(stage.getCamera().combined);
        batch.begin();
        batch.setColor(0.08f, 0.32f, 0.72f, 0.40f);
        batch.draw(bgTexture, 0, 0, 1280, 720);
        batch.setColor(Color.WHITE);

        // Giant subtle background watermark text: "CUSTOM GAME ROOM"
        watermarkFont.setColor(0f, 0.28f, 0.65f, 0.22f);
        watermarkFont.draw(batch, "CUSTOM GAME ROOM", 50, 430);

        batch.end();

        // 3. Draw Jagged White Lightning Shards & Cyan Corners (Matching Reference Image 2)
        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(stage.getCamera().combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        // --- Top-Left Sky Cyan Corner ---
        shapes.setColor(Color.valueOf("2BD9FFE8"));
        shapes.triangle(0, 480, 0, 720, 440, 720);
        shapes.triangle(0, 480, 440, 720, 280, 620);

        // --- Top-Left Jagged White Lightning Slash ---
        shapes.setColor(1f, 1f, 1f, 0.98f);
        // Spiky lightning jagged polygons cutting along the cyan edge
        shapes.triangle(0, 480, 60, 520, 20, 530);
        shapes.triangle(20, 530, 160, 580, 110, 600);
        shapes.triangle(110, 600, 290, 640, 240, 660);
        shapes.triangle(240, 660, 420, 700, 370, 720);
        shapes.triangle(370, 720, 470, 720, 280, 620);

        // --- Bottom-Right Sky Cyan Corner ---
        shapes.setColor(Color.valueOf("2BD9FFE8"));
        shapes.triangle(1280, 0, 840, 0, 1280, 260);
        shapes.triangle(840, 0, 1040, 130, 1280, 260);

        // --- Bottom-Right Jagged White Lightning Slash ---
        shapes.setColor(1f, 1f, 1f, 0.98f);
        shapes.triangle(830, 0, 890, 0, 940, 60);
        shapes.triangle(940, 60, 900, 50, 1070, 120);
        shapes.triangle(1070, 120, 1020, 110, 1200, 190);
        shapes.triangle(1200, 190, 1150, 180, 1280, 250);

        shapes.end();

        // 4. Draw Stage UI Elements
        stage.getViewport().apply();
        stage.act(delta);
        stage.draw();
    }

    @Override
    public void resize(int w, int h) {
        stage.getViewport().update(w, h, true);
    }

    @Override public void pause() { }
    @Override public void resume() { }
    @Override public void hide() { dispose(); }

    @Override
    public void dispose() {
        if (disposed) { return; }
        disposed = true;
        model.close();
        if (Gdx.input.getInputProcessor() == stage) {
            Gdx.input.setInputProcessor(null);
        }
        stage.dispose();
        batch.dispose();
        shapes.dispose();
        watermarkFont.dispose();
        for (var tex : textures) {
            tex.dispose();
        }
        textures.clear();
        ui.close();
    }
}
