package com.smashprofs.game.Screens;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.smashprofs.common.model.MatchRules;
import com.smashprofs.game.Game;
import com.smashprofs.game.network.client.*;
import com.smashprofs.game.ui.OnlineUi;
import java.util.*;

/**
 * Brawlhalla-styled Custom Online Game Room.
 * Features:
 * - Top header with room code, quick stats badge, copy button, and settings button.
 * - Horizontal character selection roster with neon glow highlights.
 * - 4 player pedestals displaying character sprites in real-time as players join and select heroes.
 * - Comic modal dialog for host game rules configuration (Stock, Time, Items, Max Players).
 * - High-contrast crisp typography and comic artwork aesthetics.
 */
public class OnlineRoomScreen implements Screen {
    private static final String[] CHARACTERS = {"Alex", "Maurice", "Luca", "Leo", "Viktor"};
    private static final String[] PORTRAIT_FILES = {
        "Sprites/Alex/alex_stand.png",
        "Sprites/Momo/momo_strip.png",
        "Sprites/Luca/luca_stand.png",
        "Sprites/Leo/leo_stand.png",
        "Sprites/Viktor/viktor_stand.png"
    };
    private static final Integer[] TIMES = {0, 60, 90, 120, 180, 300}, LIVES = {1, 2, 3, 5};

    private final Game game;
    private final LobbyClient lobby;
    private final OnlineUi ui = new OnlineUi();
    private final Stage stage = new Stage(new FitViewport(1280, 720));
    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final ArrayList<Texture> textures = new ArrayList<>();
    private final TextureRegion[] characterRegions = new TextureRegion[5];

    // Header & Info
    private final String roomId;
    private final Label roomRuleSummary, memberCountLabel;
    private final TextButton copyBtn, settingsBtn, leaveBtn;
    private float copiedSeconds;

    // Character Selection Roster
    private final Table[] characterCards = new Table[5];
    private String selected = "Alex";
    private boolean initialCharacterSynced;

    // 4 Player Pedestals
    private final Table[] slotPedestalCard = new Table[4];
    private final Table[] slotPedestalBase = new Table[4];
    private final Image[] slotCharacterImg = new Image[4];
    private final Label[] slotNameLabel = new Label[4];
    private final Label[] slotHeroLabel = new Label[4];
    private final Table[] slotStatsBox = new Table[4];
    private final String[] slotLastChar = new String[4];
    private final Table[] slotStatusBadge = new Table[4];
    private final Label[] slotStatusLabel = new Label[4];

    // Action & Status
    private final TextButton readyBtn;
    private final Label readyHint, statusLabel;

    // Game Rules Modal
    private final Table settingsModal;
    private final SelectBox<String> maxSelect, timeSelect, livesSelect, dropsSelect, throwSelect;
    private final TextButton applyRulesBtn, resetRulesBtn;
    private final Label ownershipLabel, draftNoticeLabel;
    private long displayedRules = -1;
    private boolean initialRuleSync = true;

    private final Texture bgTexture;
    private boolean disposed;

