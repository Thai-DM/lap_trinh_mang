package com.smashprofs.game.Screens;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.smashprofs.game.Game;
import com.smashprofs.game.network.client.*;
import com.smashprofs.game.ui.OnlineUi;
import java.util.*;

/**
 * Brawlhalla Comic-styled Match Results Screen.
 * Features:
 * - Royal blue atmospheric background with jagged lightning corners and subtle watermark.
 * - Bold victory/defeat champion header flanked by Nordic diamond wings.
 * - Comprehensive comic scoreboard with highlighted champion row and mini hero icons.
 * - 3 Comic award badges (Damage King, Weapon Collector, K.O. Leader).
 * - High-contrast crisp typography (Segoe UI Bold) and unified aesthetic.
 */
public final class OnlineResultScreen implements Screen {
    private static final String[] CHARACTERS = {"Alex", "Maurice", "Luca", "Leo", "Viktor"};
    private static final String[] PORTRAIT_FILES = {
        "Sprites/Alex/alex_stand.png",
        "Sprites/Momo/momo_strip.png",
        "Sprites/Luca/luca_stand.png",
        "Sprites/Leo/leo_stand.png",
        "Sprites/Viktor/viktor_stand.png"
    };

    private final Stage stage = new Stage(new FitViewport(1280, 720));
    private final OnlineUi ui = new OnlineUi();
    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final ArrayList<Texture> textures = new ArrayList<>();
    private final TextureRegion[] characterRegions = new TextureRegion[5];

    private final Game game;
    private final Texture bgTexture;
    private final BitmapFont watermarkFont;
    private final String watermarkText;
    private boolean disposed;

