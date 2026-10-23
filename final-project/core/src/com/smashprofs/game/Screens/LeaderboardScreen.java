package com.smashprofs.game.Screens;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.RankingEntry;
import com.smashprofs.common.protocol.JsonFrameCodec;
import com.smashprofs.common.protocol.MessageType;
import com.smashprofs.common.protocol.ProtocolMessage;
import com.smashprofs.common.protocol.RankingProtocol;
import com.smashprofs.game.ui.OnlineUi;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Màn hình Bảng xếp hạng (Leaderboard Screen) thiết kế chuẩn đồ họa Comic Brawlhalla.
 *
 * <p>Tính năng:
 * <ul>
 *   <li>Hiển thị Top cao thủ, thứ hạng ELO, tổng số trận, số trận thắng/thua và tỉ lệ thắng.</li>
 *   <li>Huy hiệu vinh danh đặc biệt cho TOP 1 (Vàng), TOP 2 (Bạc), TOP 3 (Đồng).</li>
 *   <li>Kết nối TCP bất đồng bộ tới server để lấy danh sách thời gian thực qua giao thức RANKING_SNAPSHOT.</li>
 *   <li>Tự động hiển thị danh sách mẫu nếu server chưa bật để đảm bảo giao diện luôn sống động.</li>
 *   <li>Hỗ trợ phím tắt F5 (Làm mới) và ESC (Quay lại).</li>
 * </ul>
 */
public class LeaderboardScreen implements Screen {

    private final Game game;
    private final boolean fromOnlineLobby;
    private final OnlineUi ui = new OnlineUi();
    private final Stage stage = new Stage(new FitViewport(1280, 720));
    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();

    private final ArrayList<Texture> textures = new ArrayList<>();
    private final Texture bgTexture;
    private final TextureRegion alexAvatar;
    private final TextureRegion leoAvatar;
    private final TextureRegion lucaAvatar;

    private final TextField hostField;
    private final TextField portField;
    private final Label statusLabel;
    private final Table tableContainer;

    private boolean disposed;

    public LeaderboardScreen(Game game) {
        this(game, false);
    }

    public LeaderboardScreen(Game game, Screen previousScreen) {
        this(game, previousScreen instanceof OnlineLobbyScreen);
    }

