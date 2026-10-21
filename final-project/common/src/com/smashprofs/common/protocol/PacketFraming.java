package com.smashprofs.common.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * TCP framing utility using 4-byte big-endian length prefix followed by UTF-8 payload.
 */
public final class PacketFraming {
    public static final int MAX_FRAME_LENGTH = 1_048_576; // 1 MB safety ceiling

    private PacketFraming() {
    }

    public static void writePacket(OutputStream out, NetworkPacket packet) throws IOException {
        String json = packet.toJson();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException("Payload size " + bytes.length + " exceeds maximum limit " + MAX_FRAME_LENGTH);
        }
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(bytes.length);
        dos.write(bytes);
        dos.flush();
    }

    public static NetworkPacket readPacket(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        int length = dis.readInt();
        if (length < 0 || length > MAX_FRAME_LENGTH) {
            throw new IOException("Corrupted frame: invalid length header " + length);
        }
        byte[] buffer = new byte[length];
        dis.readFully(buffer);
        String json = new String(buffer, StandardCharsets.UTF_8);
        return NetworkPacket.fromJson(json);
    }

    public static NetworkPacket readPacketOrNull(InputStream in) throws IOException {
        int firstByte = in.read();
        if (firstByte == -1) {
            return null; // Clean EOF
        }
        int b2 = in.read();
        int b3 = in.read();
        int b4 = in.read();
        if ((b2 | b3 | b4) < 0) {
            throw new EOFException("Truncated 4-byte frame length header");
        }
        int length = ((firstByte << 24) + (b2 << 16) + (b3 << 8) + (b4 << 0));
        if (length < 0 || length > MAX_FRAME_LENGTH) {
            throw new IOException("Corrupted frame: invalid length header " + length);
        }
        byte[] buffer = new byte[length];
        int totalRead = 0;
        while (totalRead < length) {
            int read = in.read(buffer, totalRead, length - totalRead);
            if (read == -1) {
                throw new EOFException("Truncated frame payload: expected " + length + " bytes, read " + totalRead);
            }
            totalRead += read;
        }
        String json = new String(buffer, StandardCharsets.UTF_8);
        return NetworkPacket.fromJson(json);
    }
}