    public OnlineRoomScreen(Game game) {
        this.game = game;
        this.lobby = Objects.requireNonNull(game.getLobbyClient());
        this.roomId = lobby.getSnapshot().roomId();

        // 1. Background
        bgTexture = new Texture(Gdx.files.internal("mainmenu/bgmenu.png"));
        textures.add(bgTexture);

        // 2. Load 5 Character sprites (Frame 0, 100x100)
        for (int i = 0; i < PORTRAIT_FILES.length; i++) {
            var tex = new Texture(Gdx.files.internal(PORTRAIT_FILES[i]));
            tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            textures.add(tex);
            characterRegions[i] = new TextureRegion(tex, 0, 0, 100, 100);
        }

        // --- ROOT LAYOUT ---
        var root = new Table();
        root.setFillParent(true);
        root.pad(16, 28, 12, 28);
        root.defaults().top();

        // ================= HEADER BAR =================
        var header = new Table();

        // Left buttons: Rời phòng + Cài đặt luật
        leaveBtn = ui.comicButton("< RỜI PHÒNG", "comic-white", this::leave);
        settingsBtn = ui.comicButton("⚙ CÀI ĐẶT LUẬT [X]", "comic-primary", this::toggleSettings);
        header.add(leaveBtn).height(44).padRight(10);
        header.add(settingsBtn).height(44);

        // Center room summary badge
        var summaryBox = new Table();
        summaryBox.setBackground(ui.comicBox(Color.valueOf("051428"), OnlineUi.BRAWL_BORDER, 2.5f, 2, 2));
        summaryBox.pad(4, 16, 4, 16);
        roomRuleSummary = ui.small("PHÒNG #" + roomId, Color.WHITE);
        summaryBox.add(roomRuleSummary);
        header.add(summaryBox).expandX().center();

        // Right: Member count + Copy code button
        var memberBadge = new Table();
        memberBadge.setBackground(ui.comicBox(Color.valueOf("051428"), OnlineUi.BRAWL_BORDER, 2f, 0, 0));
        memberBadge.pad(4, 10, 4, 10);
        memberCountLabel = ui.small("1/4 NGƯỜI", OnlineUi.BRAWL_CYAN);
        memberBadge.add(memberCountLabel);
        header.add(memberBadge).height(44).padRight(10);

        copyBtn = ui.comicButton("SAO CHÉP MÃ", "comic-gold", () -> {
            Gdx.app.getClipboard().setContents(roomId);
            copiedSeconds = 2f;
        });
        header.add(copyBtn).height(44);

        root.add(header).growX();
        root.row();

        // ================= CHARACTER SELECTION ROSTER (UPPER ROW) =================
        var rosterBox = new Table();
        rosterBox.padTop(10).padBottom(4);
        var rosterTitle = ui.small("CHỌN NHÂN VẬT CỦA BẠN", Color.valueOf("B8D8FF"));
        rosterBox.add(rosterTitle).center().padBottom(6);
        rosterBox.row();

        var heroesRow = new Table();
        for (int i = 0; i < CHARACTERS.length; i++) {
            final String name = CHARACTERS[i];
            var card = new Table();
            card.setTouchable(Touchable.enabled);
            card.setBackground(ui.comicBox(Color.valueOf("091424"), OnlineUi.BRAWL_BORDER, 2.5f, 3, 3));
            card.pad(4, 8, 4, 8);

            var portraitImg = new Image(characterRegions[i]);
            card.add(portraitImg).size(60, 60).center();
            card.row();
            var nameLbl = ui.small(name, Color.WHITE);
            card.add(nameLbl).center().padTop(2);

            card.addListener(new ClickListener() {
                @Override public void clicked(InputEvent event, float x, float y) {
                    if (lobby.getSnapshot().canReady() && lobby.getSnapshot().phase() != LobbyClient.Phase.WAITING_FOR_START) {
                        selected = name;
                        lobby.selectCharacter(name);
                    }
                }
            });

            characterCards[i] = card;
            heroesRow.add(card).width(115).height(92).padRight(i == 4 ? 0 : 14);
        }
        rosterBox.add(heroesRow).center();
        root.add(rosterBox).growX();
        root.row();

        // ================= 4 PLAYER PEDESTALS (LOWER AREA) =================
        var pedestalsRow = new Table();
        pedestalsRow.defaults().top();

        for (int i = 0; i < 4; i++) {
            var pedestal = new Table();
            pedestal.setBackground(ui.comicBox(OnlineUi.BRAWL_DARK, OnlineUi.BRAWL_BORDER, 3.5f, 4, 6));
            pedestal.pad(6, 6, 8, 6);

            // Upper area: Character standing on glowing circle platform
            var stageArea = new Table();

            slotCharacterImg[i] = new Image(characterRegions[0]);
            stageArea.add(slotCharacterImg[i]).size(130, 130).center().padBottom(-14);
            stageArea.row();

            slotPedestalBase[i] = new Table();
            slotPedestalBase[i].setBackground(ui.comicBox(Color.valueOf("004AA5"), Color.valueOf("00FFA2"), 2.5f, 1, 1));
            slotPedestalBase[i].add().size(175, 18);
            stageArea.add(slotPedestalBase[i]).center();

            pedestal.add(stageArea).growX().padTop(2).padBottom(4);
            pedestal.row();

            // Tablet / Info beneath pedestal
            var tablet = new Table();
            tablet.setBackground(ui.comicBox(Color.valueOf("061324"), OnlineUi.BRAWL_BORDER, 2f, 0, 0));
            tablet.pad(4, 6, 6, 6);

            slotNameLabel[i] = ui.small("P" + (i + 1) + "  ·  TRỐNG", Color.WHITE);
            slotHeroLabel[i] = ui.heading("ALEX", Color.WHITE);

            slotStatsBox[i] = new Table();
            slotStatsBox[i].setVisible(false);

            slotStatusBadge[i] = new Table();
            slotStatusBadge[i].setBackground(ui.comicBox(Color.valueOf("0D1B2A"), OnlineUi.BRAWL_BORDER, 1.5f, 0, 0));
            slotStatusLabel[i] = ui.small("ĐANG CHỜ NGƯỜI", OnlineUi.MUTED);
            slotStatusBadge[i].add(slotStatusLabel[i]).pad(2, 6, 2, 6);

            tablet.add(slotNameLabel[i]).center();
            tablet.row();
            tablet.add(slotHeroLabel[i]).center().padTop(1).padBottom(2);
            tablet.row();
            tablet.add(slotStatsBox[i]).center().padBottom(4);
            tablet.row();
            tablet.add(slotStatusBadge[i]).center();

            pedestal.add(tablet).growX().bottom();

            slotPedestalCard[i] = pedestal;
            pedestalsRow.add(pedestal).width(286).height(340).padRight(i == 3 ? 0 : 20);
        }

        root.add(pedestalsRow).padTop(4);
        root.row();

        // ================= BOTTOM ACTION BAR =================
        var actionBar = new Table();
        readyBtn = ui.comicButton("SẴN SÀNG  [ENTER]", "comic-primary", this::toggleReady);
        readyBtn.setName("ready");settingsBtn.setName("room-settings");
        actionBar.add(readyBtn).width(360).height(48).center();
        actionBar.row();

        readyHint = ui.small("Mỗi người chọn nhân vật rồi bấm Sẵn sàng.", Color.valueOf("E0F0FF"));
        if(lobby.isMatchmaking()) { settingsBtn.setVisible(false);copyBtn.setVisible(false); }
        actionBar.add(readyHint).center().padTop(4);
        actionBar.row();

        statusLabel = ui.small("A/D di chuyển · W nhảy · S dậm · V/Space tấn công · F tuyệt chiêu · Ctrl đỡ đòn", Color.valueOf("94B8E6"));
        actionBar.add(statusLabel).center().padTop(2);

        root.add(actionBar).growX().padTop(6);
        stage.addActor(root);

        // ================= GAME RULES MODAL OVERLAY =================
        maxSelect = select("2 người", "3 người", "4 người");
        timeSelect = lobby.getSettings()!=null && lobby.getSettings().rules().boss()?select("60 giây", "90 giây", "120 giây", "180 giây", "300 giây"):select("Không giới hạn", "60 giây", "90 giây", "120 giây", "180 giây", "300 giây");
        livesSelect = select("1 mạng", "2 mạng", "3 mạng", "5 mạng");
        dropsSelect = select("Tắt", "Thấp · 30s/lần", "Vừa · 15s/lần", "Cao · 8s/lần");
        throwSelect = select("Ném mất vũ khí (Mặc định)", "Ném giữ lại vũ khí");
        maxSelect.setName("max-players");timeSelect.setName("match-time");livesSelect.setName("lives");dropsSelect.setName("drops");throwSelect.setName("throw-rule");

        applyRulesBtn = ui.comicButton("LƯU LUẬT PHÒNG", "comic-primary", () -> lobby.updateSettings(draft()));
        applyRulesBtn.setName("save-rules");
        resetRulesBtn = ui.comicButton("HOÀN TÁC", "comic-white", () -> {
            if (lobby.getSettings() != null) {
                sync(lobby.getSettings().rules());
            }
        });

        ownershipLabel = ui.small("BẠN LÀ CHỦ PHÒNG", OnlineUi.BRAWL_CYAN);
        draftNoticeLabel = ui.small("Luật đã đồng bộ với máy chủ.", OnlineUi.MUTED);

        settingsModal = createSettingsModal();
        stage.addActor(settingsModal);
    }

