package com.smashprofs.tests.network;

import com.smashprofs.common.protocol.MessageType;
import com.smashprofs.common.protocol.NetworkPacket;
import com.smashprofs.common.protocol.PacketFraming;
import org.junit.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class PacketFramingTest {

    private static class ChunkedInputStream extends FilterInputStream {
        private final int maxChunkSize;

        public ChunkedInputStream(InputStream in, int maxChunkSize) {
            super(in);
            this.maxChunkSize = maxChunkSize;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int toRead = Math.min(len, maxChunkSize);
            return super.read(b, off, toRead);
        }
    }

    @Test
    public void testCleanRoundTrip() throws IOException {
        NetworkPacket packet = new NetworkPacket(MessageType.PING, "client-01", null);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PacketFraming.writePacket(baos, packet);

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        NetworkPacket restored = PacketFraming.readPacket(bais);

        assertNotNull(restored);
        assertEquals(MessageType.PING, restored.getType());
        assertEquals("client-01", restored.getSenderId());
    }

    @Test
    public void testDeterministicFragmentedStreamByteByByte() throws IOException {
        NetworkPacket packet = new NetworkPacket(MessageType.JOIN_ROOM, "player-99", "{\"name\":\"Alex\"}");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PacketFraming.writePacket(baos, packet);

        ChunkedInputStream chunkedIn = new ChunkedInputStream(new ByteArrayInputStream(baos.toByteArray()), 1);
        NetworkPacket restored = PacketFraming.readPacket(chunkedIn);

        assertNotNull(restored);
        assertEquals(MessageType.JOIN_ROOM, restored.getType());
        assertEquals("player-99", restored.getSenderId());
        assertEquals("{\"name\":\"Alex\"}", restored.getPayloadJson());
    }

    @Test
    public void testMultiplePacketsConcatenatedInSameStream() throws IOException {
        NetworkPacket p1 = new NetworkPacket(MessageType.JOIN_ROOM, "p1", null);
        NetworkPacket p2 = new NetworkPacket(MessageType.PLAYER_READY, "p1", null);
        NetworkPacket p3 = new NetworkPacket(MessageType.PING, "p1", null);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PacketFraming.writePacket(baos, p1);
        PacketFraming.writePacket(baos, p2);
        PacketFraming.writePacket(baos, p3);

        ChunkedInputStream chunkedIn = new ChunkedInputStream(new ByteArrayInputStream(baos.toByteArray()), 3);

        NetworkPacket r1 = PacketFraming.readPacket(chunkedIn);
        NetworkPacket r2 = PacketFraming.readPacket(chunkedIn);
        NetworkPacket r3 = PacketFraming.readPacket(chunkedIn);

        assertEquals(MessageType.JOIN_ROOM, r1.getType());
        assertEquals(MessageType.PLAYER_READY, r2.getType());
        assertEquals(MessageType.PING, r3.getType());
    }

    @Test
    public void testNormalCleanConnectionCloseThrowsEof() {
        ByteArrayInputStream bais = new ByteArrayInputStream(new byte[0]);
        try {
            PacketFraming.readPacket(bais);
            fail("Expected EOFException on empty stream");
        } catch (EOFException e) {
            assertNotNull(e);
        } catch (IOException e) {
            fail("Expected specifically EOFException, got: " + e.getClass().getName());
        }
    }

    @Test
    public void testTruncatedHeaderEofThrowsEof() {
        byte[] truncatedHeader = new byte[]{0x00, 0x01};
        ByteArrayInputStream bais = new ByteArrayInputStream(truncatedHeader);

        try {
            PacketFraming.readPacket(bais);
            fail("Expected EOFException on truncated header");
        } catch (EOFException e) {
            assertNotNull(e);
        } catch (IOException e) {
            fail("Expected specifically EOFException, got: " + e.getClass().getName());
        }
    }

    @Test
    public void testTruncatedPayloadEofThrowsEof() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(20);
        out.write("12345".getBytes(StandardCharsets.UTF_8));

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());

        try {
            PacketFraming.readPacket(bais);
            fail("Expected EOFException on truncated payload");
        } catch (EOFException e) {
            assertNotNull(e);
        } catch (IOException e) {
            fail("Expected specifically EOFException, got: " + e.getClass().getName());
        }
    }

    @Test
    public void testNegativeLengthThrowsInvalidPacketLength() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(-1);

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());

        try {
            PacketFraming.readPacket(bais);
            fail("Expected IOException for negative length");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("invalid length header"));
        }
    }

    @Test
    public void testOversizedLengthThrowsInvalidPacketLength() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(15 * 1024 * 1024);

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());

        try {
            PacketFraming.readPacket(bais);
            fail("Expected IOException for oversized packet length");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("invalid length header"));
        }
    }

    @Test
    public void testReadPacketOrNullReturnsNullOnCleanEof() throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(new byte[0]);
        NetworkPacket packet = PacketFraming.readPacketOrNull(bais);
        assertNull("readPacketOrNull must return null on clean EOF at frame boundary", packet);
    }

    @Test
    public void testReadPacketOrNullThrowsEofOnTruncatedHeader() throws IOException {
        byte[] twoBytes = new byte[]{0x00, 0x01};
        ByteArrayInputStream bais = new ByteArrayInputStream(twoBytes);

        try {
            PacketFraming.readPacketOrNull(bais);
            fail("Expected EOFException on truncated header");
        } catch (EOFException e) {
            assertTrue(e.getMessage().contains("Truncated 4-byte frame length header"));
        }
    }

    @Test
    public void testReadPacketOrNullReadsValidPacket() throws IOException {
        NetworkPacket packet = new NetworkPacket(MessageType.PING, "c1", null);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PacketFraming.writePacket(baos, packet);

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        NetworkPacket restored = PacketFraming.readPacketOrNull(bais);

        assertNotNull(restored);
        assertEquals(MessageType.PING, restored.getType());
    }
}