    public OnlineResultScreen(Game game, WireMatchResult result, String localId) {
        this.game = game;

        // 1. Background texture & Watermark
        bgTexture = new Texture(Gdx.files.internal("mainmenu/bgmenu.png"));
        textures.add(bgTexture);

        watermarkFont = new BitmapFont(Gdx.files.internal("ui/online/heading.fnt"));
        watermarkFont.getData().setScale(2.8f);

        // 2. Character icons (Frame 0, 100x100)
        for (int i = 0; i < PORTRAIT_FILES.length; i++) {
            var tex = new Texture(Gdx.files.internal(PORTRAIT_FILES[i]));
            tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            textures.add(tex);
            characterRegions[i] = new TextureRegion(tex, 0, 0, 100, 100);
        }

        // 3. Nordic diamond texture
        var diamondPix = new Pixmap(17, 16, Pixmap.Format.RGBA8888);
        diamondPix.setColor(OnlineUi.BRAWL_CYAN);
        for (int y = 0; y < 16; y++) {
            int dx = y <= 7 ? y : 15 - y;
            diamondPix.drawLine(8 - dx, y, 8 + dx, y);
        }
        var diamondTex = new Texture(diamondPix);
        diamondPix.dispose();
        textures.add(diamondTex);

        // Determine outcome
        var winner = result.winner();
        boolean localWon = winner != null && winner.playerId().equals(localId);
        boolean isDraw = result.isDraw();

        if (isDraw) {
            watermarkText = "DRAW MATCH";
        } else if (localWon) {
            watermarkText = "VICTORY";
        } else {
            watermarkText = "MATCH OVER";
        }

        // --- ROOT LAYOUT ---
        var root = new Table();
        root.setFillParent(true);
        root.pad(14, 32, 10, 32);
        root.defaults().top();

        // ================= HEADER BAR =================
        var header = new Table();

        // Left button: Về menu
        var backBtn = ui.comicButton("< VỀ MENU [ESC]", "comic-white", this::back);
        header.add(backBtn).height(44).left();

        // Center: Nordic diamond wings + Victory title
        var titleBox = new Table();
        var wingsRow = new Table();
        var leftWing = createNordicWing(diamondTex, false);
        var rightWing = createNordicWing(diamondTex, true);

        Label championTitle;
        if (isDraw) {
            championTitle = ui.comicHeading("TRẬN ĐẤU HÒA", Color.WHITE);
        } else if (localWon) {
            championTitle = ui.comicHeading("BẠN ĐÃ CHIẾN THẮNG!", OnlineUi.BRAWL_GOLD);
        } else {
            championTitle = ui.comicHeading("P" + (winner.slotIndex() + 1) + " · " + winner.characterId().toUpperCase(Locale.ROOT) + " CHIẾN THẮNG!", OnlineUi.BRAWL_CYAN);
        }

        wingsRow.add(leftWing).padRight(12);
        wingsRow.add(championTitle);
        wingsRow.add(rightWing).padLeft(12);
        titleBox.add(wingsRow).center().row();

        String reason = switch (result.finishReason()) {
            case "TIME_LIMIT" -> "Hết thời gian trận đấu  ·  Xét HP cao nhất";
            case "LAST_PLAYER_STANDING" -> "Chỉ còn một người chơi sống sót cuối cùng";
            default -> "Tất cả người chơi đã hết mạng";
        };
        var subReason = ui.small(reason, Color.valueOf("C8E6FF"));
        titleBox.add(subReason).center().padTop(2);

        header.add(titleBox).expandX().center();

        // Right: Match duration & room badge
        var infoBadge = new Table();
        infoBadge.setBackground(ui.comicBox(Color.valueOf("051428"), OnlineUi.BRAWL_BORDER, 2f, 0, 0));
        infoBadge.pad(4, 12, 4, 12);
        String durStr = result.gameplay() != null ? String.format(Locale.ROOT, "%.1fs", result.gameplay().durationTicks() / 60f) : "";
        var infoLabel = ui.small("PHÒNG #" + result.roomId() + (durStr.isEmpty() ? "" : "  ·  " + durStr), OnlineUi.BRAWL_CYAN);
        infoBadge.add(infoLabel);
        header.add(infoBadge).height(44).right();

        root.add(header).growX();
        root.row();

        // ================= SCOREBOARD TABLE =================
        var boardCard = new Table();
        boardCard.setBackground(ui.comicBox(OnlineUi.BRAWL_DARK, OnlineUi.BRAWL_BORDER, 3.5f, 4, 6));
        boardCard.pad(8, 12, 8, 12);

        // Header Row inside Black Ribbon
        var headerRibbon = new Table();
        headerRibbon.setBackground(ui.box("000000", 4));
        headerRibbon.pad(6, 10, 6, 10);
        headerRibbon.defaults().center();

        headerRibbon.add(ui.small("NGƯỜI CHƠI", Color.valueOf("94C2FA"))).width(280).left();
        headerRibbon.add(ui.small("HP CUỐI", Color.valueOf("94C2FA"))).width(95);
        headerRibbon.add(ui.small("MẠNG", Color.valueOf("94C2FA"))).width(80);
        headerRibbon.add(ui.small("SÁT THƯƠNG", Color.valueOf("94C2FA"))).width(115);
        headerRibbon.add(ui.small("K.O.", Color.valueOf("94C2FA"))).width(80);
        headerRibbon.add(ui.small("BỊ HẠ", Color.valueOf("94C2FA"))).width(80);
        headerRibbon.add(ui.small("VẬT PHẨM", Color.valueOf("94C2FA"))).width(95);
        headerRibbon.add(ui.small("VŨ KHÍ", Color.valueOf("94C2FA"))).width(95);

        boardCard.add(headerRibbon).growX();
        boardCard.row();

        // Sort participants: Winner first, then by slot
        var sortedList = new ArrayList<>(result.participants());
        sortedList.sort((a, b) -> {
            boolean aWin = a.playerId().equals(result.winnerId());
            boolean bWin = b.playerId().equals(result.winnerId());
            if (aWin && !bWin) return -1;
            if (!aWin && bWin) return 1;
            return Integer.compare(a.slotIndex(), b.slotIndex());
        });

        for (var p : sortedList) {
            boolean isWinner = p.playerId().equals(result.winnerId());
            boolean isLocal = p.playerId().equals(localId);

            var rowTable = new Table();
            if (isWinner) {
                rowTable.setBackground(ui.comicBox(Color.valueOf("0E2E4A"), OnlineUi.BRAWL_GOLD, 2.5f, 0, 0));
            } else if (isLocal) {
                rowTable.setBackground(ui.comicBox(Color.valueOf("092038"), OnlineUi.BRAWL_CYAN, 1.5f, 0, 0));
            } else {
                rowTable.setBackground(ui.comicBox(Color.valueOf("071424"), OnlineUi.BRAWL_BORDER, 1.5f, 0, 0));
            }
            rowTable.pad(5, 10, 5, 10);
            rowTable.defaults().center();

            // Player identity with mini icon
            var identityBox = new Table();
            var heroIcon = new Image(getCharacterRegion(p.characterId()));
            identityBox.add(heroIcon).size(30, 30).padRight(8);

            String pText = (isWinner ? "👑 " : "") + "P" + (p.slotIndex() + 1) + " · " + p.characterId() + (isLocal ? " (Bạn)" : "");
            Color nameColor = isWinner ? OnlineUi.BRAWL_GOLD : (isLocal ? OnlineUi.BRAWL_CYAN : Color.WHITE);
            var nameLabel = ui.small(pText, nameColor);
            identityBox.add(nameLabel).left();
            rowTable.add(identityBox).width(280).left();

            // Final HP
            int finalHpVal = Math.round(p.finalHp());
            Color hpColor = finalHpVal > 50 ? OnlineUi.BRAWL_CYAN : (finalHpVal > 0 ? OnlineUi.BRAWL_GOLD : Color.valueOf("FF6B81"));
            rowTable.add(ui.small(Integer.toString(finalHpVal), hpColor)).width(95);

            if (result.gameplay() != null) {
                var s = result.gameplay().player(p.playerId());
                rowTable.add(ui.small("" + s.lives(), Color.WHITE)).width(80);
                rowTable.add(ui.small(String.format(Locale.ROOT, "%.0f", s.damageDealt()), Color.valueOf("FFE066"))).width(115);
                rowTable.add(ui.small("" + s.kills(), Color.valueOf("6DE4D0"))).width(80);
                rowTable.add(ui.small("" + s.deaths(), Color.valueOf("FF858E"))).width(80);
                rowTable.add(ui.small("" + s.itemsCollected(), Color.valueOf("E0F0FF"))).width(95);
                rowTable.add(ui.small("" + s.weaponsCollected(), Color.valueOf("E0F0FF"))).width(95);
            } else {
                for (int c = 0; c < 6; c++) {
                    rowTable.add(ui.small("—", OnlineUi.MUTED)).width(c == 1 ? 115 : (c == 0 || c == 2 || c == 3 ? 80 : 95));
                }
            }

            boardCard.add(rowTable).growX().padTop(4);
            boardCard.row();
        }

        root.add(boardCard).growX().padTop(8);
        root.row();

        // ================= SPECIAL AWARDS (3 CARDS) =================
        if (result.gameplay() != null) {
            var awards = new Table();
            awards.defaults().top();

            String[] types = {"DAMAGE", "WEAPONS", "KO"};
            String[] titles = {"👑 VUA SÁT THƯƠNG", "🗡 THỢ NHẶT VŨ KHÍ", "💥 NHIỀU K.O. NHẤT"};
            Color[] accentCols = {OnlineUi.BRAWL_GOLD, OnlineUi.BRAWL_CYAN, Color.valueOf("FF6B81")};

            for (int i = 0; i < types.length; i++) {
                var ids = result.gameplay().leaders(types[i]);
                String names = ids.isEmpty() ? "Chưa có danh hiệu" : ids.stream().map(id -> name(result, id)).collect(java.util.stream.Collectors.joining(", "));

                var card = new Table();
                card.setBackground(ui.comicBox(Color.valueOf("09182C"), OnlineUi.BRAWL_BORDER, 2.5f, 3, 3));
                card.pad(8, 14, 8, 14);

                var badgeTitle = ui.small(titles[i], accentCols[i]);
                card.add(badgeTitle).left();
                card.row();

                var label = ui.small(names + (ids.size() > 1 ? " · Đồng hạng" : ""), Color.WHITE);
                card.add(label).left().padTop(4).width(340);

                awards.add(card).width(378).height(74).padRight(i == 2 ? 0 : 16);
            }
            root.add(awards).growX().padTop(8);
            root.row();
        }

        // ================= ACTION BUTTON =================
        var actionRow = new Table();
        var playAgainBtn = ui.comicButton("CHƠI TRẬN KHÁC  [ENTER]", "comic-gold", this::back);
        actionRow.add(playAgainBtn).width(360).height(48).center();
        actionRow.row();

        var hint = ui.small("Trở về màn hình Sảnh đấu / Tạo phòng   ·   [ESC] Về Menu chính", Color.valueOf("B8D8FF"));
        actionRow.add(hint).center().padTop(4);

        root.add(actionRow).growX().padTop(8);
        stage.addActor(root);
    }