    private Table createSettingsModal() {
        var overlay = new Table();
        overlay.setFillParent(true);
        overlay.setBackground(ui.skin.newDrawable("pixel", new Color(0, 0, 0, 0.78f)));
        overlay.setVisible(false);

        var dialog = new Table();
        dialog.setBackground(ui.comicBox(Color.valueOf("09182E"), OnlineUi.BRAWL_CYAN, 4.5f, 8, 10));
        dialog.pad(18, 24, 18, 24);

        // Header: Title + Close button
        var dlgHeader = new Table();
        var dlgTitle = ui.comicHeading("CÀI ĐẶT LUẬT PHÒNG", Color.WHITE);
        var closeBtn = ui.comicButton("X", "comic-white", this::toggleSettings);
        dlgHeader.add(dlgTitle).expandX().left();
        dlgHeader.add(closeBtn).size(42, 42).right();
        dialog.add(dlgHeader).growX().padBottom(14);
        dialog.row();

        // Dropdowns grid
        dialog.add(ui.small("Số người tối đa (2 - 4 người)", Color.valueOf("B8D8FF"))).left().padTop(6);
        dialog.add(ui.small("Thời gian trận đấu", Color.valueOf("B8D8FF"))).left().padTop(6).padLeft(16);
        dialog.row();

        dialog.add(maxSelect).width(245).height(42).padTop(4);
        dialog.add(timeSelect).width(245).height(42).padTop(4).padLeft(16);
        dialog.row();

        dialog.add(ui.small("Số mạng mỗi người", Color.valueOf("B8D8FF"))).left().padTop(12);
        dialog.add(ui.small("Vật phẩm rơi trong trận", Color.valueOf("B8D8FF"))).left().padTop(12).padLeft(16);
        dialog.row();

        dialog.add(livesSelect).width(245).height(42).padTop(4);
        dialog.add(dropsSelect).width(245).height(42).padTop(4).padLeft(16);
        dialog.row();

        dialog.add(ui.small("Cơ chế ném vũ khí", Color.valueOf("B8D8FF"))).colspan(2).left().padTop(10);
        dialog.row();
        dialog.add(throwSelect).colspan(2).growX().height(42).padTop(4);
        dialog.row();

        // Ownership info & draftNotice
        dialog.add(ownershipLabel).colspan(2).padTop(14).center();
        dialog.row();
        dialog.add(draftNoticeLabel).colspan(2).padTop(4).center();
        dialog.row();

        // Buttons: Lưu luật, Hoàn tác
        var btnRow = new Table();
        btnRow.add(applyRulesBtn).width(235).height(48).padRight(14);
        btnRow.add(resetRulesBtn).width(235).height(48);
        dialog.add(btnRow).colspan(2).padTop(16).center();

        overlay.add(dialog).center();
        return overlay;
    }

