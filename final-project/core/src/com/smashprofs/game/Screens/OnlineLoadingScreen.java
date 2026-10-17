package com.smashprofs.game.Screens;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.smashprofs.game.Game;
import com.smashprofs.game.network.client.LobbyClient;
import com.smashprofs.game.ui.OnlineUi;
import java.util.*;

/**
 * Brawlhalla-styled Match Versus / Loading Screen.
 * Displays when 2, 3, or 4 players are all ready in the room during server countdown,
 * showcasing stone/metallic arch character cards, hero portraits, Nordic Valknut medallions,
 * animated breathing stances, and canonical segmented stat bars before entering the match map.
 */
public class OnlineLoadingScreen implements Screen {

    public static class CharacterStats {
        public final int attack;
        public final int defense;
        public final int speed;
        public final int dexterity;

        public CharacterStats(int attack, int defense, int speed, int dexterity) {
            this.attack = attack;
            this.defense = defense;
            this.speed = speed;
            this.dexterity = dexterity;
        }
    }

    public static CharacterStats getStats(String characterName) {
        if (characterName == null) return new CharacterStats(5, 5, 5, 5);
        return switch (characterName.toLowerCase(Locale.ROOT)) {
            case "alex" -> new CharacterStats(com.smashprofs.game.Actors.Players.Alex.STAT_ATTACK, com.smashprofs.game.Actors.Players.Alex.STAT_DEFENSE, com.smashprofs.game.Actors.Players.Alex.STAT_SPEED, com.smashprofs.game.Actors.Players.Alex.STAT_DEXTERITY);
            case "maurice", "momo" -> new CharacterStats(com.smashprofs.game.Actors.Players.Maurice.STAT_ATTACK, com.smashprofs.game.Actors.Players.Maurice.STAT_DEFENSE, com.smashprofs.game.Actors.Players.Maurice.STAT_SPEED, com.smashprofs.game.Actors.Players.Maurice.STAT_DEXTERITY);
            case "luca" -> new CharacterStats(com.smashprofs.game.Actors.Players.Luca.STAT_ATTACK, com.smashprofs.game.Actors.Players.Luca.STAT_DEFENSE, com.smashprofs.game.Actors.Players.Luca.STAT_SPEED, com.smashprofs.game.Actors.Players.Luca.STAT_DEXTERITY);
            case "leo" -> new CharacterStats(com.smashprofs.game.Actors.Players.Leo.STAT_ATTACK, com.smashprofs.game.Actors.Players.Leo.STAT_DEFENSE, com.smashprofs.game.Actors.Players.Leo.STAT_SPEED, com.smashprofs.game.Actors.Players.Leo.STAT_DEXTERITY);
            case "viktor" -> new CharacterStats(com.smashprofs.game.Actors.Players.Viktor.STAT_ATTACK, com.smashprofs.game.Actors.Players.Viktor.STAT_DEFENSE, com.smashprofs.game.Actors.Players.Viktor.STAT_SPEED, com.smashprofs.game.Actors.Players.Viktor.STAT_DEXTERITY);
            default -> new CharacterStats(5, 5, 5, 5);
        };
    }

    public static class PlayerCardData {
        public final String playerId;
        public final String playerName;
        public final String characterName;
        public final int slotIndex;
        public final boolean isLocal;
        public final boolean isHost;
        public final CharacterStats stats;