    private Table createNordicWing(Texture diamondTex, boolean right) {
        var wing = new Table();
        var line = new Image(ui.skin.newDrawable("pixel", OnlineUi.BRAWL_CYAN));
        var diamondLarge = new Image(diamondTex);
        var diamondSmall = new Image(diamondTex);

        if (right) {
            wing.add(diamondLarge).size(16, 16).padRight(6);
            wing.add(diamondSmall).size(10, 10).padRight(8);
            wing.add(line).width(50).height(3);
        } else {
            wing.add(line).width(50).height(3).padRight(8);
            wing.add(diamondSmall).size(10, 10).padRight(6);
            wing.add(diamondLarge).size(16, 16);
        }
        return wing;
    }

    private TextureRegion getCharacterRegion(String name) {
        if (name != null) {
            for (int i = 0; i < CHARACTERS.length; i++) {
                if (CHARACTERS[i].equalsIgnoreCase(name)) {
                    return characterRegions[i];
                }
            }
        }
        return characterRegions[0];
    }

    private static String name(WireMatchResult result, String id) {
        return result.participants().stream()
            .filter(p -> p.playerId().equals(id))
            .map(p -> "P" + (p.slotIndex() + 1) + " · " + p.characterId())
            .findFirst()
            .orElse("Người chơi");
    }