    private SelectBox<String> select(String... items) {
        var box = new SelectBox<String>(ui.skin);
        box.setItems(items);
        return box;
    }

    private MatchRules draft() {
        return new MatchRules(
            maxSelect.getSelectedIndex() + 2,
            TIMES[timeSelect.getSelectedIndex()+(lobby.getSettings()!=null && lobby.getSettings().rules().boss()?1:0)],
            LIVES[livesSelect.getSelectedIndex()],
            MatchRules.DropRate.values()[dropsSelect.getSelectedIndex()],
            true, lobby.getSettings()!=null && lobby.getSettings().rules().fighter(),
            throwSelect.getSelectedIndex() == 1, lobby.getSettings()!=null && lobby.getSettings().rules().boss()
        );
    }

    private void sync(MatchRules rules) {
        maxSelect.setSelectedIndex(rules.maxPlayers() - 2);
        timeSelect.setSelectedIndex(Arrays.asList(TIMES).indexOf(rules.matchSeconds())-(rules.boss()?1:0));
        livesSelect.setSelectedIndex(Arrays.asList(LIVES).indexOf(rules.lives()));
        dropsSelect.setSelectedIndex(rules.dropRate().ordinal());
        throwSelect.setSelectedIndex(rules.keepWeaponOnThrow() ? 1 : 0);
    }