    public LeaderboardScreen(Game game, boolean fromOnlineLobby) {
        this.game = game;
        this.fromOnlineLobby = fromOnlineLobby;

        // 1. Tải textures
        bgTexture = new Texture(Gdx.files.internal("mainmenu/bgmenu.png"));
        textures.add(bgTexture);

        var alexTex = new Texture(Gdx.files.internal("Sprites/Alex/alex_stand.png"));
        var leoTex = new Texture(Gdx.files.internal("Sprites/Leo/leo_stand.png"));
        var lucaTex = new Texture(Gdx.files.internal("Sprites/Luca/luca_stand.png"));
        alexTex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        leoTex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        lucaTex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        textures.add(alexTex);
        textures.add(leoTex);
        textures.add(lucaTex);

        alexAvatar = new TextureRegion(alexTex, 0, 0, 100, 100);
        leoAvatar = new TextureRegion(leoTex, 0, 0, 100, 100);
        lucaAvatar = new TextureRegion(lucaTex, 0, 0, 100, 100);

        // --- ROOT LAYOUT ---
        var root = new Table();
        root.setFillParent(true);
        root.pad(12, 36, 14, 36);

        // ================= HEADER =================
        var headerTable = new Table();

        var titleHeading = ui.comicHeading("BẢNG XẾP HẠNG CAO THỦ", OnlineUi.BRAWL_GOLD);
        headerTable.add(titleHeading).center();
        headerTable.row();

        var subBadge = new Table();
        subBadge.setBackground(ui.comicBox(Color.valueOf("061324"), OnlineUi.BRAWL_BORDER, 2f, 0, 0));
        subBadge.pad(3, 20, 3, 20);
        var subText = ui.small("ĐẤU TRƯỜNG ĐỐI KHÁNG TRỰC TUYẾN SUPER SMASH PROFS  ·  HỆ THỐNG ĐIỂM ELO", Color.valueOf("94D2FF"));
        subBadge.add(subText);
        headerTable.add(subBadge).center().padTop(4);

        root.add(headerTable).growX().center();
        root.row();

        // ================= CONTROLS & STATUS BAR =================
        var controlBar = new Table();
        controlBar.setBackground(ui.comicBox(Color.valueOf("0A1526"), OnlineUi.BRAWL_BORDER, 2.5f, 2, 2));
        controlBar.pad(6, 16, 6, 16);

        // Row 1: Back button | User Profile Badge | Refresh button
        var barRow1 = new Table();

        var backBtn = ui.comicButton("< QUAY LẠI  [ESC]", "comic-white", this::goBack);
        barRow1.add(backBtn).size(150, 36).left();

        var myProf = com.smashprofs.game.profile.PlayerProfile.get();
        var profBadge = new Table();
        profBadge.setBackground(ui.comicBox(Color.valueOf("06182B"), OnlineUi.BRAWL_CYAN, 2f, 1, 1));
        profBadge.pad(4, 14, 4, 14);
        profBadge.add(ui.small("BẠN:  ", OnlineUi.BRAWL_CYAN));
        profBadge.add(ui.small(myProf.getDisplayName(), Color.WHITE)).padRight(8);
        profBadge.add(ui.small("ID: " + myProf.getProfileId(), OnlineUi.BRAWL_GOLD));
        barRow1.add(profBadge).expandX().center();

        var refreshBtn = ui.comicButton("LÀM MỚI  [F5]", "comic-primary", this::fetchLeaderboard);
        barRow1.add(refreshBtn).size(140, 36).right();

        controlBar.add(barRow1).growX().padBottom(6);
        controlBar.row();

        // Row 2: Status Message | Server Host & Port config
        var barRow2 = new Table();

        statusLabel = ui.small("Đang kết nối lấy dữ liệu...", OnlineUi.BRAWL_CYAN);
        barRow2.add(statusLabel).expandX().left();

        var serverConfig = new Table();
        serverConfig.add(ui.small("MÁY CHỦ: ", OnlineUi.MUTED)).padRight(4);
        hostField = new TextField("127.0.0.1", ui.skin, "comic");
        hostField.setAlignment(Align.center);
        serverConfig.add(hostField).width(115).height(32).padRight(6);

        serverConfig.add(ui.small(":", OnlineUi.MUTED)).padRight(6);
        portField = new TextField("54555", ui.skin, "comic");
        portField.setAlignment(Align.center);
        serverConfig.add(portField).width(85).height(32);

        barRow2.add(serverConfig).right();

        controlBar.add(barRow2).growX();

        root.add(controlBar).growX().padTop(8).padBottom(6);
        root.row();

        // ================= MAIN LEADERBOARD CARD =================
        var mainCard = new Table();
        mainCard.setBackground(ui.comicBox(Color.valueOf("09182E"), OnlineUi.BRAWL_BORDER, 3.5f, 5, 6));
        mainCard.pad(8, 12, 10, 12);

        // Header Row của Bảng
        var tableHeader = new Table();
        tableHeader.setBackground(ui.comicBox(Color.valueOf("050D18"), OnlineUi.BRAWL_BORDER, 2f, 0, 0));
        tableHeader.pad(6, 10, 6, 10);
        tableHeader.add(ui.small("HẠNG", OnlineUi.BRAWL_GOLD)).width(100).center();
        tableHeader.add(ui.small("CHIẾN BINH", Color.WHITE)).width(340).left().padLeft(12);
        tableHeader.add(ui.small("ĐIỂM ELO", OnlineUi.BRAWL_GOLD)).width(150).center();
        tableHeader.add(ui.small("THẮNG - THUA", Color.valueOf("35F3B7"))).width(170).center();
        tableHeader.add(ui.small("TỈ LỆ THẮNG", Color.valueOf("FFE066"))).width(150).center();
        tableHeader.add(ui.small("TỔNG TRẬN", Color.valueOf("94D2FF"))).width(130).center();

        mainCard.add(tableHeader).growX().padBottom(6);
        mainCard.row();

        // Container danh sách (bên trong ScrollPane)
        tableContainer = new Table();
        tableContainer.defaults().growX();

        var scrollPane = new ScrollPane(tableContainer, ui.skin);
        scrollPane.setFadeScrollBars(false);
        scrollPane.setScrollingDisabled(true, false);

        mainCard.add(scrollPane).grow().padTop(2);

        root.add(mainCard).grow();

        stage.addActor(root);

        // Hiển thị dữ liệu mẫu trước để giao diện có nội dung ngay
        showSampleData();

        // Sau đó gửi yêu cầu TCP bất đồng bộ lấy dữ liệu thật từ Server
        fetchLeaderboard();
    }

