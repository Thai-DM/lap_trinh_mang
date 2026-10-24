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
import com.smashprofs.game.network.client.WireMatchResult;
import com.smashprofs.game.ui.OnlineUi;
import java.util.*;

/**
 * Brawlhalla Podium Match Results Screen.
 * Features:
 * - Celestial blue atmospheric background with starlight sparkles and mist.
 * - Dynamic Stone Pillars (Podiums) that adapt to 2, 3, or 4 players.
 * - Character models standing proudly on top with 4-frame idle animations.
 * - Golden Crown for the 1st place winner.
 * - Platform discs with glowing cyan runic circles.
 * - Detailed Scoreboard Cards mounted on each pillar with Rank badges (1st, 2nd, 3rd, 4th),
 *   Score, KOS, Falls, Damage Done, and Final HP stats.
 * - Bottom cloud mist covering the base of the columns.
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

    private final Game game;
    private final WireMatchResult result;
    private final String localId;

    private final Stage stage = new Stage(new FitViewport(1280, 720));
    private final OnlineUi ui = new OnlineUi();
    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();

    private final ArrayList<Texture> textures = new ArrayList<>();
    private final TextureRegion[][] characterFrames = new TextureRegion[5][4];

    private final Texture bgTexture;
    private final Texture pillarTexture;
    private final Texture discTexture;
    private final Texture fogTexture;

    private final BitmapFont titleFont;
    private final BitmapFont crownFont;
    private final java.util.List<WireMatchResult.Participant> sortedList;

    private float stateTime = 0f;
    private boolean disposed;
    private Label rematchLabel;
    private static String lastUpdatedMatchId = "";

    public OnlineResultScreen(Game game, WireMatchResult result, String localId) {
        this.game = game;
        this.result = result;
        this.localId = localId;

        // 1. Load Background texture
        bgTexture = new Texture(Gdx.files.internal("mainmenu/bgmenu.png"));
        textures.add(bgTexture);

        titleFont = new BitmapFont(Gdx.files.internal("ui/online/heading.fnt"));
        titleFont.getRegion().getTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);

        crownFont = new BitmapFont(Gdx.files.internal("ui/online/heading.fnt"));
        crownFont.getData().setScale(1.2f);

        // 2. Load 5 Character Animation Strips (4 frames of 100x100 each)
        for (int i = 0; i < PORTRAIT_FILES.length; i++) {
            var tex = new Texture(Gdx.files.internal(PORTRAIT_FILES[i]));
            tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            textures.add(tex);
            var split = TextureRegion.split(tex, 100, 100);
            for (int f = 0; f < 4; f++) {
                characterFrames[i][f] = split[0][f < split[0].length ? f : 0];
            }
        }

        // 3. Generate Procedural Stone Pillar, Disc, and Fog Textures
        pillarTexture = createPillarTexture();
        discTexture = createDiscTexture();
        fogTexture = createFogTexture();
        textures.add(pillarTexture);
        textures.add(discTexture);
        textures.add(fogTexture);

        // 4.1 Update local Elo if PvP and not yet updated for this match
        if (!result.isBoss() && !result.matchId().equals(lastUpdatedMatchId)) {
            lastUpdatedMatchId = result.matchId();
            boolean localWon = result.winner() != null && result.winner().playerId().equals(localId);
            int localDelta = result.isDraw() ? 0 : (localWon ? 16 : -16);
            var profile = com.smashprofs.game.profile.PlayerProfile.get();
            profile.setElo(profile.getElo() + localDelta);
        }

        // 4. Sort Participants: Winner first (Rank 1), then by rank
        sortedList = new ArrayList<>(result.participants());
        sortedList.sort((a, b) -> {
            boolean aWin = a.playerId().equals(result.winnerId());
            boolean bWin = b.playerId().equals(result.winnerId());
            if (aWin && !bWin) return -1;
            if (!aWin && bWin) return 1;
            if (result.gameplay() != null) {
                var sa = result.gameplay().player(a.playerId());
                var sb = result.gameplay().player(b.playerId());
                if (sa.lives() != sb.lives()) return Integer.compare(sb.lives(), sa.lives());
                if (sa.kills() != sb.kills()) return Integer.compare(sb.kills(), sa.kills());
                if (sa.damageDealt() != sb.damageDealt()) return Double.compare(sb.damageDealt(), sa.damageDealt());
            }
            return Float.compare(b.finalHp(), a.finalHp());
        });

        // 5. Build Stage UI (Top Header and Scoreboard Cards)
        buildUi();
    }

    private void buildUi() {
        stage.clear();
        if(result.isBoss()) {
            var credit=new Table();credit.setFillParent(true);credit.bottom().padBottom(8);
            credit.add(ui.small("Boss: Demon Slime · chierit · CC BY 4.0 · chierit.itch.io/boss-demon-slime",OnlineUi.MUTED));stage.addActor(credit);
        }

        // Determine Winner / Champion
        var winner = result.winner();
        boolean isDraw = result.isDraw();
        boolean localWon = winner != null && winner.playerId().equals(localId);

        String winnerCharName = winner != null ? winner.characterId().toUpperCase(Locale.ROOT) : "CHIẾN BINH";
        String mainTitle = result.isBoss()?(result.teamWon()?"CẢ ĐỘI CHIẾN THẮNG!":"CẢ ĐỘI THẤT BẠI"):isDraw ? "TRẬN ĐẤU HÒA!" : (localWon ? "BẠN ĐÃ CHIẾN THẮNG!" : winnerCharName + " WINS!");
        Color titleColor = result.isBoss()?(result.teamWon()?OnlineUi.BRAWL_GOLD:OnlineUi.DANGER):isDraw ? Color.WHITE : (localWon ? OnlineUi.BRAWL_GOLD : OnlineUi.BRAWL_CYAN);

        // --- TOP HEADER BAR ---
        var headerBar = new Table();
        headerBar.setFillParent(true);
        headerBar.top().padTop(14).padLeft(24).padRight(24);

        // Top Left Button: VỀ SẢNH
        var backBtn = ui.comicButton("< VỀ SẢNH [ESC]", "comic-white", this::back);
        headerBar.add(backBtn).height(44).left();

        // Top Center: Title Ribbon Capsule + Keyboard action chips
        var centerBox = new Table();

        // Capsule banner
        var titleCapsule = new Table();
        titleCapsule.setBackground(ui.comicBox(Color.valueOf("061324E8"), titleColor, 3f, 2, 4));
        titleCapsule.pad(6, 24, 6, 24);

        var titleLabel = ui.comicHeading(mainTitle, titleColor);
        titleCapsule.add(titleLabel);
        centerBox.add(titleCapsule).center().row();

        // Keyboard Action Buttons Row below Title
        var actionChips = new Table();
        actionChips.padTop(6);

        var lobby = game.getLobbyClient();
        boolean isRanked = lobby != null && lobby.isMatchmaking();
        if (!isRanked) {
            var playAgainBtn = ui.comicButton("ĐẤU LẠI [ENTER]", "comic-gold", this::playAgain);
            actionChips.add(playAgainBtn).height(34).padRight(12);
        } else {
            var rankedBadge = new Table();
            rankedBadge.setBackground(ui.box("12243B", 4));
            rankedBadge.pad(4, 10, 4, 10);
            rankedBadge.add(ui.small("ĐẤU XẾP HẠNG · VỀ SẢNH ĐỂ GHÉP TRẬN MỚI", OnlineUi.BRAWL_GOLD));
            actionChips.add(rankedBadge).height(34).padRight(12);
        }

        var reason = switch (result.finishReason()) {
            case "BOSS_DEFEATED" -> "Đã hạ Demon Slime · Chiến thắng hợp tác";
            case "TEAM_ELIMINATED" -> "Cả đội đã hết mạng · Thử lại và né vùng đỏ";
            case "BOSS_TIMEOUT" -> "Hết giờ · Boss còn "+Math.round(result.gameplay().boss().hp())+" HP";
            case "TIME_LIMIT" -> result.gameplay()!=null && result.gameplay().fighter()!=null?"Hết giờ · Xét mạng, rồi HP cao nhất":"Hết giờ · Xét HP cao nhất";
            case "LAST_PLAYER_STANDING" -> "Sống sót cuối cùng";
            default -> "Đấu trường sinh tử";
        };
        var subChip = ui.small(reason, Color.valueOf("94C2FA"));
        actionChips.add(subChip).center();

        centerBox.add(actionChips).center();
        centerBox.row();rematchLabel=ui.small("",OnlineUi.BRAWL_GOLD);centerBox.add(rematchLabel).padTop(3);
        headerBar.add(centerBox).expandX().center();

        // Top Right: Room & Duration Badge
        var infoBadge = new Table();
        infoBadge.setBackground(ui.comicBox(Color.valueOf("061324"), OnlineUi.BRAWL_BORDER, 2f, 0, 0));
        infoBadge.pad(5, 12, 5, 12);
        String durStr = result.gameplay() != null ? String.format(Locale.ROOT, "%.1fs", result.gameplay().durationTicks() / 60f) : "";
        infoBadge.add(ui.small("PHÒNG #" + result.roomId() + (durStr.isEmpty() ? "" : " · " + durStr), OnlineUi.BRAWL_CYAN));
        headerBar.add(infoBadge).height(44).right();

        stage.addActor(headerBar);

        // --- PODIUM SCOREBOARD CARDS ---
        int count = Math.max(1, sortedList.size());
        float pillarW, gap;
        if (count <= 1) {
            pillarW = 280; gap = 0;
        } else if (count == 2) {
            pillarW = 270; gap = 70;
        } else if (count == 3) {
            pillarW = 245; gap = 36;
        } else {
            pillarW = 230; gap = 22;
        }

        float totalSpan = count * pillarW + (count - 1) * gap;
        float startX = (1280f - totalSpan) / 2f;

        for (int i = 0; i < count; i++) {
            var p = sortedList.get(i);
            boolean isWinner = result.isBoss()?result.teamWon():p.playerId().equals(result.winnerId());
            boolean isLocal = p.playerId().equals(localId);

            float pillarX = startX + i * (pillarW + gap);
            float platformY = getPlatformY(i, count);

            // Card container attached to the front of each pillar
            var card = new Table();
            float cardW = pillarW - 14;
            float cardH = 270;
            float cardX = pillarX + 7;
            float cardY = platformY - 26 - cardH;

            card.setBounds(cardX, cardY, cardW, cardH);

            Color borderColor = isWinner ? OnlineUi.BRAWL_GOLD : (isLocal ? OnlineUi.BRAWL_CYAN : Color.valueOf("1C3552"));
            Color bgColor = Color.valueOf("061324F0");
            card.setBackground(ui.comicBox(bgColor, borderColor, isWinner ? 3f : 2f, 2, 4));
            card.pad(6, 8, 6, 8);
            card.defaults().top();

            // 1. Rank Badge on top
            String rankText = (i + 1) + switch (i + 1) {
                case 1 -> "st";
                case 2 -> "nd";
                case 3 -> "rd";
                default -> "th";
            };
            if(result.isBoss()) { rankText="ĐỒNG ĐỘI "+(p.slotIndex()+1); }
            Color rankColor = switch (i) {
                case 0 -> OnlineUi.BRAWL_GOLD;
                case 1 -> Color.valueOf("38BDF8");
                case 2 -> Color.valueOf("FB923C");
                default -> Color.valueOf("94A3B8");
            };
            var rankBadge = new Table();
            rankBadge.setBackground(ui.box("050C17", 4));
            rankBadge.pad(2, 10, 2, 10);
            rankBadge.add(ui.small(rankText, rankColor));
            card.add(rankBadge).center().padBottom(4).row();

            // 2. Character Name & Identity
            var nameBox = new Table();
            var heroIcon = new Image(getCharacterRegion(p.characterId()));
            nameBox.add(heroIcon).size(28, 28).padRight(6);

            var nameCol = new Table();
            nameCol.defaults().left();
            String pName = p.characterId().toUpperCase(Locale.ROOT);
            var nameLbl = ui.small(pName, isWinner ? OnlineUi.BRAWL_GOLD : (isLocal ? OnlineUi.BRAWL_CYAN : Color.WHITE));
            nameCol.add(nameLbl).row();

            String subtitle = "Player " + (p.slotIndex() + 1) + (isLocal ? " (Bạn)" : "");
            nameCol.add(ui.small(subtitle, isLocal ? OnlineUi.BRAWL_CYAN : Color.valueOf("7EA2C7")));
            nameBox.add(nameCol).left();

            card.add(nameBox).growX().left().padBottom(6).row();

            // 3. Stats Rows (Dark framed box)
            var statsBox = new Table();
            statsBox.setBackground(ui.box("030812", 4));
            statsBox.pad(6, 8, 6, 8);
            statsBox.defaults().growX();

            int kills = 0, deaths = 0;
            double dmgDone = 0;
            if (result.gameplay() != null) {
                var progress = result.gameplay().player(p.playerId());
                kills = progress.kills();
                deaths = progress.deaths();
                dmgDone = progress.damageDealt();
            }

            int score = kills * 100 + (int) (dmgDone / 10) + (isWinner ? 500 : 0);

            statsBox.add(createStatRow("SCORE", Integer.toString(score), OnlineUi.BRAWL_GOLD)).row();
            statsBox.add(createStatRow("KOS", Integer.toString(kills), Color.valueOf("35F3B7"))).padTop(3).row();
            statsBox.add(createStatRow("FALLS", Integer.toString(deaths), Color.valueOf("FF7A85"))).padTop(3).row();
            statsBox.add(createStatRow("DMG DONE", String.format(Locale.ROOT, "%.0f", dmgDone), Color.valueOf("FFE066"))).padTop(3).row();
            boolean fighter=result.gameplay()!=null && result.gameplay().fighter()!=null;
            statsBox.add(createStatRow("FINAL HP",Math.round(p.finalHp())+" HP",Color.valueOf("E0F2FE"))).padTop(3).row();
            if (result.isBoss()) {
                statsBox.add(createStatRow("ELO", "KHÔNG ĐỔI (BOSS)", Color.valueOf("94A3B8"))).padTop(3).row();
            } else {
                int delta = isDraw ? 0 : (isWinner ? 16 : -16);
                String deltaStr = (delta >= 0 ? "+" : "") + delta;
                Color eloColor = delta > 0 ? Color.valueOf("35F3B7") : (delta < 0 ? Color.valueOf("FF7A85") : Color.valueOf("CDE6FF"));
                if (isLocal) {
                    int elo = com.smashprofs.game.profile.PlayerProfile.get().getElo();
                    statsBox.add(createStatRow("ELO", elo + " (" + deltaStr + ")", eloColor)).padTop(3).row();
                } else {
                    statsBox.add(createStatRow("ELO", deltaStr, eloColor)).padTop(3).row();
                }
            }

            card.add(statsBox).growX().padTop(2);

            stage.addActor(card);
        }
    }

    private Table createStatRow(String label, String value, Color valColor) {
        var row = new Table();
        var lbl = ui.small(label, Color.valueOf("84A2C4"));
        var val = ui.small(value, valColor);
        row.add(lbl).left().expandX();
        row.add(val).right();
        return row;
    }

    private float getPlatformY(int rankIndex, int count) {
        if(result.isBoss()) { return 355f; }
        if (count <= 2) {
            return rankIndex == 0 ? 380f : 335f;
        } else if (count == 3) {
            return switch (rankIndex) {
                case 0 -> 385f;
                case 1 -> 345f;
                default -> 315f;
            };
        } else {
            return switch (rankIndex) {
                case 0 -> 385f;
                case 1 -> 350f;
                case 2 -> 320f;
                default -> 310f;
            };
        }
    }

    private TextureRegion getCharacterRegion(String name) {
        int idx = getCharacterIndex(name);
        return characterFrames[idx][0];
    }

    private int getCharacterIndex(String name) {
        if (name != null) {
            for (int i = 0; i < CHARACTERS.length; i++) {
                if (CHARACTERS[i].equalsIgnoreCase(name)) {
                    return i;
                }
            }
        }
        return 0;
    }

    private Texture createPillarTexture() {
        int width = 256;
        int height = 512;
        var pixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);

        // 1. Shaded stone cylinder gradient
        for (int x = 0; x < width; x++) {
            float norm = Math.abs(x - width / 2f) / (width / 2f);
            float factor = 1.0f - norm * norm * 0.52f;
            float r = 0.22f * factor;
            float g = 0.32f * factor;
            float b = 0.44f * factor;

            pixmap.setColor(r, g, b, 1f);
            pixmap.drawLine(x, 0, x, height - 1);
        }

        // 2. Horizontal stone block seams
        for (int y = 50; y < height; y += 64) {
            pixmap.setColor(Color.valueOf("0D1722"));
            pixmap.drawLine(0, y, width - 1, y);
            pixmap.setColor(Color.valueOf("48688C"));
            pixmap.drawLine(0, y + 1, width - 1, y + 1);
        }

        // 3. Nordic carved diamond interlaces along center
        pixmap.setColor(Color.valueOf("121F2D"));
        for (int cy = 40; cy < height - 30; cy += 48) {
            int cx = width / 2;
            int r = 28;
            pixmap.drawLine(cx, cy - r, cx + r, cy);
            pixmap.drawLine(cx + r, cy, cx, cy + r);
            pixmap.drawLine(cx, cy + r, cx - r, cy);
            pixmap.drawLine(cx - r, cy, cx, cy - r);
        }
        pixmap.setColor(Color.valueOf("4D6E94"));
        for (int cy = 40; cy < height - 30; cy += 48) {
            int cx = width / 2;
            int r = 28;
            pixmap.drawLine(cx, cy - r + 1, cx + r, cy + 1);
            pixmap.drawLine(cx - r, cy + 1, cx, cy + r + 1);
        }

        // 4. Solid outer borders
        pixmap.setColor(Color.valueOf("07101B"));
        pixmap.fillRectangle(0, 0, 4, height);
        pixmap.fillRectangle(width - 4, 0, 4, height);

        var tex = new Texture(pixmap);
        pixmap.dispose();
        return tex;
    }

    private Texture createDiscTexture() {
        int width = 300;
        int height = 52;
        var pixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);

        int cx = width / 2;
        int cy = 26;

        // Outer base bevel
        pixmap.setColor(Color.valueOf("142232"));
        pixmap.fillCircle(cx, cy, 24);
        pixmap.fillRectangle(10, cy, width - 20, 20);

        // Stone ellipse
        pixmap.setColor(Color.valueOf("354C68"));
        for (int dy = -16; dy <= 16; dy++) {
            float rx = 138f * (float) Math.sqrt(Math.max(0, 1.0 - (dy * dy) / (16.0 * 16.0)));
            pixmap.drawLine((int) (cx - rx), cy + dy, (int) (cx + rx), cy + dy);
        }

        // Glowing Cyan runic concentric circles
        pixmap.setColor(OnlineUi.BRAWL_CYAN);
        for (int dy = -12; dy <= 12; dy++) {
            float rx = 114f * (float) Math.sqrt(Math.max(0, 1.0 - (dy * dy) / (12.0 * 12.0)));
            pixmap.drawPixel((int) (cx - rx), cy + dy);
            pixmap.drawPixel((int) (cx + rx), cy + dy);
        }

        pixmap.setColor(Color.valueOf("7DF9FF"));
        for (int dy = -8; dy <= 8; dy++) {
            float rx = 82f * (float) Math.sqrt(Math.max(0, 1.0 - (dy * dy) / (8.0 * 8.0)));
            pixmap.drawPixel((int) (cx - rx), cy + dy);
            pixmap.drawPixel((int) (cx + rx), cy + dy);
        }

        // Radiating tick marks
        pixmap.setColor(OnlineUi.BRAWL_CYAN);
        pixmap.drawLine(cx - 114, cy, cx - 82, cy);
        pixmap.drawLine(cx + 82, cy, cx + 114, cy);
        pixmap.drawLine(cx, cy - 12, cx, cy - 8);
        pixmap.drawLine(cx, cy + 8, cx, cy + 12);

        var tex = new Texture(pixmap);
        pixmap.dispose();
        return tex;
    }

    private Texture createFogTexture() {
        int width = 1280;
        int height = 80;
        var pixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);

        for (int y = 0; y < height; y++) {
            float a = (1.0f - y / (float) height) * 0.45f;
            pixmap.setColor(0.75f, 0.90f, 1.0f, a);
            pixmap.drawLine(0, y, width - 1, y);
        }

        var tex = new Texture(pixmap);
        pixmap.dispose();
        return tex;
    }

    private void back() {
        game.leaveOnlineLobby();
        game.setScreen(new OnlineLobbyScreen(game));
    }
    private void playAgain() {
        if(result.gameplay()!=null && result.gameplay().fighter()!=null && game.getLobbyClient()!=null) { game.getLobbyClient().requestRematch(); }
        else { back(); }
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(stage);
    }

    @Override
    public void render(float delta) {
        if (disposed) return;

        stateTime += delta;
        var lobby=game.getLobbyClient();
        if(lobby!=null && result.gameplay()!=null && result.gameplay().fighter()!=null) {
            rematchLabel.setText(lobby.getRematchStatus());
            if(lobby.getSnapshot().phase()==com.smashprofs.game.network.client.LobbyClient.Phase.WAITING_FOR_START
                    || lobby.getMatchSession()!=null && !lobby.getMatchSession().getRoster().matchId().equals(result.matchId())) {
                game.setScreen(new OnlineLoadingScreen(game,lobby));return;
            }
        }

        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            back();
            return;
        }
        boolean isRanked = lobby != null && lobby.isMatchmaking();
        if(!isRanked && (Gdx.input.isKeyJustPressed(Input.Keys.ENTER) || Gdx.input.isKeyJustPressed(Input.Keys.SPACE))) { playAgain(); }

        // 1. Clear with Deep Brawlhalla Space Blue
        ScreenUtils.clear(Color.valueOf("00224A"));

        // 2. Draw Background & Twinkling Starlight
        batch.setProjectionMatrix(stage.getCamera().combined);
        batch.begin();
        batch.setColor(0.04f, 0.22f, 0.55f, 0.40f);
        batch.draw(bgTexture, 0, 0, 1280, 720);
        batch.setColor(Color.WHITE);

        // 3. Draw Stone Pillars (Columns)
        int count = Math.max(1, sortedList.size());
        float pillarW, gap;
        if (count <= 1) {
            pillarW = 280; gap = 0;
        } else if (count == 2) {
            pillarW = 270; gap = 70;
        } else if (count == 3) {
            pillarW = 245; gap = 36;
        } else {
            pillarW = 230; gap = 22;
        }

        float totalSpan = count * pillarW + (count - 1) * gap;
        float startX = (1280f - totalSpan) / 2f;

        for (int i = 0; i < count; i++) {
            float pillarX = startX + i * (pillarW + gap);
            float platformY = getPlatformY(i, count);

            // Pillar column body
            batch.draw(pillarTexture, pillarX, 0, pillarW, platformY - 14);

            // Top Disc platform
            float discW = pillarW + 24;
            float discH = 36;
            float discX = pillarX - 12;
            float discY = platformY - 20;
            batch.draw(discTexture, discX, discY, discW, discH);
        }

        // 4. Draw Characters standing on top of their Podium Discs
        int currentFrame = (int) (stateTime * 4.5f) % 4;
        for (int i = 0; i < count; i++) {
            var p = sortedList.get(i);
            boolean isWinner = result.isBoss()?result.teamWon():p.playerId().equals(result.winnerId());
            boolean isLocal = p.playerId().equals(localId);

            float pillarX = startX + i * (pillarW + gap);
            float platformY = getPlatformY(i, count);

            int charIdx = getCharacterIndex(p.characterId());
            var region = characterFrames[charIdx][currentFrame];

            float charW = 145;
            float charH = 145;
            float charX = pillarX + (pillarW - charW) / 2f;
            float charY = platformY - 6;

            // Character sprite
            batch.draw(region, charX, charY, charW, charH);

            // Winner Crown 👑
            if (isWinner) {
                crownFont.setColor(OnlineUi.BRAWL_GOLD);
                crownFont.draw(batch, "👑", charX + charW / 2f - 18, charY + charH + 24);
            }

            // Local Player Indicator
            if (isLocal) {
                titleFont.setColor(OnlineUi.BRAWL_CYAN);
                titleFont.getData().setScale(0.85f);
                titleFont.draw(batch, "▲ BẠN", charX + charW / 2f - 30, charY + charH + (isWinner ? 48 : 22));
                titleFont.getData().setScale(1f);
            }
        }

        // 5. Draw Bottom Fog Mist Layer
        batch.draw(fogTexture, 0, 0, 1280, 75);

        batch.end();

        // 6. Draw Stage UI Elements (Cards and Top Header)
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
        if (disposed) return;
        disposed = true;
        if (Gdx.input.getInputProcessor() == stage) {
            Gdx.input.setInputProcessor(null);
        }
        stage.dispose();
        batch.dispose();
        shapes.dispose();
        titleFont.dispose();
        crownFont.dispose();
        ui.close();
        textures.forEach(Texture::dispose);
    }
}