    private void toggleSettings() {
        boolean next = !settingsModal.isVisible();
        settingsModal.setVisible(next);
        if (next && lobby.getSettings() != null) {
            sync(lobby.getSettings().rules());
        }
    }

    private void toggleReady() {
        if (lobby.getSnapshot().phase() == LobbyClient.Phase.WAITING_FOR_START) {
            lobby.unready();
        } else {
            lobby.ready(selected);
        }
    }

    private void leave() {
        game.leaveOnlineLobby();
        game.setScreen(new OnlineLobbyScreen(game));
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

    private Table createMiniStatRow(String label, int val, int max) {
        var row = new Table();
        var lbl = ui.small(label, Color.valueOf("84A8C8"));
        row.add(lbl).padRight(3);

        var bars = new Table();
        for (int seg = 0; seg < max; seg++) {
            var drawable = ui.skin.newDrawable("pixel", seg < val ? Color.valueOf("00E5FF") : Color.valueOf("122034"));
            bars.add(new Image(drawable)).size(5.5f, 6f).padRight(1.5f);
        }
        row.add(bars);
        return row;
    }

    private void updateSlotStats(int slotIndex, OnlineLoadingScreen.CharacterStats stats) {
        var box = slotStatsBox[slotIndex];
        box.clear();
        if (stats == null) {
            box.setVisible(false);
            return;
        }
        box.setVisible(true);
        box.add(createMiniStatRow("DMG", stats.attack, 8)).padRight(8);
        box.add(createMiniStatRow("DEF", stats.defense, 8));
        box.row().padTop(2);
        box.add(createMiniStatRow("SPD", stats.speed, 8)).padRight(8);
        box.add(createMiniStatRow("DEX", stats.dexterity, 8));
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(stage);
    }

    @Override
    public void render(float delta) {
        if (disposed) { return; }
        var state = lobby.getSnapshot();
        if(lobby.isMatchmaking() && state.selectedCharacter()!=null) { selected=state.selectedCharacter(); }
        if ("COUNTDOWN".equals(state.serverPhase()) || state.isMatchStarted()
                || lobby.getSettings()!=null && lobby.getSettings().rules().fighter() && state.members().size()>=2 && state.members().stream().allMatch(LobbyClient.Member::ready)) {
            game.setScreen(new OnlineLoadingScreen(game, lobby));
            return;
        }

        // Hotkeys
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            if (settingsModal.isVisible()) {
                settingsModal.setVisible(false);
            } else {
                leave();
            }
            return;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.X)) {
            toggleSettings();
        }

        var settings = lobby.getSettings();
        boolean joined = state.canReady();
        boolean waiting = state.phase() == LobbyClient.Phase.WAITING_FOR_START;
        boolean host = settings != null && state.localPlayerId() != null && settings.hostId().equals(state.localPlayerId().value());

        // Sync initial default character to server once room is joined
        if (joined && !initialCharacterSynced && state.phase() == LobbyClient.Phase.JOINED) {
            lobby.selectCharacter(selected);
            initialCharacterSynced = true;
        }

        // Initial rule sync from lobby preference if host
        if (host && initialRuleSync && settings != null) {
            initialRuleSync = false;
            if (!settings.rules().boss() && lobby.getPreferredMaxPlayers() != settings.rules().maxPlayers()) {
                lobby.updateSettings(new com.smashprofs.common.model.MatchRules(
                    lobby.getPreferredMaxPlayers(),
                    settings.rules().matchSeconds(),
                    settings.rules().lives(),
                    settings.rules().dropRate(),
                    settings.rules().gameplay(),
                    settings.rules().fighter(),
                    settings.rules().keepWeaponOnThrow(),
                    settings.rules().boss()
                ));
            }
        }
        // Sync rules from server if updated by host
        if (settings != null && settings.rulesVersion() != displayedRules) {
            sync(settings.rules());
            displayedRules = settings.rulesVersion();
        }

