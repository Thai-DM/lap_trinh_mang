package com.smashprofs.tests.tools;

import com.smashprofs.common.protocol.*;
import com.smashprofs.game.network.client.*;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real launcher process/socket tests. Same-machine interfaces do not prove two-machine LAN. */
public class LocalGameServerTest {
    @Test public void configurationKeepsLoopbackDefaultAndRequiresExplicitHost() {
        assertEquals(new LocalGameServer.Configuration(54555, "127.0.0.1"),
                LocalGameServer.parseArguments(new String[0]));
        assertEquals(new LocalGameServer.Configuration(0, "127.0.0.1"),
                LocalGameServer.parseArguments(new String[]{"0"}));
        assertEquals(new LocalGameServer.Configuration(54555, "0.0.0.0"),
                LocalGameServer.parseArguments(new String[]{"54555", "0.0.0.0"}));
        for (String[] invalid : List.of(new String[]{"-1"}, new String[]{"65536"},
                new String[]{"invalid"}, new String[]{"0", " "}, new String[]{"0", " localhost"},
                new String[]{"0", "localhost", "extra"})) {
            assertThrows(IllegalArgumentException.class, () -> LocalGameServer.parseArguments(invalid));
        }
    }

    @Test(timeout=20000) public void defaultLauncherConnectsAndStopCommandReleasesListener() throws Exception {
        try (var launcher = new Launcher("127.0.0.1", false)) {
            var probe = new Probe("127.0.0.1", launcher.port);
            try {
                probe.connect();
                assertNotNull(probe.client.getPlayerId());
                assertTrue(probe.client.sendMessage(new ProtocolMessage(MessageType.PING, "manual-ping", ProtocolV1.object())));
                assertEquals("manual-ping", probe.next(MessageType.PONG).requestId());
            } finally { probe.client.disconnectAndWait(3000); }
            launcher.stop();
            assertThrows(IOException.class, () -> {
                try (var socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", launcher.port), 1000); }
            });
        }
    }

    @Test(timeout=40000) public void wildcardLauncherServesTwoClientsAndIdleHeartbeatOnLocalInterface() throws Exception {
        // Prefer a real IPv4 interface; loopback fallback is reported, not passed off as LAN evidence.
        String configuredHost = System.getenv("SMASHPROFS_SMOKE_HOST");
        String connectHost = configuredHost == null || configuredHost.isBlank() ? "127.0.0.1" : configuredHost;
        for (var network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (configuredHost != null && !configuredHost.isBlank()) { break; }
            if (!network.isUp() || network.isLoopback()) { continue; }
            for (var address : Collections.list(network.getInetAddresses())) {
                if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                    connectHost = address.getHostAddress(); break;
                }
            }
            if (!connectHost.equals("127.0.0.1")) { break; }
        }
        System.out.println("LAN readiness SAME-MACHINE ONLY: bind=0.0.0.0 connectHost=" + connectHost);
        try (var launcher = new Launcher("0.0.0.0", true)) {
            var a = new Probe(connectHost, launcher.port);
            var b = new Probe(connectHost, launcher.port);
            try {
                a.connect(); b.connect();
                assertNotEquals(a.client.getPlayerId(), b.client.getPlayerId());
                a.next(MessageType.PING); b.next(MessageType.PING);
                a.next(MessageType.PING); b.next(MessageType.PING);
                assertTrue(a.client.isConnected()); assertTrue(b.client.isConnected());
                a.client.disconnectAndWait(3000);
                assertTrue(b.client.sendMessage(new ProtocolMessage(MessageType.PING, "survivor", ProtocolV1.object())));
                assertEquals("survivor", b.next(MessageType.PONG).requestId());
            } finally { a.client.disconnectAndWait(3000); b.client.disconnectAndWait(3000); }
            launcher.stop();
        }
    }

    private static final class Probe implements ConnectionListener {
        final BlockingQueue<ProtocolMessage> messages = new LinkedBlockingQueue<>();
        final NetworkClient client;
        Probe(String host, int port) { client = new NetworkClient(host, port, this); }
        void connect() throws Exception { client.connect(); assertEquals(1, next(MessageType.CONNECT_ACK).version()); }
        ProtocolMessage next(MessageType type) throws Exception {
            // Default maintenance is every 5 s; the first eligible probe may be the 10 s pass.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
            while (true) {
                var message = messages.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                assertNotNull("Timed out awaiting " + type, message);
                assertNotEquals("Unexpected ERROR " + message, MessageType.ERROR, message.type());
                if (message.type() == type) { return message; }
            }
        }
        @Override public void onConnected() { }
        @Override public void onPacketReceived(NetworkPacket packet) { }
        @Override public void onProtocolMessageReceived(ProtocolMessage message) { messages.offer(message); }
        @Override public void onDisconnected(String reason) { }
    }

    private static final class Launcher implements AutoCloseable {
        final Process process;
        final ExecutorService outputReader = Executors.newSingleThreadExecutor();
        final int port;
        Launcher(String bindHost, boolean explicitHost) throws Exception {
            var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), LocalGameServer.class.getName(), "0"));
            if (explicitHost) { command.add(bindHost); }
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            try {
                String line = outputReader.submit(() -> new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8)).readLine()).get(8, TimeUnit.SECONDS);
                String prefix = "Local GameServer listening on " + bindHost + ":";
                assertNotNull("Launcher exited before ready", line);
                assertTrue("Unexpected startup: " + line, line.startsWith(prefix));
                port = Integer.parseInt(line.substring(prefix.length(), line.indexOf(' ', prefix.length())));
                assertTrue(port > 0);
            } catch (Exception | AssertionError failure) { close(); throw failure; }
        }
        void stop() throws Exception {
            process.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            assertTrue("Graceful shutdown timed out", process.waitFor(5, TimeUnit.SECONDS));
            assertEquals("Launcher must exit successfully", 0, process.exitValue());
        }
        @Override public void close() throws Exception {
            if (process.isAlive()) { process.destroy(); }
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
            outputReader.shutdownNow();
            assertTrue("Output reader must stop", outputReader.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
