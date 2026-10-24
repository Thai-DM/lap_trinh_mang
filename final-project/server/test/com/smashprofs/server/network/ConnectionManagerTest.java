package com.smashprofs.server.network;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ConnectionManagerTest {

    @Test
    public void managerStartsEmpty() {
        ConnectionManager manager = new ConnectionManager();

        assertEquals(0, manager.size());
        assertFalse(manager.contains("session-1"));
    }

    @Test
    public void sessionCanBeRegistered() {
        ConnectionManager manager = new ConnectionManager();
        ClientSession session = new ClientSession("session-1", new FakeSessionChannel());

        manager.register(session);

        assertEquals(1, manager.size());
        assertSame(session, manager.get("session-1"));
    }

    @Test
    public void sessionCanBeRetrievedById() {
        ConnectionManager manager = new ConnectionManager();
        ClientSession first = new ClientSession("session-1", new FakeSessionChannel());
        ClientSession second = new ClientSession("session-2", new FakeSessionChannel());
        manager.register(first);
        manager.register(second);

        assertSame(first, manager.get("session-1"));
        assertSame(second, manager.get("session-2"));
    }

    @Test
    public void duplicateSessionIdIsRejected() {
        ConnectionManager manager = new ConnectionManager();
        ClientSession original = new ClientSession("session-1", new FakeSessionChannel());
        ClientSession duplicate = new ClientSession("session-1", new FakeSessionChannel());
        manager.register(original);

        assertThrows(IllegalArgumentException.class, () -> manager.register(duplicate));

        assertSame(original, manager.get("session-1"));
        assertEquals(1, manager.size());
    }

    @Test
    public void sessionCanBeRemoved() {
        ConnectionManager manager = new ConnectionManager();
        FakeSessionChannel channel = new FakeSessionChannel();
        ClientSession session = new ClientSession("session-1", channel);
        manager.register(session);

        assertSame(session, manager.remove("session-1"));

        assertNull(manager.get("session-1"));
        assertEquals(0, manager.size());
        assertTrue(session.isConnected());
        assertTrue(channel.isOpen());
        assertEquals(0, channel.closeCalls);
    }

    @Test
    public void containsReflectsRegistration() {
        ConnectionManager manager = new ConnectionManager();
        assertFalse(manager.contains("session-1"));

        manager.register(new ClientSession("session-1", new FakeSessionChannel()));
        assertTrue(manager.contains("session-1"));

        manager.remove("session-1");
        assertFalse(manager.contains("session-1"));
    }

    @Test
    public void sizeReflectsNumberOfSessions() {
        ConnectionManager manager = new ConnectionManager();
        manager.register(new ClientSession("session-1", new FakeSessionChannel()));
        manager.register(new ClientSession("session-2", new FakeSessionChannel()));
        assertEquals(2, manager.size());

        manager.remove("session-1");
        assertEquals(1, manager.size());
    }

    @Test
    public void closeAllClosesAllSessions() {
        ConnectionManager manager = new ConnectionManager();
        FakeSessionChannel firstChannel = new FakeSessionChannel();
        FakeSessionChannel secondChannel = new FakeSessionChannel();
        ClientSession first = new ClientSession("session-1", firstChannel);
        ClientSession second = new ClientSession("session-2", secondChannel);
        manager.register(first);
        manager.register(second);

        manager.closeAll();

        assertFalse(first.isConnected());
        assertFalse(second.isConnected());
        assertFalse(firstChannel.isOpen());
        assertFalse(secondChannel.isOpen());
        assertEquals(1, firstChannel.closeCalls);
        assertEquals(1, secondChannel.closeCalls);
    }

    @Test
    public void closeAllClearsManager() {
        ConnectionManager manager = new ConnectionManager();
        manager.register(new ClientSession("session-1", new FakeSessionChannel()));
        manager.register(new ClientSession("session-2", new FakeSessionChannel()));

        manager.closeAll();

        assertEquals(0, manager.size());
        assertFalse(manager.contains("session-1"));
        assertFalse(manager.contains("session-2"));
        manager.closeAll();
        assertEquals(0, manager.size());
    }

    @Test
    public void nullSessionIsRejected() {
        ConnectionManager manager = new ConnectionManager();

        assertThrows(NullPointerException.class, () -> manager.register(null));
        assertEquals(0, manager.size());
    }

    @Test
    public void blankSessionIdIsRejected() {
        ConnectionManager manager = new ConnectionManager();
        for (String sessionId : new String[]{"", " ", "\t\n"}) {
            ClientSession session = new ClientSession(sessionId, new FakeSessionChannel());
            assertThrows(IllegalArgumentException.class, () -> manager.register(session));
        }
        assertEquals(0, manager.size());
    }

    @Test
    public void unknownSessionReturnsNull() {
        ConnectionManager manager = new ConnectionManager();

        assertNull(manager.get("unknown"));
        assertNull(manager.remove("unknown"));
        assertFalse(manager.contains("unknown"));
    }

    @Test
    public void closeFailureDoesNotPreventOtherSessionsFromClosing() {
        ConnectionManager manager = new ConnectionManager();
        FakeSessionChannel failingChannel = new FakeSessionChannel();
        failingChannel.closeFailure = new IllegalStateException("Đóng channel bị lỗi");
        FakeSessionChannel workingChannel = new FakeSessionChannel();
        ClientSession failing = new ClientSession("session-1", failingChannel);
        ClientSession working = new ClientSession("session-2", workingChannel);
        manager.register(failing);
        manager.register(working);

        IllegalStateException failure = assertThrows(IllegalStateException.class, manager::closeAll);

        assertSame(failingChannel.closeFailure, failure.getCause());
        assertEquals(1, failingChannel.closeCalls);
        assertEquals(1, workingChannel.closeCalls);
        assertFalse(failing.isConnected());
        assertFalse(working.isConnected());
        assertFalse(workingChannel.isOpen());
        assertEquals(0, manager.size());
    }

    @Test
    public void closeAllCollectsMultipleCloseFailures() {
        ConnectionManager manager = new ConnectionManager();
        FakeSessionChannel firstChannel = new FakeSessionChannel();
        FakeSessionChannel secondChannel = new FakeSessionChannel();
        firstChannel.closeFailure = new IllegalStateException("Channel thứ nhất bị lỗi");
        secondChannel.closeFailure = new IllegalStateException("Channel thứ hai bị lỗi");
        manager.register(new ClientSession("session-1", firstChannel));
        manager.register(new ClientSession("session-2", secondChannel));

        IllegalStateException failure = assertThrows(IllegalStateException.class, manager::closeAll);

        assertEquals(1, firstChannel.closeCalls);
        assertEquals(1, secondChannel.closeCalls);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(0, manager.size());
    }

    /** Test double tối thiểu cho trạng thái đóng channel. */
    private static final class FakeSessionChannel implements SessionChannel {
        private boolean open = true;
        private int closeCalls;
        private RuntimeException closeFailure;

        @Override
        public void send(Object message) {
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