        public PlayerCardData(String playerId, String playerName, String characterName, int slotIndex, boolean isLocal, boolean isHost) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.characterName = characterName != null ? characterName : "Alex";
            this.slotIndex = slotIndex;
            this.isLocal = isLocal;
            this.isHost = isHost;
            this.stats = getStats(this.characterName);
        }
    }

    private static final String[] CHARACTERS = {"Alex", "Maurice", "Luca", "Leo", "Viktor"};
    private static final String[] PORTRAIT_FILES = {
        "Sprites/Alex/alex_stand.png",
        "Sprites/Momo/momo_strip.png",
        "Sprites/Luca/luca_stand.png",
        "Sprites/Leo/leo_stand.png",
        "Sprites/Viktor/viktor_stand.png"
    };

    private final Game game;
    private final LobbyClient lobby;
    private final List<PlayerCardData> customRoster;
    private final Runnable customOnFinished;

    private final FitViewport viewport = new FitViewport(1280, 720);
    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final OnlineUi ui = new OnlineUi();

    private final ArrayList<Texture> textures = new ArrayList<>();
    private final Texture bgTexture;
    private final TextureRegion[] characterRegions = new TextureRegion[5];

    private float stateTime = 0f;
    private float displayTime = 0f;
    private boolean transitioned = false;
    private boolean disposed = false;

    public OnlineLoadingScreen(Game game, LobbyClient lobby) {
        this(game, lobby, null, null);
    }

    public OnlineLoadingScreen(Game game, List<PlayerCardData> customRoster, Runnable customOnFinished) {
        this(game, null, customRoster, customOnFinished);
    }

    private OnlineLoadingScreen(Game game, LobbyClient lobby, List<PlayerCardData> customRoster, Runnable customOnFinished) {
        this.game = game;
        this.lobby = lobby;
        this.customRoster = customRoster;
        this.customOnFinished = customOnFinished;

        // Background
        bgTexture = new Texture(Gdx.files.internal("mainmenu/bgmenu.png"));
        textures.add(bgTexture);

        // Load 5 Character sprites (Frame 0, 100x100)
        for (int i = 0; i < PORTRAIT_FILES.length; i++) {
            var tex = new Texture(Gdx.files.internal(PORTRAIT_FILES[i]));
            tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            textures.add(tex);
            characterRegions[i] = new TextureRegion(tex, 0, 0, 100, 100);
        }
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

    private List<PlayerCardData> resolvePlayers() {
        if (customRoster != null && !customRoster.isEmpty()) {
            return customRoster;
        }
        if (lobby == null) {
            return List.of(
                new PlayerCardData("p1", "Player 1 (Bạn)", "Alex", 0, true, true),
                new PlayerCardData("p2", "Player 2", "Maurice", 1, false, false)
            );
        }

        var state = lobby.getSnapshot();
        var settings = lobby.getSettings();
        var match = state.match();

        List<PlayerCardData> list = new ArrayList<>();
        if (match != null && !match.players().isEmpty()) {
            for (var p : match.players()) {
                boolean isLocal = state.localPlayerId() != null && p.playerId().equals(state.localPlayerId());
                boolean isHost = settings != null && settings.hostId().equals(p.playerId().value());
                String pLabel = "Player " + (p.slotIndex() + 1) + (isLocal ? " (Bạn)" : "");
                list.add(new PlayerCardData(p.playerId().value(), pLabel, p.characterId(), p.slotIndex(), isLocal, isHost));
            }
        } else {
            var members = settings != null && !settings.members().isEmpty() ? settings.members() : state.members();
            for (var m : members) {
                if (m.characterId() != null || m.ready()) {
                    boolean isLocal = state.localPlayerId() != null && m.playerId().equals(state.localPlayerId());
                    boolean isHost = settings != null && settings.hostId().equals(m.playerId().value());
                    String charName = m.characterId() != null ? m.characterId() : "Alex";
                    String pLabel = "Player " + (m.slotIndex() + 1) + (isLocal ? " (Bạn)" : "");
                    list.add(new PlayerCardData(m.playerId().value(), pLabel, charName, m.slotIndex(), isLocal, isHost));
                }
            }
        }

        list.sort(Comparator.comparingInt(a -> a.slotIndex));
        if (list.isEmpty()) {
            list.add(new PlayerCardData("p1", "Player 1 (Bạn)", "Alex", 0, true, true));
            list.add(new PlayerCardData("p2", "Player 2", "Maurice", 1, false, false));
        }
        return list;
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(null);
    }

    @Override
    public void render(float delta) {
        if (disposed) { return; }
        stateTime += delta;
        displayTime += delta;

        // Transition check
        if (!transitioned) {
            if (customOnFinished != null) {
                if (displayTime >= 2.5f) {
                    transitioned = true;
                    customOnFinished.run();
                    return;
                }
            } else if (lobby != null) {
                var state = lobby.getSnapshot();
                // When match starts on server and minimum loading presentation time has elapsed
                if (state.isMatchStarted() && (displayTime >= 2.0f || Game.unitTestMode)) {
                    transitioned = true;
                    game.setScreen(new PlayScreen(game));
                    return;
                }

                // If countdown was cancelled back to waiting/ready (and not started)
                if (!"COUNTDOWN".equals(state.serverPhase()) && !state.isMatchStarted() && !"PLAYING".equals(state.serverPhase())) {
                    transitioned = true;
                    game.setScreen(new OnlineRoomScreen(game));
                    return;
                }

                // If disconnected
                if (state.phase() == LobbyClient.Phase.DISCONNECTED || state.phase() == LobbyClient.Phase.ERROR) {
                    transitioned = true;
                    game.leaveOnlineLobby();
                    game.setScreen(new OnlineLobbyScreen(game));
                    return;
                }

                // ESC key allows backing out to lobby if match has not started yet
                if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) && !state.isMatchStarted()) {
                    transitioned = true;
                    game.leaveOnlineLobby();
                    game.setScreen(new OnlineLobbyScreen(game));
                    return;
                }
            }
        }

        viewport.apply();
        var camera = viewport.getCamera();
        camera.update();

        // 1. Royal Blue Cosmic Clear
        ScreenUtils.clear(Color.valueOf("004899"));

        // 2. Draw Shaded Background Texture
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        batch.setColor(0.06f, 0.26f, 0.60f, 0.42f);
        batch.draw(bgTexture, 0, 0, 1280, 720);
        batch.setColor(Color.WHITE);
        batch.end();

        // 3. Render Vector Shapes (Vignette, Particles, Cards, Frames, Stats)
        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(camera.combined);

        // Subtle gradient vignette top & bottom
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0.12f, 0.32f, 0.55f);
        shapes.rect(0, 580, 1280, 140);
        shapes.setColor(0f, 0.08f, 0.22f, 0.65f);
        shapes.rect(0, 0, 1280, 90);
        shapes.end();

        // Top Banner Shapes
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        // Cyan glowing horizontal accent lines
        shapes.setColor(Color.valueOf("00E5FF80"));
        shapes.rect(80, 680, 320, 2);
        shapes.rect(880, 680, 320, 2);
        shapes.setColor(Color.valueOf("FFFFFFC0"));
        shapes.rect(200, 680, 200, 1.5f);
        shapes.rect(880, 680, 200, 1.5f);

        // Central pill banner
        float bannerW = 460f, bannerH = 38f;
        float bannerX = (1280f - bannerW) / 2f, bannerY = 661f;
        shapes.setColor(Color.valueOf("00E5FF")); // outer glow
        shapes.rect(bannerX - 2, bannerY - 2, bannerW + 4, bannerH + 4);
        shapes.setColor(Color.valueOf("040C1A")); // dark interior
        shapes.rect(bannerX, bannerY, bannerW, bannerH);
        shapes.setColor(Color.valueOf("2BD9FF")); // top/bottom trim lines
        shapes.rect(bannerX + 6, bannerY + bannerH - 2, bannerW - 12, 2);
        shapes.rect(bannerX + 6, bannerY, bannerW - 12, 2);
        shapes.end();

        // Dynamic Players Layout (2, 3, or 4 players)
        var players = resolvePlayers();
        int n = MathUtils.clamp(players.size(), 2, 4);

        float cardW = n == 2 ? 310f : (n == 3 ? 260f : 225f);
        float gap = n == 2 ? 50f : (n == 3 ? 32f : 20f);
        float totalW = n * cardW + (n - 1) * gap;
        float startX = (1280f - totalW) / 2f;
        float cardY = 95f;
        float cardH = 490f;

        // Render Cards (Outer Drop Shadow & Frame Geometry)
        for (int i = 0; i < n; i++) {
            float x = startX + i * (cardW + gap);
            renderCardFrame(x, cardY, cardW, cardH);
        }

        // Render Stat Tablets inside cards (with ShapeRenderer)
        for (int i = 0; i < n; i++) {
            float x = startX + i * (cardW + gap);
            var p = players.get(i);
            renderStatTablet(x, cardY, cardW, p);
        }

        // Bottom Bar Background
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(Color.valueOf("030814F0"));
        shapes.rect(0, 0, 1280, 52);
        shapes.setColor(Color.valueOf("00E5FF60"));
        shapes.rect(0, 52, 1280, 2);
        shapes.setColor(Color.valueOf("2BD9FFB0"));
        shapes.rect(400, 52, 480, 2);
        shapes.end();

        // 4. Render Text & Character Sprites with SpriteBatch
        batch.begin();

        // Top Banner Text
        var headingFont = ui.skin.getFont("heading");
        headingFont.getData().setScale(0.85f);
        headingFont.setColor(Color.valueOf("001830")); // drop shadow
        headingFont.draw(batch, "LOADING - ĐẤU TRƯỜNG BRAWLHAVEN", bannerX + 2, bannerY + 28, bannerW, Align.center, false);
        headingFont.setColor(Color.WHITE);
        headingFont.draw(batch, "LOADING - ĐẤU TRƯỜNG BRAWLHAVEN", bannerX, bannerY + 30, bannerW, Align.center, false);
        headingFont.getData().setScale(1f);

        // Characters, Hero Names, and Subtitles
        var bodyFont = ui.skin.getFont("body");
        var smallFont = ui.skin.getFont("small");

        for (int i = 0; i < n; i++) {
            float x = startX + i * (cardW + gap);
            var p = players.get(i);

            // Character Standing Pose Art (Clean static pose, no bobbing)
            var region = getCharacterRegion(p.characterName);
            float spriteSize = n == 2 ? 155f : (n == 3 ? 138f : 122f);
            float charX = x + (cardW - spriteSize) / 2f;
            float charY = cardY + 195f;

            batch.draw(region, charX, charY, spriteSize, spriteSize);

            // Hero Name (Bold uppercase)
            headingFont.getData().setScale(n == 4 ? 0.95f : 1.15f);
            headingFont.setColor(Color.BLACK);
            headingFont.draw(batch, p.characterName.toUpperCase(Locale.ROOT), x + 2, cardY + cardH - 58, cardW, Align.center, false);
            headingFont.setColor(Color.WHITE);
            headingFont.draw(batch, p.characterName.toUpperCase(Locale.ROOT), x, cardY + cardH - 56, cardW, Align.center, false);
            headingFont.getData().setScale(1f);

            // Player Subtitle
            smallFont.setColor(p.isLocal ? OnlineUi.BRAWL_CYAN : (p.isHost ? OnlineUi.BRAWL_GOLD : Color.valueOf("B4D2EE")));
            smallFont.draw(batch, p.playerName, x, cardY + cardH - 85, cardW, Align.center, false);

            // Stat Row Label Abbreviations (Inside bottom panel)
            float tabletY = cardY + 16f;
            float tabletH = 92f;
            float labelX = x + (n == 4 ? 58f : 68f);
            String[] statLabels = {"DMG", "DEF", "SPD", "DEX"};
            smallFont.setColor(Color.valueOf("84A8C8"));
            for (int r = 0; r < 4; r++) {
                float rY = tabletY + tabletH - 20f - r * 18f;
                smallFont.draw(batch, statLabels[r], labelX, rY + 8f);
            }
        }

        // Bottom Controls Hint & Loading Dots
        smallFont.setColor(Color.valueOf("B8D8F8"));
        smallFont.draw(batch, "Mẹo: A/D di chuyển · W nhảy · S dậm · V tấn công · F tuyệt chiêu · Ctrl đỡ đòn", 0, 40, 1280, Align.center, false);

        int dotCount = ((int) (stateTime * 2.5f)) % 4;
        String dots = ".".repeat(dotCount);
        smallFont.setColor(OnlineUi.BRAWL_CYAN);
        smallFont.draw(batch, "Đang tải dữ liệu trận đấu" + dots, 0, 20, 1280, Align.center, false);

        batch.end();
    }

    /** Draws the beveled stone/metallic arch card frame with the top Valknut medallion. */
    private void renderCardFrame(float x, float y, float w, float h) {
        float bodyH = h - 65f; // height of rectangular base
        float archApexY = y + h;
        float archBaseY = y + bodyH;
        float centerX = x + w / 2f;

        shapes.begin(ShapeRenderer.ShapeType.Filled);

        // 1. Drop Shadow
        shapes.setColor(0f, 0f, 0f, 0.45f);
        shapes.rect(x + 5, y - 5, w, bodyH);
        renderArchShape(centerX + 5, archBaseY - 5, w / 2f, 65f);

        // 2. Outer Pale Steel Border
        shapes.setColor(Color.valueOf("9EC4DE"));
        shapes.rect(x - 3, y - 3, w + 6, bodyH + 3);
        renderArchShape(centerX, archBaseY, w / 2f + 3, 68f);

        // 3. Bevel Inset Layer (Slate Blue)
        shapes.setColor(Color.valueOf("355474"));
        shapes.rect(x, y, w, bodyH);
        renderArchShape(centerX, archBaseY, w / 2f, 65f);

        // 4. Dark Abyss Interior (Deep Navy)
        shapes.setColor(Color.valueOf("050B16"));
        shapes.rect(x + 5, y + 5, w - 10, bodyH - 5);
        renderArchShape(centerX, archBaseY, w / 2f - 5, 60f);

        // 5. Top Medallion Circle (Overlapping the arch crown)
        float medRadius = 28f;
        float medCenterY = y + h - 14f;

        // Outer Metallic Rim
        shapes.setColor(Color.valueOf("A2C8E4"));
        shapes.circle(centerX, medCenterY, medRadius + 2.5f, 32);

        // Dark Bevel Rim
        shapes.setColor(Color.valueOf("2B4460"));
        shapes.circle(centerX, medCenterY, medRadius, 32);

        // Glowing Purple Jewel Core
        shapes.setColor(Color.valueOf("7B1FA2"));
        shapes.circle(centerX, medCenterY, medRadius - 3.5f, 32);
        shapes.setColor(Color.valueOf("9C27B0"));
        shapes.circle(centerX - 3, medCenterY + 3, medRadius - 9f, 24);

        // 6. Nordic Valknut Symbol (Three Interlocking Triangles)
        shapes.setColor(Color.valueOf("EDE7F6"));
        drawValknut(centerX, medCenterY, 14f);

        shapes.end();
    }

    /** Draws an arched dome shape using polygonal triangle fan segments. */
    private void renderArchShape(float cx, float baseY, float rx, float ry) {
        int segments = 16;
        for (int i = 0; i < segments; i++) {
            float a1 = (float) (Math.PI * i / segments);
            float a2 = (float) (Math.PI * (i + 1) / segments);
            float x1 = cx - rx * (float) Math.cos(a1);
            float y1 = baseY + ry * (float) Math.sin(a1);
            float x2 = cx - rx * (float) Math.cos(a2);
            float y2 = baseY + ry * (float) Math.sin(a2);
            shapes.triangle(cx, baseY, x1, y1, x2, y2);
        }
    }

    /** Draws the Nordic Valknut (three interlocking triangles) with ShapeRenderer. */
    private void drawValknut(float cx, float cy, float r) {
        float h = r * 0.866f;
        float lineW = 1.4f;

        // Triangle 1 (Top)
        float x1 = cx, y1 = cy + r * 0.75f;
        float x2 = cx - h * 0.7f, y2 = cy - r * 0.45f;
        float x3 = cx + h * 0.7f, y3 = cy - r * 0.45f;
        shapes.rectLine(x1, y1, x2, y2, lineW);
        shapes.rectLine(x2, y2, x3, y3, lineW);
        shapes.rectLine(x3, y3, x1, y1, lineW);

        // Triangle 2 (Bottom Left)
        float x4 = cx - r * 0.45f, y4 = cy + r * 0.15f;
        float x5 = cx - r * 0.75f, y5 = cy - r * 0.45f;
        float x6 = cx + r * 0.15f, y6 = cy - r * 0.45f;
        shapes.rectLine(x4, y4, x5, y5, lineW);
        shapes.rectLine(x5, y5, x6, y6, lineW);
        shapes.rectLine(x6, y6, x4, y4, lineW);

        // Triangle 3 (Bottom Right)
        float x7 = cx + r * 0.45f, y7 = cy + r * 0.15f;
        float x8 = cx - r * 0.15f, y8 = cy - r * 0.45f;
        float x9 = cx + r * 0.75f, y9 = cy - r * 0.45f;
        shapes.rectLine(x7, y7, x8, y8, lineW);
        shapes.rectLine(x8, y8, x9, y9, lineW);
        shapes.rectLine(x9, y9, x7, y7, lineW);
    }



    /** Draws the bottom stat panel with square class crest and 4 segmented cyan bars. */
    private void renderStatTablet(float x, float y, float w, PlayerCardData player) {
        float tabletX = x + 10f;
        float tabletY = y + 16f;
        float tabletW = w - 20f;
        float tabletH = 92f;

        shapes.begin(ShapeRenderer.ShapeType.Filled);

        // Inset panel background
        shapes.setColor(Color.valueOf("07101E"));
        shapes.rect(tabletX, tabletY, tabletW, tabletH);
        shapes.setColor(Color.valueOf("1C324E"));
        shapes.rect(tabletX, tabletY + tabletH - 1.5f, tabletW, 1.5f);
        shapes.rect(tabletX, tabletY, tabletW, 1.5f);

        // Left Crest Emblem
        float crestSize = 42f;
        float crestX = tabletX + 6f;
        float crestY = tabletY + (tabletH - crestSize) / 2f;

        shapes.setColor(Color.valueOf("355478"));
        shapes.rect(crestX - 1.5f, crestY - 1.5f, crestSize + 3f, crestSize + 3f);
        shapes.setColor(Color.valueOf("0B1626"));
        shapes.rect(crestX, crestY, crestSize, crestSize);

        // Crest inner Valknut or symbol
        shapes.setColor(Color.valueOf("7B1FA2"));
        drawValknut(crestX + crestSize / 2f, crestY + crestSize / 2f, 11f);

        // 4 Stat Rows (DMG, DEF, SPD, DEX)
        int[] statValues = {player.stats.attack, player.stats.defense, player.stats.speed, player.stats.dexterity};
        int maxSegments = 8;
        float barStartX = crestX + crestSize + (w > 250f ? 44f : 34f);
        float segW = w > 250f ? 11f : (w > 230f ? 9f : 7.5f);
        float segH = 6.5f;
        float segGap = 2.2f;
        float rowSpacing = 18f;

        for (int row = 0; row < 4; row++) {
            // Row 0 is at top (row = 0 -> top Y, row = 3 -> bottom Y)
            float rowY = tabletY + tabletH - 20f - row * rowSpacing;
            int val = statValues[row];

            for (int seg = 0; seg < maxSegments; seg++) {
                float sx = barStartX + seg * (segW + segGap);
                if (seg < val) {
                    // Luminous Cyan filled segment
                    shapes.setColor(Color.valueOf("00E5FF"));
                    shapes.rect(sx, rowY, segW, segH);
                    shapes.setColor(Color.valueOf("E0F7FA")); // top edge highlight
                    shapes.rect(sx, rowY + segH - 1.5f, segW, 1.5f);
                } else {
                    // Dark Navy/Slate unfilled segment
                    shapes.setColor(Color.valueOf("122034"));
                    shapes.rect(sx, rowY, segW, segH);
                }
            }
        }

        shapes.end();
    }

    @Override
    public void resize(int width, int height) {
        viewport.update(width, height, true);
    }

    @Override public void pause() {}
    @Override public void resume() {}
    @Override public void hide() { dispose(); }

    @Override
    public void dispose() {
        if (disposed) { return; }
        disposed = true;
        batch.dispose();
        shapes.dispose();
        ui.close();
        textures.forEach(Texture::dispose);
    }
}
