package com.smashprofs.game.Screens;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.smashprofs.common.model.MatchRules;
import com.smashprofs.game.Game;
import com.smashprofs.game.network.client.*;
import com.smashprofs.game.ui.OnlineUi;
import java.util.*;

/** One lobby for roster, rules, character and readiness. All decisions remain server-owned. */
public class OnlineRoomScreen implements Screen {
    private static final String[] CHARACTERS={"Alex","Maurice","Luca","Leo","Viktor"};
    private static final String[] PORTRAITS={"Sprites/Alex/alex_stand.png","Sprites/Momo/momo_strip.png","Sprites/Luca/luca_stand.png","Sprites/Leo/leo_stand.png","Sprites/Viktor/viktor_stand.png"};
    private static final Integer[] TIMES={0,60,90,120,180,300},LIVES={1,2,3,5};
    private final Game game;
    private final LobbyClient lobby;
    private final OnlineUi ui=new OnlineUi();
    private final Stage stage=new Stage(new FitViewport(1280,720));
    private final Label count,status,rule,ownership,draftNotice,readyHint;
    private final Label[] names=new Label[4],details=new Label[4];
    private final TextButton[] characters=new TextButton[5];
    private final ArrayList<Texture> textures=new ArrayList<>();
    private final SelectBox<String> max,time,lives,drops;
    private final TextButton apply,reset,ready,copy;
    private final String roomId;
    private String selected="Alex";
    private long displayedRules=-1;
    private float copiedSeconds;
    private boolean disposed;

