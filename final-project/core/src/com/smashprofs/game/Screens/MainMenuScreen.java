package com.smashprofs.game.Screens;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.smashprofs.game.Helper.KeyBindingConfig;
import com.smashprofs.game.Helper.Keys;
import com.smashprofs.game.Helper.SoundManager;
import com.smashprofs.game.Helper.gamePropertiesManager;
import com.smashprofs.game.ui.OnlineUi;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;

/**
 * Main menu screen redesigned to match the Brawlhalla comic format of
 * OnlineLobbyScreen, OnlineRoomScreen, and OnlineResultScreen.
 */
public class MainMenuScreen implements Screen {
    private static final Logger log = LogManager.getLogger(MainMenuScreen.class);

    private final Game game;
    private final OnlineUi ui = new OnlineUi();
    private final Stage stage = new Stage(new FitViewport(1280, 720));
    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final SoundManager sound = SoundManager.getSoundManager_INSTANCE();

    private final ArrayList<Texture> textures = new ArrayList<>();
    private final Texture bgTexture;
    private final Texture logoTexture;
    private final Table logoContainer;

    private Table settingsOverlay;
    private KeyBindingConfig.Action currentlyRebinding = null;
    private final Map<KeyBindingConfig.Action, TextButton> actionButtons = new EnumMap<>(KeyBindingConfig.Action.class);
    private Label waitingActionLabel;

    private float timer = 0f;
    private boolean disposed;

