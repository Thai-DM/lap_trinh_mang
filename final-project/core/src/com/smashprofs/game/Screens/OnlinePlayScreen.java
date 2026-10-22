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
    private boolean debugCombat;
    private UnarmedControls.Preset controls=Gdx.app.getPreferences("unarmed-controls").getString("preset","B").equals("A")?UnarmedControls.Preset.A:UnarmedControls.Preset.B;
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
    private long bossHitSerial;
    private int bossPhase=1;
    private boolean bossDeathPlayed;
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
    private static final class ActiveExplosion {
        final long id;
        final float x, y;
        float elapsed;
        final float duration = 0.42f;
        ActiveExplosion(long id, float x, float y) { this.id = id; this.x = x; this.y = y; }
    }
    private final List<ActiveExplosion> activeExplosions = new ArrayList<>();
    private final Set<Long> seenExplosions = new HashSet<>();
    private final Texture explosionTexture = new Texture("explosions/explosion-6.png");
    private final TextureRegion[] explosionFrames = TextureRegion.split(explosionTexture, explosionTexture.getHeight(), explosionTexture.getHeight())[0];
    private final com.badlogic.gdx.audio.Sound stompSound = Gdx.audio.newSound(Gdx.files.internal("sounds/stomp.wav"));

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
        hud = new OnlineHud(batch, session.getRoster(), session.getLocalPlayerId().value(),session.isFighter());
        identityFont.getData().setScale(0.008f);
    }
    @Override public void show() { Gdx.input.setInputProcessor(null); }
    @Override public void render(float delta) {
        if (disposed) { return; }
        presentation.update();
        var result = presentation.takeResultTransition();if(result!=null) { pendingResult=result; }
        if(pendingResult!=null) { finishTime+=Math.max(0,delta); }
        if (pendingResult!=null && finishTime>=(pendingResult.isBoss()?2.4f:1.5f)) {
            game.setScreen(new OnlineResultScreen(game,pendingResult, session.getLocalPlayerId().value()));
            return; // setScreen hides/disposes this screen on the render thread.
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) { game.leaveOnlineLobby();game.setScreen(new OnlineLobbyScreen(game));return; }
        if (Gdx.input.isKeyJustPressed(Input.Keys.BACKSPACE)) { game.getLobbyClient().close(); }
        if (Gdx.input.isKeyJustPressed(Input.Keys.TAB)) { hud.toggleInventory(); }
        if(Gdx.input.isKeyJustPressed(Input.Keys.U) || Gdx.input.isKeyJustPressed(Input.Keys.GRAVE)) { debugCombat=!debugCombat; }
        if(Gdx.input.isKeyJustPressed(Input.Keys.O) && session.isFighter()) {
            controls=controls==UnarmedControls.Preset.B?UnarmedControls.Preset.A:UnarmedControls.Preset.B;
            Gdx.app.getPreferences("unarmed-controls").putString("preset",controls.name()).flush();
        }
        hud.controls(controls,debugCombat);
        if (Gdx.input.isKeyJustPressed(Input.Keys.H)) { hud.toggleHelp(); }
        if (game.getLobbyClient().getMatchSession() == session) {
            boolean fighter=session.isFighter();
            if(fighter) {
                var command=UnarmedControls.sample(controls,Gdx.input::isKeyPressed,Gdx.input::isKeyJustPressed);
                int abilityKey=com.smashprofs.game.Helper.KeyBindingConfig.getInstance().getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.ABILITY);
                boolean ability=Gdx.input.isKeyJustPressed(abilityKey) || Gdx.input.isKeyJustPressed(Input.Keys.F) || Gdx.input.isKeyJustPressed(Input.Keys.P);
                game.getLobbyClient().recordFrameInput(command.horizontal(),command.jumpPressed(),false,command.lightPressed(),command.lightHeld(),ability,command.interactPressed(),command.dropPressed(),command.fighter());
            } else {
            float axis = (Gdx.input.isKeyPressed(Input.Keys.D) ? 1 : 0) - (Gdx.input.isKeyPressed(Input.Keys.A) ? 1 : 0);
            game.getLobbyClient().recordFrameInput(axis, Gdx.input.isKeyJustPressed(Input.Keys.W),
                    Gdx.input.isKeyJustPressed(Input.Keys.S), Gdx.input.isKeyJustPressed(Input.Keys.V)
                            || Gdx.input.isKeyJustPressed(Input.Keys.SPACE), Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT),
                    (Gdx.input.isKeyJustPressed(Input.Keys.F) || Gdx.input.isKeyJustPressed(Input.Keys.P)),Gdx.input.isKeyJustPressed(Input.Keys.E),Gdx.input.isKeyJustPressed(Input.Keys.G),
                    fighter?new com.smashprofs.common.model.FighterInput(Gdx.input.isKeyPressed(Input.Keys.S)||Gdx.input.isKeyPressed(Input.Keys.DOWN),
                            Gdx.input.isKeyPressed(Input.Keys.W),Gdx.input.isKeyPressed(Input.Keys.UP)||Gdx.input.isKeyPressed(Input.Keys.W),
                            Gdx.input.isKeyJustPressed(Input.Keys.J)||Gdx.input.isKeyJustPressed(Input.Keys.K),
                            Gdx.input.isKeyPressed(Input.Keys.J)||Gdx.input.isKeyPressed(Input.Keys.K),
                            Gdx.input.isKeyJustPressed(Input.Keys.SHIFT_LEFT)||Gdx.input.isKeyJustPressed(Input.Keys.CONTROL_LEFT),
                            Gdx.input.isKeyJustPressed(Input.Keys.Q),Gdx.input.isKeyJustPressed(Input.Keys.C)):com.smashprofs.common.model.FighterInput.NONE);
            }
        }
        animationTime += Math.min(Math.max(delta, 0), 0.1f)*(pendingResult==null?1:.2f);
        updatePrediction(delta);
        ScreenUtils.clear(.25f,.70f,.86f,1);
        updateCamera(delta);viewport.apply(); camera.update(); tiles.setView(camera); tiles.render();
        var authoritative=presentation.getFrame()==null?null:presentation.getFrame().authoritative();
        var gameplay=authoritative==null?null:authoritative.gameplay();
        if(gameplay!=null && gameplay.boss()!=null && arena.boss!=null) {
            var boss=gameplay.boss();
            if(boss.hitSerial()>bossHitSerial && Game.soundEffects) { hitSound.play(.2f); }bossHitSerial=boss.hitSerial();
            if(boss.phase()!=bossPhase && Game.soundEffects) { readySound.play(.5f); }bossPhase=boss.phase();
            if(boss.hp()==0 && !bossDeathPlayed) { bossDeathPlayed=true;if(Game.soundEffects) { koSound.play(.5f); } }
            arena.boss.update(boss,authoritative.serverTick(),delta);
            shapes.setProjectionMatrix(camera.combined);arena.boss.warnings(shapes,gameplay.boss());
        }
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
                double hp=authoritative.player(p.playerId()).hp();Double previous=lastDamage.put(p.playerId(),hp);
                if(previous!=null && hp<previous) { flashes.put(p.playerId(),animationTime+.08f);if(Game.soundEffects) { hitSound.play(.35f); } }
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
                    if(move.activeAt(f.attackFrame())) {
                        int dir=f.detail().attackDirection();
                        shapes.setColor(move.heavy()?Color.GOLD:Color.WHITE);
                        float lineW=move.heavy()?.018f:.010f;
                        float len=move.heavy()?.070f:.042f;
                        float aimAngleDeg;
                        boolean isLocal=p.playerId().equals(session.getLocalPlayerId().value());
                        if(isLocal) {
                            var cfg=com.smashprofs.game.Helper.KeyBindingConfig.getInstance();
                            boolean up=com.badlogic.gdx.Gdx.input.isKeyPressed(cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.UP)) || com.badlogic.gdx.Gdx.input.isKeyPressed(com.badlogic.gdx.Input.Keys.UP);
                            boolean down=com.badlogic.gdx.Gdx.input.isKeyPressed(cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.DOWN)) || com.badlogic.gdx.Gdx.input.isKeyPressed(com.badlogic.gdx.Input.Keys.DOWN);
                            boolean left=com.badlogic.gdx.Gdx.input.isKeyPressed(cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.LEFT)) || com.badlogic.gdx.Gdx.input.isKeyPressed(com.badlogic.gdx.Input.Keys.LEFT);
                            boolean right=com.badlogic.gdx.Gdx.input.isKeyPressed(cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.RIGHT)) || com.badlogic.gdx.Gdx.input.isKeyPressed(com.badlogic.gdx.Input.Keys.RIGHT);
                            boolean h=left || right;
                            if(up && h) { aimAngleDeg=45f; }
                            else if(down && h) { aimAngleDeg=-45f; }
                            else if(up) { aimAngleDeg=90f; }
                            else if(down) { aimAngleDeg=-90f; }
                            else { aimAngleDeg=0f; }
                        } else {
                            aimAngleDeg=Math.abs(move.x())<.03f?(move.y()>=0?90f:-90f):move.angle();
                        }
                        if(Math.abs(aimAngleDeg-90f)<5f) {
                            shapes.rectLine(p.x(),p.y()+.10f,p.x(),p.y()+.10f+len,lineW);
                        } else if(Math.abs(aimAngleDeg-(-90f))<5f) {
                            shapes.rectLine(p.x(),p.y()+.08f,p.x(),p.y()+.08f-len,lineW);
                        } else {
                            float rad=(float)Math.toRadians(Math.abs(aimAngleDeg));
                            float cos=(float)Math.cos(rad);
                            float sin=(aimAngleDeg>=0?1f:-1f)*(float)Math.sin(rad);
                            float startX=p.x()+dir*.035f;
                            float startY=p.y()+.10f;
                            float endX=startX+dir*len*cos;
                            float endY=startY+len*sin;
                            shapes.rectLine(startX,startY,endX,endY,lineW);
                        }
                    }
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
        }
        batch.setProjectionMatrix(camera.combined); batch.begin();
        for (var view : renderedPlayers) {
            var p = view.state(); var sprite = players.get(p.playerId());
            if(gameplay!=null && !gameplay.player(p.playerId()).status().equals("ACTIVE")) { continue; }
            var fighter=gameplay==null || gameplay.fighter()==null?null:gameplay.fighter().player(p.playerId());
            sprite.setRegion(assets.frame(p, animationTime,fighter)); sprite.setFlip(fighter!=null && !fighter.attack().equals("NONE")?fighter.detail().attackDirection()>0:p.facingRight(), false);
            sprite.setColor(p.dead() ? new Color(0.5f, 0.5f, 0.5f, 0.4f) : p.blocking() ? Color.CYAN : Color.WHITE);
            sprite.setRotation(0);
            if(fighter!=null) {
                if(fighter.action().equals("DODGE") || fighter.detail().state().contains("DASH") || fighter.detail().dashTicks()>0) {
                    sprite.setColor(.55f,.95f,1,fighter.dodgeTicks()<=20 && fighter.dodgeTicks()>8?.35f:.75f);
                }
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
            sprite.setPosition(p.x() - sprite.getWidth() / 2, p.y() - sprite.getHeight() / 4);
            if(fighter!=null && (fighter.action().equals("DODGE") || fighter.detail().state().contains("DASH") || fighter.detail().dashTicks()>0)) {
                float trailDir=p.facingRight()?1f:-1f;
                if(fighter.detail().dashVelocity()!=0) { trailDir=Math.signum(fighter.detail().dashVelocity()); }
                float origX=sprite.getX(),origY=sprite.getY();
                var origColor=new Color(sprite.getColor());
                for(int trail=2;trail>=1;trail--) {
                    sprite.setPosition(origX-trailDir*trail*0.08f,origY);
                    sprite.setColor(0.35f,0.85f,1f,0.22f/trail);
                    sprite.draw(batch);
                }
                sprite.setPosition(origX,origY);
                sprite.setColor(origColor);
            }
            sprite.draw(batch);
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
                if(shot.type().equals("EXPLOSION") && seenExplosions.add(shot.id())) {
                    activeExplosions.add(new ActiveExplosion(shot.id(), shot.x(), shot.y()));
                    if(Game.soundEffects) { stompSound.play(0.45f); }
                }
                float angle=(float)Math.toDegrees(Math.atan2(shot.vy(),shot.vx()));
                switch(shot.type()) {
                    case "SHOTGUN"->combatIcons.draw(batch,"PELLET",shot.x(),shot.y(),.09f,.055f,angle,false);
                    case "BAZOOKA"->combatIcons.draw(batch,"MISSILE",shot.x(),shot.y(),.20f,.13f,angle,false);
                    case "GRENADE"->combatIcons.draw(batch,"GRENADE",shot.x(),shot.y(),.12f,.12f,animationTime*150,false);
                    case "ORBIT"->combatIcons.draw(batch,"ORBIT",shot.x(),shot.y(),.15f,.15f,animationTime*300,false);
                    case "THROWN"->combatIcons.draw(batch,"SHOTGUN",shot.x(),shot.y(),.23f,.16f,animationTime*720,false);
                    default->{
                        if(shot.type().startsWith("THROWN_")) {
                            String weapon=shot.type().substring(7);
                            combatIcons.draw(batch,weapon,shot.x(),shot.y(),.23f,.16f,animationTime*720,false);
                        }
                    }
                }
            }
        }
        if(gameplay!=null && gameplay.boss()!=null && arena.boss!=null) { arena.boss.draw(batch,gameplay.boss(),authoritative.serverTick()); }
        for(var it=activeExplosions.iterator();it.hasNext();) {
            var exp=it.next();
            exp.elapsed+=delta;
            if(exp.elapsed>=exp.duration) { it.remove(); }
        }
        for(var exp:activeExplosions) {
            float progress=Math.min(1f,exp.elapsed/exp.duration);
            int frame=Math.min(explosionFrames.length-1,(int)(progress*explosionFrames.length));
            float size=0.85f*2.0f*(0.65f+0.45f*(float)Math.sin(progress*Math.PI*0.75f));
            batch.setColor(1f,1f,1f,Math.max(0f,1f-progress*0.75f));
            batch.draw(explosionFrames[frame],exp.x-size/2f,exp.y-size/2f,size,size);
        }
        batch.setColor(Color.WHITE);
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
        if(!activeExplosions.isEmpty()) {
            Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA, com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
            shapes.setProjectionMatrix(camera.combined);
            shapes.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
            for(var exp:activeExplosions) {
                float progress=Math.min(1f,exp.elapsed/exp.duration);
                float radius=0.85f*(float)Math.sin(progress*Math.PI*0.5f);
                if(progress<0.35f) {
                    float coreAlpha=(0.35f-progress)/0.35f*0.75f;
                    shapes.setColor(1f,0.95f,0.5f,coreAlpha);
                    shapes.circle(exp.x,exp.y,radius*0.6f,24);
                }
                float fireAlpha=(1f-progress)*0.40f;
                shapes.setColor(1f,0.5f+0.3f*(1f-progress),0.1f,fireAlpha);
                shapes.circle(exp.x,exp.y,radius*0.85f,28);
                float sparkAlpha=(1f-progress);
                shapes.setColor(1f,0.85f,0.2f,sparkAlpha);
                for(int s=0;s<8;s++) {
                    double angle=(s*Math.PI/4.0)+(exp.id*0.35);
                    float dist=radius*(1.05f+0.12f*(float)Math.sin(s*1.5));
                    shapes.circle(exp.x+dist*(float)Math.cos(angle),exp.y+dist*(float)Math.sin(angle),0.02f*(1f-progress),6);
                }
            }
            shapes.end();
        }
        if(gameplay!=null) {
            boolean hasMelee = false;
            for(var shot:gameplay.shots()) {
                if(shot.type().equals("SWORD_SLASH") || shot.type().equals("WARHAMMER_SWING")
                        || shot.type().equals("DAGGER_SLASH") || shot.type().equals("SPEAR_THRUST")) {
                    hasMelee = true; break;
                }
            }
            if(hasMelee) {
                Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
                Gdx.gl.glBlendFunc(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA, com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
                shapes.setProjectionMatrix(camera.combined);
                shapes.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
                for(var shot:gameplay.shots()) {
                    float baseAngle = (float)Math.atan2(shot.vy(), shot.vx());
                    float ux = shot.vx(), uy = shot.vy();
                    double len = Math.hypot(ux, uy);
                    if(len > 0) { ux /= (float)len; uy /= (float)len; }
                    float sx = shot.x(), sy = shot.y();
                    switch(shot.type()) {
                        case "SWORD_SLASH"->{
                            float radius = 0.50f;
                            int steps = 14;
                            for(int s=0; s<steps; s++) {
                                float t = (float)s / steps;
                                float ang = baseAngle - 0.75f + t * 1.50f;
                                float r1 = radius * 0.72f;
                                float r2 = radius * (1.0f + 0.08f * (float)Math.sin(t * Math.PI));
                                float alpha = (float)Math.sin(t * Math.PI) * 0.85f;
                                shapes.setColor(0.0f, 0.85f, 1.0f, alpha * 0.6f);
                                shapes.rectLine(sx + r1*(float)Math.cos(ang), sy + r1*(float)Math.sin(ang),
                                                sx + r2*(float)Math.cos(ang), sy + r2*(float)Math.sin(ang), 0.045f);
                                shapes.setColor(1.0f, 1.0f, 1.0f, alpha);
                                shapes.rectLine(sx + (r1+r2)*0.5f*(float)Math.cos(ang), sy + (r1+r2)*0.5f*(float)Math.sin(ang),
                                                sx + r2*(float)Math.cos(ang), sy + r2*(float)Math.sin(ang), 0.020f);
                            }
                        }
                        case "WARHAMMER_SWING"->{
                            float radius = 0.52f;
                            int steps = 16;
                            for(int s=0; s<steps; s++) {
                                float t = (float)s / steps;
                                float ang = baseAngle - 0.90f + t * 1.80f;
                                float r1 = radius * 0.65f;
                                float r2 = radius * (1.05f + 0.12f * (float)Math.sin(t * Math.PI));
                                float alpha = (float)Math.sin(t * Math.PI) * 0.90f;
                                shapes.setColor(1.0f, 0.55f, 0.0f, alpha * 0.7f);
                                shapes.rectLine(sx + r1*(float)Math.cos(ang), sy + r1*(float)Math.sin(ang),
                                                sx + r2*(float)Math.cos(ang), sy + r2*(float)Math.sin(ang), 0.065f);
                                shapes.setColor(1.0f, 0.88f, 0.2f, alpha);
                                shapes.rectLine(sx + (r1+r2)*0.5f*(float)Math.cos(ang), sy + (r1+r2)*0.5f*(float)Math.sin(ang),
                                                sx + r2*(float)Math.cos(ang), sy + r2*(float)Math.sin(ang), 0.030f);
                            }
                            shapes.setColor(1f, 0.9f, 0.4f, 0.85f);
                            shapes.circle(sx + ux * radius, sy + uy * radius, 0.07f, 12);
                            shapes.setColor(1f, 0.4f, 0.1f, 0.65f);
                            shapes.circle(sx + ux * radius, sy + uy * radius, 0.12f, 14);
                        }
                        case "DAGGER_SLASH"->{
                            float r = 0.38f;
                            for(int d=-1; d<=1; d+=2) {
                                float ang1 = baseAngle + d * 0.55f;
                                float ang2 = baseAngle - d * 0.25f;
                                shapes.setColor(0.88f, 0.10f, 0.75f, 0.70f);
                                shapes.rectLine(sx + r*0.3f*(float)Math.cos(ang1), sy + r*0.3f*(float)Math.sin(ang1),
                                                sx + r*(float)Math.cos(ang2), sy + r*(float)Math.sin(ang2), 0.035f);
                                shapes.setColor(1.0f, 0.85f, 1.0f, 0.90f);
                                shapes.rectLine(sx + r*0.4f*(float)Math.cos(ang1), sy + r*0.4f*(float)Math.sin(ang1),
                                                sx + r*0.95f*(float)Math.cos(ang2), sy + r*0.95f*(float)Math.sin(ang2), 0.015f);
                            }
                        }
                        case "SPEAR_THRUST"->{
                            float lenDist = 0.68f;
                            float tipX = sx + ux * lenDist, tipY = sy + uy * lenDist;
                            shapes.setColor(1.0f, 0.85f, 0.20f, 0.70f);
                            shapes.rectLine(sx + ux * 0.10f, sy + uy * 0.10f, tipX, tipY, 0.045f);
                            shapes.setColor(1.0f, 1.0f, 1.0f, 0.95f);
                            shapes.rectLine(sx + ux * 0.15f, sy + uy * 0.15f, tipX, tipY, 0.018f);
                            shapes.setColor(1.0f, 0.95f, 0.5f, 0.90f);
                            shapes.circle(tipX, tipY, 0.045f, 8);
                            shapes.setColor(1.0f, 1.0f, 1.0f, 1.0f);
                            shapes.circle(tipX, tipY, 0.022f, 6);
                        }
                        default->{ }
                    }
                }
                shapes.end();
            }
        }
        if(debugCombat && gameplay!=null && gameplay.fighter()!=null) { drawCombatDebug(gameplay); }
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
    private void drawCombatDebug(com.smashprofs.common.model.GameplaySnapshot gameplay) {
        Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA, com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.setProjectionMatrix(camera.combined);
        // Filled pass for transparent hitbox areas
        shapes.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
        for(var view:renderedPlayers) {
            var p=view.state();if(p.dead()) { continue; }var f=gameplay.fighter().player(p.playerId());
            if(!f.attack().equals("NONE")) {
                var m=com.smashprofs.common.model.FighterMoves.get(f.attack());
                int direction=f.detail().attackDirection();
                float boxX=p.x()+direction*m.x()-m.halfWidth(), boxY=p.y()+m.y()-m.halfHeight(), w=m.halfWidth()*2, h=m.halfHeight()*2;
                String phase=f.detail().phase();
                if(phase.equals("ACTIVE")) {
                    shapes.setColor(1f, 0.1f, 0.1f, 0.45f);
                    shapes.rect(boxX, boxY, w, h);
                } else if(phase.equals("STARTUP") || f.action().equals("CHARGE")) {
                    shapes.setColor(1f, 0.85f, 0.1f, 0.30f);
                    shapes.rect(boxX, boxY, w, h);
                }
            }
        }
        shapes.end();
        // Line pass for hurtbox and hitbox outlines
        shapes.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Line);
        for(var view:renderedPlayers) {
            var p=view.state();if(p.dead()) { continue; }var f=gameplay.fighter().player(p.playerId());
            float high=com.smashprofs.common.model.FighterMoves.hurtboxHigh(f.attack(),f.attackFrame());
            shapes.setColor(Color.CYAN);shapes.rect(p.x()-.085f,p.y()-.095f,.17f,high+.18f);
            shapes.circle(p.x(),p.y()-.01f,.085f,16);shapes.circle(p.x(),p.y()+high,.085f,16);
            if(!f.attack().equals("NONE")) {
                var m=com.smashprofs.common.model.FighterMoves.get(f.attack());
                int direction=f.detail().attackDirection();
                float boxX=p.x()+direction*m.x()-m.halfWidth(), boxY=p.y()+m.y()-m.halfHeight(), w=m.halfWidth()*2, h=m.halfHeight()*2;
                String phase=f.detail().phase();
                if(phase.equals("ACTIVE")) {
                    shapes.setColor(Color.RED);
                    shapes.rect(boxX, boxY, w, h);
                } else if(phase.equals("STARTUP") || f.action().equals("CHARGE")) {
                    shapes.setColor(Color.GOLD);
                    shapes.rect(boxX, boxY, w, h);
                }
            }
        }
        shapes.end();
    }
    private void updateCamera(float delta) {
        float minX=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY,minY=Float.POSITIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY;
        for(var view:renderedPlayers) {
            var p=view.state();if(p.dead()) { continue; }
            minX=Math.min(minX,p.x());maxX=Math.max(maxX,p.x());minY=Math.min(minY,p.y());maxY=Math.max(maxY,p.y());
        }
        if(!Float.isFinite(minX)) { minX=maxX=mapWidth/2;minY=maxY=.25f; }
        if(presentation.getFrame()!=null && presentation.getFrame().authoritative().gameplay()!=null) {
            var boss=presentation.getFrame().authoritative().gameplay().boss();
            if(boss!=null && boss.hp()>0) { minX=Math.min(minX,boss.x()-.4f);maxX=Math.max(maxX,boss.x()+.4f);minY=Math.min(minY,boss.y());maxY=Math.max(maxY,boss.y()+1.05f); }
        }
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
        hud.close();combatIcons.close();tiles.dispose();arena.close();identityFont.dispose();shapes.dispose();readySound.dispose();hitSound.dispose();koSound.dispose();explosionTexture.dispose();stompSound.dispose(); batch.dispose();
        // LobbyClient/Game own the session, listener and TCP connection; this screen never closes them.
    }
}
