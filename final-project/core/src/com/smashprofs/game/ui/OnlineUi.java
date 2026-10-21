package com.smashprofs.game.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.*;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.*;

/** Screen-owned Scene2D skin; Vietnamese bitmap glyphs require no runtime font dependency. */
public final class OnlineUi implements AutoCloseable {
    public static final Color BACKGROUND=Color.valueOf("0B101C"), PANEL=Color.valueOf("151E30"),
            MUTED=Color.valueOf("97A8C3"), ACCENT=Color.valueOf("6DE4D0"), GOLD=Color.valueOf("F7C970"),
            DANGER=Color.valueOf("FF858E"), TEXT=Color.valueOf("EAF0FA");
    public final Skin skin=new Skin();
    public OnlineUi() {
        var pixel=new Pixmap(1,1,Pixmap.Format.RGBA8888);pixel.setColor(Color.WHITE);pixel.fill();
        skin.add("pixel",new Texture(pixel));pixel.dispose();
        var arrow=new Pixmap(10,6,Pixmap.Format.RGBA8888);arrow.setColor(Color.WHITE);
        for(int y=0;y<5;y++) { arrow.drawLine(y,y,9-y,y); }skin.add("arrow",new Texture(arrow));arrow.dispose();
        for(String name:new String[]{"body","small","heading"}) {
            var font=new BitmapFont(Gdx.files.internal("ui/online/"+name+".fnt"));
            font.getRegion().getTexture().setFilter(Texture.TextureFilter.Linear,Texture.TextureFilter.Linear);
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
        var field=new TextField.TextFieldStyle();field.font=skin.getFont("body");field.fontColor=TEXT;field.messageFontColor=MUTED;
        field.background=box("0C1423",14);field.focusedBackground=box("233B52",14);field.disabledBackground=box("131D2B",14);
        field.cursor=box("6DE4D0",1);field.selection=box("385E78",0);skin.add("default",field);
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
    public static ChangeListener action(Runnable run) { return new ChangeListener() {
        @Override public void changed(ChangeEvent event,Actor actor) { run.run(); }
    }; }
    @Override public void close() { skin.dispose(); }
}
