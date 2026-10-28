package com.smashprofs.game.Screens;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.utils.*;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.smashprofs.game.Game;
import com.smashprofs.game.network.client.*;
import com.smashprofs.game.ui.OnlineUi;
import java.util.ArrayList;

/** Three bounded play choices; connection settings are available in a separate overlay. */
public final class OnlineLobbyScreen implements Screen {
    private final Game game;
    private final Stage stage=new Stage(new FitViewport(1280,720));
    private final OnlineUi ui=new OnlineUi();
    private final SpriteBatch batch=new SpriteBatch();
    private final OnlineLobbyFormModel model=new OnlineLobbyFormModel();
    private final TextField address,port,code;
    private final TextButton create,join,back,quickMatch,serverSettings;
    private final Label status,serverBadge;
    private final Table serverModal=new Table();
    private final Texture bgTexture;
    private final ArrayList<Texture> textures=new ArrayList<>();
    private boolean disposed;

    public OnlineLobbyScreen(Game game) {
        this.game=game;
        bgTexture=new Texture(Gdx.files.internal("mainmenu/bgmenu.png"));textures.add(bgTexture);
        var alex=portrait("Sprites/Alex/alex_punch.png",100);
        var leo=portrait("Sprites/Leo/leo_stand.png",0);
        var luca=portrait("Sprites/Luca/luca_stand.png",0);
        var saved=Gdx.app.getPreferences("online-server");
        String initHost=System.getProperty("smashprofs.server.host",saved.getString("host","127.0.0.1"));
        if(initHost==null || initHost.isBlank()) { initHost="127.0.0.1"; }
        address=new TextField(initHost,ui.skin,"comic");
        address.setMaxLength(253);address.setName("server-host");
        String initPort=System.getProperty("smashprofs.server.port",saved.getString("port","54555"));
        if(initPort==null || initPort.isBlank()) { initPort="54555"; }
        port=new TextField(initPort,ui.skin,"comic");
        port.setMaxLength(5);port.setName("server-port");port.setTextFieldFilter((field,c)->c>='0' && c<='9');
        code=new TextField("",ui.skin,"comic");code.setName("room-code");code.setMaxLength(64);
        code.setMessageText("NHẬP MÃ PHÒNG");code.setAlignment(Align.center);
        code.setTextFieldFilter((field,c)->Character.isLetterOrDigit(c) && c<128 || c=='-' || c=='_');
        create=ui.comicButton("TẠO PHÒNG RIÊNG","comic-white",()->connect(LobbyConfiguration.Mode.CREATE));create.setName("create-room");
        join=ui.comicButton("VÀO PHÒNG","comic-primary",()->connect(LobbyConfiguration.Mode.JOIN));join.setName("join-room");
        quickMatch=ui.comicButton("GHÉP TRẬN NHANH","comic-gold",()->{
            if(!model.isConnecting()) {
                game.setScreen(new OnlineMatchmakingScreen(game,getHost(),getPort(),false));
            }
        });quickMatch.setName("quick-match");
        back=ui.comicButton("< VỀ MENU","comic-white",this::back);back.setName("back");
        serverSettings=ui.comicButton("ĐỔI MÁY CHỦ","comic-white",()->{serverModal.setVisible(true);stage.setKeyboardFocus(address);});serverSettings.setName("server-settings");
        status=ui.small("Chọn cách chơi. Ghép nhanh không cần mã phòng.",Color.WHITE);status.setWrap(true);status.setAlignment(Align.center);
        serverBadge=ui.small("",OnlineUi.BRAWL_CYAN);serverBadge.setEllipsis(true);serverBadge.setName("server-endpoint-preview");

        var root=new Table();root.setFillParent(true);root.pad(20,40,20,40);
        var header=new Table();header.defaults().minWidth(0);
        header.add(back).width(150).height(44);
        var title=ui.comicHeading("CHƠI ONLINE",Color.WHITE);header.add(title).expandX().center();
        var profile=ui.small(com.smashprofs.game.profile.PlayerProfile.get().getDisplayName() + " · " + com.smashprofs.game.profile.PlayerProfile.get().getElo() + " ELO", Color.WHITE);
        profile.setEllipsis(true);profile.setAlignment(Align.right);header.add(profile).width(260).padRight(16);
        var ranking=ui.comicButton("BXH","comic-gold",()->game.setScreen(new LeaderboardScreen(game,this)));ranking.setName("leaderboard");
        header.add(ranking).width(78).height(44);root.add(header).growX().height(56).row();



        var quickCard=card("GHÉP TRẬN",alex,OnlineUi.BRAWL_GOLD,"Tìm đối thủ hoặc đồng đội online.\nĐủ người theo chế độ là tự vào trận.");
        quickCard.add(quickMatch).growX().height(50).padTop(8);
        var createCard=card("TẠO PHÒNG",luca,OnlineUi.BRAWL_CYAN,"Chơi cùng bạn bè, tự chỉnh luật.\nGửi mã phòng để mời mọi người.");
        createCard.add(create).growX().height(50).padTop(8);
        var joinCard=card("VÀO PHÒNG",leo,Color.valueOf("58BFFF"),"Đã có mã từ bạn bè?\nNhập mã bên dưới để tham gia.");
        joinCard.add(code).growX().height(44).padTop(8).row();joinCard.add(join).growX().height(50).padTop(8);
        var cards=new Table();cards.defaults().minWidth(0);
        cards.add(quickCard).width(360).height(400).padRight(20);
        cards.add(createCard).width(360).height(400).padRight(20);
        cards.add(joinCard).width(360).height(400);
        root.add(cards).growX().expandY().center().padTop(14).padBottom(14).row();

        var footer=new Table();footer.defaults().minWidth(0);
        footer.add(ui.small("ESC: Về menu",Color.valueOf("C8E6FF"))).width(155).left();
        footer.add(status).growX().height(42).padLeft(12).padRight(12);
        footer.add(serverSettings).width(190).height(40);
        root.add(footer).growX().height(48);
        stage.addActor(root);buildServerModal();stage.addActor(serverModal);
        address.setTextFieldListener((field,c)->updateBadge());port.setTextFieldListener((field,c)->updateBadge());updateBadge();
    }

