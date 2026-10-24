package com.smashprofs.game.Screens;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.scenes.scene2d.*;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.utils.*;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.smashprofs.common.model.RankingEntry;
import com.smashprofs.common.protocol.*;
import com.smashprofs.game.network.client.*;
import com.smashprofs.game.profile.PlayerProfile;
import com.smashprofs.game.ui.OnlineUi;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** Bounded columns, top-aligned results, and remembered guest ranking identity. */
public final class LeaderboardScreen implements Screen {
    private static final float[] COLUMNS={92,412,144,160,144,144};
    private final com.badlogic.gdx.Game game;
    private final boolean fromOnlineLobby;
    private final OnlineUi ui=new OnlineUi();
    private final Stage stage=new Stage(new FitViewport(1280,720));
    private final SpriteBatch batch=new SpriteBatch();
    private final Texture background=new Texture(Gdx.files.internal("mainmenu/bgmenu.png"));
    private final Table entriesTable=new Table(),serverModal=new Table();
    private final ScrollPane scroll;
    private final TextField hostField,portField;
    private final Label status,profileBadge,endpoint;
    private volatile boolean disposed;
    private NetworkClient connection;
    private volatile long generation;
    private long started;

    public LeaderboardScreen(com.badlogic.gdx.Game game) { this(game,false); }
    public LeaderboardScreen(com.badlogic.gdx.Game game,Screen previous) { this(game,previous instanceof OnlineLobbyScreen); }
    public LeaderboardScreen(com.badlogic.gdx.Game game,boolean fromOnlineLobby) {
        this.game=game;this.fromOnlineLobby=fromOnlineLobby;
        var saved=Gdx.app.getPreferences("online-server");
        hostField=new TextField(System.getProperty("smashprofs.server.host",saved.getString("host","127.0.0.1")),ui.skin);
        hostField.setMaxLength(253);hostField.setName("ranking-server-host");
        portField=new TextField(System.getProperty("smashprofs.server.port",saved.getString("port","54555")),ui.skin);
        portField.setMaxLength(5);portField.setTextFieldFilter((f,c)->c>='0' && c<='9');portField.setName("ranking-server-port");
        var root=new Table();root.setFillParent(true);root.pad(20,32,16,32);root.defaults().minWidth(0);
        var header=new Table();header.defaults().minWidth(0);
        var back=ui.comicButton("QUAY LẠI","comic-white",this::goBack);back.setName("ranking-back");
        header.add(back).width(152).height(42);
        header.add(ui.comicHeading("BẢNG XẾP HẠNG",OnlineUi.BRAWL_GOLD)).expandX();
        var refresh=ui.comicButton("LÀM MỚI","comic-primary",this::fetch);refresh.setName("ranking-refresh");
        header.add(refresh).width(152).height(42);root.add(header).growX().height(54).row();
        root.add(ui.small("Đối kháng PvP tính Elo · Hợp tác Boss ghi nhận thắng / thua",Color.valueOf("CDE6FF"))).padTop(2).padBottom(12).row();
        var toolbar=new Table();toolbar.setBackground(ui.box("0A1526",12));toolbar.defaults().minWidth(0);
        profileBadge=ui.small("",OnlineUi.BRAWL_CYAN);profileBadge.setName("ranking-profile");profileBadge.setEllipsis(true);
        toolbar.add(profileBadge).growX().padRight(12);
        endpoint=ui.small("",OnlineUi.MUTED);endpoint.setEllipsis(true);endpoint.setAlignment(Align.right);endpoint.setName("ranking-endpoint");
        toolbar.add(endpoint).width(400).padRight(12);
        var server=ui.button("Đổi máy chủ",()->{serverModal.setVisible(true);stage.setKeyboardFocus(hostField);});server.setName("ranking-server-settings");
        toolbar.add(server).width(164).height(36);root.add(toolbar).growX().padBottom(8).row();
        status=ui.small("Đang lấy bảng xếp hạng…",OnlineUi.BRAWL_CYAN);status.setEllipsis(true);status.setName("ranking-status");
        root.add(status).growX().height(28).padBottom(4).row();
        var card=new Table();card.setBackground(ui.comicBox(Color.valueOf("09182E"),OnlineUi.BRAWL_BORDER,3,2,2));card.pad(10);
        var headings=row("050D18");
        String[] titles={"HẠNG","NGƯỜI CHƠI","ĐIỂM ELO","THẮNG / THUA","TỈ LỆ THẮNG","SỐ TRẬN"};
        for(int i=0;i<titles.length;i++) { var label=bounded(titles[i],OnlineUi.MUTED);label.setAlignment(i==1?Align.left:Align.center);headings.add(label).width(COLUMNS[i]); }
        card.add(headings).growX().height(38).row();
        entriesTable.top();entriesTable.defaults().minWidth(0);
        scroll=new ScrollPane(entriesTable,ui.skin);scroll.setName("ranking-scroll");scroll.setFadeScrollBars(false);scroll.setScrollingDisabled(true,false);
        card.add(scroll).grow().padTop(6);root.add(card).grow().row();
        var hint=ui.small("Hồ sơ khách được nhớ trên thiết bị này. ESC: Quay lại · F5: Làm mới",Color.valueOf("CDE6FF"));
        root.add(hint).padTop(10);stage.addActor(root);buildServerModal();stage.addActor(serverModal);
        fetch();
    }
    private Label bounded(String text,Color color) { var label=ui.small(text,color);label.setEllipsis(true);return label; }
    private Table row(String color) { var table=new Table();table.setBackground(ui.box(color,0));table.pad(8,10,8,10);table.defaults().minWidth(0);return table; }
    private int port() { try { int p=Integer.parseInt(portField.getText().trim());return p>=1 && p<=65535?p:54555; }catch(NumberFormatException e) { return 54555; } }
    private void buildServerModal() {
        serverModal.setFillParent(true);serverModal.setTouchable(Touchable.enabled);serverModal.setBackground(ui.box("07101DEB",0));serverModal.setVisible(false);
        var panel=ui.panel();panel.add(ui.heading("MÁY CHỦ",Color.WHITE)).left().row();
        panel.add(ui.small("Địa chỉ WebSocket hoặc IP máy chủ",OnlineUi.MUTED)).left().padTop(10).row();
        panel.add(hostField).width(640).height(44).padTop(8).row();
        if(!hostField.getText().startsWith("ws")) { panel.add(portField).width(140).height(40).left().padTop(8).row(); }
        var close=ui.primary("LƯU VÀ TẢI LẠI",()->{serverModal.setVisible(false);stage.setKeyboardFocus(null);fetch();});close.setName("ranking-server-save");
        panel.add(close).growX().height(44).padTop(16);serverModal.add(panel).width(700);
    }
    private void updateProfile() {
        var profile=PlayerProfile.get();
        profileBadge.setText("BẠN: "+profile.getDisplayName()+" · "+profile.getElo()+" ELO · "+shortId(profile.getServerProfileId(hostField.getText().trim(),port())));
        endpoint.setText(hostField.getText().trim());
    }
    private static String shortId(String id) { return id.length()<=18?id:id.substring(0,10)+"…"+id.substring(id.length()-4); }
    private void fetch() {
        if(disposed) { return; }status.setText("Đang lấy bảng xếp hạng…");status.setColor(OnlineUi.BRAWL_CYAN);updateProfile();
        var saved=Gdx.app.getPreferences("online-server");saved.putString("host",hostField.getText().trim()).putString("port",String.valueOf(port())).flush();
        long current=++generation;started=System.nanoTime();if(connection!=null) { connection.disconnect(); }
        var client=new NetworkClient(hostField.getText().trim(),port(),null);connection=client;
        var profile=PlayerProfile.get();client.setConnectProfile(profile.getProfileId(),profile.getDisplayName());
        client.setListener(new ConnectionListener() {
            private boolean received;
            @Override public void onConnected() { if(!client.sendMessage(RankingProtocol.createGetLeaderboardMessage("ranking-ui",50))) { client.disconnect(); } }
            @Override public void onPacketReceived(NetworkPacket ignored) { }
            @Override public void onProtocolMessageReceived(ProtocolMessage response) {
                if(response.type()==MessageType.ERROR) {
                    received=true;post(()->failure(response.payload().path("code").asText().equals("INVALID_PROFILE_TOKEN")?
                            "Không khôi phục được hồ sơ. Kiểm tra đúng máy chủ hoặc tạo hồ sơ mới trong menu.":"Không tải được dữ liệu. Hãy thử làm mới."));client.disconnect();
                } else if(response.type()==MessageType.RANKING_SNAPSHOT) {
                    try { var entries=RankingProtocol.parseSnapshot(response);received=true;post(()->renderEntries(entries));client.disconnect(); }
                    catch(RuntimeException invalid) { client.disconnect(); }
                }
            }
            private void post(Runnable work) { Gdx.app.postRunnable(()->{if(!disposed && current==generation) { work.run(); }}); }
            @Override public void onDisconnected(String reason) { if(!received) { post(()->failure("Không kết nối được máy chủ. Kiểm tra địa chỉ và thử lại.")); } }
        });
        new Thread(()->{try { if(!disposed && current==generation) { client.connect(); } }
            catch(IOException|RuntimeException error) { Gdx.app.postRunnable(()->{if(!disposed && current==generation) { failure("Không kết nối được máy chủ."); }}); }
            finally { if(disposed || current!=generation) { client.disconnect(); } }},"Leaderboard-Connect").start();
    }
    private void failure(String message) { connection=null;status.setText(message);status.setColor(OnlineUi.GOLD); }
    private void renderEntries(List<RankingEntry> entries) {
        connection=null;updateProfile();entriesTable.clearChildren();entriesTable.top();
        status.setText(entries.isEmpty()?"Chưa có kết quả. Chơi một trận online để xuất hiện ở đây.":"Đã cập nhật · "+entries.size()+" người chơi");status.setColor(OnlineUi.BRAWL_CYAN);
        if(entries.isEmpty()) { entriesTable.add(ui.small("Bảng xếp hạng đang trống",OnlineUi.MUTED)).pad(24);return; }
        String me=PlayerProfile.get().getServerProfileId(hostField.getText().trim(),port());
        for(int i=0;i<entries.size();i++) {
            var e=entries.get(i);boolean mine=e.playerId().value().equals(me);
            if(mine) { PlayerProfile.get().setElo(e.rating()); }
            var item=row(mine?"103D36":i%2==0?"0C1B30":"12243B");item.setName("ranking-row-"+e.rank());
            Color accent=e.rank()==1?OnlineUi.BRAWL_GOLD:e.rank()<=3?OnlineUi.BRAWL_CYAN:Color.WHITE;
            var rank=bounded("#"+e.rank(),accent);rank.setAlignment(Align.center);item.add(rank).width(COLUMNS[0]);
            String name=e.displayName();if(name==null || name.isBlank() || name.equals(e.playerId().value())) { name="Khách "+shortId(e.playerId().value()); }
            var player=new Table();player.defaults().minWidth(0);var label=bounded((mine?"BẠN · ":"")+name,mine?OnlineUi.BRAWL_CYAN:Color.WHITE);label.setName("ranking-name-"+e.rank());
            player.add(label).growX().row();player.add(bounded("ID: "+shortId(e.playerId().value()),OnlineUi.MUTED)).growX();item.add(player).width(COLUMNS[1]);
            var elo=ui.label(String.valueOf(e.rating()),accent);elo.setName("ranking-elo-"+e.rank());elo.setAlignment(Align.center);item.add(elo).width(COLUMNS[2]);
            var record=bounded(e.wins()+" / "+e.losses(),Color.WHITE);record.setAlignment(Align.center);item.add(record).width(COLUMNS[3]);
            var rate=bounded(String.format(Locale.ROOT,"%.1f%%",e.winRate()*100),OnlineUi.BRAWL_CYAN);rate.setAlignment(Align.center);item.add(rate).width(COLUMNS[4]);
            var matches=bounded(String.valueOf(e.totalMatches()),Color.WHITE);matches.setAlignment(Align.center);item.add(matches).width(COLUMNS[5]);
            entriesTable.add(item).growX().height(62).padBottom(4).row();
        }
    }
    private void goBack() { if(!disposed) { game.setScreen(fromOnlineLobby?new OnlineLobbyScreen((com.smashprofs.game.Game)game):new MainMenuScreen(game)); } }
    @Override public void show() { Gdx.input.setInputProcessor(stage);stage.setScrollFocus(scroll); }
    @Override public void render(float delta) {
        if(connection!=null && System.nanoTime()-started>8_000_000_000L) { var old=connection;connection=null;generation++;old.disconnect();failure("Máy chủ chưa trả lời. Bấm Làm mới để thử lại."); }
        if(Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) { if(serverModal.isVisible()) { serverModal.setVisible(false);stage.setKeyboardFocus(null); }else { goBack();return; } }
        if(Gdx.input.isKeyJustPressed(Input.Keys.F5) && !serverModal.isVisible()) { fetch(); }
        ScreenUtils.clear(Color.valueOf("004AA5"));batch.setProjectionMatrix(stage.getCamera().combined);batch.begin();batch.setColor(.22f,.36f,.55f,1);batch.draw(background,0,0,1280,720);batch.end();stage.act(delta);stage.draw();
    }
    @Override public void resize(int width,int height) { stage.getViewport().update(width,height,true); }
    @Override public void pause() { }
    @Override public void resume() { }
    @Override public void hide() { dispose(); }
    @Override public void dispose() { if(!disposed) { disposed=true;generation++;if(connection!=null) { connection.disconnect(); }stage.dispose();batch.dispose();background.dispose();ui.close(); } }
}
