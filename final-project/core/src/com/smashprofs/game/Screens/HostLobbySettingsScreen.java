package com.smashprofs.game.Screens;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.scenes.scene2d.*;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.smashprofs.game.Game;
import com.smashprofs.game.network.client.LobbyClient;
import com.smashprofs.common.model.MatchRules;

/** Host edits a local draft; only the server's accepted settings are displayed as room rules. */
public final class HostLobbySettingsScreen implements Screen {
    private final Game game;
    private final LobbyClient lobby;
    private final Stage stage=new Stage(new FitViewport(960,540));
    private final BitmapFont font=new BitmapFont();
    private final Label status,current;
    private final TextButton max,time,lives,drops,apply;
    private int maxIndex=2,timeIndex=2,livesIndex=2,dropIndex=2;
    private static final int[] MAX={2,3,4},TIME={60,90,120,180,300,0},LIVES={1,2,3,5};
    private boolean initialized,disposed;
    public HostLobbySettingsScreen(Game game) {
        this.game=game;this.lobby=game.getLobbyClient();
        var style=new TextButton.TextButtonStyle();style.font=font;style.fontColor=Color.CYAN;style.disabledFontColor=Color.GRAY;
        var labels=new Label.LabelStyle(font,Color.WHITE);var root=new Table();root.setFillParent(true);root.defaults().pad(10).height(34);
        root.add(new Label("ROOM RULES - HOST SETTINGS",labels));root.row();
        current=new Label("Waiting for server rules...",labels);root.add(current);root.row();
        max=new TextButton("",style);time=new TextButton("",style);lives=new TextButton("",style);drops=new TextButton("",style);
        apply=new TextButton("Apply rules (all players must ready again)",style);
        max.addListener(action(()->maxIndex=(maxIndex+1)%MAX.length));time.addListener(action(()->timeIndex=(timeIndex+1)%TIME.length));
        lives.addListener(action(()->livesIndex=(livesIndex+1)%LIVES.length));drops.addListener(action(()->dropIndex=(dropIndex+1)%MatchRules.DropRate.values().length));
        apply.addListener(action(()->lobby.updateSettings(new MatchRules(MAX[maxIndex],TIME[timeIndex],LIVES[livesIndex],MatchRules.DropRate.values()[dropIndex],true))));
        for(var button:new TextButton[]{max,time,lives,drops,apply}) { root.add(button).width(680);root.row(); }
        var next=new TextButton("Choose character / Ready",style);next.addListener(action(()->game.setScreen(new CharacterSelectScreen(game))));root.add(next);root.row();
        status=new Label("",labels);status.setWrap(true);root.add(status).width(800).height(50);stage.addActor(root);
    }
    private ChangeListener action(Runnable run) { return new ChangeListener() { public void changed(ChangeEvent event,Actor actor) { run.run(); } }; }
    @Override public void show() { Gdx.input.setInputProcessor(stage); }
    @Override public void render(float delta) {
        if(disposed) { return; }var settings=lobby.getSettings();
        boolean host=settings!=null && lobby.getSnapshot().localPlayerId()!=null && settings.hostId().equals(lobby.getSnapshot().localPlayerId().value());
        boolean editable=host && lobby.getSnapshot().phase()==LobbyClient.Phase.JOINED;
        for(var button:new TextButton[]{max,time,lives,drops,apply}) { button.setDisabled(!editable); }
        if(settings!=null) {
            var rules=settings.rules();
            if(!initialized) {
                for(int i=0;i<MAX.length;i++) { if(MAX[i]==rules.maxPlayers()) { maxIndex=i; } }
                for(int i=0;i<TIME.length;i++) { if(TIME[i]==rules.matchSeconds()) { timeIndex=i; } }
                for(int i=0;i<LIVES.length;i++) { if(LIVES[i]==rules.lives()) { livesIndex=i; } }
                dropIndex=rules.dropRate().ordinal();initialized=true;
            }
            current.setText((host?"YOU ARE HOST":"Host: "+settings.hostId().substring(0,Math.min(8,settings.hostId().length())))+" | "+settings.playerCount()+"/"+rules.maxPlayers()+" players\nServer: "+(rules.matchSeconds()==0?"INFINITE":rules.matchSeconds()+"s")+" | "+rules.lives()+" lives | "+rules.dropRate());
        }
        max.setText("Max players: "+MAX[maxIndex]);time.setText("Match time: "+(TIME[timeIndex]==0?"INFINITE":TIME[timeIndex]+"s"));
        lives.setText("Lives: "+LIVES[livesIndex]);drops.setText("Items: "+MatchRules.DropRate.values()[dropIndex]);
        status.setText(lobby.getSnapshot().statusText());ScreenUtils.clear(.025f,.05f,.08f,1);stage.getViewport().apply();stage.act(delta);stage.draw();
    }
    @Override public void resize(int w,int h) { stage.getViewport().update(w,h,true); }
    @Override public void pause() { }
    @Override public void resume() { }
    @Override public void hide() { dispose(); }
    @Override public void dispose() { if(disposed) { return; }disposed=true;if(Gdx.input.getInputProcessor()==stage) { Gdx.input.setInputProcessor(null); }stage.dispose();font.dispose(); }
}