        // Disable settings editing if not host or during countdown
        for (var box : new SelectBox[]{maxSelect, timeSelect, livesSelect, dropsSelect, throwSelect}) {
            box.setDisabled(!host || !joined || settings == null || state.serverPhase().equals("COUNTDOWN"));
        }
        boolean dirty = settings != null && !draft().equals(settings.rules());
        boolean belowCount = settings != null && draft().maxPlayers() < settings.playerCount();
        applyRulesBtn.setDisabled(!host || !joined || !dirty || belowCount || state.serverPhase().equals("COUNTDOWN"));
        resetRulesBtn.setDisabled(!host || !joined || !dirty);

        ownershipLabel.setText(host ? "BẠN LÀ CHỦ PHÒNG" : "CHỈ CHỦ PHÒNG ĐƯỢC PHÉP SỬA LUẬT");
        ownershipLabel.setColor(host ? OnlineUi.BRAWL_CYAN : OnlineUi.MUTED);
        draftNoticeLabel.setText(
            waiting && host ? "Hủy sẵn sàng trước khi chỉnh luật." :
            belowCount ? "Không thể giảm chỗ dưới số người đang có trong phòng." :
            dirty ? "Chưa lưu! Bấm Lưu luật phòng để đồng bộ tới mọi người." :
            "Luật đã đồng bộ với máy chủ."
        );
        draftNoticeLabel.setColor(dirty ? OnlineUi.BRAWL_GOLD : OnlineUi.MUTED);

        // Update Top Header Room Summary
        if (settings != null) {
            var r = settings.rules();
            String timeStr = r.matchSeconds() == 0 ? "Vô hạn" : r.matchSeconds() + "s";
            if (lobby.isMatchmaking()) {
                roomRuleSummary.setText((r.boss() ? "GHÉP NHANH: HỢP TÁC ĐÁNH BOSS" : "GHÉP NHANH: 1 VS 1 ĐỐI KHÁNG") + "   ·   " + r.lives() + " MẠNG   ·   " + timeStr);
            } else {
                roomRuleSummary.setText((r.boss()?"BOSS #":"PHÒNG #") + roomId + "   ·   " + r.lives() + " MẠNG   ·   " + timeStr + "   ·   TỐI ĐA " + r.maxPlayers() + " NGƯỜI");
            }
        } else {
            roomRuleSummary.setText("PHÒNG #" + roomId);
        }

        // Update Character Selection Roster Highlighting
        for (int i = 0; i < CHARACTERS.length; i++) {
            boolean isCurSelected = CHARACTERS[i].equalsIgnoreCase(selected);
            if (isCurSelected) {
                characterCards[i].setBackground(ui.comicBox(Color.valueOf("0D2F4F"), Color.valueOf("00FFA2"), 3.5f, 2, 2));
            } else {
                characterCards[i].setBackground(ui.comicBox(Color.valueOf("081424"), OnlineUi.BRAWL_BORDER, 2.5f, 2, 2));
            }
        }

