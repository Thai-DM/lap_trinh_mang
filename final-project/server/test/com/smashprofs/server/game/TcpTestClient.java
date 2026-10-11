package com.smashprofs.server.game;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.common.protocol.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import static org.junit.Assert.*;

/** Client chỉ dành cho test: TCP/codec thật, không reader thread hoặc protocol thay thế. */
final class TcpTestClient implements AutoCloseable {
    private static final int TIMEOUT_MS = 5000;
    private final Socket socket = new Socket();
    private final JsonFrameCodec codec = new JsonFrameCodec();
    private final List<ProtocolMessage> received = new ArrayList<>();
    private final List<ProtocolMessage> pending = new ArrayList<>();
    private int requestSequence;
    private PlayerId playerId;
    private boolean autoPong = true;
    void setAutoPong(boolean enabled) { autoPong = enabled; }
    private boolean replyToPing(ProtocolMessage message) throws IOException {
        if (!autoPong || message.type() != MessageType.PING) { return false; }
        assertTrue(message.payload().isEmpty()); assertNotNull(message.requestId());
        send(new ProtocolMessage(MessageType.PONG, message.requestId(), ProtocolV1.object())); return true;
    }

    TcpTestClient(int port) throws IOException {
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
        } catch (IOException failure) { socket.close(); throw failure; }
    }
    PlayerId playerId() { return playerId; }
    List<ProtocolMessage> received() { return List.copyOf(received); }

    void connect() throws IOException {
        var ack = request(MessageType.CONNECT, ProtocolV1.object());
        assertEquals(MessageType.CONNECT_ACK, ack.type()); assertEquals(1, ack.version());
        playerId = new PlayerId(ack.payload().get("playerId").textValue());
    }
    ProtocolMessage request(MessageType type, ObjectNode payload) throws IOException {
        String requestId = "request-" + requestSequence++;
        send(new ProtocolMessage(type, requestId, payload));
        return await(message -> requestId.equals(message.requestId()));
    }
    void send(ProtocolMessage message) throws IOException {
        socket.getOutputStream().write(codec.encode(message)); socket.getOutputStream().flush();
    }
    ObjectNode input(long sequence, float axis, boolean jump, boolean stomp, boolean melee) {
        return ProtocolV1.inputPayload(new PlayerInput(playerId, sequence, 0, axis, jump, stomp, melee, false, false));
    }
    void inputAndBarrier(long sequence, float axis, boolean jump, boolean stomp, boolean melee) throws IOException {
        send(new ProtocolMessage(MessageType.PLAYER_INPUT, null, input(sequence, axis, jump, stomp, melee)));
        barrier();
    }
    /** CONNECT lặp là barrier đọc tuần tự của cùng TCP stream, KHÔNG phải input success ACK mới. */
    void barrier() throws IOException {
        var ack = request(MessageType.CONNECT, ProtocolV1.object());
        assertEquals(MessageType.CONNECT_ACK, ack.type()); assertEquals(playerId.value(), ack.payload().get("playerId").textValue());
    }
    ProtocolMessage awaitType(MessageType type) throws IOException { return await(message -> message.type() == type); }
    private ProtocolMessage await(Predicate<ProtocolMessage> predicate) throws IOException {
        for (int i = 0; i < pending.size(); i++) { if (predicate.test(pending.get(i))) { return pending.remove(i); } }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) { throw new SocketTimeoutException("Hết deadline chờ message phù hợp"); }
            socket.setSoTimeout((int) Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)));
            ProtocolMessage message = codec.readNext(socket.getInputStream());
            assertNotNull("EOF trước message mong đợi", message); received.add(message);
            if (predicate.test(message)) { return message; }
            if (!replyToPing(message)) { pending.add(message); }
        }
    }
    /** Chỉ kiểm tra cửa sổ im lặng hữu hạn; dữ liệu đã buffer cũng phải được xử lý trước. */
    void assertQuiet() throws IOException {
        assertTrue("Còn message chưa được assert: " + pending, pending.isEmpty());
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(150);
        try {
            while (System.nanoTime() < deadline) {
                socket.setSoTimeout((int) Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
                try {
                    var message = codec.readNext(socket.getInputStream()); assertNotNull(message); received.add(message);
                    assertTrue("Message không mong đợi: " + message, replyToPing(message));
                } catch (SocketTimeoutException quiet) { return; }
            }
        }
        finally { socket.setSoTimeout(TIMEOUT_MS); }
    }
    void assertEof() throws IOException {
        assertTrue(pending.isEmpty()); socket.setSoTimeout(TIMEOUT_MS);
        assertEquals(-1, socket.getInputStream().read());
    }
    @Override public void close() throws IOException { socket.close(); }
}
