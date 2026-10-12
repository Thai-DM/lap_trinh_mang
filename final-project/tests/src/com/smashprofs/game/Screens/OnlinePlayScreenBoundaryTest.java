package com.smashprofs.game.Screens;

import com.badlogic.gdx.ScreenAdapter;
import com.smashprofs.game.Game;
import com.smashprofs.game.network.client.*;
import com.smashprofs.server.network.GameServer;
import org.junit.Test;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** No GL mocking: injection verifies PlayScreen never enters the offline simulation branch online. */
public class OnlinePlayScreenBoundaryTest {
    @Test(timeout=20000) public void onlineScreenDelegatesWithoutCreatingLocalPlayersWorldCombatOrOfflineHud() throws Exception {
        var server = new GameServer(); var conditions = Executors.newSingleThreadScheduledExecutor(); server.start();
        var a = new Game(new LobbyConfiguration("127.0.0.1", server.getBoundPort(), "boundary", LobbyConfiguration.Mode.CREATE));
        var b = new Game(new LobbyConfiguration("127.0.0.1", server.getBoundPort(), "boundary", LobbyConfiguration.Mode.JOIN));
        try {
            assertTrue(a.getLobbyClient().startAsync()); await(conditions, () -> a.getLobbyClient().getSnapshot().canReady());
            assertTrue(b.getLobbyClient().startAsync()); await(conditions, () -> b.getLobbyClient().getSnapshot().canReady());
            assertTrue(a.getLobbyClient().ready("Alex")); assertTrue(b.getLobbyClient().ready("Leo"));
            await(conditions, () -> a.getLobbyClient().getMatchSession() != null && b.getLobbyClient().getMatchSession() != null);
            var ownedListener = a.getNetworkClient().getListener(); var originalWorld = PlayScreen.world;
            var delegate = new SpyScreen();
            var screen = new PlayScreen(a, session -> { assertSame(a.getLobbyClient().getMatchSession(), session); return delegate; });
            assertNull(screen.playerOne); assertNull(screen.playerTwo); assertSame(originalWorld, PlayScreen.world);
            screen.show();
            for (int i = 0; i < 10; i++) { screen.checkInput(1 / 144f); screen.update(1 / 144f); screen.render(1 / 144f); }
            assertEquals(10, delegate.renders);
            assertNull(screen.playerOne); assertNull(screen.playerTwo); assertSame(originalWorld, PlayScreen.world);
            screen.resize(800, 600); screen.pause(); screen.resume(); screen.hide(); screen.dispose();
            assertEquals(1, delegate.shows); assertEquals(1, delegate.resizes); assertEquals(1, delegate.pauses);
            assertEquals(1, delegate.resumes); assertEquals(1, delegate.hides); assertEquals(1, delegate.disposes);
            assertSame(ownedListener, a.getNetworkClient().getListener()); assertTrue(a.getNetworkClient().isConnected());
        } finally {
            a.getLobbyClient().closeAndWait(5000); b.getLobbyClient().closeAndWait(5000);
            server.stop(); conditions.shutdownNow(); assertTrue(conditions.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
    private static void await(ScheduledExecutorService executor, BooleanSupplier condition) throws Exception {
        var complete = new CompletableFuture<Void>();
        var task = executor.scheduleAtFixedRate(() -> {
            try { if (condition.getAsBoolean()) { complete.complete(null); } }
            catch (Throwable t) { complete.completeExceptionally(t); }
        }, 0, 10, TimeUnit.MILLISECONDS);
        try { complete.get(6, TimeUnit.SECONDS); } finally { task.cancel(false); }
    }
    private static final class SpyScreen extends ScreenAdapter {
        int shows, renders, resizes, pauses, resumes, hides, disposes;
        @Override public void show() { shows++; }
        @Override public void render(float delta) { renders++; }
        @Override public void resize(int w, int h) { resizes++; }
        @Override public void pause() { pauses++; }
        @Override public void resume() { resumes++; }
        @Override public void hide() { hides++; }
        @Override public void dispose() { disposes++; }
    }
}
