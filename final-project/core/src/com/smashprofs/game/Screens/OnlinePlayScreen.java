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
    private final OnlineSpriteAssets assets;
    private final OnlineArenaAssets arena;
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
    private final OnlineCameraController framing=new OnlineCameraController();
    private final Map<String,String> cameraLifeStates=new HashMap<>();
    private WireMatchResult pendingResult;private float finishTime;
    private final com.badlogic.gdx.audio.Sound hitSound=Gdx.audio.newSound(Gdx.files.internal("sounds/punch.mp3"));
    private final com.badlogic.gdx.audio.Sound koSound=Gdx.audio.newSound(Gdx.files.internal("sounds/death.mp3"));
    private final Map<String,Double> lastDamage=new HashMap<>();
    private final Map<String,Float> flashes=new HashMap<>();
    private final Map<String,Integer> lastStocks=new HashMap<>();
    private float koFlash;
    private long visualEventSequence;
    private final List<KoBurst> bursts=new ArrayList<>(4);
    private static final class KoBurst {
        final String cause;final float x,y;float time=.4f;
        KoBurst(String cause,float x,float y) { this.cause=cause;this.x=x;this.y=y; }
    }
    private LocalFighterPrediction prediction;
    private final List<LocalFighterPrediction.Rect> predictionGeometry=new ArrayList<>();
    private List<OnlineMatchPresentation.PlayerView> renderedPlayers=List.of();
    private final com.badlogic.gdx.graphics.glutils.ShapeRenderer shapes=new com.badlogic.gdx.graphics.glutils.ShapeRenderer();
    private boolean ultimateWasReady=true;
    private final com.badlogic.gdx.audio.Sound readySound=Gdx.audio.newSound(Gdx.files.internal("sounds/minecraft_click.mp3"));

    OnlinePlayScreen(Game game, ClientMatchSession session) {
        this.game = game; this.session = session;
        presentation = new OnlineMatchPresentation(session);
        arena=game.takeOnlineAssets();map=arena.map;assets=arena.sprites;
        for(var object:map.getLayers().get("obj").getObjects()) {
            if(object instanceof com.badlogic.gdx.maps.objects.RectangleMapObject rectangle) {
                var r=rectangle.getRectangle();predictionGeometry.add(new LocalFighterPrediction.Rect(r.x/100,r.y/100,r.width/100,r.height/100));
            }
        }
        tiles = new OrthogonalTiledMapRenderer(map, 1 / 100f);
        mapWidth = map.getProperties().get("width", Integer.class) * map.getProperties().get("tilewidth", Integer.class) / 100f;
        mapHeight = map.getProperties().get("height", Integer.class) * map.getProperties().get("tileheight", Integer.class) / 100f;
        updateCamera(0);
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
        var result = presentation.takeResultTransition();if(result!=null) { pendingResult=result; }
        if(pendingResult!=null) { finishTime+=Math.max(0,delta); }
        if (pendingResult!=null && finishTime>=1.5f) {
            game.setScreen(new OnlineResultScreen(game,pendingResult, session.getLocalPlayerId().value()));
            return; // setScreen hides/disposes this screen on the render thread.
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) { game.leaveOnlineLobby();game.setScreen(new OnlineLobbyScreen(game));return; }
        if (Gdx.input.isKeyJustPressed(Input.Keys.F9)) { game.getLobbyClient().close(); }
        if (Gdx.input.isKeyJustPressed(Input.Keys.TAB)) { hud.toggleInventory(); }
        if (Gdx.input.isKeyJustPressed(Input.Keys.H)) { hud.toggleHelp(); }
        if (game.getLobbyClient().getMatchSession() == session) {
            boolean fighter=session.isFighter();
            float axis = (Gdx.input.isKeyPressed(Input.Keys.D) ? 1 : 0) - (Gdx.input.isKeyPressed(Input.Keys.A) ? 1 : 0);
            game.getLobbyClient().recordFrameInput(axis, Gdx.input.isKeyJustPressed(Input.Keys.W),
                    Gdx.input.isKeyJustPressed(Input.Keys.S), Gdx.input.isKeyJustPressed(Input.Keys.V)
                            || Gdx.input.isKeyJustPressed(Input.Keys.SPACE), Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT),
                    !fighter && Gdx.input.isKeyJustPressed(Input.Keys.F),Gdx.input.isKeyJustPressed(Input.Keys.E),Gdx.input.isKeyJustPressed(Input.Keys.G),
                    fighter?new com.smashprofs.common.model.FighterInput(Gdx.input.isKeyPressed(Input.Keys.S)||Gdx.input.isKeyPressed(Input.Keys.DOWN),
                            Gdx.input.isKeyPressed(Input.Keys.W),Gdx.input.isKeyPressed(Input.Keys.UP)||Gdx.input.isKeyPressed(Input.Keys.W),
                            Gdx.input.isKeyJustPressed(Input.Keys.F)||Gdx.input.isKeyJustPressed(Input.Keys.J),
                            Gdx.input.isKeyPressed(Input.Keys.F)||Gdx.input.isKeyPressed(Input.Keys.J),
                            Gdx.input.isKeyJustPressed(Input.Keys.SHIFT_LEFT)||Gdx.input.isKeyJustPressed(Input.Keys.CONTROL_LEFT),
                            Gdx.input.isKeyJustPressed(Input.Keys.Q),Gdx.input.isKeyJustPressed(Input.Keys.C)):com.smashprofs.common.model.FighterInput.NONE);
        }
        animationTime += Math.min(Math.max(delta, 0), 0.1f)*(pendingResult==null?1:.2f);
        updatePrediction(delta);
        ScreenUtils.clear(.25f,.70f,.86f,1);
        updateCamera(delta);viewport.apply(); camera.update(); tiles.setView(camera); tiles.render();
        var authoritative=presentation.getFrame()==null?null:presentation.getFrame().authoritative();
        var gameplay=authoritative==null?null:authoritative.gameplay();
        if(authoritative!=null) {
            for(var entry:session.getKillFeed().entries()) {
                var event=entry.event();if(event.sequence()<=visualEventSequence) { continue; }visualEventSequence=event.sequence();
                if(event.cause().startsWith("BLAST_")) {
                    var p=authoritative.player(event.victim());if(bursts.size()==4) { bursts.remove(0); }bursts.add(new KoBurst(event.cause(),p.x(),p.y()));
                }
            }
        }
        if(gameplay!=null && gameplay.fighter()!=null) {
            for(var p:gameplay.fighter().players()) {
                Double previous=lastDamage.put(p.playerId(),p.damagePercent());
                if(previous!=null && p.damagePercent()>previous) { flashes.put(p.playerId(),animationTime+.08f);if(Game.soundEffects) { hitSound.play(.35f); } }
                Integer stocks=lastStocks.put(p.playerId(),gameplay.player(p.playerId()).lives());
                if(stocks!=null && stocks>gameplay.player(p.playerId()).lives()) { koFlash=.18f;if(Game.soundEffects) { koSound.play(.5f); } }
            }
            shapes.setProjectionMatrix(camera.combined);shapes.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
            for(var r:predictionGeometry) {
                if(r.height()>0 && r.height()<=.4f && r.width()>=.3f && r.y()+r.height()>.65f) { shapes.setColor(.5f,.95f,1,1);shapes.rect(r.x(),r.y()+r.height(),r.width(),.008f); }
            }
            for(var view:renderedPlayers) {
                var p=view.state();var f=gameplay.fighter().player(p.playerId());
                if(f.platformTicks()>0) { shapes.setColor(.4f,.9f,1,1);shapes.rect(p.x()-.20f,p.y()-.075f,.40f,.025f); }
                if(f.fastFalling()) { shapes.setColor(Color.WHITE);for(int i=0;i<3;i++) { shapes.rect(p.x()-.06f+i*.06f,p.y()+.12f,.012f,.22f); } }
                if(f.action().equals("ATTACK")) {
                    var move=com.smashprofs.common.model.FighterMoves.get(f.attack());
                    if(move.activeAt(f.attackFrame())) { shapes.setColor(move.heavy()?Color.GOLD:Color.WHITE);shapes.rectLine(p.x(),p.y()+.1f,p.x()+(p.facingRight()?1:-1)*move.x()*2,p.y()+move.y(),.018f); }
                }
            }
            shapes.end();
        }
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
        for (var view : renderedPlayers) {
            var p = view.state(); var sprite = players.get(p.playerId());
            if(gameplay!=null && !gameplay.player(p.playerId()).status().equals("ACTIVE")) { continue; }
            var fighter=gameplay==null || gameplay.fighter()==null?null:gameplay.fighter().player(p.playerId());
            sprite.setRegion(assets.frame(p, animationTime,fighter)); sprite.setFlip(p.facingRight(), false);
            sprite.setColor(p.dead() ? new Color(0.5f, 0.5f, 0.5f, 0.4f) : p.blocking() ? Color.CYAN : Color.WHITE);
            sprite.setRotation(0);
            if(fighter!=null) {
                if(fighter.action().equals("DODGE")) { sprite.setColor(.55f,.95f,1,fighter.dodgeTicks()<=20 && fighter.dodgeTicks()>8?.35f:.8f); }
                if(fighter.action().equals("TUMBLE")) { sprite.setRotation(animationTime*720);sprite.setColor(Color.SALMON); }
                if(flashes.getOrDefault(p.playerId(),0f)>animationTime) { sprite.setColor(Color.WHITE); }
                if(fighter.platformTicks()>0) { sprite.setColor(1,1,.5f,((int)(animationTime*12)%2==0)?.35f:1); }
            }
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
                    case "THROWN"->combatIcons.draw(batch,"SHOTGUN",shot.x(),shot.y(),.23f,.16f,animationTime*600,false);
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
        if(!bursts.isEmpty()) {
            shapes.setProjectionMatrix(camera.combined);shapes.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Line);
            float left=camera.position.x-viewport.getWorldWidth()/2+.2f,right=camera.position.x+viewport.getWorldWidth()/2-.2f;
            float bottom=camera.position.y-viewport.getWorldHeight()/2+.25f,top=camera.position.y+viewport.getWorldHeight()/2-.4f;
            for(var burst:bursts) {
                burst.time-=Math.max(0,delta);float x=Math.max(left,Math.min(right,burst.x)),y=Math.max(bottom,Math.min(top,burst.y));
                switch(burst.cause) { case "BLAST_LEFT"->x=left;case "BLAST_RIGHT"->x=right;case "BLAST_TOP"->y=top;default->y=bottom; }
                float radius=.12f+(.4f-burst.time)*.8f;shapes.setColor(Color.GOLD);
                for(int i=0;i<8;i++) { float angle=i*(float)Math.PI/4;shapes.line(x+radius*.3f*(float)Math.cos(angle),y+radius*.3f*(float)Math.sin(angle),x+radius*(float)Math.cos(angle),y+radius*(float)Math.sin(angle)); }
                shapes.setColor(Color.WHITE);shapes.circle(x,y,radius*.25f,8);
            }
            shapes.end();bursts.removeIf(b->b.time<=0);
        }
        if(koFlash>0) {
            koFlash=Math.max(0,koFlash-Math.max(0,delta));
            shapes.setProjectionMatrix(camera.combined);shapes.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Line);shapes.setColor(Color.GOLD);
            float left=camera.position.x-viewport.getWorldWidth()/2,right=camera.position.x+viewport.getWorldWidth()/2;
            float bottom=camera.position.y-viewport.getWorldHeight()/2,top=camera.position.y+viewport.getWorldHeight()/2;
            shapes.rect(left+.04f,bottom+.04f,right-left-.08f,top-bottom-.08f);shapes.end();
        }
        var lobby = game.getLobbyClient().getSnapshot();
        boolean connected = game.getNetworkClient().isConnected() && lobby.phase() != LobbyClient.Phase.ERROR;
        String status = !connected ? lobby.statusText() : lobby.errorCode() != null ? lobby.statusText()
                : String.format(Locale.ROOT, "Connected | %.1f snapshots/s | skipped broadcasts: %d", session.getMetrics().getSnapshotFrequencyHz(), session.getMetrics().getDroppedSnapshots());
        hud.update(presentation.getFrame(), status, connected,session); hud.draw(delta);
    }
    private void updatePrediction(float delta) {
        renderedPlayers=presentation.getPlayers();var frame=presentation.getFrame();
        if(frame==null || frame.authoritative().gameplay()==null || frame.authoritative().gameplay().fighter()==null) { return; }
        if(prediction==null) { prediction=new LocalFighterPrediction(predictionGeometry); }
        var source=frame.authoritative();String id=session.getLocalPlayerId().value();
        for(var p:source.gameplay().players()) {
            String previous=cameraLifeStates.put(p.playerId(),p.status());
            if(p.status().equals("ACTIVE") && "RESPAWNING".equals(previous)) { framing.reset(); }
        }
        var position=prediction.sample(source.player(id),source.gameplay().fighter().player(id),session.getPredictionInputs(),delta);
        renderedPlayers=renderedPlayers.stream().map(view->{
            if(!view.local()) { return view; }var p=view.state();
            return new OnlineMatchPresentation.PlayerView(new WireGameSnapshot.Player(p.playerId(),p.slotIndex(),p.characterId(),position.x(),position.y(),
                    p.velocityX(),p.velocityY(),p.hp(),p.grounded(),p.blocking(),p.dead(),p.facingRight()),true);
        }).toList();
    }
    private void updateCamera(float delta) {
        float minX=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY,minY=Float.POSITIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY;
        for(var view:renderedPlayers) {
            var p=view.state();if(p.dead()) { continue; }
            minX=Math.min(minX,p.x());maxX=Math.max(maxX,p.x());minY=Math.min(minY,p.y());maxY=Math.max(maxY,p.y());
        }
        if(!Float.isFinite(minX)) { minX=maxX=mapWidth/2;minY=maxY=.25f; }
        var frame=framing.update(minX,maxX,minY,maxY,Gdx.graphics.getWidth()/(float)Math.max(1,Gdx.graphics.getHeight()),mapWidth,mapHeight,delta);
        viewport.setWorldSize(frame.width(),frame.height());
        camera.position.set(frame.x(),frame.y(),0);
        viewport.update(Gdx.graphics.getWidth(),Gdx.graphics.getHeight(),false);
    }
    @Override public void resize(int width, int height) { framing.reset();updateCamera(0);hud.resize(width,height); }
    @Override public void pause() { }
    @Override public void resume() { }
    @Override public void hide() { dispose(); }
    @Override public void dispose() {
        if (disposed) { return; } disposed = true;
        presentation.close(); players.clear(); projectiles.clear();
        if(prediction!=null) { prediction.close(); }
        hud.close();combatIcons.close();tiles.dispose();arena.close();identityFont.dispose();shapes.dispose();readySound.dispose();hitSound.dispose();koSound.dispose(); batch.dispose();
        // LobbyClient/Game own the session, listener and TCP connection; this screen never closes them.
    }
}
