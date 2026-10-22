package com.smashprofs.game.Screens;

import com.badlogic.gdx.*;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.maps.objects.RectangleMapObject;
import com.badlogic.gdx.maps.tiled.*;
import com.badlogic.gdx.maps.tiled.renderers.OrthogonalTiledMapRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.ScreenUtils;
import com.smashprofs.common.model.BossSnapshot;
import com.smashprofs.common.model.FighterMovement;
import com.smashprofs.game.network.client.OnlineVisualCatalog;
import com.smashprofs.game.network.client.OnlineCameraController;
import com.smashprofs.game.ui.CombatIcons;
import com.smashprofs.probe.BrowserRoom;
import org.teavm.jso.JSBody;
import java.util.*;

/** Local compatibility trial. Movement collisions here are a fixture, not the authoritative game. */
public final class TeaVmProbe extends ApplicationAdapter {
    private static final String[] CHARACTERS={"Alex","Luca","Maurice","Leo","Viktor"};
    private final Map<String,Texture> textures=new HashMap<>();
    private final List<Rectangle> floors=new ArrayList<>();
    private final OnlineCameraController framing=new OnlineCameraController();
    private TiledMap map;
    private OrthogonalTiledMapRenderer tiles;
    private SpriteBatch batch;
    private OrthographicCamera world,hud;
    private BitmapFont font;
    private OnlineBossAssets bossArt;
    private Sound sound;
    private BrowserRoom room;
    private CombatIcons icons;
    private float x=5.45f,y=.23f,vx,vy,accumulator,time,punch;
    private boolean grounded=true,right=true;
    private long tick;
    private int character,bossMode;

