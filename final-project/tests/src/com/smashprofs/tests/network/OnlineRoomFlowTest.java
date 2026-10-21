package com.smashprofs.tests.network;

import com.smashprofs.game.network.client.*;
import com.smashprofs.server.network.GameServer;
import org.junit.Test;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

public class OnlineRoomFlowTest {
    private static void until(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+5_000_000_000L;
        while(!condition.getAsBoolean()) { if(System.nanoTime()>deadline) { fail("Room UI flow timed out"); }Thread.sleep(5); }
    }
    @Test(timeout=20000) public void generatedRoomSharesRosterReadinessCancellationAndHostTransferWithoutChangingRules() throws Exception {
        var server=new GameServer();server.start();String room=OnlineRoomPresentation.newRoomCode();
        assertTrue(room.matches("[A-Z2-9]{6}"));assertNotEquals("demo",room);
        var host=new LobbyClient(new LobbyConfiguration("127.0.0.1",server.getBoundPort(),room,LobbyConfiguration.Mode.CREATE));
        var peer=new LobbyClient(new LobbyConfiguration("127.0.0.1",server.getBoundPort(),room,LobbyConfiguration.Mode.JOIN));
        host.enableGameplay();peer.enableGameplay();
        try {
            assertTrue(host.startAsync());until(()->host.getSnapshot().canReady());
            assertTrue(peer.startAsync());until(()->peer.getSnapshot().canReady());
            until(()->host.getSettings()!=null && peer.getSettings()!=null && host.getSettings().members().size()==2 && peer.getSettings().members().size()==2);
            String hostId=host.getSnapshot().localPlayerId().value();long rules=peer.getSettings().rulesVersion();
            assertTrue(host.ready("Luca"));until(()->host.getSnapshot().phase()==LobbyClient.Phase.WAITING_FOR_START);
            until(()->peer.getSettings().members().stream().anyMatch(m->m.playerId().value().equals(hostId) && m.ready() && "Luca".equals(m.characterId())));
            assertEquals(rules,peer.getSettings().rulesVersion());
            assertTrue(host.unready());until(()->host.getSnapshot().canReady());
            until(()->peer.getSettings().members().stream().noneMatch(LobbyClient.Member::ready));
            assertEquals(rules,peer.getSettings().rulesVersion());assertFalse(peer.unready());
            assertTrue(host.ready("Viktor"));until(()->host.getSnapshot().phase()==LobbyClient.Phase.WAITING_FOR_START);
            until(()->peer.getSettings().members().stream().anyMatch(m->m.playerId().value().equals(hostId) && "Viktor".equals(m.characterId()) && m.ready()));
            host.closeAndWait(5000);
            until(()->peer.getSettings().members().size()==1 && peer.getSettings().hostId().equals(peer.getSnapshot().localPlayerId().value()));
            assertEquals(rules,peer.getSettings().rulesVersion());
            try { peer.getSettings().members().clear();fail("Roster must be immutable"); }catch(UnsupportedOperationException expected) { }
        } finally { host.closeAndWait(5000);peer.closeAndWait(5000);server.stop(); }
    }
}
