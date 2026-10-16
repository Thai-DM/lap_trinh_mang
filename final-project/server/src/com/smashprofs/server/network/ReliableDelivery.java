package com.smashprofs.server.network;

import com.smashprofs.common.protocol.*;
import com.smashprofs.server.metrics.PerformanceMetrics;
import java.io.*;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** One reliable immutable control/event; a shared frame encodes once on the first writer. */
public record ReliableDelivery(Message frame, BooleanSupplier stillValid) {
    public ReliableDelivery { Objects.requireNonNull(frame);Objects.requireNonNull(stillValid); }
    public static final class Message implements SharedFrame {
        private final ProtocolMessage message;
        private byte[] bytes;
        public Message(ProtocolMessage message) { this.message=Objects.requireNonNull(message); }
        ProtocolMessage message() { return message; }
        synchronized void encode(TcpMessageCodec codec,PerformanceMetrics metrics) throws IOException {
            if(bytes!=null) { return; }
            long started=System.nanoTime();bytes=codec.encode(message,ProtocolV1.MAX_FRAME_BYTES);
            metrics.record(PerformanceMetrics.Stage.JSON_SERIALIZATION,System.nanoTime()-started);
        }
        @Override public synchronized int size() { if(bytes==null) { throw new IllegalStateException("Not encoded"); }return bytes.length; }
        @Override public void writeTo(OutputStream output) throws IOException {
            byte[] owned;synchronized(this) { if(bytes==null) { throw new IllegalStateException("Not encoded"); }owned=bytes; }
            output.write(owned);
        }
    }
}
