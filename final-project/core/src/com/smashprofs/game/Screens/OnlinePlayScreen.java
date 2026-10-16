package com.smashprofs.game.Screens;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.maps.tiled.*;
import com.badlogic.gdx.maps.tiled.renderers.OrthogonalTiledMapRenderer;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.smashprofs.game.Game;
import com.smashprofs.game.Scenes.OnlineHud;
import com.smashprofs.game.network.client.*;
import java.util.*;

/** PlayScreen's online branch. No Player, World, combat, local respawn or local victory code. */
final class OnlinePlayScreen implements Screen {
    private final Game game;
    private final ClientMatchSession session;
    private final OnlineMatchPresentation presentation;
    private final OnlineSpriteAssets assets = new OnlineSpriteAssets();
    private final Map<String, Sprite> players = new LinkedHashMap<>();
    private final Map<Long, Sprite> projectiles = new LinkedHashMap<>();
    private final SpriteBatch batch = new SpriteBatch();
    private final BitmapFont identityFont = new BitmapFont();
    private final OrthographicCamera camera = new OrthographicCamera();
    private final FitViewport viewport = new FitViewport(12, 4, camera);
    private final TiledMap map;
    private final OrthogonalTiledMapRenderer tiles;
    private final OnlineHud hud;
    private final com.smashprofs.game.ui.CombatIcons combatIcons=new com.smashprofs.game.ui.CombatIcons();
    private final float mapWidth,mapHeight;
    private boolean disposed;
    private float animationTime;
    private final com.badlogic.gdx.graphics.glutils.ShapeRenderer shapes=new com.badlogic.gdx.graphics.glutils.ShapeRenderer();
    private boolean ultimateWasReady=true;
    private final com.badlogic.gdx.audio.Sound readySound=Gdx.audio.newSound(Gdx.files.internal("sounds/minecraft_click.mp3"));

