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
    private final Label pickupHint,networkPing,bagHint;
    private final Table bossPanel;
    private final Label bossTitle;
    private final ProgressBar bossHp;
    private final Label[] feed=new Label[2];
    private final Image weaponIcon,bagIcon,shieldIcon;
    private final Table bag,help,stunPanel;
    private final boolean unarmedMode;
    private String displayedWeapon="NONE";
    private final java.util.List<com.badlogic.gdx.graphics.Texture> portraits=new ArrayList<>();
    private final Label helpText;
    private float uiTime;
    private final Label unarmedHint,airBudget,stunInfo;
    private UnarmedControls.Preset controlPreset=UnarmedControls.Preset.B;
    private boolean showStun;
    private static final Color[] DAMAGE_COLORS={Color.WHITE,Color.valueOf("FFE35B"),Color.valueOf("FF932C"),Color.valueOf("FF4545"),Color.valueOf("AD142B")};
    private static final class PlayerCard {
        final Label hp,lives,ping;final ProgressBar bar;
        final Image[] stockIcons=new Image[5];
        PlayerCard(OnlineUi ui) { hp=ui.small("100 HP");lives=ui.small("×3 mạng");ping=ui.small("… ms");bar=new ProgressBar(0,100,1,false,ui.skin); }
    }
    public OnlineHud(SpriteBatch batch,GameStartRoster roster,String localId) {
        this(batch,roster,localId,false);
    }
    public OnlineHud(SpriteBatch batch,GameStartRoster roster,String localId,boolean unarmed) {
        this.localId=localId;unarmedMode=unarmed;stage=new Stage(new FitViewport(1280,720),batch);
        unarmedHint=ui.small("");airBudget=ui.small("");stunInfo=ui.small("");
        stunPanel=ui.panel();stunPanel.pad(7);stunPanel.add(stunInfo);stunPanel.setVisible(false);
        var root=new Table();root.setFillParent(true);root.top().pad(8);
        var strip=new Table();int index=0,split=(roster.players().size()+1)/2;
        timer=ui.label("--:--");remaining=ui.small("Đang vào trận");
        for(var member:roster.players()) {
            if(index++==split) { addClock(strip); }
            String id=member.playerId().value();slots.put(id,member.slotIndex());var card=new PlayerCard(ui);players.put(id,card);
            var table=ui.panel();table.pad(6);table.defaults().left();
            table.setBackground(ui.comicBox(Color.valueOf("08172CF0"),id.equals(localId)?OnlineUi.ACCENT:Color.valueOf("375F85"),2,2,3));
            var portrait=new com.badlogic.gdx.graphics.Texture(com.badlogic.gdx.Gdx.files.internal(OnlineVisualCatalog.character(member.characterId()).stand()));portraits.add(portrait);
            var region=new com.badlogic.gdx.graphics.g2d.TextureRegion(portrait,0,0,100,100);
            table.add(new Image(region)).size(30).padRight(5);var info=new Table();info.defaults().left();table.add(info).grow();
            var title=ui.small("P"+(member.slotIndex()+1)+" "+member.displayName());title.setColor(id.equals(localId)?OnlineUi.ACCENT:OnlineUi.TEXT);
            info.add(title).expandX();info.add(card.lives).right();info.row();
            info.add(card.bar).colspan(2).growX().height(4).padTop(3);info.row();
            info.add(card.hp).padTop(3);info.add(card.ping).right().padTop(3);
            info.row();var stocks=new Table();
            for(int i=0;i<card.stockIcons.length;i++) { card.stockIcons[i]=new Image(region);stocks.add(card.stockIcons[i]).size(13); }
            info.add(stocks).colspan(2).height(12);
            strip.add(table).width(roster.players().size()==2?225:210).height(72).padRight(5);
        }
        if(roster.players().size()==1) { addClock(strip); }
        root.add(strip).growX();root.row();status=ui.small("");status.setColor(OnlineUi.DANGER);root.add(status).padTop(3);root.row();
        spectator=ui.small("");spectator.setColor(OnlineUi.DANGER);root.add(spectator).padTop(2);stage.addActor(root);
        bossPanel=new Table();bossPanel.setFillParent(true);bossPanel.top().padTop(87);
        var bossBox=ui.panel();bossBox.pad(4,12,4,12);bossTitle=ui.small("");bossTitle.setColor(OnlineUi.GOLD);
        bossHp=new ProgressBar(0,100,1,false,ui.skin);
        var bossBarStyle=new ProgressBar.ProgressBarStyle(bossHp.getStyle());bossBarStyle.knobBefore=ui.skin.newDrawable("pixel",OnlineUi.DANGER);bossBarStyle.knobBefore.setMinHeight(7);bossHp.setStyle(bossBarStyle);
        bossBox.add(bossTitle).row();bossBox.add(bossHp).width(340).height(7).padTop(3);
        bossPanel.add(bossBox);bossPanel.setVisible(false);stage.addActor(bossPanel);

        var feedRoot=new Table();feedRoot.setFillParent(true);feedRoot.top().right().padTop(80).padRight(12);
        for(int i=0;i<feed.length;i++) { feed[i]=ui.small("");feed[i].setWrap(true);feedRoot.add(feed[i]).width(270).padBottom(4);feedRoot.row(); }stage.addActor(feedRoot);

        var bottom=new Table();bottom.setFillParent(true);bottom.bottom().padBottom(8);
        pickupHint=ui.small("");pickupHint.setColor(OnlineUi.ACCENT);bottom.add(pickupHint).padBottom(6);bottom.row();
        var equipment=ui.panel();equipment.pad(6);equipment.defaults().left();
        weaponIcon=new Image(icons.drawable("NONE"));equipment.add(weaponIcon).size(36).padRight(6);
        var description=new Table();description.defaults().left();weaponName=ui.small("Tay không");ammo=ui.small("V / Space: tấn công");description.add(weaponName);description.row();description.add(ammo).padTop(3);
        equipment.add(description).width(140).padRight(12);
        shieldIcon=new Image(icons.drawable("SHIELD"));shieldIcon.setColor(1,1,1,.25f);equipment.add(shieldIcon).size(30).padRight(12);
        equipment.add(new Image(icons.drawable("ABILITY"))).size(30).padRight(4);ability=ui.small("F · Sẵn sàng");equipment.add(ability).width(115);
        equipment.add(ui.small("[E] Nhặt  [G] Thả  [Q] Ném  [Tab] Túi  [H] Phím")).padLeft(12);
        if(unarmed) {
            equipment.clearChildren();equipment.add(weaponIcon).size(30).padRight(6);equipment.add(description).width(170).padRight(10);
            equipment.add(shieldIcon).size(24).padRight(10);equipment.add(ability).width(130).padRight(10);
            equipment.add(ui.small("E nhặt · G thả · Q ném · Tab túi · H phím")).colspan(2);
            equipment.row();equipment.add(unarmedHint).colspan(4).padTop(5).padRight(12);equipment.add(airBudget).colspan(2).padTop(5);
        }
        bottom.add(stunPanel).padBottom(4);bottom.row();bottom.add(equipment).height(unarmed?72:48);stage.addActor(bottom);
        var pingRoot=new Table();pingRoot.setFillParent(true);pingRoot.bottom().right().padRight(12).padBottom(12);networkPing=ui.small("");networkPing.setColor(OnlineUi.MUTED);pingRoot.add(networkPing);stage.addActor(pingRoot);

        var bagRoot=new Table();bagRoot.setFillParent(true);bagRoot.bottom().right().padRight(12).padBottom(unarmed?92:68);
        bag=ui.panel();bag.pad(14);bag.defaults().left();bag.add(ui.label("TRANG BỊ ĐANG MANG")).colspan(2);bag.row();
        bagIcon=new Image(icons.drawable("NONE"));bag.add(bagIcon).size(54).padRight(10).padTop(12);
        bagWeapon=ui.wrapped("Tay không");bag.add(bagWeapon).width(245).padTop(12);bag.row();
        bag.add(new Image(icons.drawable("SHIELD"))).size(40).padRight(10).padTop(8);bagShield=ui.small("Chưa có khiên");bag.add(bagShield).padTop(8);bag.row();
        bagHint=ui.small("");bagHint.setWrap(true);
        bag.add(bagHint).colspan(2).width(310).padTop(12);bag.setVisible(false);bagRoot.add(bag).width(350);stage.addActor(bagRoot);
        var helpRoot=new Table();helpRoot.setFillParent(true);helpRoot.bottom().left().padLeft(12).padBottom(unarmed?92:68);
        help=ui.panel();help.pad(12);helpText=ui.small("");helpText.setWrap(true);help.add(helpText).width(740);help.setVisible(false);helpRoot.add(help);stage.addActor(helpRoot);
    }
    private void addClock(Table strip) { strip.add().expandX();var clock=ui.panel();clock.pad(5);clock.add(timer);clock.row();clock.add(remaining).padTop(3);strip.add(clock).width(106).height(52).padLeft(5).padRight(10);strip.add().expandX(); }
    public void toggleInventory() { bag.setVisible(!bag.isVisible()); }
    public void controls(UnarmedControls.Preset preset,boolean debug) { controlPreset=preset;showStun=debug;stunPanel.setVisible(unarmedMode && debug); }
    public void toggleHelp() { help.setVisible(!help.isVisible()); }
    public void update(StateInterpolator.V1.Frame frame,String connectionStatus,boolean connected) { update(frame,connectionStatus,connected,null); }
    public void update(StateInterpolator.V1.Frame frame,String connectionStatus,boolean connected,ClientMatchSession session) {
        pickupHint.setText("");
        if(frame!=null) {
            var latest=frame.authoritative();var gameplay=latest.gameplay();
            bossPanel.setVisible(gameplay!=null && gameplay.boss()!=null);
            if(gameplay!=null && gameplay.boss()!=null) {
                var boss=gameplay.boss();bossHp.setRange(0,boss.maxHp());bossHp.setValue(boss.hp());
                bossTitle.setText("DEMON SLIME · PHA "+boss.phase()+" · "+Math.round(boss.hp())+" / "+Math.round(boss.maxHp())+" HP");
            }
            boolean fighter=gameplay!=null && gameplay.fighter()!=null;
            if(fighter) { unarmedHint.setText(UnarmedControls.hint()); }
            bagHint.setText(fighter?"E nhặt · G thả · Q ném khi đang tự do. Đổi súng giữ đạn của súng cũ. Bình hồi phục: hồi 30 HP. Khiên chặn một đòn.":
                    "Đến gần, bấm E để nhặt; G để thả súng. Đổi súng giữ đạn của súng cũ. Bình máu dùng ngay khi nhặt.");
            helpText.setText(fighter?UnarmedControls.hint()+"\nF chiêu đặc trưng · Có súng: chỉ bắn · Hết đạn hoặc bỏ súng: đánh tay không\nE nhặt · G thả · Q ném (I lên / xuống để ngắm) · Tab túi\nI hướng lên: ưu tiên Neutral, không tiêu nhảy\nXuống + nhảy: xuyên sàn mềm · Giữ xuống: rơi nhanh\nNé tại chỗ trên không rồi đánh tay không: Gravity Cancel\nSau đánh trúng: hướng đuổi + né để Chase Dodge\n2 lượt nhảy trên không · Recovery tay không dùng chung ngân sách\nHết HP hoặc vượt biên: mất mạng · Dung nham -10 HP\nU hitbox / Show Stun · O đổi preset A/B":
                    "A/D di chuyển · W nhảy · S dậm\nV/Space bắn hoặc đánh · F tuyệt chiêu\nE nhặt gần nhất · G thả súng\nCtrl đỡ · Tab túi đồ · Esc rời trận\nDung nham: -10 HP và bật lên");
            if(gameplay!=null && gameplay.boss()!=null) {
                helpText.setText(UnarmedControls.hint()+"\nHỢP TÁC ĐÁNH BOSS: Hạ Demon Slime trước khi hết giờ.\nĐồng đội không gây sát thương nhau.\nCó sẵn Laser; hết đạn tự nạp sau 1,5 giây.\nNé vùng đỏ và đạn lửa; pha 2 bắt đầu ở 50% HP boss.\nHết HP mất một mạng; cả đội hết mạng thì thua.");
                bagHint.setText("Boss: hết đạn tự nạp sau 1,5 giây. E đổi súng · G thả · Q ném. Bình máu và khiên dùng như thường.");
            }
            for(var p:latest.players()) {
                var card=players.get(p.playerId());card.bar.setValue(p.hp());card.hp.setText(Math.round(p.hp())+" HP");
                card.bar.setColor(p.hp()<30?OnlineUi.DANGER:Color.WHITE);
                if(gameplay==null) { card.lives.setText(p.dead()?"Đã loại":"");continue; }
                var progress=gameplay.player(p.playerId());card.lives.setText("×"+progress.lives()+" mạng");card.lives.setColor(progress.lives()==0?OnlineUi.DANGER:OnlineUi.TEXT);
                for(int i=0;i<card.stockIcons.length;i++) { card.stockIcons[i].setVisible(i<gameplay.rules().lives());card.stockIcons[i].setColor(i<progress.lives()?Color.GOLD:Color.DARK_GRAY); }
                if(fighter) {
                    var value=gameplay.fighter().player(p.playerId());double damage=value.damagePercent();
                    card.hp.setFontScale(1.1f);card.hp.setText(Math.round(p.hp())+" HP");card.hp.setColor(p.hp()<=30?OnlineUi.DANGER:Color.WHITE);
                    card.bar.setRange(0,100);card.bar.setValue(p.hp());card.bar.setColor(p.hp()<=30?OnlineUi.DANGER:OnlineUi.ACCENT);
                    if(value.platformTicks()>0) { card.hp.setText("Bệ hồi sinh"); }
                }
                if(progress.status().equals("RESPAWNING")) { card.hp.setText("Hồi sinh "+String.format(Locale.ROOT,"%.1fs",progress.respawnTicks()/60f)); }
                else if(progress.status().equals("ELIMINATED")) { card.hp.setText("Đang xem"); }
                var ping=session==null?null:session.getPings().get(p.playerId());
                card.ping.setVisible(!fighter);
                if(fighter) { ((Table)card.ping.getParent()).getCell(card.ping).width(0); }
                if(p.playerId().equals(localId)) { networkPing.setText(!connected?"Mất kết nối":ping==null || ping.milliseconds()==null?"RTT … ms":String.format(Locale.ROOT,"RTT %.0f ms",ping.milliseconds())); }
                card.ping.setText(!connected || ping!=null && !ping.connected()?"Mất mạng":ping==null || ping.milliseconds()==null?"… ms":String.format(Locale.ROOT,"%.0f ms",ping.milliseconds()));
                card.ping.setColor(ping!=null && ping.milliseconds()!=null && ping.milliseconds()>100?OnlineUi.GOLD:OnlineUi.MUTED);
            }
            long seconds=(latest.remainingTimeMs()+999)/1000;
            timer.setText(gameplay!=null && gameplay.rules().matchSeconds()==0?"SINH TỒN":String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60));
            if(gameplay!=null) {
                remaining.setText("Còn "+gameplay.players().stream().filter(p->p.lives()>0).count()+" người");
                var local=gameplay.player(localId);
                if(connected && local.status().equals("ACTIVE")) {
                    var position=latest.player(localId);com.smashprofs.common.model.GameplaySnapshot.Item nearest=null;double best=.35*.35;
                    for(var item:gameplay.items()) {
                        double dx=position.x()-item.x(),dy=position.y()-item.y(),distance=dx*dx+dy*dy;
                        if(distance<=best && (nearest==null || distance<best || item.id()<nearest.id())) { nearest=item;best=distance; }
                    }
                    if(nearest!=null) { pickupHint.setText("[E] Nhặt "+CombatIcons.name(nearest.type())); }
                }
                if(!displayedWeapon.equals(local.weapon())) { displayedWeapon=local.weapon();weaponIcon.setDrawable(icons.drawable(displayedWeapon));bagIcon.setDrawable(icons.drawable(displayedWeapon)); }
                weaponName.setText(CombatIcons.name(local.weapon()));String ammunition=local.ammo()<0?"—":Integer.toString(local.ammo());
                var cfg=com.smashprofs.game.Helper.KeyBindingConfig.getInstance();
                String attackKey=fighter?com.smashprofs.game.Helper.KeyBindingConfig.keyName(cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.LIGHT)):"V / Space";
                boolean isMelee = local.weapon().equals("SWORD") || local.weapon().equals("WARHAMMER")
                        || local.weapon().equals("DUAL_DAGGERS") || local.weapon().equals("SPEAR");
                String actionVerb = isMelee ? "chém" : "bắn";
                String unit = isMelee ? "lần" : "viên";
                ammo.setText(local.weapon().equals("NONE")?attackKey+": đánh":attackKey+": "+actionVerb+" · "+ammunition+" "+unit);
                if(gameplay.rules().boss() && !isMelee && !local.weapon().equals("NONE") && local.ammo()==0) { ammo.setText("Đang nạp đạn · 1,5 giây"); }
                bagWeapon.setText(CombatIcons.name(local.weapon())+(local.weapon().equals("NONE")?"\nChưa nhặt vũ khí":"\nCòn "+ammunition+" "+unit+" · "+attackKey+" để dùng"));
                bagShield.setText(local.shield()?"Khiên · Chặn 1 đòn":"Chưa có khiên");shieldIcon.setColor(1,1,1,local.shield()?1:.25f);
                String abilityKey=com.smashprofs.game.Helper.KeyBindingConfig.keyName(cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.ABILITY));
                ability.setText(!local.status().equals("ACTIVE")?abilityKey+" · Tạm khóa":local.ultimateTicks()==0?abilityKey+" · SẴN SÀNG":abilityKey+" · "+((local.ultimateTicks()+59)/60)+" giây");
                ability.setColor(local.ultimateTicks()==0 && local.status().equals("ACTIVE")?OnlineUi.ACCENT:OnlineUi.MUTED);
                if(fighter) {
                    var value=gameplay.fighter().player(localId);
                    if(!local.weapon().equals("NONE")) {
                        String dodgeKey=com.smashprofs.game.Helper.KeyBindingConfig.keyName(cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.DODGE));
                        unarmedHint.setText(attackKey+" "+actionVerb+" · "+dodgeKey+" né/lướt · O đổi phím");
                    }
                    int cooldown=Math.max(value.dodgeCooldownTicks(),value.detail().dashCooldownTicks());
                    airBudget.setText("Né/Lướt "+(cooldown==0?"sẵn sàng":String.format(Locale.ROOT,"%.1fs",cooldown/60f))+" · Nhảy "+value.airJumps()+"/1 · GC "+value.detail().gravityCancelTicks()+" · CD "+value.detail().chaseCharges()+" · Tường "+value.detail().wallActions());
                    airBudget.setColor(value.detail().wallActions()>=com.smashprofs.common.model.UnarmedRules.WALL_DANGER?OnlineUi.DANGER:value.detail().wallActions()>=com.smashprofs.common.model.UnarmedRules.WALL_WARNING?OnlineUi.GOLD:OnlineUi.TEXT);
                    if(gameplay.boss()!=null) { airBudget.setText("Hợp tác · Né vùng đỏ · Súng tự nạp sau 1,5 giây");airBudget.setColor(OnlineUi.TEXT); }
                    var lines=new StringBuilder();if(showStun) { for(var player:gameplay.fighter().players()) {
                        var d=player.detail();lines.append("P").append(slots.get(player.playerId())+1).append(" ").append(d.state()).append("/").append(d.phase())
                                .append(" f").append(player.attackFrame()).append(" stun ").append(player.hitstunTicks()).append(" DodgeFrames ").append(d.dodgeFrames()).append(" ").append(d.combo()).append('\n');
                    } }stunInfo.setText(lines.toString());
                }
            }
        }
        spectator.setText(session!=null && session.isSpectating()?"HẾT MẠNG · ĐANG XEM TRẬN ĐẤU":"");
        var entries=session==null?java.util.List.<KillFeedModel.Entry>of():session.getKillFeed().entries();int offset=Math.max(0,entries.size()-feed.length);
        for(int i=0;i<feed.length;i++) {
            if(i+offset>=entries.size()) { feed[i].setText("");continue; }var entry=entries.get(i+offset);var event=entry.event();
            String victim=event.victim()==null?"":"P"+(slots.getOrDefault(event.victim(),0)+1);String killer=event.killer()==null?"":"P"+(slots.getOrDefault(event.killer(),0)+1)+" → ";
            feed[i].setText(switch(event.type()) {
                case "PLAYER_FELL"->killer+victim+" hết HP vì dung nham";
                case "PLAYER_KILLED"->killer+victim+(event.cause().startsWith("BLAST_")?" K.O. · "+FighterViewModel.blastEdge(event.cause()):" mất 1 mạng ["+event.weapon()+"]");
                case "PLAYER_RESPAWNED"->victim+" hồi sinh";case "PLAYER_ELIMINATED"->victim+" hết mạng";default->"Trận đấu kết thúc";
            });feed[i].setColor(1,.85f,.5f,entry.alpha());
        }
        status.setText(connected?"":"Mất kết nối máy chủ");
    }
    public void draw(float delta) { uiTime+=Math.max(0,delta);stage.getViewport().apply();stage.act(delta);stage.draw(); }
    public void resize(int w,int h) { stage.getViewport().update(w,h,true); }
    @Override public void close() { stage.dispose();icons.close();ui.close();portraits.forEach(com.badlogic.gdx.graphics.Texture::dispose); }
}
