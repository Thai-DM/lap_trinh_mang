package com.smashprofs.server.network;

import com.smashprofs.common.protocol.JsonFrameCodec;
import com.smashprofs.common.protocol.ProtocolMessage;
import java.io.IOException;
import java.io.InputStream;

/** Adapter mỏng: framing/JSON nằm trong common để client và server dùng cùng codec. */
public final class JsonTcpMessageCodec implements TcpMessageCodec {
    private final JsonFrameCodec codec = new JsonFrameCodec();
    @Override public DecodedMessage readNext(InputStream input, int maxMessageBytes) throws IOException {
        ProtocolMessage message = codec.readNext(input, maxMessageBytes);
        return message == null ? null : new DecodedMessage(message.type(), message);
    }
    @Override public byte[] encode(Object message, int maxMessageBytes) throws IOException {
        if (!(message instanceof ProtocolMessage envelope)) { throw new IOException("Expected ProtocolMessage v1"); }
        return codec.encode(envelope, maxMessageBytes);
    }
}
