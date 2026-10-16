package com.smashprofs.game.Screens;

import com.badlogic.gdx.*;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.smashprofs.game.Game;
import com.smashprofs.game.network.client.*;
import com.smashprofs.game.ui.OnlineUi;
import java.util.*;

/** Readable final standings from the immutable server result, with a path back to online play. */
final class OnlineResultScreen implements Screen {
    private final Stage stage=new Stage(new FitViewport(1280,720));
    private final OnlineUi ui=new OnlineUi();
    private final Game game;
    private boolean disposed;
    OnlineResultScreen(Game game,WireMatchResult result,String localId) {
        this.game=game;var root=new Table();root.pad(24);root.defaults().left();
        root.add(ui.small("KẾT QUẢ TRẬN ĐẤU  /  PHÒNG "+result.roomId()));root.row();
        var winner=result.winner();String champion=result.isDraw()?"TRẬN ĐẤU HÒA":(winner.playerId().equals(localId)?"BẠN ĐÃ CHIẾN THẮNG":"P"+(winner.slotIndex()+1)+" · "+winner.characterId()+" CHIẾN THẮNG");
        var title=ui.heading(champion);title.setColor(OnlineUi.ACCENT);root.add(title).padTop(16);root.row();
        String reason=switch(result.finishReason()) {
            case "TIME_LIMIT"->"Hết thời gian · Xét HP cao nhất; bằng HP thì hòa.";
            case "LAST_PLAYER_STANDING"->"Chỉ còn một người có mạng.";
            default->"Không còn người chơi có mạng.";
        };
        root.add(ui.label(reason)).padTop(8);root.row();
        var table=ui.panel();table.defaults().pad(12).left();
        String[] headers={"Người chơi","HP cuối","Mạng","Sát thương","K.O.","Lần chết","Vật phẩm","Vũ khí"};
        for(String header:headers) { table.add(ui.small(header)); }table.row();
        for(var p:result.participants()) {
            var identity=ui.label("P"+(p.slotIndex()+1)+" · "+p.characterId()+(p.playerId().equals(localId)?" · Bạn":""));
            identity.setColor(p.playerId().equals(localId)?OnlineUi.ACCENT:OnlineUi.TEXT);table.add(identity).minWidth(230);
            table.add(ui.label(Integer.toString(Math.round(p.finalHp()))));
            if(result.gameplay()!=null) {
                var s=result.gameplay().player(p.playerId());
                for(String value:new String[]{""+s.lives(),String.format(Locale.ROOT,"%.0f",s.damageDealt()),""+s.kills(),""+s.deaths(),""+s.itemsCollected(),""+s.weaponsCollected()}) { table.add(ui.label(value)); }
            } else { for(int i=0;i<6;i++) { table.add(ui.label("—")); } }
            table.row();
        }
        root.add(table).growX().padTop(24);root.row();
        if(result.gameplay()!=null) {
            var awards=new Table();String[] types={"DAMAGE","WEAPONS","KO"};String[] titles={"VUA SÁT THƯƠNG","THỢ NHẶT VŨ KHÍ","NHIỀU K.O. NHẤT"};
            for(int i=0;i<types.length;i++) {
                var ids=result.gameplay().leaders(types[i]);String names=ids.isEmpty()?"Chưa có danh hiệu":ids.stream().map(id->name(result,id)).collect(java.util.stream.Collectors.joining(", "));
                var card=ui.panel();card.pad(16);card.add(ui.small(titles[i])).left();card.row();var label=ui.wrapped(names+(ids.size()>1?" · Đồng hạng":""));card.add(label).width(330).padTop(10);
                awards.add(card).width(370).height(96).padRight(10);
            }
            root.add(awards).growX().padTop(16);root.row();
            root.add(ui.small("Thời lượng: "+String.format(Locale.ROOT,"%.1f",result.gameplay().durationTicks()/60f)+" giây · Số liệu do máy chủ ghi nhận.")).padTop(16);root.row();
        }
        root.add(ui.primary("Chơi trận khác",this::back)).width(330).height(48).padTop(16);root.row();
        root.add(ui.small("Trở về màn hình Tạo phòng / Vào phòng.  [Esc]")).padTop(10);
        var scroll=new ScrollPane(root,new ScrollPane.ScrollPaneStyle());scroll.setScrollingDisabled(true,false);
        var frame=new Table();frame.setFillParent(true);frame.add(scroll).grow();stage.addActor(frame);
    }
    private static String name(WireMatchResult result,String id) { return result.participants().stream().filter(p->p.playerId().equals(id)).map(p->"P"+(p.slotIndex()+1)+" · "+p.characterId()).findFirst().orElse("Người chơi"); }
    private void back() { game.leaveOnlineLobby();game.setScreen(new OnlineLobbyScreen(game)); }
    @Override public void show() { Gdx.input.setInputProcessor(stage); }
    @Override public void render(float delta) {
        if(disposed) { return; }if(Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) { back();return; }
        ScreenUtils.clear(OnlineUi.BACKGROUND);stage.getViewport().apply();stage.act(delta);stage.draw();
    }
    @Override public void resize(int w,int h) { stage.getViewport().update(w,h,true); }
    @Override public void pause() { } @Override public void resume() { }
    @Override public void hide() { dispose(); }
    @Override public void dispose() { if(disposed) { return; }disposed=true;if(Gdx.input.getInputProcessor()==stage) { Gdx.input.setInputProcessor(null); }stage.dispose();ui.close(); }
}