    public MainMenuScreen(Game game) {
        this.game = game;

        // 1. Load Textures
        bgTexture = new Texture(Gdx.files.internal("mainmenu/bgmenu.png"));
        logoTexture = new Texture(Gdx.files.internal("mainmenu/ssp.png"));
        textures.add(bgTexture);
        textures.add(logoTexture);

        // Character preview textures
        var alexTex = new Texture(Gdx.files.internal("Sprites/Alex/alex_stand.png"));
        var leoTex = new Texture(Gdx.files.internal("Sprites/Leo/leo_stand.png"));
        alexTex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        leoTex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        textures.add(alexTex);
        textures.add(leoTex);

        var alexRegion = new TextureRegion(alexTex, 0, 0, 100, 100);
        var leoRegion = new TextureRegion(leoTex, 0, 0, 100, 100);

        // --- ROOT LAYOUT ---
        var root = new Table();
        root.setFillParent(true);
        root.pad(16, 32, 14, 32);

        // ================= TOP LOGO & TAGLINE =================
        var topSection = new Table();

        logoContainer = new Table();
        logoContainer.setTransform(true);
        logoContainer.setOrigin(Align.center);
        var logoImage = new Image(logoTexture);
        logoContainer.add(logoImage).size(780, 66);
        topSection.add(logoContainer).center();
        topSection.row();

        var subtitleBadge = new Table();
        subtitleBadge.setBackground(ui.comicBox(Color.valueOf("061324"), OnlineUi.BRAWL_BORDER, 2f, 0, 0));
        subtitleBadge.pad(3, 18, 3, 18);
        var subTitle = ui.small("ĐẤU TRƯỜNG ĐỐI KHÁNG ĐỈNH CAO  ·  SUPER SMASH PROFS", Color.valueOf("94D2FF"));
        subtitleBadge.add(subTitle);
        topSection.add(subtitleBadge).center().padTop(6);

        root.add(topSection).growX().center();
        root.row();

        // ================= CENTER ROW (LEFT HERO - MENU CARD - RIGHT HERO) =================
        var centerRow = new Table();
        centerRow.defaults().top();

        // 1. Left Pedestal: ALEX
        var leftPedestal = new Table();
        leftPedestal.setBackground(ui.comicBox(OnlineUi.BRAWL_DARK, OnlineUi.BRAWL_BORDER, 3.5f, 4, 6));
        leftPedestal.pad(8, 8, 10, 8);

        var alexStage = new Table();
        var alexImg = new Image(alexRegion);
        alexStage.add(alexImg).size(150, 150).center().padBottom(-16);
        alexStage.row();

        var alexBase = new Table();
        alexBase.setBackground(ui.comicBox(Color.valueOf("004AA5"), Color.valueOf("00FFA2"), 2.5f, 1, 1));
        alexBase.add().size(185, 18);
        alexStage.add(alexBase).center();
        leftPedestal.add(alexStage).growX().padTop(6).padBottom(8);
        leftPedestal.row();

        var alexTablet = new Table();
        alexTablet.setBackground(ui.comicBox(Color.valueOf("061324"), OnlineUi.BRAWL_BORDER, 2f, 0, 0));
        alexTablet.pad(6, 8, 8, 8);
        alexTablet.add(ui.small("CHIẾN BINH", OnlineUi.BRAWL_CYAN)).center();
        alexTablet.row();
        alexTablet.add(ui.heading("ALEX", Color.WHITE)).center().padTop(2);
        leftPedestal.add(alexTablet).growX().bottom();

        centerRow.add(leftPedestal).width(230).height(400).padRight(24);

        // 2. Center Action Menu Card
        var menuCard = new Table();
        menuCard.setBackground(ui.comicBox(Color.valueOf("09182E"), OnlineUi.BRAWL_BORDER, 4f, 6, 8));
        menuCard.pad(16, 32, 16, 32);

        var menuTitle = ui.comicHeading("CHỌN CHẾ ĐỘ CHƠI", Color.WHITE);
        menuCard.add(menuTitle).center().padBottom(14);
        menuCard.row();

        // Button 1: Chơi Local
        var localBtn = ui.comicButton("CHƠI LOCAL  [SPACE]", "comic-gold", () -> {
            playSound();
            openLocal();
        });
        menuCard.add(localBtn).size(420, 48);
        menuCard.row();
        var localHint = ui.small("2 người chơi đối kháng trên cùng một bàn phím", Color.valueOf("FFE8A3"));
        menuCard.add(localHint).center().padTop(3).padBottom(10);
        menuCard.row();

        // Button 2: Chơi Online
        var onlineBtn = ui.comicButton("CHƠI ONLINE  [O]", "comic-primary", () -> {
            playSound();
            openOnline();
        });
        menuCard.add(onlineBtn).size(420, 48);
        menuCard.row();
        var onlineHint = ui.small("Sảnh đấu trực tuyến qua mạng LAN / Internet (2-4 người)", Color.valueOf("94FFE5"));
        menuCard.add(onlineHint).center().padTop(3).padBottom(10);
        menuCard.row();

        // Button 3: Cài đặt phím
        var settingsBtn = ui.comicButton("CÀI ĐẶT PHÍM  [C]", "comic-white", () -> {
            playSound();
            openSettings();
        });
        menuCard.add(settingsBtn).size(420, 48);
        menuCard.row();
        var settingsHint = ui.small("Tùy chỉnh phím điều khiển (di chuyển, nhảy, đánh, né...)", Color.valueOf("CFE2FE"));
        menuCard.add(settingsHint).center().padTop(3).padBottom(10);
        menuCard.row();

        // Button 4: Thoát
        var exitBtn = ui.comicButton("THOÁT GAME  [ESC]", "comic-white", () -> {
            playSound();
            Gdx.app.exit();
        });
        menuCard.add(exitBtn).size(420, 42).padBottom(2);

        centerRow.add(menuCard).width(484).height(400);

        // 3. Right Pedestal: LEO
        var rightPedestal = new Table();
        rightPedestal.setBackground(ui.comicBox(OnlineUi.BRAWL_DARK, OnlineUi.BRAWL_BORDER, 3.5f, 4, 6));
        rightPedestal.pad(8, 8, 10, 8);

        var leoStage = new Table();
        var leoImg = new Image(leoRegion);
        leoStage.add(leoImg).size(150, 150).center().padBottom(-16);
        leoStage.row();

        var leoBase = new Table();
        leoBase.setBackground(ui.comicBox(Color.valueOf("8A4000"), Color.valueOf("FFC800"), 2.5f, 1, 1));
        leoBase.add().size(185, 18);
        leoStage.add(leoBase).center();
        rightPedestal.add(leoStage).growX().padTop(6).padBottom(8);
        rightPedestal.row();

        var leoTablet = new Table();
        leoTablet.setBackground(ui.comicBox(Color.valueOf("061324"), OnlineUi.BRAWL_BORDER, 2f, 0, 0));
        leoTablet.pad(6, 8, 8, 8);
        leoTablet.add(ui.small("ĐẤU THỦ", OnlineUi.BRAWL_GOLD)).center();
        leoTablet.row();
        leoTablet.add(ui.heading("LEO", Color.WHITE)).center().padTop(2);
        rightPedestal.add(leoTablet).growX().bottom();

        centerRow.add(rightPedestal).width(230).height(400).padLeft(24);

        root.add(centerRow).growX().center().padTop(8);
        root.row();

        // ================= BOTTOM STATISTICS & DEV CREDITS TABLET =================
        var statsTablet = new Table();
        statsTablet.setBackground(ui.comicBox(Color.valueOf("061324"), OnlineUi.BRAWL_BORDER, 2f, 0, 0));
        statsTablet.pad(6, 24, 6, 24);

        statsTablet.add(ui.small("THỜI GIAN ĐÃ CHƠI: ", OnlineUi.MUTED));
        statsTablet.add(ui.small(gamePropertiesManager.getEntry(Keys.GAMETIME), Color.WHITE));

        statsTablet.add(ui.small("   ·   ", OnlineUi.MUTED)).padLeft(12).padRight(12);

        statsTablet.add(ui.small("SỐ TRẬN ĐÃ ĐẤU: ", OnlineUi.MUTED));
        statsTablet.add(ui.small(gamePropertiesManager.getEntry(Keys.TIMESPLAYED), OnlineUi.BRAWL_GOLD));

        statsTablet.add(ui.small("   ·   ", OnlineUi.MUTED)).padLeft(12).padRight(12);

        statsTablet.add(ui.small("DEV TEAM: ", OnlineUi.MUTED));
        statsTablet.add(ui.small("Leo · Maurice · Alex · Luca", OnlineUi.BRAWL_CYAN));

        root.add(statsTablet).center().padTop(10);

        stage.addActor(root);

        // Settings modal dialog
        settingsOverlay = createSettingsModal();
        stage.addActor(settingsOverlay);

        // Input listener for key rebinding
        stage.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (settingsOverlay != null && settingsOverlay.isVisible() && currentlyRebinding != null) {
                    if (keycode == Input.Keys.ESCAPE) {
                        currentlyRebinding = null;
                        refreshKeyButtons();
                        if (waitingActionLabel != null) waitingActionLabel.setText("");
                        return true;
                    }
                    KeyBindingConfig.getInstance().setKey(currentlyRebinding, keycode);
                    KeyBindingConfig.getInstance().save();
                    playSound();
                    currentlyRebinding = null;
                    refreshKeyButtons();
                    if (waitingActionLabel != null) {
                        waitingActionLabel.setText("Đã gán phím thành công!");
                    }
                    return true;
                }
                return false;
            }
        });

        log.info("Initialized MainMenuScreen in Brawlhalla comic format with customizable keybindings");
    }

    private void openSettings() {
        if (settingsOverlay != null) {
            currentlyRebinding = null;
            refreshKeyButtons();
            if (waitingActionLabel != null) waitingActionLabel.setText("");
            settingsOverlay.setVisible(true);
        }
    }

    private void closeSettings() {
        if (settingsOverlay != null) {
            currentlyRebinding = null;
            KeyBindingConfig.getInstance().save();
            settingsOverlay.setVisible(false);
        }
    }

    private void refreshKeyButtons() {
        var cfg = KeyBindingConfig.getInstance();
        for (var entry : actionButtons.entrySet()) {
            var action = entry.getKey();
            var btn = entry.getValue();
            if (currentlyRebinding == action) {
                btn.setText("[ BẤM PHÍM... ]");
            } else {
                btn.setText("[ " + KeyBindingConfig.keyName(cfg.getKey(action)) + " ]");
            }
        }
    }

    private Table createSettingsModal() {
        var overlay = new Table();
        overlay.setFillParent(true);
        overlay.setBackground(ui.skin.newDrawable("pixel", new Color(0, 0, 0, 0.85f)));
        overlay.setVisible(false);

        var dialog = new Table();
        dialog.setBackground(ui.comicBox(Color.valueOf("09182E"), OnlineUi.BRAWL_CYAN, 4.5f, 8, 10));
        dialog.pad(16, 24, 16, 24);

        // Header: Title + Close button
        var dlgHeader = new Table();
        var dlgTitle = ui.comicHeading("CÀI ĐẶT PHÍM ĐIỀU KHIỂN", Color.WHITE);
        var closeBtn = ui.comicButton("X", "comic-white", () -> {
            playSound();
            closeSettings();
        });
        dlgHeader.add(dlgTitle).expandX().left();
        dlgHeader.add(closeBtn).size(40, 40).right();
        dialog.add(dlgHeader).growX().padBottom(8);
        dialog.row();

        var instruction = ui.small("Bấm vào ô phím muốn đổi, sau đó ấn phím bất kỳ trên bàn phím để gán", OnlineUi.BRAWL_GOLD);
        dialog.add(instruction).center().padBottom(6);
        dialog.row();

        waitingActionLabel = ui.small("", OnlineUi.BRAWL_CYAN);
        dialog.add(waitingActionLabel).center().height(18).padBottom(8);
        dialog.row();

        // 2-column actions grid
        var grid = new Table();
        var actions = KeyBindingConfig.Action.values();
        int half = (actions.length + 1) / 2;

        for (int i = 0; i < half; i++) {
            // Col 1 (left)
            final var actionLeft = actions[i];
            grid.add(ui.small(actionLeft.label, Color.WHITE)).left().width(155).padRight(8);
            var btnLeft = ui.comicButton("", "comic-white", () -> {
                playSound();
                currentlyRebinding = actionLeft;
                if (waitingActionLabel != null) {
                    waitingActionLabel.setText("Đang chờ phím cho: " + actionLeft.label.toUpperCase() + " (Ấn ESC để hủy)");
                }
                refreshKeyButtons();
            });
            actionButtons.put(actionLeft, btnLeft);
            grid.add(btnLeft).width(125).height(38).padRight(28);

            // Col 2 (right)
            if (i + half < actions.length) {
                final var actionRight = actions[i + half];
                grid.add(ui.small(actionRight.label, Color.WHITE)).left().width(155).padRight(8);
                var btnRight = ui.comicButton("", "comic-white", () -> {
                    playSound();
                    currentlyRebinding = actionRight;
                    if (waitingActionLabel != null) {
                        waitingActionLabel.setText("Đang chờ phím cho: " + actionRight.label.toUpperCase() + " (Ấn ESC để hủy)");
                    }
                    refreshKeyButtons();
                });
                actionButtons.put(actionRight, btnRight);
                grid.add(btnRight).width(125).height(38);
            }
            grid.row().padTop(6);
        }

        refreshKeyButtons();
        dialog.add(grid).padBottom(12);
        dialog.row();

        // Bottom presets and close buttons
        var footer = new Table();
        var wasdBtn = ui.comicButton("MẶC ĐỊNH (WASD)", "comic-white", () -> {
            playSound();
            KeyBindingConfig.getInstance().resetDefaults();
            currentlyRebinding = null;
            if (waitingActionLabel != null) waitingActionLabel.setText("Đã khôi phục cài đặt WASD mặc định!");
            refreshKeyButtons();
        });
        var arrowsBtn = ui.comicButton("MẪU MŨI TÊN", "comic-white", () -> {
            playSound();
            KeyBindingConfig.getInstance().applyPreset(KeyBindingConfig.Preset.ARROWS);
            currentlyRebinding = null;
            if (waitingActionLabel != null) waitingActionLabel.setText("Đã áp dụng mẫu phím Mũi tên!");
            refreshKeyButtons();
        });
        var saveBtn = ui.comicButton("LƯU & ĐÓNG  [ESC]", "comic-primary", () -> {
            playSound();
            closeSettings();
        });

        footer.add(wasdBtn).width(180).height(42).padRight(12);
        footer.add(arrowsBtn).width(160).height(42).padRight(12);
        footer.add(saveBtn).width(180).height(42);
        dialog.add(footer).center().padTop(4);

        overlay.add(dialog).center();
        return overlay;
    }

    private void playSound() {
        try {
            sound.playSound("sounds/minecraft_click.mp3");
        } catch (Exception e) {
            log.warn("Unable to play click sound", e);
        }
    }

    private void openLocal() {
        log.info("Opening CharacterSelectScreen (Local Play)");
        game.setScreen(new CharacterSelectScreen(game));
    }

    private void openOnline() {
        log.info("Opening OnlineLobbyScreen");
        game.setScreen(new OnlineLobbyScreen((com.smashprofs.game.Game) game));
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(stage);
    }

    @Override
    public void render(float delta) {
        if (disposed) { return; }

        if (settingsOverlay != null && settingsOverlay.isVisible()) {
            if (currentlyRebinding == null && (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) || Gdx.input.isKeyJustPressed(Input.Keys.C))) {
                playSound();
                closeSettings();
            }
        } else {
            // Keyboard shortcuts
            if (Gdx.input.isKeyJustPressed(Input.Keys.C) || Gdx.input.isKeyJustPressed(Input.Keys.S)) {
                playSound();
                openSettings();
                return;
            }
            if (Gdx.input.isKeyJustPressed(Input.Keys.O)) {
                playSound();
                openOnline();
                return;
            }
            if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE) || Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) {
                playSound();
                openLocal();
                return;
            }
            if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
                playSound();
                Gdx.app.exit();
                return;
            }
        }

        // Easter egg toggle: '6' + '9'
        if (Gdx.input.isKeyPressed(Input.Keys.NUM_6) && Gdx.input.isKeyJustPressed(Input.Keys.NUM_9)) {
            if ("true".equals(gamePropertiesManager.getEntry(Keys.EASTEREGG))) {
                gamePropertiesManager.edit(Keys.EASTEREGG, "false");
                log.warn("Easter egg disabled");
            } else {
                gamePropertiesManager.edit(Keys.EASTEREGG, "true");
                log.warn("Easter egg enabled");
            }
        }

        // Subtle logo breathing animation
        timer += delta * 1.6f;
        float zoom = 1f + 0.025f * (float) Math.sin(timer);
        logoContainer.setScale(zoom, zoom);

        // 1. Clear with Brawlhalla Royal Blue
        ScreenUtils.clear(Color.valueOf("0056BD"));

        // 2. Draw Textured Background with atmospheric blue tint
        batch.setProjectionMatrix(stage.getCamera().combined);
        batch.begin();
        batch.setColor(0.08f, 0.32f, 0.72f, 0.40f);
        batch.draw(bgTexture, 0, 0, 1280, 720);
        batch.setColor(Color.WHITE);
        batch.end();

        // 3. Draw Jagged White Lightning Shards & Cyan Corners (Matching format of Lobby & Result)
        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(stage.getCamera().combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        // Top-Left Sky Cyan Corner
        shapes.setColor(Color.valueOf("2BD9FFE8"));
        shapes.triangle(0, 480, 0, 720, 440, 720);
        shapes.triangle(0, 480, 440, 720, 280, 620);

        // Top-Left Jagged White Lightning Slash
        shapes.setColor(1f, 1f, 1f, 0.98f);
        shapes.triangle(0, 480, 60, 520, 20, 530);
        shapes.triangle(20, 530, 160, 580, 110, 600);
        shapes.triangle(110, 600, 290, 640, 240, 660);
        shapes.triangle(240, 660, 420, 700, 370, 720);
        shapes.triangle(370, 720, 470, 720, 280, 620);

        // Bottom-Right Sky Cyan Corner
        shapes.setColor(Color.valueOf("2BD9FFE8"));
        shapes.triangle(1280, 0, 840, 0, 1280, 260);
        shapes.triangle(840, 0, 1040, 130, 1280, 260);

        // Bottom-Right Jagged White Lightning Slash
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

    @Override
    public void hide() {
        dispose();
    }

    @Override
    public void dispose() {
        if (disposed) { return; }
        disposed = true;
        if (Gdx.input.getInputProcessor() == stage) {
            Gdx.input.setInputProcessor(null);
        }
        stage.dispose();
        batch.dispose();
        shapes.dispose();
        ui.close();
        for (Texture tex : textures) {
            tex.dispose();
        }
        log.info("Disposed MainMenuScreen");
    }
}