    @Override public void create() {
        map=new TmxMapLoader().load("1/Map1New2Remake.tmx");
        tiles=new OrthogonalTiledMapRenderer(map,.01f);
        for(var object:map.getLayers().get("obj").getObjects()) {
            if(object instanceof RectangleMapObject block) {
                var r=block.getRectangle();floors.add(new Rectangle(r.x/100,r.y/100,r.width/100,r.height/100));
            }
        }
        batch=new SpriteBatch();world=new OrthographicCamera();hud=new OrthographicCamera();
        font=new BitmapFont(Gdx.files.internal("ui/online/body.fnt"));
        for(var name:CHARACTERS) {
            var paths=OnlineVisualCatalog.character(name);texture(paths.stand());texture(paths.run());
        }
        bossArt=new OnlineBossAssets();sound=Gdx.audio.newSound(Gdx.files.internal("sounds/minecraft_click.mp3"));
        icons=new CombatIcons();room=BrowserRoom.fromUrl();
        resize(Gdx.graphics.getWidth(),Gdx.graphics.getHeight());
    }
    private Texture texture(String path) {
        return textures.computeIfAbsent(path,p->{ var texture=new Texture(Gdx.files.internal(p));texture.setFilter(Texture.TextureFilter.Nearest,Texture.TextureFilter.Nearest);return texture; });
    }
    @Override public void resize(int width,int height) {
        if(world==null) { return; }
        world.setToOrtho(false,12,12f*Math.max(1,height)/Math.max(1,width));world.position.set(6,2.2f,0);world.update();
        hud.setToOrtho(false,Math.max(1,width),Math.max(1,height));hud.update();
    }
    @Override public void render() {
        float delta=Math.min(.1f,Gdx.graphics.getDeltaTime());time+=delta;accumulator+=delta;
        for(int i=0;i<5;i++) { if(Gdx.input.isKeyJustPressed(Input.Keys.NUM_1+i)) { character=i; } }
        if(room==null && Gdx.input.isKeyJustPressed(Input.Keys.W) && grounded) { vy=3.4f;grounded=false; }
        if(room==null && Gdx.input.isKeyJustPressed(Input.Keys.J)) { punch=.35f; }
        if(Gdx.input.isKeyJustPressed(Input.Keys.B)) { bossMode=(bossMode+1)%3; }
        if(room==null && Gdx.input.isKeyJustPressed(Input.Keys.F)) { sound.play(.25f); }
        if(room==null && Gdx.input.isKeyJustPressed(Input.Keys.R)) { x=5.45f;y=.23f;vx=vy=0;grounded=true; }
        if(room==null) { while(accumulator>=1f/60) { step();accumulator-=1f/60; } }
        else {
            accumulator=0;room.update(delta);var local=room.player();
            if(local!=null) {
                x=local.getFloat("x");y=local.getFloat("y");vx=local.getFloat("velocityX");vy=local.getFloat("velocityY");grounded=local.getBoolean("grounded");
                for(int i=0;i<CHARACTERS.length;i++) { if(CHARACTERS[i].equals(local.getString("characterId"))) { character=i; } }
                tick=room.latest.getLong("serverTick");
            }
        }
        punch=Math.max(0,punch-delta);
        var view=framing.update(Math.min(x,6.1f),Math.max(x,6.1f),Math.min(y,.16f),Math.max(y,.88f),
                (float)Gdx.graphics.getWidth()/Math.max(1,Gdx.graphics.getHeight()),12,4,delta);
        world.viewportWidth=view.width();world.viewportHeight=view.height();world.position.set(view.x(),view.y(),0);world.update();
        ScreenUtils.clear(.22f,.67f,.88f,1);
        tiles.setView(world);tiles.render();
        var boss=room==null?snapshot():room.boss();if(boss!=null) { bossArt.update(boss,tick,delta); }
        batch.setProjectionMatrix(world.combined);batch.begin();if(boss!=null) { bossArt.draw(batch,boss,tick); }
        if(room==null || room.latest==null) { drawAvatar(CHARACTERS[character],x,y,vx,right,punch); }
        else {
            for(var avatar=room.latest.get("players").child;avatar!=null;avatar=avatar.next) {
                if(!avatar.getBoolean("dead")) {
                    drawAvatar(avatar.getString("characterId"),avatar.getFloat("x"),avatar.getFloat("y"),avatar.getFloat("velocityX"),avatar.getBoolean("facingRight"),avatar.getString("playerId").equals(room.playerId)?punch:0);
                }
            }
            if(room.latest.has("gameplay")) {
                var gameplay=room.latest.get("gameplay");
                for(var item=gameplay.get("items").child;item!=null;item=item.next) { icons.draw(batch,item.getString("type"),item.getFloat("x"),item.getFloat("y"),.22f,.18f,0,false); }
                for(var shot=gameplay.get("shots").child;shot!=null;shot=shot.next) {
                    String type=shot.getString("type");String icon=switch(type) { case "SHOTGUN"->"PELLET";case "BAZOOKA"->"MISSILE";case "THROWN"->"SHOTGUN";case "EXPLOSION"->"ABILITY";default->type; };
                    icons.draw(batch,icon,shot.getFloat("x"),shot.getFloat("y"),type.equals("LASER")?.30f:.14f,.12f,(float)Math.toDegrees(Math.atan2(shot.getFloat("vy"),shot.getFloat("vx"))),false);
                }
                for(var equipped=gameplay.get("players").child;equipped!=null;equipped=equipped.next) {
                    for(var avatar=room.latest.get("players").child;avatar!=null;avatar=avatar.next) {
                        if(avatar.getString("playerId").equals(equipped.getString("playerId")) && !avatar.getBoolean("dead") && !equipped.getString("weapon").equals("NONE")) {
                            icons.draw(batch,equipped.getString("weapon"),avatar.getFloat("x")+.1f,avatar.getFloat("y")+.05f,.22f,.15f,0,!avatar.getBoolean("facingRight"));
                        }
                    }
                }
            }
        }
        batch.end();
        batch.setProjectionMatrix(hud.combined);batch.begin();font.setColor(Color.WHITE);
        font.draw(batch,"SMASHPROFS - TEAVM COMPATIBILITY TRIAL",18,Gdx.graphics.getHeight()-16);
        font.draw(batch,room==null?"A/D move  |  W jump  |  J animation  |  1-5 character  |  B boss pose  |  F sound  |  R reset":"A/D move | W jump | J shoot | I/S aim | K heavy | L dodge | E pickup | Q throw | F ability",18,54);
        font.draw(batch,room==null?"Local asset/control test - multiplayer and combat are not connected.":"Room "+room.room+" | "+room.status+" | server snapshots: "+room.received,18,26);
        font.draw(batch,"Boss art: chierit / CC BY 4.0",18,Gdx.graphics.getHeight()-44);
        if(boss!=null && room!=null) { font.draw(batch,"BOSS HP "+(int)boss.hp()+" / "+(int)boss.maxHp(),18,Gdx.graphics.getHeight()-72); }
        batch.end();
        publish(x,y,vx,vy,grounded,tick,CHARACTERS[character],bossMode,Gdx.graphics.getWidth(),Gdx.graphics.getHeight());
        if(room!=null) { publishNetwork(room.playerId,room.room,room.status,room.error,room.matchId,room.started,room.received,room.latest==null?0:room.latest.get("players").size,boss==null?-1:boss.hp(),room.latest==null?"":room.latest.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json)); }
    }
    private void drawAvatar(String name,float x,float y,float vx,boolean right,float punch) {
        var paths=OnlineVisualCatalog.character(name);
        Texture sheet=texture(Math.abs(vx)>.05f?paths.run():paths.stand());
        int count=sheet.getWidth()/100,index=(int)(time/(Math.abs(vx)>.05f?.15f:.25f))%count;
        if(punch>0) {
            String folder=name.equals("Maurice")?"Momo":name,base=name.equals("Maurice")?"momo":name.toLowerCase(Locale.ROOT);
            sheet=texture("Sprites/"+folder+"/"+base+"_punch.png");count=sheet.getWidth()/100;index=Math.min(count-1,(int)((.35f-punch)*16));
        }
        var frame=new TextureRegion(sheet,index*100,0,100,100);if(!right) { frame.flip(true,false); }
        batch.draw(frame,x-.125f,y-.05f,.25f,.25f);
    }
    private void step() {
        tick++;
        float axis=(Gdx.input.isKeyPressed(Input.Keys.D)?1:0)-(Gdx.input.isKeyPressed(Input.Keys.A)?1:0);
        if(axis!=0) { right=axis>0; }
        var velocity=FighterMovement.step(vx,vy,axis,5,grounded,false,false,true);
        vx=velocity.x();vy=velocity.y();
        float previous=y-.05f;
        x=Math.max(.13f,Math.min(11.87f,x+vx/60));y+=vy/60;grounded=false;
        if(vy<=0) { for(var floor:floors) {
            float top=floor.y+floor.height;
            if(floor.width>0 && floor.height>0 && x+.07f>floor.x && x-.07f<floor.x+floor.width && previous>=top-.01f && y-.05f<=top) {
                y=top+.05f;vy=0;grounded=true;
            }
        } }
        if(y<-.15f) { x=5.45f;y=.23f;vx=vy=0;grounded=true; }
    }
    private BossSnapshot snapshot() {
        String action=bossMode==0?"IDLE":bossMode==1?"ACTIVE":"WALK";
        return new BossSnapshot(6.10f,.16f,true,1000,1000,1,action,bossMode==1?"CLEAVE":"NONE",Math.max(0,tick-6),1,0,List.of());
    }
    @JSBody(params={"x","y","vx","vy","grounded","tick","character","bossMode","width","height"},script="window.smashprofsProbe={ready:true,x:x,y:y,vx:vx,vy:vy,grounded:grounded,tick:tick,character:character,bossMode:bossMode,width:width,height:height};")
    private static native void publish(float x,float y,float vx,float vy,boolean grounded,double tick,String character,int bossMode,int width,int height);
    @JSBody(params={"id","room","status","error","match","started","received","players","bossHp","snapshot"},script="window.smashprofsNetwork={playerId:id,room:room,status:status,error:error,matchId:match,started:started,received:received,players:players,bossHp:bossHp,snapshot:snapshot};")
    private static native void publishNetwork(String id,String room,String status,String error,String match,boolean started,double received,int players,float bossHp,String snapshot);
    @Override public void dispose() {
        if(tiles!=null) { tiles.dispose(); }if(map!=null) { map.dispose(); }
        if(bossArt!=null) { bossArt.close(); }if(sound!=null) { sound.dispose(); }
        if(font!=null) { font.dispose(); }if(batch!=null) { batch.dispose(); }
        if(room!=null) { room.close(); }if(icons!=null) { icons.close(); }
        textures.values().forEach(Texture::dispose);textures.clear();
    }
}
