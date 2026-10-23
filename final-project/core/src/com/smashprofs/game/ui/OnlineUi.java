package com.smashprofs.game.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.scenes.scene2d.*;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.*;

/** Screen-owned Scene2D skin; Vietnamese bitmap glyphs require no runtime font dependency. */
public final class OnlineUi implements AutoCloseable {
    public static final Color BACKGROUND=Color.valueOf("0B101C"), PANEL=Color.valueOf("151E30"),
            MUTED=Color.valueOf("97A8C3"), ACCENT=Color.valueOf("6DE4D0"), GOLD=Color.valueOf("F7C970"),
            DANGER=Color.valueOf("FF858E"), TEXT=Color.valueOf("EAF0FA"),
            BRAWL_BG=Color.valueOf("0056BD"), BRAWL_DARK=Color.valueOf("1E2333"),
            BRAWL_DEEP=Color.valueOf("003474"), BRAWL_BLUE=Color.valueOf("0074E4"),
            BRAWL_CYAN=Color.valueOf("35F3B7"), BRAWL_GOLD=Color.valueOf("FFC800"),
            BRAWL_BORDER=Color.valueOf("0D131A");
    public final Skin skin=new Skin();
    public OnlineUi() {
        var pixel=new Pixmap(1,1,Pixmap.Format.RGBA8888);pixel.setColor(Color.WHITE);pixel.fill();
        skin.add("pixel",new Texture(pixel));pixel.dispose();
        var arrow=new Pixmap(10,6,Pixmap.Format.RGBA8888);arrow.setColor(Color.WHITE);
        for(int y=0;y<5;y++) { arrow.drawLine(y,y,9-y,y); }skin.add("arrow",new Texture(arrow));arrow.dispose();
        for(String name:new String[]{"body","small","heading"}) {
            var font=new BitmapFont(Gdx.files.internal("ui/online/"+name+".fnt"));
            font.getRegion().getTexture().setFilter(Texture.TextureFilter.Linear,Texture.TextureFilter.Linear);
            font.setUseIntegerPositions(true);
            skin.add(name,font);
        }
        skin.add("default",new Label.LabelStyle(skin.getFont("body"),TEXT));
        skin.add("small",new Label.LabelStyle(skin.getFont("small"),MUTED));
        skin.add("heading",new Label.LabelStyle(skin.getFont("heading"),TEXT));
        var button=new TextButton.TextButtonStyle();button.font=skin.getFont("body");button.fontColor=TEXT;
        button.up=box("26364F",12);button.over=box("354C6A",12);button.down=box("1C2A40",12);
        button.disabled=box("1B2638",12);button.disabledFontColor=Color.valueOf("63718A");button.checked=box("296356",12);
        skin.add("default",button);
        var primary=new TextButton.TextButtonStyle(button);primary.up=box("6DE4D0",12);primary.over=box("93F2E2",12);
        primary.down=box("47BAA7",12);primary.checked=primary.up;primary.fontColor=BACKGROUND;skin.add("primary",primary);

        var comicPrimary=new TextButton.TextButtonStyle();comicPrimary.font=skin.getFont("body");comicPrimary.fontColor=BRAWL_BORDER;
        comicPrimary.up=comicBox(BRAWL_CYAN,BRAWL_BORDER,3.5f,3,3);comicPrimary.over=comicBox(Color.valueOf("72F9D0"),BRAWL_BORDER,3.5f,4,4);
        comicPrimary.down=comicBox(Color.valueOf("24C493"),BRAWL_BORDER,3.5f,1,1);comicPrimary.disabled=comicBox(Color.valueOf("51786E"),BRAWL_BORDER,3.5f,0,0);
        comicPrimary.disabledFontColor=Color.valueOf("2A3834");skin.add("comic-primary",comicPrimary);

        var comicGold=new TextButton.TextButtonStyle();comicGold.font=skin.getFont("body");comicGold.fontColor=BRAWL_BORDER;
        comicGold.up=comicBox(BRAWL_GOLD,BRAWL_BORDER,3.5f,3,3);comicGold.over=comicBox(Color.valueOf("FFE066"),BRAWL_BORDER,3.5f,4,4);
        comicGold.down=comicBox(Color.valueOf("D9A700"),BRAWL_BORDER,3.5f,1,1);comicGold.disabled=comicBox(Color.valueOf("736438"),BRAWL_BORDER,3.5f,0,0);
        comicGold.disabledFontColor=Color.valueOf("2E2818");skin.add("comic-gold",comicGold);

        var comicWhite=new TextButton.TextButtonStyle();comicWhite.font=skin.getFont("body");comicWhite.fontColor=BRAWL_BORDER;
        comicWhite.up=comicBox(Color.WHITE,BRAWL_BORDER,3.5f,3,3);comicWhite.over=comicBox(Color.valueOf("E6F0FA"),BRAWL_BORDER,3.5f,4,4);
        comicWhite.down=comicBox(Color.valueOf("C8D6E5"),BRAWL_BORDER,3.5f,1,1);comicWhite.disabled=comicBox(Color.valueOf("808080"),BRAWL_BORDER,3.5f,0,0);
        skin.add("comic-white",comicWhite);

        var field=new TextField.TextFieldStyle();field.font=skin.getFont("body");field.fontColor=TEXT;field.messageFontColor=MUTED;
        field.background=box("0C1423",14);field.focusedBackground=box("233B52",14);field.disabledBackground=box("131D2B",14);
        field.cursor=box("6DE4D0",1);field.selection=box("385E78",0);skin.add("default",field);

        var comicField=new TextField.TextFieldStyle();comicField.font=skin.getFont("body");comicField.fontColor=Color.WHITE;
        comicField.messageFontColor=MUTED;comicField.background=comicBox(Color.valueOf("091220"),BRAWL_BORDER,3,2,2);
        comicField.focusedBackground=comicBox(Color.valueOf("112238"),BRAWL_CYAN,3,3,3);comicField.disabledBackground=comicBox(Color.valueOf("060B14"),BRAWL_BORDER,3,0,0);
        comicField.cursor=box("35F3B7",2);comicField.selection=box("0077F7",0);skin.add("comic",comicField);

        var list=new List.ListStyle(skin.getFont("body"),TEXT,TEXT,box("33566C",8));
        list.background=box("1C2A40",8);skin.add("default",list);
        var scroll=new ScrollPane.ScrollPaneStyle();scroll.background=box("151E30",0);
        scroll.vScroll=box("1A2940",0);scroll.vScrollKnob=box("526780",0);skin.add("default",scroll);
        var select=new SelectBox.SelectBoxStyle(skin.getFont("body"),TEXT,selectBackground("26364F"),scroll,list);
        select.backgroundOver=selectBackground("354C6A");select.backgroundDisabled=selectBackground("1B2638");select.disabledFontColor=MUTED;
        skin.add("default",select);
        var progress=new ProgressBar.ProgressBarStyle();progress.background=box("29354B",0);progress.background.setMinHeight(7);
        progress.knobBefore=box("6DE4D0",0);progress.knobBefore.setMinHeight(7);skin.add("default-horizontal",progress);
    }
    public Drawable comicBox(Color fillCol,Color borderCol,float borderWidth,float shadowX,float shadowY) {
        var fill=skin.newDrawable("pixel",fillCol);
        var border=skin.newDrawable("pixel",borderCol);
        var shadow=skin.newDrawable("pixel",Color.valueOf("000000D0"));
        var drawable=new BaseDrawable() {
            @Override public void draw(Batch batch,float x,float y,float width,float height) {
                if(shadowX>0 || shadowY>0) { shadow.draw(batch,x+shadowX,y-shadowY,width,height); }
                border.draw(batch,x,y,width,height);
                fill.draw(batch,x+borderWidth,y+borderWidth,width-borderWidth*2,height-borderWidth*2);
            }
        };
        drawable.setLeftWidth(borderWidth+6);drawable.setRightWidth(borderWidth+6);
        drawable.setTopHeight(borderWidth+6);drawable.setBottomHeight(borderWidth+6);
        return drawable;
    }
    public Drawable box(String hex,float padding) {
        var drawable=skin.newDrawable("pixel",Color.valueOf(hex));
        drawable.setLeftWidth(padding);drawable.setRightWidth(padding);drawable.setTopHeight(padding);drawable.setBottomHeight(padding);
        return drawable;
    }
    private Drawable selectBackground(String hex) {
        var background=box(hex,12);var arrow=skin.getRegion("arrow");
        var drawable=new BaseDrawable() {
            @Override public void draw(Batch batch,float x,float y,float width,float height) {
                background.draw(batch,x,y,width,height);batch.draw(arrow,x+width-22,y+height/2-3,10,6);
            }
        };
        drawable.setLeftWidth(12);drawable.setRightWidth(32);drawable.setTopHeight(12);drawable.setBottomHeight(12);return drawable;
    }
    public Label label(String text) { return new Label(text,skin); }
    public Label small(String text) { return new Label(text,skin,"small"); }
    public Label heading(String text) { return new Label(text,skin,"heading"); }
    public Label wrapped(String text) { var label=label(text);label.setWrap(true);return label; }
    public Table panel() { var table=new Table();table.setBackground(box("151E30",0));table.pad(22);return table; }
    public TextButton button(String text,Runnable callback) {
        var button=new TextButton(text,skin);button.addListener(action(callback));return button;
    }
    public TextButton primary(String text,Runnable callback) {
        var button=new TextButton(text,skin,"primary");button.addListener(action(callback));return button;
    }
    public TextButton comicButton(String text,String styleName,Runnable callback) {
        var button=new TextButton(text,skin,styleName);button.addListener(action(callback));return button;
    }
    public Label label(String text, Color color) {
        return new Label(text, new Label.LabelStyle(skin.getFont("body"), color));
    }
    public Label small(String text, Color color) {
        return new Label(text, new Label.LabelStyle(skin.getFont("small"), color));
    }
    public Label heading(String text, Color color) {
        return new Label(text, new Label.LabelStyle(skin.getFont("heading"), color));
    }
    public ComicHeading comicHeading(String text, Color color) {
        return new ComicHeading(text, skin, color, 2.5f);
    }
    public Label comicBody(String text, Color color) {
        return label(text, color);
    }
    public Label comicSmall(String text, Color color) {
        return small(text, color);
    }
    public static class ComicHeading extends Label {
        private final Color shadowColor = new Color(0, 0, 0, 0.85f);
        private final float shadowOffset;
        public ComicHeading(CharSequence text, Skin skin, Color color, float shadowOffset) {
            super(text, new LabelStyle(skin.getFont("heading"), color));
            this.shadowOffset = shadowOffset;
        }
        @Override public void draw(Batch batch, float parentAlpha) {
            validate();
            Color mainColor = new Color(getColor());
            setColor(shadowColor.r, shadowColor.g, shadowColor.b, shadowColor.a * parentAlpha);
            moveBy(shadowOffset, -shadowOffset);
            super.draw(batch, parentAlpha);
            moveBy(-shadowOffset, shadowOffset);
            setColor(mainColor.r, mainColor.g, mainColor.b, mainColor.a * parentAlpha);
            super.draw(batch, parentAlpha);
        }
    }
    public static void fit(Label l, float maxW) {
        var gl = new GlyphLayout(l.getStyle().font, l.getText());
        l.setFontScale(gl.width > maxW ? Math.max(.7f, maxW / gl.width) : 1f);
    }
    public static ChangeListener action(Runnable run) { return new ChangeListener() {
        @Override public void changed(ChangeEvent event,Actor actor) { run.run(); }
    }; }
    @Override public void close() { skin.dispose(); }
}
