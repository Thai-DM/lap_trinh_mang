package com.smashprofs.game.Screens;

import com.badlogic.gdx.*;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.smashprofs.game.Game;
import com.smashprofs.game.network.client.*;
import com.smashprofs.game.ui.OnlineUi;

/** Connection screen: room creation needs no typed room identifier. */
public final class OnlineLobbyScreen implements Screen {
    private final Game game;
    private final Stage stage=new Stage(new FitViewport(1280,720));
    private final OnlineUi ui=new OnlineUi();
    private final OnlineLobbyFormModel model=new OnlineLobbyFormModel();
    private final TextField address,port,code;
    private final TextButton create,join,back,advanced;
    private final Table connection=new Table();
    private final Label status;
    private boolean disposed,expanded;
    public OnlineLobbyScreen(Game game) {
        this.game=game;
        address=new TextField("127.0.0.1",ui.skin);address.setMaxLength(253);
        port=new TextField("54555",ui.skin);port.setMaxLength(5);
        port.setTextFieldFilter((field,c)->c>='0' && c<='9');
        code=new TextField("",ui.skin);code.setMaxLength(64);code.setMessageText("Nhập mã bạn bè gửi");
        code.setTextFieldFilter((field,c)->Character.isLetterOrDigit(c) && c<128 || c=='-' || c=='_');
        create=ui.primary("Tạo phòng mới",()->connect(LobbyConfiguration.Mode.CREATE));
        join=ui.primary("Vào phòng",()->connect(LobbyConfiguration.Mode.JOIN));
        back=ui.button("Về menu",this::back);
        advanced=ui.button("Địa chỉ máy chủ: máy này  ·  Thay đổi",()->{ expanded=!expanded;updateConnection(); });
        status=ui.wrapped("Mở server trước, sau đó tạo phòng hoặc nhập mã để chơi cùng bạn bè.");status.setColor(OnlineUi.MUTED);
        var root=new Table();root.setFillParent(true);root.pad(36);
        var header=new Table();header.add(ui.small("SUPER SMASH PROFS  /  CHƠI ONLINE")).expandX().left();header.add(back).height(42);
        root.add(header).growX();root.row();root.add(ui.heading("Vào sàn đấu cùng bạn bè")).left().padTop(24);root.row();
        root.add(ui.label("2–4 người chơi · Mỗi người một cửa sổ game")).left().padTop(6);root.row();
        var cards=new Table();
        var host=ui.panel();host.defaults().left();host.add(ui.small("01  /  LÀM CHỦ PHÒNG"));host.row();
        host.add(ui.heading("Tạo trận đấu")).padTop(12);host.row();
        host.add(ui.wrapped("Nhận mã phòng tự động, chọn nhân vật và chỉnh luật ngay trong sảnh.")).width(475).padTop(16);host.row();
        host.add(create).growX().height(52).padTop(30);host.row();host.add(ui.small("Gửi mã phòng cho người chơi còn lại.")).padTop(12);
        var guest=ui.panel();guest.defaults().left();guest.add(ui.small("02  /  THAM GIA"));guest.row();
        guest.add(ui.heading("Chơi cùng bạn")).padTop(12);guest.row();
        guest.add(ui.small("Mã phòng")).padTop(10);guest.row();guest.add(code).width(475).height(44).padTop(5);guest.row();guest.add(join).growX().height(52).padTop(12);guest.row();
        guest.add(ui.small("Dùng đúng mã và cùng máy chủ với chủ phòng.")).padTop(12);
        cards.add(host).width(570).height(285).padRight(20);cards.add(guest).width(570).height(285);
        root.add(cards).padTop(24);root.row();root.add(advanced).growX().height(42).padTop(20);root.row();
        root.add(connection).growX();root.row();root.add(status).growX().height(65).padTop(12);root.row();
        root.add(ui.small("LUẬT CƠ BẢN  ·  Dung nham: -10 HP và bật lên. Hết HP = mất 1 mạng. Hết mạng = xem trận đấu.")).left().expandY().bottom();
        var scroll=new ScrollPane(root,new ScrollPane.ScrollPaneStyle());scroll.setScrollingDisabled(true,false);scroll.setFadeScrollBars(false);
        var frame=new Table();frame.setFillParent(true);root.setFillParent(false);frame.add(scroll).grow();stage.addActor(frame);updateConnection();
    }
    private void updateConnection() {
        connection.clear();
        if(expanded) {
            connection.add(ui.small("IP máy chạy server")).left().padTop(10);connection.add(ui.small("Cổng")).left().padTop(10);connection.row();
            connection.add(address).growX().height(42).padRight(12).padTop(5);connection.add(port).width(120).height(42).padTop(5);connection.row();
            connection.add(ui.small("Hai cửa sổ trên máy này: 127.0.0.1. Chơi LAN: nhập IP máy đang chạy server.")).colspan(2).left().padTop(7);
        }
        advanced.setText("Máy chủ: "+address.getText()+":"+port.getText()+ (expanded?"  ·  Thu gọn":"  ·  Thay đổi"));
    }
    private void connect(LobbyConfiguration.Mode mode) {
        String room=mode==LobbyConfiguration.Mode.CREATE?OnlineRoomPresentation.newRoomCode():code.getText().strip();
        if(mode==LobbyConfiguration.Mode.JOIN && room.isEmpty()) { status.setText("Hãy nhập mã phòng trước khi bấm Vào phòng.");status.setColor(OnlineUi.DANGER);return; }
        model.connect(address.getText(),port.getText(),room,mode);refresh();
    }
    private void refresh() {
        boolean busy=model.isConnecting();address.setDisabled(busy);port.setDisabled(busy);code.setDisabled(busy);
        create.setDisabled(busy);join.setDisabled(busy);advanced.setDisabled(busy);back.setText(busy?"Hủy kết nối":"Về menu");
        status.setText(model.getStatus());status.setColor(model.hasError()?OnlineUi.DANGER:OnlineUi.MUTED);
    }
    private void back() { if(model.isConnecting()) { model.cancel();refresh(); }else { game.setScreen(new MainMenuScreen(game)); } }
    @Override public void show() { Gdx.input.setInputProcessor(stage);stage.setKeyboardFocus(code); }
    @Override public void render(float delta) {
        if(disposed) { return; }var client=model.takeJoinedClient();if(client!=null) { game.openOnlineLobby(client);return; }
        if(Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) { back();if(disposed) { return; } }
        if(Gdx.input.isKeyJustPressed(Input.Keys.ENTER) && !model.isConnecting()) { connect(LobbyConfiguration.Mode.JOIN); }
        if(model.isConnecting() || model.hasError()) { refresh(); }
        ScreenUtils.clear(OnlineUi.BACKGROUND);stage.getViewport().apply();stage.act(delta);stage.draw();
    }
    @Override public void resize(int w,int h) { stage.getViewport().update(w,h,true); }
    @Override public void pause() { } @Override public void resume() { }
    @Override public void hide() { dispose(); }
    @Override public void dispose() { if(disposed) { return; }disposed=true;model.close();if(Gdx.input.getInputProcessor()==stage) { Gdx.input.setInputProcessor(null); }stage.dispose();ui.close(); }
}