    public OnlineRoomScreen(Game game) {
        this.game=game;this.lobby=Objects.requireNonNull(game.getLobbyClient());roomId=lobby.getSnapshot().roomId();
        var root=new Table();root.setFillParent(true);root.pad(24);root.defaults().left();
        var header=new Table();
        var title=new Table();title.add(ui.small("SẢNH ĐẤU  /  MÃ PHÒNG")).left();title.row();title.add(ui.heading(roomId)).left();
        header.add(title).expandX().left();
        copy=ui.button("Sao chép mã",()->{ Gdx.app.getClipboard().setContents(roomId);copiedSeconds=2; });
        header.add(copy).height(44).padRight(10);header.add(ui.button("Rời phòng",this::leave)).height(44);
        root.add(header).growX();root.row();
        count=ui.small("Đang kết nối…");root.add(count).padTop(6);root.row();
        var roster=new Table();
        for(int i=0;i<4;i++) {
            var card=ui.panel();card.pad(15);card.defaults().left();
            names[i]=ui.label("P"+(i+1));details[i]=ui.small("Chờ người chơi");
            card.add(names[i]).width(250);card.row();card.add(details[i]).width(250).padTop(8);
            roster.add(card).width(298).height(94).padRight(i==3?0:12);
        }
        root.add(roster).growX().padTop(12);root.row();
        var columns=new Table();
        var settings=ui.panel();settings.pad(18);settings.defaults().left();
        var settingsHeader=new Table();settingsHeader.add(ui.label("Luật trận đấu")).expandX().left();ownership=ui.small("");settingsHeader.add(ownership);
        settings.add(settingsHeader).colspan(2).growX();settings.row();
        max=select("2 người","3 người","4 người");time=select("Không giới hạn","60 giây","90 giây","120 giây","180 giây","300 giây");
        lives=select("1 mạng","2 mạng","3 mạng","5 mạng");
        drops=select("Tắt","Thấp · 30 giây/lần","Vừa · 15 giây/lần","Cao · 8 giây/lần");
        settings.add(ui.small("Số người tối đa")).padTop(12);settings.add(ui.small("Thời gian")).padTop(12);settings.row();
        settings.add(max).width(252).height(38).padRight(12).padTop(4);settings.add(time).width(252).height(38).padTop(4);settings.row();
        settings.add(ui.small("Số mạng mỗi người")).padTop(10);settings.add(ui.small("Vật phẩm")).padTop(10);settings.row();
        settings.add(lives).width(252).height(38).padRight(12).padTop(4);settings.add(drops).width(252).height(38).padTop(4);settings.row();
        apply=ui.button("Lưu luật phòng",()->lobby.updateSettings(draft()));
        reset=ui.button("Hoàn tác",()->{ if(lobby.getSettings()!=null) { sync(lobby.getSettings().rules()); } });
        settings.add(apply).growX().height(38).padRight(12).padTop(12);settings.add(reset).growX().height(38).padTop(12);settings.row();
        draftNotice=ui.small("");settings.add(draftNotice).colspan(2).padTop(6);
        var character=ui.panel();character.pad(18);character.defaults().left();
        character.add(ui.label("Chọn nhân vật của bạn")).colspan(5);character.row();
        for(int i=0;i<CHARACTERS.length;i++) {
            final String name=CHARACTERS[i];var texture=new Texture(PORTRAITS[i]);textures.add(texture);
            var image=new Image(new TextureRegionDrawable(new TextureRegion(texture,0,0,100,100)));
            var button=new TextButton(name,ui.skin);
            button.addListener(new com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
                @Override public void clicked(com.badlogic.gdx.scenes.scene2d.InputEvent event,float x,float y) { if(!button.isDisabled()) { selected=name; } }
            });
            button.clearChildren();button.add(image).size(56);button.row();button.add(ui.small(name)).padBottom(4);
            characters[i]=button;character.add(button).width(99).height(94).padRight(i==4?0:5).padTop(12);
        }
        character.row();ready=ui.primary("Sẵn sàng",this::toggleReady);character.add(ready).colspan(5).growX().height(46).padTop(14);character.row();
        readyHint=ui.small("Chọn nhân vật, rồi bấm Sẵn sàng.");readyHint.setWrap(true);character.add(readyHint).colspan(5).width(515).height(40).padTop(6);
        columns.add(settings).width(606).height(280).padRight(16);columns.add(character).width(606).height(280);
        root.add(columns).growX().padTop(12);root.row();
        var explanation=ui.panel();explanation.pad(12);explanation.defaults().left();
        explanation.add(ui.small("HP VÀ SỐ MẠNG LÀ HAI CHỈ SỐ RIÊNG")).left();explanation.row();
        var stock=ui.small(OnlineRoomPresentation.stockRule());explanation.add(stock).padTop(4);explanation.row();
        rule=ui.small("");explanation.add(rule).padTop(4);root.add(explanation).growX().padTop(12);root.row();
        status=ui.small("");status.setWrap(true);root.add(status).growX().height(38).padTop(8);stage.addActor(root);
    }
    private SelectBox<String> select(String...items) { var box=new SelectBox<String>(ui.skin);box.setItems(items);return box; }
    private MatchRules draft() { return new MatchRules(max.getSelectedIndex()+2,TIMES[time.getSelectedIndex()],LIVES[lives.getSelectedIndex()],MatchRules.DropRate.values()[drops.getSelectedIndex()],true); }
    private void sync(MatchRules rules) {
        max.setSelectedIndex(rules.maxPlayers()-2);time.setSelectedIndex(Arrays.asList(TIMES).indexOf(rules.matchSeconds()));
        lives.setSelectedIndex(Arrays.asList(LIVES).indexOf(rules.lives()));drops.setSelectedIndex(rules.dropRate().ordinal());
    }
    private void toggleReady() {
        if(lobby.getSnapshot().phase()==LobbyClient.Phase.WAITING_FOR_START) { lobby.unready(); }else { lobby.ready(selected); }
    }
    private void leave() { game.leaveOnlineLobby();game.setScreen(new OnlineLobbyScreen(game)); }
    @Override public void show() { Gdx.input.setInputProcessor(stage); }
    @Override public void render(float delta) {
        if(disposed) { return; }var state=lobby.getSnapshot();
        if(state.isMatchStarted()) { game.setScreen(new PlayScreen(game));return; }
        if(Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) { leave();return; }
        var settings=lobby.getSettings();boolean joined=state.canReady();boolean waiting=state.phase()==LobbyClient.Phase.WAITING_FOR_START;
        boolean host=settings!=null && state.localPlayerId()!=null && settings.hostId().equals(state.localPlayerId().value());
        if(settings!=null && settings.rulesVersion()!=displayedRules) { sync(settings.rules());displayedRules=settings.rulesVersion(); }
        for(var box:new SelectBox[]{max,time,lives,drops}) { box.setDisabled(!host || !joined || settings==null || state.serverPhase().equals("COUNTDOWN")); }
        boolean dirty=settings!=null && !draft().equals(settings.rules());
        boolean belowCount=settings!=null && draft().maxPlayers()<settings.playerCount();
        apply.setDisabled(!host || !joined || !dirty || belowCount || state.serverPhase().equals("COUNTDOWN"));
        reset.setDisabled(!host || !joined || !dirty);
        ownership.setText(host?"BẠN LÀ CHỦ PHÒNG":"CHỈ CHỦ PHÒNG ĐƯỢC SỬA");ownership.setColor(host?OnlineUi.ACCENT:OnlineUi.MUTED);
        draftNotice.setText(waiting && host?"Hủy sẵn sàng trước khi chỉnh luật.":belowCount?"Không thể giảm chỗ dưới số người đang có.":dirty?"Chưa lưu. Lưu luật sẽ hủy sẵn sàng của mọi người.":"Luật đã đồng bộ với máy chủ.");
        draftNotice.setColor(dirty?OnlineUi.GOLD:OnlineUi.MUTED);
        for(int i=0;i<characters.length;i++) { characters[i].setDisabled(!joined);characters[i].setChecked(CHARACTERS[i].equals(selected)); }
        ready.setDisabled(settings==null || !joined && !waiting || dirty && host);
        ready.setText(waiting?"Đã sẵn sàng  ·  Bấm để hủy":state.phase()==LobbyClient.Phase.READY_PENDING?"Đang xác nhận…":"Sẵn sàng  [Enter]");
        if(Gdx.input.isKeyJustPressed(Input.Keys.ENTER) && !ready.isDisabled()) { toggleReady(); }
        var members=settings!=null && !settings.members().isEmpty()?settings.members():state.members();
        int capacity=settings==null?4:settings.rules().maxPlayers();long readied=members.stream().filter(LobbyClient.Member::ready).count();
        count.setText(members.size()+"/"+capacity+" người  ·  "+readied+" đã sẵn sàng  ·  Gửi mã phòng cho bạn bè để tham gia");
        for(int i=0;i<4;i++) {
            final int slot=i;var member=members.stream().filter(m->m.slotIndex()==slot).findFirst().orElse(null);
            if(member==null) { names[i].setText("P"+(i+1)+"  ·  "+(i>=capacity?"Đã khóa":"Trống"));names[i].setColor(OnlineUi.MUTED);details[i].setText(i>=capacity?"Tăng giới hạn để mở chỗ":"Đang chờ người chơi");continue; }
            boolean local=member.playerId().equals(state.localPlayerId());boolean owner=settings!=null && settings.hostId().equals(member.playerId().value());
            names[i].setText("P"+(i+1)+(local?" · Bạn":"")+(owner?" · Chủ phòng":""));names[i].setColor(local?OnlineUi.ACCENT:OnlineUi.TEXT);
            details[i].setText((member.characterId()==null?"Chưa chọn":member.characterId())+"  ·  "+(member.ready()?"Sẵn sàng":"Chưa sẵn sàng"));
            details[i].setColor(member.ready()?OnlineUi.ACCENT:OnlineUi.MUTED);
        }
        readyHint.setText(members.size()<2?"Cần ít nhất 2 người. Sao chép mã để mời bạn bè.":waiting?"Trận tự bắt đầu sau 3 giây khi tất cả sẵn sàng.":dirty && host?"Lưu hoặc bỏ thay đổi luật trước khi sẵn sàng.":"Mỗi người chọn nhân vật rồi bấm Sẵn sàng.");
        if(settings!=null) { rule.setText(OnlineRoomPresentation.winRule(settings.rules())); }
        String message=state.errorCode()!=null?OnlineRoomPresentation.error(state.errorCode(),state.statusText()):switch(state.phase()) {
            case CONNECTING,CREATING,JOINING,IDLE->"Đang mở phòng…";case READY_PENDING->"Đang chờ máy chủ xác nhận…";
            case WAITING_FOR_START->"Bạn đã sẵn sàng. Đang đợi những người chơi còn lại.";
            case ERROR,DISCONNECTED->"Mất kết nối. Bấm Rời phòng để thử lại.";default->"A/D di chuyển · W nhảy · S dậm · V/Space tấn công · F tuyệt chiêu · Ctrl đỡ đòn";
        };
        status.setText(message);status.setColor(state.errorCode()!=null || state.phase()==LobbyClient.Phase.DISCONNECTED?OnlineUi.DANGER:OnlineUi.MUTED);
        copiedSeconds=Math.max(0,copiedSeconds-delta);copy.setText(copiedSeconds>0?"Đã sao chép!":"Sao chép mã");
        ScreenUtils.clear(OnlineUi.BACKGROUND);stage.getViewport().apply();stage.act(delta);stage.draw();
    }
    @Override public void resize(int w,int h) { stage.getViewport().update(w,h,true); }
    @Override public void pause() { } @Override public void resume() { }
    @Override public void hide() { dispose(); }
    @Override public void dispose() { if(disposed) { return; }disposed=true;if(Gdx.input.getInputProcessor()==stage) { Gdx.input.setInputProcessor(null); }stage.dispose();ui.close();textures.forEach(Texture::dispose); }
}
