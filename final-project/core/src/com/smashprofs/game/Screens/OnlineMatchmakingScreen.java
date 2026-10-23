package com.smashprofs.game.Screens;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.*;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.utils.*;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.smashprofs.game.Game;
import com.smashprofs.game.network.client.*;
import com.smashprofs.game.ui.OnlineUi;

/** Character choice and cancellable casual pairing; the server owns roster and readiness. */
public final class OnlineMatchmakingScreen implements Screen {
    private final Game game;
    private final String host,port;
    private final Stage stage=new Stage(new FitViewport(1280,720));
    private final OnlineUi ui=new OnlineUi();
    private final SelectBox<String> mode,character;
    private final TextButton search,cancel;
    private final Label status,timer;
    private final Image portrait=new Image();
    private final Texture[] textures=new Texture[5];
    private LobbyClient client;
    private float elapsed;
    private boolean disposed;
    public LobbyClient getLobbyClient() { return client; }

    public OnlineMatchmakingScreen(Game game,String host,String port,boolean boss) {
        this.game=game;this.host=host;this.port=port;
        String[] paths={"Sprites/Alex/alex_stand.png","Sprites/Momo/momo_strip.png","Sprites/Luca/luca_stand.png","Sprites/Leo/leo_stand.png","Sprites/Viktor/viktor_stand.png"};
        for(int i=0;i<paths.length;i++) { textures[i]=new Texture(Gdx.files.internal(paths[i]));textures[i].setFilter(Texture.TextureFilter.Nearest,Texture.TextureFilter.Nearest); }
        mode=new SelectBox<>(ui.skin);mode.setItems("Đối kháng PvP · 1 đấu 1","Hợp tác Boss · 2 người");mode.setSelectedIndex(boss?1:0);mode.setName("quick-mode");
        character=new SelectBox<>(ui.skin);character.setItems("Alex","Maurice","Luca","Leo","Viktor");character.setName("quick-character");
        character.addListener(new ChangeListener() { public void changed(ChangeEvent event,Actor actor) { updatePortrait(); } });updatePortrait();
        search=ui.comicButton("TÌM TRẬN", "comic-gold",this::search);search.setName("find-match");
        cancel=ui.comicButton("HỦY TÌM", "comic-white",this::cancel);cancel.setName("cancel-matchmaking");cancel.setDisabled(true);
        status=ui.small("Chọn nhân vật và chế độ rồi bấm Tìm trận.",Color.WHITE);status.setWrap(true);status.setAlignment(Align.center);status.setName("matchmaking-status");
        timer=ui.small("",OnlineUi.BRAWL_CYAN);
        var root=new Table();root.setFillParent(true);root.pad(28);
        var back=ui.comicButton("< VỀ SẢNH", "comic-white",this::back);back.setName("matchmaking-back");
        root.add(back).left().height(44).row();
        root.add(ui.comicHeading("GHÉP TRẬN ONLINE",Color.WHITE)).padTop(14).row();
        root.add(ui.small("Không cần mã phòng · Đủ hai người là tự vào trận",OnlineUi.BRAWL_CYAN)).padTop(8).row();
        var panel=new Table();panel.setBackground(ui.comicBox(OnlineUi.BRAWL_DARK,OnlineUi.BRAWL_CYAN,3,4,4));panel.pad(20);
        panel.add(portrait).size(120).row();panel.add(character).width(340).height(38).padTop(8).row();
        panel.add(mode).width(340).height(38).padTop(10).row();
        var buttons=new Table();buttons.add(search).width(220).height(48).padRight(10);buttons.add(cancel).width(150).height(48);
        panel.add(buttons).padTop(18).row();panel.add(status).width(490).height(55).padTop(12).row();panel.add(timer).padTop(5);
        root.add(panel).width(560).padTop(20).expandY().center();stage.addActor(root);
    }
    private void updatePortrait() { portrait.setDrawable(new TextureRegionDrawable(new TextureRegion(textures[character.getSelectedIndex()],0,0,100,100))); }
    private void search() {
        if(client!=null) { return; }
        try {
            var config=new LobbyConfiguration(host.strip(),Integer.parseInt(port.strip()),"matchmaking",LobbyConfiguration.Mode.JOIN);
            client=new LobbyClient(config);client.enableMatchmaking(mode.getSelectedIndex()==1,character.getSelected());
            elapsed=0;client.startAsync();status.setColor(Color.WHITE);
        } catch(IllegalArgumentException failure) {
            if(client!=null) { client.close();client=null; }
            status.setText("Không thể tìm trận. Kiểm tra địa chỉ và cổng máy chủ.");status.setColor(OnlineUi.DANGER);
        }
    }
    private void cancel() {
        if(client!=null) { client.close();client=null; }
        elapsed=0;status.setText("Đã hủy tìm trận. Bạn có thể chọn lại nhân vật.");status.setColor(Color.WHITE);
    }
    private void back() { cancel();game.setScreen(new OnlineLobbyScreen(game)); }
    public void show() { Gdx.input.setInputProcessor(stage); }
    public void render(float delta) {
        if(disposed) { return; }
        if(Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) { back();return; }
        if(client!=null) {
            elapsed+=delta;var state=client.getSnapshot();
            if(state.phase()==LobbyClient.Phase.ERROR || state.phase()==LobbyClient.Phase.DISCONNECTED) {
                String error=OnlineRoomPresentation.error(state.errorCode(),state.statusText());
                client.close();client=null;status.setText(error);status.setColor(OnlineUi.DANGER);
            } else if(state.phase()==LobbyClient.Phase.WAITING_FOR_START && state.members().size()==2 && state.members().stream().allMatch(LobbyClient.Member::ready)) {
                var found=client;client=null;game.openOnlineLobby(found);return;
            } else {
                status.setText(state.phase()==LobbyClient.Phase.CONNECTING || state.phase()==LobbyClient.Phase.JOINING
                        ?"Đang kết nối máy chủ…":(mode.getSelectedIndex()==1?"Đang tìm đồng đội…":"Đang tìm đối thủ…")+" 1/2\nTrận sẽ tự bắt đầu khi có người cùng tìm.");
            }
        }
        boolean searching=client!=null;search.setDisabled(searching);cancel.setDisabled(!searching);mode.setDisabled(searching);character.setDisabled(searching);
        timer.setText(searching?"Đã tìm: "+(int)elapsed+" giây":"");
        ScreenUtils.clear(OnlineUi.BRAWL_DEEP);stage.getViewport().apply();stage.act(delta);stage.draw();
    }
    public void resize(int width,int height) { stage.getViewport().update(width,height,true); }
    public void pause() { } public void resume() { } public void hide() { dispose(); }
    public void dispose() { if(disposed) { return; }disposed=true;if(client!=null) { client.close();client=null; }if(Gdx.input.getInputProcessor()==stage) { Gdx.input.setInputProcessor(null); }stage.dispose();ui.close();for(var texture:textures) { texture.dispose(); } }
}
