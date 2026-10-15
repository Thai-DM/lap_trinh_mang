package com.smashprofs.server.network;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sở hữu socket accepted và khóa ghi bytes; không tự encode/frame hoặc đọc message. */
final class TcpSessionChannel implements SessionChannel {
    private final Socket socket;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object writeLock = new Object();

    TcpSessionChannel(Socket socket) {
        this.socket = Objects.requireNonNull(socket, "socket không được null");
    }

    @Override public void send(Object message) {
        if (!isOpen()) { throw new IllegalStateException("Kênh TCP đã đóng"); }
        // Message encoding thuộc boundary TcpClientConnection sau khi Member 3 cung cấp codec được duyệt.
        throw new UnsupportedOperationException("Kênh raw TCP không tự encode/frame Object");
    }

    /** Chỉ nhận bytes đã encode/frame bên ngoài; không đặt delimiter/header, không queue không giới hạn. */
    void writeEncodedBytes(byte[] bytes, int maxBytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes không được null");
        if (maxBytes <= 0 || bytes.length > maxBytes) { throw new IOException("Encoded bytes vượt limit"); }
        byte[] ownedBytes = bytes.clone();
        synchronized (writeLock) {
            if (!isOpen()) { throw new IOException("TCP socket đã đóng"); }
            try {
                socket.getOutputStream().write(ownedBytes);
                socket.getOutputStream().flush();
            } catch (IOException failure) {
                try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
    }

    /** Trusted immutable holder: no byte[] reference escapes and no per-recipient payload clone. */
    void writeSharedFrame(SharedSnapshotCache.Frame frame,int maxBytes) throws IOException {
        if(frame.size()>maxBytes) { throw new IOException("Encoded bytes exceed connection limit"); }
        synchronized(writeLock) {
            if(!isOpen()) { throw new IOException("TCP socket closed"); }
            try { frame.writeTo(socket.getOutputStream()); socket.getOutputStream().flush(); }
            catch(IOException failure) { try { close(); } catch(RuntimeException cleanup) { failure.addSuppressed(cleanup); } throw failure; }
        }
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            try { socket.close(); }
            catch (IOException failure) { throw new UncheckedIOException("Không đóng được TCP socket", failure); }
        }
    }

    /** Chỉ trạng thái local; chưa đọc EOF nên không khẳng định peer vẫn còn online. */
    @Override public boolean isOpen() { return !closed.get() && !socket.isClosed(); }
}
