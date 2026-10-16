package com.smashprofs.game.Scenes;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.smashprofs.game.network.client.*;
import com.smashprofs.game.ui.*;
import java.util.*;

/** Small fixed-size player cards; equipment details are opt-in instead of covering the arena. */
public final class OnlineHud implements AutoCloseable {
    private final Stage stage;
    private final OnlineUi ui=new OnlineUi();
    private final CombatIcons icons=new CombatIcons();
    private final Map<String,PlayerCard> players=new LinkedHashMap<>();
    private final Map<String,Integer> slots=new HashMap<>();
    private final String localId;
    private final Label timer,remaining,status,spectator,weaponName,ammo,ability,bagWeapon,bagShield;
    private final Label[] feed=new Label[2];
    private final Image weaponIcon,bagIcon,shieldIcon;
    private final Table bag,help;
    private String displayedWeapon="NONE";
    private static final class PlayerCard {
        final Label hp,lives,ping;final ProgressBar bar;
        PlayerCard(OnlineUi ui) { hp=ui.small("100 HP");lives=ui.small("×3 mạng");ping=ui.small("… ms");bar=new ProgressBar(0,100,1,false,ui.skin); }
    }
    public OnlineHud(SpriteBatch batch,GameStartRoster roster,String localId) {
        this.localId=localId;stage=new Stage(new FitViewport(1280,720),batch);
        var root=new Table();root.setFillParent(true);root.top().pad(8);
        var strip=new Table();int index=0,split=(roster.players().size()+1)/2;
        timer=ui.label("--:--");remaining=ui.small("Đang vào trận");
        for(var member:roster.players()) {
            if(index++==split) { addClock(strip); }
            String id=member.playerId().value();slots.put(id,member.slotIndex());var card=new PlayerCard(ui);players.put(id,card);
            var table=ui.panel();table.pad(6);table.defaults().left();
            var title=ui.small("P"+(member.slotIndex()+1)+" "+member.characterId());title.setColor(id.equals(localId)?OnlineUi.ACCENT:OnlineUi.TEXT);
            table.add(title).expandX();table.add(card.lives).right();table.row();
            table.add(card.bar).colspan(2).growX().height(4).padTop(3);table.row();
            table.add(card.hp).padTop(3);table.add(card.ping).right().padTop(3);
            strip.add(table).width(178).height(52).padRight(5);
        }
        if(roster.players().size()==1) { addClock(strip); }
        root.add(strip);root.row();status=ui.small("");status.setColor(OnlineUi.DANGER);root.add(status).padTop(3);root.row();
        spectator=ui.small("");spectator.setColor(OnlineUi.DANGER);root.add(spectator).padTop(2);stage.addActor(root);

        var feedRoot=new Table();feedRoot.setFillParent(true);feedRoot.top().right().padTop(80).padRight(12);
        for(int i=0;i<feed.length;i++) { feed[i]=ui.small("");feed[i].setWrap(true);feedRoot.add(feed[i]).width(270).padBottom(4);feedRoot.row(); }stage.addActor(feedRoot);

        var bottom=new Table();bottom.setFillParent(true);bottom.bottom().padBottom(8);
        var equipment=ui.panel();equipment.pad(6);equipment.defaults().left();
        weaponIcon=new Image(icons.drawable("NONE"));equipment.add(weaponIcon).size(36).padRight(6);
        var description=new Table();description.defaults().left();weaponName=ui.small("Tay không");ammo=ui.small("V / Space: tấn công");description.add(weaponName);description.row();description.add(ammo).padTop(3);
        equipment.add(description).width(140).padRight(12);
        shieldIcon=new Image(icons.drawable("SHIELD"));shieldIcon.setColor(1,1,1,.25f);equipment.add(shieldIcon).size(30).padRight(12);
        equipment.add(new Image(icons.drawable("ABILITY"))).size(30).padRight(4);ability=ui.small("F · Sẵn sàng");equipment.add(ability).width(115);
        equipment.add(ui.small("[Tab] Túi đồ   [H] Phím")).padLeft(12);bottom.add(equipment).height(48);stage.addActor(bottom);

        var bagRoot=new Table();bagRoot.setFillParent(true);bagRoot.bottom().right().padRight(12).padBottom(68);
        bag=ui.panel();bag.pad(14);bag.defaults().left();bag.add(ui.label("TRANG BỊ ĐANG MANG")).colspan(2);bag.row();
        bagIcon=new Image(icons.drawable("NONE"));bag.add(bagIcon).size(54).padRight(10).padTop(12);
        bagWeapon=ui.wrapped("Tay không");bag.add(bagWeapon).width(245).padTop(12);bag.row();
        bag.add(new Image(icons.drawable("SHIELD"))).size(40).padRight(10).padTop(8);bagShield=ui.small("Chưa có khiên");bag.add(bagShield).padTop(8);bag.row();
        var hint=ui.small("Đến gần để tự nhặt. Nhặt súng mới sẽ thay súng đang cầm. Bình máu dùng ngay, không cất trong túi.");hint.setWrap(true);
        bag.add(hint).colspan(2).width(310).padTop(12);bag.setVisible(false);bagRoot.add(bag).width(350);stage.addActor(bagRoot);
        var helpRoot=new Table();helpRoot.setFillParent(true);helpRoot.bottom().left().padLeft(12).padBottom(68);
        help=ui.panel();help.pad(12);help.add(ui.small("A/D di chuyển · W nhảy · S dậm\nV/Space bắn hoặc đánh · F tuyệt chiêu\nCtrl đỡ · Tab túi đồ · Esc rời trận\nDung nham: -10 HP và bật lên"));help.setVisible(false);helpRoot.add(help);stage.addActor(helpRoot);
    }
    private void addClock(Table strip) { var clock=ui.panel();clock.pad(5);clock.add(timer);clock.row();clock.add(remaining).padTop(3);strip.add(clock).width(106).height(52).padLeft(5).padRight(10); }
    public void toggleInventory() { bag.setVisible(!bag.isVisible()); }
    public void toggleHelp() { help.setVisible(!help.isVisible()); }
    public void update(StateInterpolator.V1.Frame frame,String connectionStatus,boolean connected) { update(frame,connectionStatus,connected,null); }
    public void update(StateInterpolator.V1.Frame frame,String connectionStatus,boolean connected,ClientMatchSession session) {
        if(frame!=null) {
            var latest=frame.authoritative();var gameplay=latest.gameplay();
            for(var p:latest.players()) {
                var card=players.get(p.playerId());card.bar.setValue(p.hp());card.hp.setText(Math.round(p.hp())+" HP");
                card.bar.setColor(p.hp()<30?OnlineUi.DANGER:Color.WHITE);
                if(gameplay==null) { card.lives.setText(p.dead()?"Đã loại":"");continue; }
                var progress=gameplay.player(p.playerId());card.lives.setText("×"+progress.lives()+" mạng");card.lives.setColor(progress.lives()==0?OnlineUi.DANGER:OnlineUi.TEXT);
                if(progress.status().equals("RESPAWNING")) { card.hp.setText("Hồi sinh "+String.format(Locale.ROOT,"%.1fs",progress.respawnTicks()/60f)); }
                else if(progress.status().equals("ELIMINATED")) { card.hp.setText("Đang xem"); }
                var ping=session==null?null:session.getPings().get(p.playerId());
                card.ping.setText(!connected || ping!=null && !ping.connected()?"Mất mạng":ping==null || ping.milliseconds()==null?"… ms":String.format(Locale.ROOT,"%.0f ms",ping.milliseconds()));
                card.ping.setColor(ping!=null && ping.milliseconds()!=null && ping.milliseconds()>100?OnlineUi.GOLD:OnlineUi.MUTED);
            }
            long seconds=(latest.remainingTimeMs()+999)/1000;
            timer.setText(gameplay!=null && gameplay.rules().matchSeconds()==0?"SINH TỒN":String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60));
            if(gameplay!=null) {
                remaining.setText("Còn "+gameplay.players().stream().filter(p->p.lives()>0).count()+" người");
                var local=gameplay.player(localId);
                if(!displayedWeapon.equals(local.weapon())) { displayedWeapon=local.weapon();weaponIcon.setDrawable(icons.drawable(displayedWeapon));bagIcon.setDrawable(icons.drawable(displayedWeapon)); }
                weaponName.setText(CombatIcons.name(local.weapon()));String ammunition=local.ammo()<0?"—":Integer.toString(local.ammo());
                ammo.setText(local.weapon().equals("NONE")?"V / Space: đánh":"V / Space: bắn · "+ammunition+" viên");
                bagWeapon.setText(CombatIcons.name(local.weapon())+(local.weapon().equals("NONE")?"\nChưa nhặt vũ khí":"\nCòn "+ammunition+" viên · V/Space để dùng"));
                bagShield.setText(local.shield()?"Khiên · Chặn 1 đòn":"Chưa có khiên");shieldIcon.setColor(1,1,1,local.shield()?1:.25f);
                ability.setText(!local.status().equals("ACTIVE")?"F · Tạm khóa":local.ultimateTicks()==0?"F · SẴN SÀNG":"F · "+((local.ultimateTicks()+59)/60)+" giây");
                ability.setColor(local.ultimateTicks()==0 && local.status().equals("ACTIVE")?OnlineUi.ACCENT:OnlineUi.MUTED);
            }
        }
        spectator.setText(session!=null && session.isSpectating()?"HẾT MẠNG · ĐANG XEM TRẬN ĐẤU":"");
        var entries=session==null?java.util.List.<KillFeedModel.Entry>of():session.getKillFeed().entries();int offset=Math.max(0,entries.size()-feed.length);
        for(int i=0;i<feed.length;i++) {
            if(i+offset>=entries.size()) { feed[i].setText("");continue; }var entry=entries.get(i+offset);var event=entry.event();
            String victim=event.victim()==null?"":"P"+(slots.getOrDefault(event.victim(),0)+1);String killer=event.killer()==null?"":"P"+(slots.getOrDefault(event.killer(),0)+1)+" → ";
            feed[i].setText(switch(event.type()) {
                case "PLAYER_FELL"->killer+victim+" hết HP vì dung nham";
                case "PLAYER_KILLED"->killer+victim+" mất 1 mạng ["+event.weapon()+"]";
                case "PLAYER_RESPAWNED"->victim+" hồi sinh";case "PLAYER_ELIMINATED"->victim+" hết mạng";default->"Trận đấu kết thúc";
            });feed[i].setColor(1,.85f,.5f,entry.alpha());
        }
        status.setText(connected?"":"Mất kết nối máy chủ");
    }
    public void draw(float delta) { stage.getViewport().apply();stage.act(delta);stage.draw(); }
    public void resize(int w,int h) { stage.getViewport().update(w,h,true); }
    @Override public void close() { stage.dispose();icons.close();ui.close(); }
}
