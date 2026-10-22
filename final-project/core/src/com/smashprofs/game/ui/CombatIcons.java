package com.smashprofs.game.ui;

import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import java.util.*;

/** Code-drawn pixel art shared by pickups, held weapons, projectiles and equipment slots. */
public final class CombatIcons implements AutoCloseable {
    private static final String[] TYPES={"NONE","SHOTGUN","LASER","BAZOOKA","GRENADE","HEALTH","SHIELD","ABILITY","PELLET","MISSILE","ORBIT","SWORD","WARHAMMER","DUAL_DAGGERS","SPEAR"};
    private final Texture texture;
    private final Map<String,TextureRegion> regions=new HashMap<>();
    private final Map<String,Sprite> sprites=new HashMap<>();
    private Pixmap atlas;
    private int origin;
    public CombatIcons() {
        atlas=new Pixmap(TYPES.length*32,32,Pixmap.Format.RGBA8888);
        for(int i=0;i<TYPES.length;i++) { origin=i*32;paint(TYPES[i]); }
        texture=new Texture(atlas);texture.setFilter(Texture.TextureFilter.Nearest,Texture.TextureFilter.Nearest);atlas.dispose();atlas=null;
        for(int i=0;i<TYPES.length;i++) { var region=new TextureRegion(texture,i*32,0,32,32);regions.put(TYPES[i],region);sprites.put(TYPES[i],new Sprite(region)); }
    }
    private void rect(String hex,int x,int y,int w,int h) { atlas.setColor(Color.valueOf(hex));atlas.fillRectangle(origin+x,y,w,h); }
    private void triangle(String hex,int ax,int ay,int bx,int by,int cx,int cy) { atlas.setColor(Color.valueOf(hex));atlas.fillTriangle(origin+ax,ay,origin+bx,by,origin+cx,cy); }
    private void circle(String hex,int x,int y,int r) { atlas.setColor(Color.valueOf(hex));atlas.fillCircle(origin+x,y,r); }
    private void paint(String type) {
        switch(type) {
            case "SHOTGUN"->{
                rect("101828",2,11,28,10);rect("6C7789",10,12,20,3);rect("B9C8DB",10,12,18,1);rect("363F52",11,16,19,3);
                rect("E0A45F",9,17,10,3);rect("805335",3,17,9,5);rect("B87A42",2,20,5,5);rect("DAAD75",2,20,3,2);rect("192338",13,20,3,5);
            }
            case "LASER"->{
                rect("0B1B30",2,10,28,11);rect("E3EFFA",9,12,14,7);rect("526C94",5,15,6,4);rect("2C455F",8,19,4,6);
                rect("68E7F2",21,13,9,3);rect("FFFFFF",27,13,3,1);rect("A4B9D4",13,9,8,3);rect("6DE4D0",16,10,4,1);rect("35C4EE",13,16,6,2);
            }
            case "BAZOOKA"->{
                rect("142722",1,9,30,13);rect("294C38",2,10,28,10);rect("79A255",6,10,20,8);rect("C2D087",6,10,18,2);
                rect("1B302D",2,12,4,6);rect("101827",27,10,4,10);rect("C4D8C0",27,10,2,10);rect("76553C",12,20,5,6);rect("EEC86C",20,11,3,7);
            }
            case "GRENADE"->{
                circle("17251E",16,19,10);circle("5A793D",16,19,8);rect("89A557",12,13,8,3);rect("344C2B",9,19,15,2);rect("344C2B",15,12,2,14);
                rect("ACB7C3",13,8,7,4);rect("D9E5E9",14,7,9,2);rect("8494A2",20,9,3,5);rect("EDBB68",14,5,3,3);
            }
            case "HEALTH"->{
                rect("1D3144",3,9,26,19);rect("DDEAF2",4,10,24,16);rect("8EAABA",5,24,22,3);rect("A8BAC6",11,6,10,4);rect("0B182A",13,7,6,3);
                rect("F45D74",13,12,6,12);rect("F45D74",10,15,12,6);rect("FFFFFF",5,11,3,11);
            }
            case "SHIELD"->{
                triangle("142D53",3,7,29,7,16,29);rect("142D53",3,6,26,9);
                triangle("299FCB",5,9,27,9,16,27);rect("299FCB",5,7,22,8);
                triangle("85E5F0",7,9,16,9,16,24);rect("C0F8FF",8,8,3,8);rect("E8FDFF",14,12,4,9);rect("E8FDFF",11,15,10,3);
            }
            case "ABILITY"->{
                triangle("5847AB",16,2,4,27,29,27);triangle("A89AF5",17,3,8,18,18,17);triangle("6DE4D0",14,14,25,14,12,30);rect("FFFFFF",15,11,4,7);
            }
            case "PELLET"->{rect("704E28",5,14,20,5);rect("FFC977",7,14,18,3);rect("FFF2C1",23,14,4,3);}
            case "MISSILE"->{
                triangle("F28653",25,12,31,16,25,20);rect("D6E5EB",8,12,17,8);rect("FFFFFF",10,12,12,2);
                triangle("829DB2",6,10,13,12,6,16);triangle("829DB2",6,16,13,20,6,24);rect("44536B",6,14,4,5);
                triangle("FAD076",0,16,6,14,6,20);
            }
            case "ORBIT"->{
                triangle("B0C5EB",16,2,12,15,23,13);triangle("728CB7",30,16,17,12,19,23);
                triangle("B0C5EB",16,30,20,17,9,19);triangle("728CB7",2,16,15,20,13,9);circle("DFECFC",16,16,4);circle("243552",16,16,2);
            }
            case "SWORD"->{
                rect("1A232E",8,6,18,18);
                rect("78909C",11,11,13,4);rect("CFD8DC",12,10,13,3);rect("ECEFF1",14,9,11,2);rect("FFFFFF",17,8,8,1);
                triangle("FFFFFF",24,7,28,8,25,11);
                rect("B45309",7,15,5,8);rect("F59E0B",8,16,3,6);rect("FEF08A",9,17,1,4);
                rect("451A03",4,19,5,4);rect("78350F",5,20,4,2);
                rect("F59E0B",3,22,3,3);
            }
            case "WARHAMMER"->{
                rect("111827",13,4,16,14);
                rect("374151",14,5,14,12);rect("6B7280",15,6,12,6);rect("9CA3AF",16,7,10,3);
                rect("D97706",18,4,4,14);rect("FBBF24",19,5,2,12);
                triangle("1F2937",28,7,32,11,28,15);triangle("9CA3AF",28,9,31,11,28,13);
                rect("451A03",5,14,12,4);rect("78350F",4,15,13,2);rect("92400E",3,17,11,3);
                rect("D97706",2,20,4,4);
            }
            case "DUAL_DAGGERS"->{
                rect("4C1D95",7,6,13,4);rect("7C3AED",9,5,12,3);rect("A78BFA",11,5,9,2);rect("DDD6FE",13,5,6,1);
                rect("BE123C",5,10,4,4);rect("881337",3,13,4,3);
                rect("4C1D95",12,6,13,4);rect("7C3AED",11,5,12,3);rect("A78BFA",12,5,9,2);rect("DDD6FE",13,5,6,1);
                rect("BE123C",23,10,4,4);rect("881337",25,13,4,3);
                circle("FFFFFF",16,11,2);
            }
            case "SPEAR"->{
                triangle("B45309",21,2,30,8,24,14);
                triangle("F59E0B",22,3,29,8,25,13);
                triangle("FEF08A",23,5,28,8,25,11);
                rect("FFFFFF",24,7,5,2);
                circle("DC2626",20,12,3);rect("B91C1C",18,13,4,3);rect("EF4444",19,14,3,4);
                rect("593618",4,15,17,3);rect("8B5A2B",5,16,16,1);
                rect("B45309",2,22,4,3);
            }
            default->{rect("9D6547",8,10,16,14);rect("E8B18A",9,9,14,12);rect("F4C6A0",10,8,4,8);rect("F4C6A0",15,7,4,9);rect("F4C6A0",20,9,4,8);rect("C68F67",7,17,6,7);rect("60473D",11,24,11,3);}
        }
    }
    public TextureRegionDrawable drawable(String type) { return new TextureRegionDrawable(regions.getOrDefault(type,regions.get("NONE"))); }
    public void draw(SpriteBatch batch,String type,float x,float y,float width,float height,float rotation,boolean flip) {
        var sprite=sprites.getOrDefault(type,sprites.get("NONE"));sprite.setBounds(x-width/2,y-height/2,width,height);sprite.setOriginCenter();sprite.setRotation(rotation);sprite.setFlip(flip,false);sprite.setColor(Color.WHITE);sprite.draw(batch);
    }
    public static String name(String type) { return switch(type) {
        case "SHOTGUN"->"Shotgun";case "LASER"->"Súng laser";case "BAZOOKA"->"Bazooka";case "GRENADE"->"Lựu đạn";case "SWORD"->"Thanh kiếm";case "WARHAMMER"->"Búa đại chiến";case "DUAL_DAGGERS"->"Song dao găm";case "SPEAR"->"Thương dài";case "HEALTH"->"Bình máu";case "SHIELD"->"Khiên";default->"Tay không";
    }; }
    @Override public void close() { texture.dispose();regions.clear();sprites.clear(); }
}
