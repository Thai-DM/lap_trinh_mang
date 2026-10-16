package com.smashprofs.tests.network;

import com.smashprofs.game.network.client.*;
import com.smashprofs.server.network.GameServer;
import org.junit.Test;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

public class OnlineLobbyFormModelTest {
    @Test public void invalidFieldsStayOnFormWithoutStartingConnection() {
        try (var model = new OnlineLobbyFormModel()) {
            for (String[] fields : new String[][]{{"", "54555", "demo"}, {"127.0.0.1", "bad", "demo"},
                    {"127.0.0.1", "0", "demo"}, {"127.0.0.1", "65536", "demo"},
                    {"127.0.0.1", "54555", "bad room"}, {"127. 0.0.1", "54555", "demo"}}) {
                assertFalse(model.connect(fields[0], fields[1], fields[2], LobbyConfiguration.Mode.CREATE));
                assertFalse(model.isConnecting()); assertTrue(model.hasError()); assertFalse(model.getStatus().isBlank());
            }
        }
    }

    @Test(timeout=15000) public void createAndJoinTransferRealConnectionsOnceAndKeepReadyFlow() throws Exception {
        var server = new GameServer(); server.start();
        var conditions = Executors.newSingleThreadScheduledExecutor();
        var a = new OnlineLobbyFormModel(); var b = new OnlineLobbyFormModel();
        LobbyClient[] joined = new LobbyClient[2];
        try {
            String port = Integer.toString(server.getBoundPort());
            assertTrue(a.connect(" 127.0.0.1 ", port, "form-room", LobbyConfiguration.Mode.CREATE));
            assertFalse(a.connect("127.0.0.1", port, "duplicate", LobbyConfiguration.Mode.CREATE));
            await(conditions, () -> (joined[0] = a.takeJoinedClient()) != null);
            assertNull(a.takeJoinedClient()); a.close();
            assertTrue(joined[0].getNetworkClient().isConnected());
            assertTrue(b.connect("127.0.0.1", port, "form-room", LobbyConfiguration.Mode.JOIN));
            await(conditions, () -> (joined[1] = b.takeJoinedClient()) != null);
            assertNotEquals(joined[0].getNetworkClient().getPlayerId(), joined[1].getNetworkClient().getPlayerId());
            assertEquals("form-room", joined[1].getSnapshot().roomId());
            assertTrue(joined[0].ready("Viktor")); assertTrue(joined[1].ready("Leo"));
            await(conditions, () -> joined[0].getSnapshot().isMatchStarted() && joined[1].getSnapshot().isMatchStarted());
        } finally {
            a.close(); b.close();
            for (var client : joined) { if (client != null) { client.closeAndWait(5000); } }
            server.stop(); conditions.shutdownNow(); assertTrue(conditions.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test(timeout=15000) public void serverErrorAllowsManualRetryAndCancelClosesPendingConnection() throws Exception {
        var server = new GameServer(); server.start();
        var conditions = Executors.newSingleThreadScheduledExecutor();
        var first = new OnlineLobbyFormModel(); var retry = new OnlineLobbyFormModel();
        LobbyClient[] joined = new LobbyClient[2];
        try {
            String port = Integer.toString(server.getBoundPort());
            assertTrue(first.connect("127.0.0.1", port, "existing", LobbyConfiguration.Mode.CREATE));
            await(conditions, () -> (joined[0] = first.takeJoinedClient()) != null);
            assertTrue(retry.connect("127.0.0.1", port, "existing", LobbyConfiguration.Mode.CREATE));
            await(conditions, () -> { retry.takeJoinedClient(); return retry.hasError(); });
            assertTrue(retry.getStatus().contains("Phòng đã tồn tại")); assertFalse(retry.isConnecting());
            assertTrue(retry.connect("127.0.0.1", port, "another", LobbyConfiguration.Mode.CREATE));
            await(conditions, () -> (joined[1] = retry.takeJoinedClient()) != null);
            joined[0].closeAndWait(5000); joined[1].closeAndWait(5000);
            await(conditions, () -> server.getConnectionManager().size() == 0);
            assertTrue(retry.connect("127.0.0.1", port, "cancelled", LobbyConfiguration.Mode.JOIN));
            retry.cancel(); assertFalse(retry.isConnecting()); assertNull(retry.takeJoinedClient());
        } finally {
            first.close(); retry.close();
            for (var client : joined) { if (client != null) { client.closeAndWait(5000); } }
            server.stop(); conditions.shutdownNow(); assertTrue(conditions.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
    private static void await(ScheduledExecutorService executor, BooleanSupplier condition) throws Exception {
        var done = new CompletableFuture<Void>();
        var task = executor.scheduleAtFixedRate(() -> {
            if (done.isDone()) { return; }
            try { if (condition.getAsBoolean()) { done.complete(null); } }
            catch (Throwable failure) { done.completeExceptionally(failure); }
        }, 0, 10, TimeUnit.MILLISECONDS);
        try { done.get(6, TimeUnit.SECONDS); } finally { task.cancel(false); }
    }
}