    OnlinePlayScreen(Game game, ClientMatchSession session) {
        this.game = game; this.session = session;
        presentation = new OnlineMatchPresentation(session);
        map = new TmxMapLoader().load("1/Map1New2Remake.tmx");
        tiles = new OrthogonalTiledMapRenderer(map, 1 / 100f);
        mapWidth = map.getProperties().get("width", Integer.class) * map.getProperties().get("tilewidth", Integer.class) / 100f;
        mapHeight = map.getProperties().get("height", Integer.class) * map.getProperties().get("tileheight", Integer.class) / 100f;
        viewport.setWorldSize(mapWidth, mapHeight); camera.position.set(mapWidth / 2, mapHeight / 2, 0);
        for (var member : session.getRoster().players()) {
            players.put(member.playerId().value(), assets.player(member.characterId()));
        }
        hud = new OnlineHud(batch, session.getRoster(), session.getLocalPlayerId().value());
        identityFont.getData().setScale(0.008f);
    }
    @Override public void show() { Gdx.input.setInputProcessor(null); }
    @Override public void render(float delta) {
        if (disposed) { return; }
        presentation.update();
        var result = presentation.takeResultTransition();
        if (result != null) {
            game.setScreen(new OnlineResultScreen(game,result, session.getLocalPlayerId().value()));
            return; // setScreen hides/disposes this screen on the render thread.
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) { game.leaveOnlineLobby();game.setScreen(new OnlineLobbyScreen(game));return; }
        if (Gdx.input.isKeyJustPressed(Input.Keys.F9)) { game.getLobbyClient().close(); }
        if (Gdx.input.isKeyJustPressed(Input.Keys.TAB)) { hud.toggleInventory(); }
        if (Gdx.input.isKeyJustPressed(Input.Keys.H)) { hud.toggleHelp(); }
        if (game.getLobbyClient().getMatchSession() == session) {
            float axis = (Gdx.input.isKeyPressed(Input.Keys.D) ? 1 : 0) - (Gdx.input.isKeyPressed(Input.Keys.A) ? 1 : 0);
            game.getLobbyClient().recordFrameInput(axis, Gdx.input.isKeyJustPressed(Input.Keys.W),
                    Gdx.input.isKeyJustPressed(Input.Keys.S), Gdx.input.isKeyJustPressed(Input.Keys.V)
                            || Gdx.input.isKeyJustPressed(Input.Keys.SPACE), Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT),
                    Gdx.input.isKeyJustPressed(Input.Keys.F));
        }
        animationTime += Math.min(Math.max(delta, 0), 0.1f); // purely visual; not server/client input ticks
        ScreenUtils.clear(Color.BLACK);
        updateCamera(); viewport.apply(); camera.update(); tiles.setView(camera); tiles.render();
        var authoritative=presentation.getFrame()==null?null:presentation.getFrame().authoritative();
        var gameplay=authoritative==null?null:authoritative.gameplay();
        if(gameplay!=null) {
            var local=gameplay.player(session.getLocalPlayerId().value());boolean ready=local.status().equals("ACTIVE") && local.ultimateTicks()==0;
            if(ready && !ultimateWasReady && Game.soundEffects) { readySound.play(.4f); }
            ultimateWasReady=ready;
            shapes.setProjectionMatrix(camera.combined);shapes.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
            for(var shot:gameplay.shots()) {
                if(shot.type().equals("LASER")) {
                    shapes.setColor(Color.CYAN);shapes.rectLine(shot.x()-shot.vx()/60,shot.y()-shot.vy()/60,shot.x(),shot.y(),.025f);
                    shapes.setColor(Color.WHITE);shapes.rectLine(shot.x()-shot.vx()/60,shot.y()-shot.vy()/60,shot.x(),shot.y(),.008f);
                }
            }
            shapes.end();
            shapes.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Line);
            for(var shot:gameplay.shots()) {
                if(shot.type().equals("EXPLOSION")) {
                    shapes.setColor(Color.ORANGE);shapes.circle(shot.x(),shot.y(),.65f,24);
                    shapes.setColor(Color.GOLD);shapes.circle(shot.x(),shot.y(),.32f,16);
                    for(int i=0;i<8;i++) { float angle=i*(float)Math.PI/4;shapes.line(shot.x()+.38f*(float)Math.cos(angle),shot.y()+.38f*(float)Math.sin(angle),shot.x()+.85f*(float)Math.cos(angle),shot.y()+.85f*(float)Math.sin(angle)); }
                }
            }
            shapes.end();
        }
        batch.setProjectionMatrix(camera.combined); batch.begin();
        for (var view : presentation.getPlayers()) {
            var p = view.state(); var sprite = players.get(p.playerId());
            if(gameplay!=null && !gameplay.player(p.playerId()).status().equals("ACTIVE")) { continue; }
            sprite.setRegion(assets.frame(p, animationTime)); sprite.setFlip(p.facingRight(), false);
            sprite.setColor(p.dead() ? new Color(0.5f, 0.5f, 0.5f, 0.4f) : p.blocking() ? Color.CYAN : Color.WHITE);
            sprite.setRotation(0);
            if(gameplay!=null) {
                var progress=gameplay.player(p.playerId());
                if(progress.status().equals("ACTIVE")) {
                    if(progress.invulnerableTicks()>0) { sprite.setColor(1,1,.5f,((int)(animationTime*12)%2==0)?.35f:1); }
                    if(progress.wallState().equals("WALL_SLIDING")) { sprite.setRotation(p.facingRight()?15:-15);sprite.setColor(Color.CYAN); }
                    if(progress.wallState().equals("WALL_JUMPING")) { sprite.setColor(Color.YELLOW); }
                }
            }
            sprite.setPosition(p.x() - sprite.getWidth() / 2, p.y() - sprite.getHeight() / 4); sprite.draw(batch);
            if(gameplay!=null) {
                var equipment=gameplay.player(p.playerId());
                if(!equipment.weapon().equals("NONE")) { combatIcons.draw(batch,equipment.weapon(),p.x()+(p.facingRight()?.08f:-.08f),p.y()+.045f,.23f,.16f,0,!p.facingRight()); }
            }
            identityFont.setColor(view.local() ? Color.CYAN : Color.WHITE);
            identityFont.draw(batch, view.local() ? "YOU" : "Slot " + p.slotIndex(), p.x() - 0.125f, p.y() + 0.32f);
        }
        if(gameplay!=null) {
            for(var item:gameplay.items()) { combatIcons.draw(batch,item.type(),item.x(),item.y()+.02f*(float)Math.sin(animationTime*3+item.id()),.22f,.22f,0,false); }
            for(var shot:gameplay.shots()) {
                float angle=(float)Math.toDegrees(Math.atan2(shot.vy(),shot.vx()));
                switch(shot.type()) {
                    case "SHOTGUN"->combatIcons.draw(batch,"PELLET",shot.x(),shot.y(),.09f,.055f,angle,false);
                    case "BAZOOKA"->combatIcons.draw(batch,"MISSILE",shot.x(),shot.y(),.20f,.13f,angle,false);
                    case "GRENADE"->combatIcons.draw(batch,"GRENADE",shot.x(),shot.y(),.12f,.12f,animationTime*150,false);
                    case "ORBIT"->combatIcons.draw(batch,"ORBIT",shot.x(),shot.y(),.15f,.15f,animationTime*300,false);
                    default->{ }
                }
            }
        }
        projectiles.keySet().retainAll(presentation.getProjectiles().keySet());
        for (var p : presentation.getProjectiles().values()) {
            var sprite = projectiles.computeIfAbsent(p.projectileId(), id -> assets.projectile(p.projectileType()));
            sprite.setPosition(p.x() - sprite.getWidth() / 2, p.y() - sprite.getHeight() / 2);
            if (OnlineVisualCatalog.projectile(p.projectileType()).orientFromVelocity() && (p.velocityX() != 0 || p.velocityY() != 0)) {
                sprite.setRotation((float) Math.toDegrees(Math.atan2(p.velocityY(), p.velocityX())));
            }
            sprite.draw(batch);
        }
        batch.end();
        var lobby = game.getLobbyClient().getSnapshot();
        boolean connected = game.getNetworkClient().isConnected() && lobby.phase() != LobbyClient.Phase.ERROR;
        String status = !connected ? lobby.statusText() : lobby.errorCode() != null ? lobby.statusText()
                : String.format(Locale.ROOT, "Connected | %.1f snapshots/s | skipped broadcasts: %d", session.getMetrics().getSnapshotFrequencyHz(), session.getMetrics().getDroppedSnapshots());
        hud.update(presentation.getFrame(), status, connected,session); hud.draw(delta);
    }
    private void updateCamera() {
        var values = presentation.getPlayers();
        float aspect = Gdx.graphics.getWidth() / (float) Math.max(1, Gdx.graphics.getHeight());

        if (values.isEmpty()) {
            float defaultW = 5.0f;
            float defaultH = defaultW / aspect;
            viewport.setWorldSize(defaultW, defaultH);
            viewport.update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), false);
            camera.position.set(mapWidth / 2f, Math.max(defaultH / 2f, 1.2f), 0);
            return;
        }

        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        boolean hasActive=values.stream().anyMatch(p->!p.state().dead());
        for (var p : values) {
            if(hasActive && p.state().dead()) { continue; }
            minX = Math.min(minX, p.state().x()); maxX = Math.max(maxX, p.state().x());
            minY = Math.min(minY, p.state().y()); maxY = Math.max(maxY, p.state().y());
        }
        float aspect = Gdx.graphics.getWidth() / (float) Math.max(1, Gdx.graphics.getHeight());
        if(!hasActive) { return; }
        float width = Math.max(5.5f, Math.max(maxX - minX + 1.5f, (maxY - minY + 1) * aspect));
        viewport.setWorldSize(width, width / aspect);
        viewport.update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), false);
        float x=width<mapWidth?Math.max(width/2,Math.min(mapWidth-width/2,(minX+maxX)/2)):mapWidth/2;
        // A narrow margin below the floor keeps the equipment strip clear of central-platform players.
        float height=width/aspect,padding=height*.09f,y=height<mapHeight?Math.max(height/2-padding,Math.min(mapHeight-height/2+padding,(minY+maxY)/2+.1f)):mapHeight/2;
        camera.position.set(x,y,0);
    }
    @Override public void resize(int width, int height) { viewport.update(width, height, false); hud.resize(width, height); }
    @Override public void pause() { }
    @Override public void resume() { }
    @Override public void hide() { dispose(); }
    @Override public void dispose() {
        if (disposed) { return; } disposed = true;
        presentation.close(); players.clear(); projectiles.clear();
        hud.close();combatIcons.close();tiles.dispose(); map.dispose(); assets.close(); identityFont.dispose();shapes.dispose();readySound.dispose(); batch.dispose();
        // LobbyClient/Game own the session, listener and TCP connection; this screen never closes them.
    }
}