    private Image portrait(String file,int x) {
        var texture=new Texture(Gdx.files.internal(file));texture.setFilter(Texture.TextureFilter.Nearest,Texture.TextureFilter.Nearest);textures.add(texture);
        return new Image(new TextureRegion(texture,x,0,100,100));
    }
    private Table card(String title,Image portrait,Color accent,String description) {
        var card=new Table();card.setBackground(ui.comicBox(OnlineUi.BRAWL_DARK,accent,3,3,4));card.pad(12);card.defaults().minWidth(0);
        card.add(ui.comicHeading(title,Color.WHITE)).height(40).row();
        card.add(portrait).size(140).padTop(8).row();
        var help=ui.small(description,Color.valueOf("CEE1F8"));help.setWrap(true);help.setAlignment(Align.center);
        card.add(help).width(300).height(50).padTop(8).row();card.add().expandY().row();return card;
    }
    private void buildServerModal() {
        serverModal.setFillParent(true);serverModal.setBackground(ui.box("000000C0",0));serverModal.setTouchable(Touchable.enabled);serverModal.setVisible(false);
        var panel=new Table();panel.setBackground(ui.comicBox(OnlineUi.BRAWL_DARK,OnlineUi.BRAWL_CYAN,3,4,4));panel.pad(24);panel.defaults().minWidth(0);
        panel.add(ui.comicHeading("MÁY CHỦ",Color.WHITE)).left().row();
        var help=ui.small("Địa chỉ đã được điền sẵn. Chỉ đổi khi bạn dùng máy chủ riêng.",Color.valueOf("CEE1F8"));help.setWrap(true);
        panel.add(help).width(620).height(46).padTop(12).row();
        var fields=new Table();fields.defaults().minWidth(0);
        if(Gdx.app.getType()==Application.ApplicationType.WebGL) {
            fields.add(address).width(620).height(46);
        } else {
            fields.add(address).width(488).height(46).padRight(12);fields.add(port).width(120).height(46);
        }
        panel.add(fields).padTop(8).row();panel.add(serverBadge).width(620).height(30).padTop(10).row();
        var done=ui.comicButton("XONG","comic-primary",this::closeServerModal);done.setName("close-server-settings");
        panel.add(done).width(180).height(46).right().padTop(18);serverModal.add(panel).width(700);
    }
    private String getHost() { String h=address.getText().strip();return h.isBlank()?"127.0.0.1":h; }
    private String getPort() { String p=port.getText().strip();return p.isBlank()?"54555":p; }
    private void closeServerModal() { serverModal.setVisible(false);stage.setKeyboardFocus(code); }
    private void updateBadge() {
        String host=address.getText().strip();
        serverBadge.setText(host.startsWith("ws://") || host.startsWith("wss://")?host:host+":"+port.getText().strip());
    }
    private void connect(LobbyConfiguration.Mode mode) {
        String room=mode==LobbyConfiguration.Mode.CREATE?OnlineRoomPresentation.newRoomCode():code.getText().strip();
        if(mode==LobbyConfiguration.Mode.JOIN && room.isEmpty()) { status.setText("Hãy nhập mã phòng trước khi bấm Vào phòng.");status.setColor(OnlineUi.DANGER);return; }
        model.connect(getHost(),getPort(),room,mode,false,4);refresh();
    }
    private void refresh() {
        boolean busy=model.isConnecting();address.setDisabled(busy);port.setDisabled(busy);code.setDisabled(busy);
        create.setDisabled(busy);quickMatch.setDisabled(busy);join.setDisabled(busy);back.setDisabled(busy);serverSettings.setDisabled(busy);
        status.setText(model.getStatus());status.setColor(model.hasError()?OnlineUi.DANGER:Color.WHITE);
    }
    private void back() {
        if(serverModal.isVisible()) { closeServerModal(); }
        else if(model.isConnecting()) { model.cancel();refresh(); }
        else { game.setScreen(new MainMenuScreen(game)); }
    }
    public void show() { Gdx.input.setInputProcessor(stage);stage.setKeyboardFocus(code); }
    public void render(float delta) {
        if(disposed) { return; }
        var client=model.takeJoinedClient();
        if(client!=null) {
            Gdx.app.getPreferences("online-server").putString("host",getHost()).putString("port",getPort()).flush();
            game.openOnlineLobby(client);return;
        }
        if(Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) { back();if(disposed) { return; } }
        if(Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) {
            if(serverModal.isVisible()) { closeServerModal(); }
            else if(!model.isConnecting()) { connect(LobbyConfiguration.Mode.JOIN); }
        }
        if(model.isConnecting() || model.hasError()) { refresh(); }
        ScreenUtils.clear(Color.valueOf("0054B5"));stage.getViewport().apply();batch.setProjectionMatrix(stage.getCamera().combined);
        batch.begin();batch.setColor(.12f,.32f,.68f,.35f);batch.draw(bgTexture,0,0,1280,720);batch.setColor(Color.WHITE);batch.end();
        stage.act(delta);stage.draw();
    }
    public void resize(int width,int height) { stage.getViewport().update(width,height,true); }
    public void pause() { } public void resume() { } public void hide() { dispose(); }
    public void dispose() {
        if(disposed) { return; }disposed=true;model.close();
        if(Gdx.input.getInputProcessor()==stage) { Gdx.input.setInputProcessor(null); }
        stage.dispose();batch.dispose();for(var texture:textures) { texture.dispose(); }textures.clear();ui.close();
    }
}
