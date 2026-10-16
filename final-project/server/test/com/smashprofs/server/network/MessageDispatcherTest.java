package com.smashprofs.server.network;

import com.smashprofs.common.protocol.MessageType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MessageDispatcherTest {
    private final FakeSessionChannel channel = new FakeSessionChannel();
    private final ClientSession session = new ClientSession("session-1", channel);
    private final RecordingHandler handler = new RecordingHandler();
    private final MessageDispatcher dispatcher = new MessageDispatcher(handler);

    @Test
    public void nullSessionIsRejected() {
        assertRejected(null, MessageType.JOIN_ROOM);
    }

    @Test
    public void disconnectedSessionIsRejected() {
        session.markDisconnected();

        assertRejected(session, MessageType.PLAYER_INPUT);
    }

    @Test
    public void nullMessageTypeIsRejected() {
        assertRejected(session, null);
    }

    @Test
    public void joinRoomIsAccepted() {
        assertAccepted(MessageType.JOIN_ROOM);
    }

    @Test
    public void playerReadyIsAccepted() {
        assertAccepted(MessageType.PLAYER_READY);
    }

    @Test
    public void playerInputIsAccepted() {
        assertAccepted(MessageType.PLAYER_INPUT);
    }

    @Test
    public void gameStateInboundIsRejected() {
        assertRejected(session, MessageType.GAME_STATE);
    }

    @Test
    public void gameEndInboundIsRejected() {
        assertRejected(session, MessageType.GAME_END);
    }

    @Test
    public void rankingUpdatedInboundIsRejected() {
        assertRejected(session, MessageType.RANKING_UPDATED);
    }

    @Test
    public void validMessageIsForwardedExactlyOnce() {
        Object payload = new Object();

        assertTrue(dispatcher.dispatch(session, MessageType.PLAYER_INPUT, payload));

        assertEquals(1, handler.calls);
        assertSame(session, handler.lastSession);
        assertSame(MessageType.PLAYER_INPUT, handler.lastType);
        assertSame(payload, handler.lastPayload);
        assertEquals(0, channel.sendCalls);
    }

    @Test
    public void rejectedMessageDoesNotReachHandler() {
        for (MessageType type : MessageType.values()) {
            if (type != MessageType.JOIN_ROOM
                    && type != MessageType.PLAYER_READY
                    && type != MessageType.PLAYER_INPUT) {
                assertRejected(session, type);
            }
        }
    }

    @Test
    public void pingIsNotForwardedToRuntimeHandler() {
        assertRejected(session, MessageType.PING);
    }

    @Test
    public void pongIsNotForwardedToRuntimeHandler() {
        assertRejected(session, MessageType.PONG);
    }

    @Test
    public void matchResultInboundIsRejected() {
        assertRejected(session, MessageType.MATCH_RESULT);
    }

    @Test
    public void errorInboundIsRejected() {
        assertRejected(session, MessageType.ERROR);
    }

    @Test
    public void nullHandlerIsRejected() {
        assertThrows(NullPointerException.class, () -> new MessageDispatcher(null));
    }

    @Test
    public void payloadIsForwardedWithoutContentValidation() {
        assertTrue(dispatcher.dispatch(session, MessageType.JOIN_ROOM, null));

        assertEquals(1, handler.calls);
        assertNull(handler.lastPayload);
    }

    private void assertAccepted(MessageType type) {
        Object payload = new Object();

        assertTrue(dispatcher.dispatch(session, type, payload));

        assertEquals(1, handler.calls);
        assertSame(session, handler.lastSession);
        assertSame(type, handler.lastType);
        assertSame(payload, handler.lastPayload);
        assertEquals(0, channel.sendCalls);
    }

    private void assertRejected(ClientSession incomingSession, MessageType type) {
        assertFalse(dispatcher.dispatch(incomingSession, type, new Object()));

        assertEquals(0, handler.calls);
        assertEquals(0, channel.sendCalls);
    }

    /** Ghi nhận các message được chuyển tới handler, không xử lý nghiệp vụ. */
    private static final class RecordingHandler implements MessageDispatcher.InboundHandler {
        private int calls;
        private ClientSession lastSession;
        private MessageType lastType;
        private Object lastPayload;

        @Override
        public void handle(ClientSession session, MessageType type, Object payload) {
            calls++;
            lastSession = session;
            lastType = type;
            lastPayload = payload;
        }
    }

    /** Test double tối thiểu để xác nhận dispatcher không gửi ERROR qua channel. */
    private static final class FakeSessionChannel implements SessionChannel {
        private boolean open = true;
        private int sendCalls;

        @Override
        public void send(Object message) {
            sendCalls++;
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }
}
