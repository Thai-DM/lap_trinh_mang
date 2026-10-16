package com.smashprofs.server.network;

import com.smashprofs.common.model.PlayerId;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ClientSessionTest {

    @Test
    public void newSessionIsConnected() {
        ClientSession session = new ClientSession("session-1", new FakeSessionChannel());

        assertTrue(session.isConnected());
        assertEquals("session-1", session.getSessionId());
        assertNull(session.getPlayerId());
        assertNull(session.getRoomId());
    }

    @Test
    public void playerCanBeBound() {
        ClientSession session = new ClientSession("session-1", new FakeSessionChannel());
        PlayerId playerId = new PlayerId("player-1");

        session.bindPlayer(playerId);

        assertEquals(playerId, session.getPlayerId());
    }

    @Test
    public void sessionCanJoinRoom() {
        ClientSession session = new ClientSession("session-1", new FakeSessionChannel());

        session.joinRoom("room-1");

        assertEquals("room-1", session.getRoomId());
    }

    @Test
    public void sessionCanLeaveRoom() {
        ClientSession session = new ClientSession("session-1", new FakeSessionChannel());
        session.joinRoom("room-1");

        session.leaveRoom();

        assertNull(session.getRoomId());
    }

    @Test
    public void markDisconnectedChangesState() {
        FakeSessionChannel channel = new FakeSessionChannel();
        ClientSession session = new ClientSession("session-1", channel);

        session.markDisconnected();

        assertFalse(session.isConnected());
        assertTrue(channel.isOpen());
        assertEquals(0, channel.closeCalls);
    }

    @Test
    public void sendDelegatesToChannel() {
        FakeSessionChannel channel = new FakeSessionChannel();
        ClientSession session = new ClientSession("session-1", channel);
        Object message = new Object();

        session.send(message);

        assertSame(message, channel.lastMessage);
        assertEquals(1, channel.sendCalls);
    }

    @Test
    public void closeClosesChannelAndMarksDisconnected() {
        FakeSessionChannel channel = new FakeSessionChannel();
        ClientSession session = new ClientSession("session-1", channel);

        session.close();

        assertEquals(1, channel.closeCalls);
        assertFalse(channel.isOpen());
        assertFalse(session.isConnected());
    }

    @Test
    public void nullSessionIdIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new ClientSession(null, new FakeSessionChannel()));
    }

    @Test
    public void nullChannelIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new ClientSession("session-1", null));
    }

    @Test
    public void closeFailureStillMarksDisconnected() {
        FakeSessionChannel channel = new FakeSessionChannel();
        channel.closeFailure = new IllegalStateException("Không thể đóng channel");
        ClientSession session = new ClientSession("session-1", channel);

        IllegalStateException failure = assertThrows(IllegalStateException.class, session::close);

        assertSame(channel.closeFailure, failure);
        assertEquals(1, channel.closeCalls);
        assertFalse(session.isConnected());
    }

    @Test
    public void openSessionRequiresConnectedAndOpenChannel() {
        FakeSessionChannel channel = new FakeSessionChannel();
        ClientSession session = new ClientSession("session-1", channel);
        assertTrue(session.isOpen());
        channel.close();
        assertTrue(session.isConnected());
        assertFalse(session.isOpen());
    }

    @Test
    public void disconnectedSessionIsNotOpenEvenIfChannelIsOpen() {
        FakeSessionChannel channel = new FakeSessionChannel();
        ClientSession session = new ClientSession("session-1", channel);
        session.markDisconnected();
        assertTrue(channel.isOpen());
        assertFalse(session.isOpen());
    }

    @Test
    public void closeMarksDisconnectedBeforeCallingChannel() {
        ClientSession[] holder = new ClientSession[1];
        SessionChannel channel = new SessionChannel() {
            @Override
            public void send(Object message) { }

            @Override
            public void close() {
                assertFalse(holder[0].isConnected());
                assertFalse(holder[0].isOpen());
                assertFalse(Thread.holdsLock(holder[0]));
            }

            @Override
            public boolean isOpen() {
                return true;
            }
        };
        holder[0] = new ClientSession("session-1", channel);
        holder[0].close();
    }

    /** Test double tối thiểu để kiểm tra delegation, không có socket. */
    private static final class FakeSessionChannel implements SessionChannel {
        private Object lastMessage;
        private int sendCalls;
        private int closeCalls;
        private boolean open = true;
        private RuntimeException closeFailure;

        @Override
        public void send(Object message) {
            lastMessage = message;
            sendCalls++;
        }

        @Override
        public void close() {
            closeCalls++;
            if (closeFailure != null) {
                throw closeFailure;
            }
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }
}
