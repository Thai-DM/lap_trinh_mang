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
    private boolean disposed;
    private float animationTime;

    OnlinePlayScreen(Game game, ClientMatchSession session) {
        this.game = game; this.session = session;
        presentation = new OnlineMatchPresentation(session);
        map = new TmxMapLoader().load("1/Map1New2Remake.tmx");
        tiles = new OrthogonalTiledMapRenderer(map, 1 / 100f);
        float mapWidth = map.getProperties().get("width", Integer.class) * map.getProperties().get("tilewidth", Integer.class) / 100f;
        float mapHeight = map.getProperties().get("height", Integer.class) * map.getProperties().get("tileheight", Integer.class) / 100f;
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
            game.setScreen(new OnlineResultScreen(result, session.getLocalPlayerId().value()));
            return; // setScreen hides/disposes this screen on the render thread.
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) { Gdx.app.exit(); return; }
        if (Gdx.input.isKeyJustPressed(Input.Keys.F9)) { game.getLobbyClient().close(); }
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
        batch.setProjectionMatrix(camera.combined); batch.begin();
        for (var view : presentation.getPlayers()) {
            var p = view.state(); var sprite = players.get(p.playerId());
            sprite.setRegion(assets.frame(p, animationTime)); sprite.setFlip(p.facingRight(), false);
            sprite.setColor(p.dead() ? new Color(0.5f, 0.5f, 0.5f, 0.4f) : p.blocking() ? Color.CYAN : Color.WHITE);
            sprite.setPosition(p.x() - sprite.getWidth() / 2, p.y() - sprite.getHeight() / 4); sprite.draw(batch);
            identityFont.setColor(view.local() ? Color.CYAN : Color.WHITE);
            identityFont.draw(batch, view.local() ? "YOU" : "Slot " + p.slotIndex(), p.x() - 0.125f, p.y() + 0.32f);
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
        hud.update(presentation.getFrame(), status, connected); hud.draw(delta);
    }
    private void updateCamera() {
        var values = presentation.getPlayers(); if (values.isEmpty()) { return; }
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (var p : values) {
            minX = Math.min(minX, p.state().x()); maxX = Math.max(maxX, p.state().x());
            minY = Math.min(minY, p.state().y()); maxY = Math.max(maxY, p.state().y());
        }
        float aspect = Gdx.graphics.getWidth() / (float) Math.max(1, Gdx.graphics.getHeight());
        float width = Math.max(4, Math.max(maxX - minX + 1.5f, (maxY - minY + 1) * aspect));
        viewport.setWorldSize(width, width / aspect);
        viewport.update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), false);
        camera.position.set((minX + maxX) / 2, (minY + maxY) / 2 + 0.15f, 0);
    }
    @Override public void resize(int width, int height) { viewport.update(width, height, false); hud.resize(width, height); }
    @Override public void pause() { }
    @Override public void resume() { }
    @Override public void hide() { dispose(); }
    @Override public void dispose() {
        if (disposed) { return; } disposed = true;
        presentation.close(); players.clear(); projectiles.clear();
        hud.close(); tiles.dispose(); map.dispose(); assets.close(); identityFont.dispose(); batch.dispose();
        // LobbyClient/Game own the session, listener and TCP connection; this screen never closes them.
    }
}