        // Ready Button State
        readyBtn.setDisabled(settings == null || !joined && !waiting || dirty && host);
        readyBtn.setText(
            waiting ? "HỦY SẴN SÀNG  [ENTER]" :
            state.phase() == LobbyClient.Phase.READY_PENDING ? "ĐANG XÁC NHẬN…" :
            "SẴN SÀNG  [ENTER]"
        );
        if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER) && !readyBtn.isDisabled() && !settingsModal.isVisible()) {
            toggleReady();
        }

        // Update 4 Player Pedestals
        var members = settings != null && !settings.members().isEmpty() ? settings.members() : state.members();
        int capacity = settings == null ? 4 : settings.rules().maxPlayers();
        long readied = members.stream().filter(LobbyClient.Member::ready).count();
        memberCountLabel.setText(members.size() + "/" + capacity + " NGƯỜI");

        for (int i = 0; i < 4; i++) {
            final int slot = i;
            var member = members.stream().filter(m -> m.slotIndex() == slot).findFirst().orElse(null);

            if (member == null) {
                slotCharacterImg[i].setVisible(false);
                if (slotLastChar[i] != null) {
                    updateSlotStats(i, null);
                    slotLastChar[i] = null;
                }
                if (i >= capacity) {
                    slotNameLabel[i].setText("P" + (i + 1) + "  ·  ĐÃ KHÓA");
                    slotNameLabel[i].setColor(Color.valueOf("6B7280"));
                    slotHeroLabel[i].setText("---");
                    slotHeroLabel[i].setColor(Color.valueOf("4B5563"));
                    slotStatusLabel[i].setText("CHỖ ĐÃ KHÓA");
                    slotStatusLabel[i].setColor(Color.valueOf("6B7280"));
                    slotStatusBadge[i].setBackground(ui.comicBox(Color.valueOf("060B12"), OnlineUi.BRAWL_BORDER, 1.5f, 0, 0));
                    slotPedestalCard[i].setBackground(ui.comicBox(Color.valueOf("060B12B0"), OnlineUi.BRAWL_BORDER, 2f, 0, 0));
                    slotPedestalBase[i].setBackground(ui.comicBox(Color.valueOf("0D1624"), OnlineUi.BRAWL_BORDER, 2f, 0, 0));
                } else {
                    slotNameLabel[i].setText("P" + (i + 1) + "  ·  TRỐNG");
                    slotNameLabel[i].setColor(OnlineUi.MUTED);
                    slotHeroLabel[i].setText("ĐANG CHỜ...");
                    slotHeroLabel[i].setColor(Color.valueOf("6B7280"));
                    slotStatusLabel[i].setText("ĐANG CHỜ NGƯỜI");
                    slotStatusLabel[i].setColor(OnlineUi.MUTED);
                    slotStatusBadge[i].setBackground(ui.comicBox(Color.valueOf("0B1624"), OnlineUi.BRAWL_BORDER, 1.5f, 0, 0));
                    slotPedestalCard[i].setBackground(ui.comicBox(Color.valueOf("091220D0"), OnlineUi.BRAWL_BORDER, 2.5f, 3, 3));
                    slotPedestalBase[i].setBackground(ui.comicBox(Color.valueOf("122238"), OnlineUi.BRAWL_BORDER, 2f, 0, 0));
                }
                continue;
            }

            // Member is in room!
            boolean local = member.playerId().equals(state.localPlayerId());
            boolean owner = settings != null && settings.hostId().equals(member.playerId().value());

            if (local && !initialCharacterSynced && member.characterId() != null) {
                selected = member.characterId();
                initialCharacterSynced = true;
            }

            String charName = local ? selected : (member.characterId() != null ? member.characterId() : "Alex");
            slotCharacterImg[i].setDrawable(new TextureRegionDrawable(getCharacterRegion(charName)));
            slotCharacterImg[i].setVisible(true);

            if (!charName.equals(slotLastChar[i])) {
                updateSlotStats(i, OnlineLoadingScreen.getStats(charName));
                slotLastChar[i] = charName;
            }

            String pLabel = "P" + (i + 1) + (local ? " · BẠN" : "") + (owner ? " ★" : "");
            slotNameLabel[i].setText(pLabel);
            slotNameLabel[i].setColor(local ? OnlineUi.BRAWL_CYAN : (owner ? OnlineUi.BRAWL_GOLD : Color.WHITE));

            slotHeroLabel[i].setText(charName.toUpperCase(Locale.ROOT));
            slotHeroLabel[i].setColor(Color.WHITE);

            if (member.ready()) {
                slotStatusLabel[i].setText("ĐÃ SẴN SÀNG");
                slotStatusLabel[i].setColor(Color.valueOf("052B28"));
                slotStatusBadge[i].setBackground(ui.comicBox(OnlineUi.BRAWL_CYAN, OnlineUi.BRAWL_BORDER, 1.5f, 0, 0));
                slotPedestalBase[i].setBackground(ui.comicBox(Color.valueOf("0E4438"), OnlineUi.BRAWL_CYAN, 3f, 2, 2));
                slotPedestalCard[i].setBackground(ui.comicBox(OnlineUi.BRAWL_DARK, OnlineUi.BRAWL_CYAN, 3.5f, 4, 6));
            } else {
                slotStatusLabel[i].setText("CHƯA SẴN SÀNG");
                slotStatusLabel[i].setColor(Color.valueOf("2E2818"));
                slotStatusBadge[i].setBackground(ui.comicBox(OnlineUi.BRAWL_GOLD, OnlineUi.BRAWL_BORDER, 1.5f, 0, 0));
                slotPedestalBase[i].setBackground(ui.comicBox(Color.valueOf("004AA5"), Color.valueOf("35B4FF"), 3f, 2, 2));
                slotPedestalCard[i].setBackground(ui.comicBox(OnlineUi.BRAWL_DARK, OnlineUi.BRAWL_BORDER, 3.5f, 4, 6));
            }
        }

        // Ready Hint & Status Text
        readyHint.setText(
            lobby.isMatchmaking() ? (waiting ? "Đang chờ đối phương sẵn sàng..." : "Chọn nhân vật yêu thích rồi bấm Sẵn sàng!") :
            (settings != null && settings.rules().boss()) ? (waiting ? "Cả phòng sẵn sàng — đếm ngược 3 giây vào trận đánh Boss!" : "Cùng hạ Boss trước khi hết giờ · Bao nhiêu người trong phòng sẵn sàng cũng vào trận được!") :
            members.size() < 2 ? "Cần ít nhất 2 người để bắt đầu. Hãy sao chép mã phòng để mời bạn bè!" :
            waiting ? "Cả phòng sẵn sàng — nạp đấu trường — đếm ngược 3 giây!" :
            dirty && host ? "Bạn đang chỉnh luật: Hãy bấm Lưu luật phòng trước khi bấm Sẵn sàng." :
            "Mọi người hãy chọn một nhân vật yêu thích rồi bấm Sẵn sàng."
        );

        String message = state.errorCode() != null ? OnlineRoomPresentation.error(state.errorCode(), state.statusText()) : switch (state.phase()) {
            case CONNECTING, CREATING, JOINING, IDLE -> "Đang mở phòng đấu…";
            case READY_PENDING -> "Đang chờ máy chủ xác nhận trạng thái…";
            case WAITING_FOR_START -> "Bạn đã sẵn sàng! Đang đợi những người chơi còn lại…";
            case ERROR, DISCONNECTED -> "Mất kết nối với máy chủ. Bấm Rời phòng để thử lại.";
            default -> settings!=null && settings.rules().fighter()?"A/D di chuyển · Space nhảy · W/S ngắm · J đánh/bắn · K đánh mạnh · L né/lướt · E nhặt · Q ném · Tab túi":
                    "A/D di chuyển · W nhảy · S dậm · V/Space tấn công · F tuyệt chiêu · Ctrl đỡ đòn";
        };
        statusLabel.setText(message);
        statusLabel.setColor(state.errorCode() != null || state.phase() == LobbyClient.Phase.DISCONNECTED ? OnlineUi.DANGER : Color.valueOf("94B8E6"));

        // Copy button timer feedback
        copiedSeconds = Math.max(0, copiedSeconds - delta);
        copyBtn.setText(copiedSeconds > 0 ? "ĐÃ SAO CHÉP!" : "SAO CHÉP MÃ");

        // 1. Clear with Brawlhalla Royal Blue
        ScreenUtils.clear(Color.valueOf("0056BD"));

        // 2. Draw Textured Background with atmospheric tint
        batch.setProjectionMatrix(stage.getCamera().combined);
        batch.begin();
        batch.setColor(0.08f, 0.32f, 0.72f, 0.40f);
        batch.draw(bgTexture, 0, 0, 1280, 720);
        batch.setColor(Color.WHITE);
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
        ui.close();
        textures.forEach(Texture::dispose);
    }
}