    private void goBack() {
        if (disposed) {
            return;
        }
        if (fromOnlineLobby) {
            game.setScreen(new OnlineLobbyScreen((com.smashprofs.game.Game) game));
        } else {
            game.setScreen(new MainMenuScreen(game));
        }
    }

    /**
     * Gửi yêu cầu GET_LEADERBOARD qua TCP bất đồng bộ.
     */
    private void fetchLeaderboard() {
        statusLabel.setText("Đang kết nối tới máy chủ...");
        statusLabel.setColor(OnlineUi.BRAWL_CYAN);

        String host = hostField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            port = 54555;
        }

        final int targetPort = port;
        new Thread(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, targetPort), 2500);
                socket.setSoTimeout(3000);

                JsonFrameCodec codec = new JsonFrameCodec();

                // 1. Gửi CONNECT kèm thông tin hồ sơ của bạn
                var myProf = com.smashprofs.game.profile.PlayerProfile.get();
                ProtocolMessage connectMsg = RankingProtocol.createConnectMessage("conn-leaderboard-ui", myProf.getProfileId(), myProf.getDisplayName());
                socket.getOutputStream().write(codec.encode(connectMsg));
                socket.getOutputStream().flush();

                ProtocolMessage connectAck = codec.readNext(socket.getInputStream());
                if (connectAck == null || connectAck.type() != MessageType.CONNECT_ACK) {
                    throw new IOException("Không nhận được CONNECT_ACK từ máy chủ");
                }

                // 2. Gửi GET_LEADERBOARD
                ProtocolMessage request = RankingProtocol.createGetLeaderboardMessage("req-ui-top", 50);
                socket.getOutputStream().write(codec.encode(request));
                socket.getOutputStream().flush();

                ProtocolMessage response = codec.readNext(socket.getInputStream());
                if (response != null && response.type() == MessageType.RANKING_SNAPSHOT) {
                    List<RankingEntry> entries = RankingProtocol.parseSnapshot(response);
                    Gdx.app.postRunnable(() -> onDataReceived(entries));
                } else {
                    throw new IOException("Phản hồi không hợp lệ từ máy chủ");
                }
            } catch (Exception e) {
                Gdx.app.postRunnable(() -> onConnectionFailed(e.getMessage()));
            }
        }, "Leaderboard-TCP-Fetcher").start();
    }

    private void onDataReceived(List<RankingEntry> entries) {
        if (disposed) {
            return;
        }
        if (entries == null || entries.isEmpty()) {
            statusLabel.setText("● Đã kết nối Server (Dữ liệu BXH hiện đang trống, hãy đấu 1 trận để ghi điểm!)");
            statusLabel.setColor(Color.valueOf("FFE066"));
            showSampleData();
        } else {
            statusLabel.setText("● Đã kết nối Server — Tải thành công " + entries.size() + " cao thủ!");
            statusLabel.setColor(Color.valueOf("35F3B7"));
            renderEntries(entries);
        }
    }

    private void onConnectionFailed(String error) {
        if (disposed) {
            return;
        }
        statusLabel.setText("○ Chưa bật server (127.0.0.1:54555) — Đang hiển thị bảng mẫu. Bật server và ấn [F5]!");
        statusLabel.setColor(Color.valueOf("FFC800"));
        showSampleData();
    }

    /**
     * Dữ liệu mẫu đẹp mắt khi chưa mở Server hoặc DB mới tinh.
     */
    private void showSampleData() {
        List<RankingEntry> sample = new ArrayList<>();
        sample.add(new RankingEntry(1, new PlayerId("p-alex-master"), "Alex The Dominator", 28, 3, 31, 90.3, 1420));
        sample.add(new RankingEntry(2, new PlayerId("p-luca-king"), "Luca Shadow Assassin", 24, 6, 30, 80.0, 1365));
        sample.add(new RankingEntry(3, new PlayerId("p-leo-smash"), "Leo Blade Master", 19, 8, 27, 70.4, 1280));
        sample.add(new RankingEntry(4, new PlayerId("p-maurice-tank"), "Maurice Stone Breaker", 15, 11, 26, 57.7, 1190));
        sample.add(new RankingEntry(5, new PlayerId("p-viktor-sniper"), "Viktor Storm Caller", 12, 14, 26, 46.2, 1110));
        sample.add(new RankingEntry(6, new PlayerId("p-novice-fighter"), "Tân Binh Đấu Trường", 5, 8, 13, 38.5, 980));
        renderEntries(sample);
    }

    /**
     * Render các hàng của Bảng xếp hạng.
     */
    private void renderEntries(List<RankingEntry> entries) {
        tableContainer.clear();

        for (int i = 0; i < entries.size(); i++) {
            RankingEntry entry = entries.get(i);
            int rank = entry.rank();
            String myProfileId = com.smashprofs.game.profile.PlayerProfile.get().getProfileId();
            boolean isMe = entry.playerId() != null && entry.playerId().value().equalsIgnoreCase(myProfileId);

            // Màu và Huy hiệu theo thứ hạng
            Color rankBadgeColor;
            String rankBadgeText;
            Color ratingColor;
            Color rowBgColor = isMe ? Color.valueOf("06372A") : ((i % 2 == 0) ? Color.valueOf("09182E") : Color.valueOf("0E223D"));

            if (rank == 1) {
                rankBadgeColor = OnlineUi.BRAWL_GOLD;
                rankBadgeText = "TOP 1";
                ratingColor = OnlineUi.BRAWL_GOLD;
            } else if (rank == 2) {
                rankBadgeColor = Color.valueOf("35F3B7");
                rankBadgeText = "TOP 2";
                ratingColor = Color.valueOf("72F9D0");
            } else if (rank == 3) {
                rankBadgeColor = Color.valueOf("FB923C");
                rankBadgeText = "TOP 3";
                ratingColor = Color.valueOf("FB923C");
            } else {
                rankBadgeColor = Color.valueOf("1E2B40");
                rankBadgeText = "#" + rank;
                ratingColor = Color.WHITE;
            }

            var row = new Table();
            row.setBackground(ui.comicBox(rowBgColor, OnlineUi.BRAWL_BORDER, 1.5f, 1, 1));
            row.pad(6, 10, 6, 10);

            // 1. Cột Hạng
            var rankBadge = new Table();
            rankBadge.setBackground(ui.comicBox(rankBadgeColor, OnlineUi.BRAWL_BORDER, 2f, 0, 0));
            rankBadge.pad(3, 10, 3, 10);
            var rankLabel = ui.small(rankBadgeText, (rank <= 3) ? OnlineUi.BRAWL_BORDER : Color.WHITE);
            rankBadge.add(rankLabel).center();
            row.add(rankBadge).width(100).center();

            // 2. Cột Chiến binh & Avatar
            var playerCol = new Table();
            playerCol.left();

            TextureRegion avatarRegion = switch (rank % 3) {
                case 0 -> alexAvatar;
                case 1 -> leoAvatar;
                default -> lucaAvatar;
            };
            var avatarImg = new Image(avatarRegion);
            playerCol.add(avatarImg).size(32, 32).padRight(8);

            var nameTable = new Table();
            nameTable.left();
            String displayName = (entry.displayName() != null && !entry.displayName().isBlank())
                    ? entry.displayName()
                    : entry.playerId().value();

            var nameRow = new Table();
            nameRow.left();
            nameRow.add(ui.small(displayName.toUpperCase(Locale.ROOT), (rank == 1) ? OnlineUi.BRAWL_GOLD : (isMe ? Color.valueOf("35F3B7") : Color.WHITE))).left();
            if (isMe) {
                var meBadge = new Table();
                meBadge.setBackground(ui.comicBox(Color.valueOf("00FFA2"), OnlineUi.BRAWL_BORDER, 1.5f, 0, 0));
                meBadge.pad(1, 6, 1, 6);
                meBadge.add(ui.small("BẠN", OnlineUi.BRAWL_BORDER));
                nameRow.add(meBadge).padLeft(6);
            }
            nameTable.add(nameRow).left();
            nameTable.row();
            nameTable.add(ui.small("ID: " + entry.playerId().value(), isMe ? OnlineUi.BRAWL_GOLD : Color.valueOf("6E8BAA"))).left();
            playerCol.add(nameTable).left().expandX();

            row.add(playerCol).width(340).left().padLeft(12);

            // 3. Cột Điểm Elo
            var eloLabel = ui.heading(String.valueOf(entry.rating()), ratingColor);
            row.add(eloLabel).width(150).center();

            // 4. Cột Thắng - Thua
            var recordTable = new Table();
            recordTable.add(ui.small(entry.wins() + "W", Color.valueOf("35F3B7"))).padRight(4);
            recordTable.add(ui.small("-", OnlineUi.MUTED)).padRight(4);
            recordTable.add(ui.small(entry.losses() + "L", Color.valueOf("FF7A85")));
            row.add(recordTable).width(170).center();

            // 5. Cột Tỉ lệ thắng
            double winRate = entry.winRate();
            double winRatePct = (winRate <= 1.0) ? (winRate * 100.0) : winRate;
            Color winRateColor = (winRatePct >= 70.0) ? Color.valueOf("35F3B7") : (winRatePct >= 50.0 ? Color.valueOf("FFE066") : Color.valueOf("94A3B8"));
            var rateBadge = new Table();
            rateBadge.setBackground(ui.comicBox(Color.valueOf("061324"), OnlineUi.BRAWL_BORDER, 1.5f, 0, 0));
            rateBadge.pad(3, 8, 3, 8);
            rateBadge.add(ui.small(String.format(Locale.ROOT, "%.1f%%", winRatePct), winRateColor));
            row.add(rateBadge).width(150).center();

            // 6. Cột Tổng trận
            var matchesLabel = ui.small(entry.totalMatches() + " trận", Color.valueOf("E0F2FE"));
            row.add(matchesLabel).width(130).center();

            tableContainer.add(row).growX().padBottom(4);
            tableContainer.row();
        }
    }

    @Override
    public void render(float delta) {
        // Phím tắt bàn phím
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            goBack();
            return;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.F5)) {
            fetchLeaderboard();
        }

        // 1. Clear nền màu Brawlhalla Blue
        ScreenUtils.clear(Color.valueOf("004AA5"));

        // 2. Vẽ hình nền vũ trụ
        batch.setProjectionMatrix(stage.getCamera().combined);
        batch.begin();
        batch.setColor(0.08f, 0.28f, 0.65f, 0.45f);
        batch.draw(bgTexture, 0, 0, 1280, 720);
        batch.setColor(Color.WHITE);
        batch.end();

        // 3. Hiệu ứng sấm sét góc trái trên
        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(stage.getCamera().combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(Color.valueOf("2BD9FFE8"));
        shapes.triangle(0, 520, 0, 720, 360, 720);
        shapes.end();

        stage.act(delta);
        stage.draw();
    }

    @Override
    public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(stage);
    }

    @Override public void pause() {}
    @Override public void resume() {}
    @Override public void hide() {
        dispose();
    }

    @Override
    public void dispose() {
        if (!disposed) {
            disposed = true;
            stage.dispose();
            batch.dispose();
            shapes.dispose();
            ui.close();
            for (Texture texture : textures) {
                texture.dispose();
            }
        }
    }
}