    private void back() {
        game.leaveOnlineLobby();
        game.setScreen(new OnlineLobbyScreen(game));
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(stage);
    }

    @Override
    public void render(float delta) {
        if (disposed) { return; }

        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) ||
            Gdx.input.isKeyJustPressed(Input.Keys.ENTER) ||
            Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            back();
            return;
        }

        // 1. Clear with Brawlhalla Royal Blue
        ScreenUtils.clear(Color.valueOf("0056BD"));

        // 2. Draw Textured Background with atmospheric tint + Watermark
        batch.setProjectionMatrix(stage.getCamera().combined);
        batch.begin();
        batch.setColor(0.08f, 0.32f, 0.72f, 0.40f);
        batch.draw(bgTexture, 0, 0, 1280, 720);
        batch.setColor(Color.WHITE);

        // Giant subtle background watermark text
        watermarkFont.setColor(0f, 0.28f, 0.65f, 0.20f);
        watermarkFont.draw(batch, watermarkText, 240, 430);

        batch.end();

        // 3. Draw Jagged White Lightning Corners
        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(stage.getCamera().combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        shapes.setColor(Color.valueOf("2BD9FFE8"));
        shapes.triangle(0, 520, 0, 720, 360, 720);
        shapes.setColor(1f, 1f, 1f, 0.95f);
        shapes.triangle(0, 520, 40, 560, 10, 570);
        shapes.triangle(10, 570, 120, 620, 80, 640);
        shapes.triangle(80, 640, 240, 680, 200, 700);
        shapes.triangle(200, 700, 360, 720, 220, 640);

        shapes.setColor(Color.valueOf("2BD9FFE8"));
        shapes.triangle(1280, 0, 920, 0, 1280, 200);
        shapes.setColor(1f, 1f, 1f, 0.95f);
        shapes.triangle(920, 0, 970, 0, 1020, 50);
        shapes.triangle(1020, 50, 980, 40, 1120, 100);
        shapes.triangle(1120, 100, 1080, 90, 1280, 190);

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
        if (Gdx.input.getInputProcessor() == stage) {
            Gdx.input.setInputProcessor(null);
        }
        stage.dispose();
        batch.dispose();
        shapes.dispose();
        watermarkFont.dispose();
        ui.close();
        textures.forEach(Texture::dispose);
    }
}
