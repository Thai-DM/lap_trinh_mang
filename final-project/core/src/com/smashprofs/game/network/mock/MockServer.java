package com.smashprofs.game.network.mock;

import com.smashprofs.common.model.GameSnapshot;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.common.model.PlayerState;
import com.smashprofs.common.protocol.MessageType;
import com.smashprofs.common.protocol.NetworkPacket;
import com.smashprofs.common.protocol.PacketFraming;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight mock game server running on loopback for independent client testing & desktop demo (--mock).
 * Broadcasts GameSnapshot packets at ~30Hz and processes incoming client inputs and pings.
 */
public class MockServer implements AutoCloseable {

    private final String host;
    private final int requestedPort;
    private ServerSocket serverSocket;
    private int boundPort = -1;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread acceptThread;
    private Thread broadcastThread;

    private final List<ClientHandler> clients = new CopyOnWriteArrayList<>();

    private final PlayerId p1Id = new PlayerId("player-1");
    private final PlayerId p2Id = new PlayerId("player-2");

    // Simulation state
    private long serverTick = 0;
    private volatile float p1X = 4.5f;
    private volatile float p1Y = 0.85f;
    private volatile float p1Vx = 0f;
    private volatile float p1Vy = 0f;
    private volatile boolean p1FacingRight = true;
    private volatile boolean p1Punching = false;

    // Remote mock opponent (player-2) standing still on the arena platform
    private volatile float p2X = 6.2f;
    private volatile float p2Y = 1.25f;
    private volatile float p2Vx = 0.0f;
    private volatile float p2Vy = 0.0f;
    private volatile boolean p2FacingRight = false;
    private volatile boolean p2Punching = false;
    private volatile boolean botMoving = false;

    private final float patrolLeft = 5.3f;
    private final float patrolRight = 6.4f;

    public MockServer() {
        this("127.0.0.1", 0);
    }

    public MockServer(int port) {
        this("127.0.0.1", port);
    }

    public MockServer(String host, int port) {
        this.host = (host == null || host.isBlank()) ? "127.0.0.1" : host;
        this.requestedPort = port;
    }

    /**
     * Starts the mock server listener and simulation broadcast loop.
     */
    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(host, requestedPort));
        boundPort = serverSocket.getLocalPort();
        running.set(true);

        // Accept thread
        acceptThread = new Thread(this::acceptLoop, "MockServer-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        // 30Hz broadcast simulation thread
        broadcastThread = new Thread(this::broadcastLoop, "MockServer-Broadcast-30Hz");
        broadcastThread.setDaemon(true);
        broadcastThread.start();
    }

    public int getPort() {
        return boundPort;
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getConnectedClientCount() {
        return clients.size();
    }

    public long getServerTick() {
        return serverTick;
    }

    public boolean isBotMoving() {
        return botMoving;
    }

    public void setBotMoving(boolean botMoving) {
        this.botMoving = botMoving;
    }

    private void acceptLoop() {
        while (running.get() && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                ClientHandler handler = new ClientHandler(socket);
                clients.add(handler);
                handler.start();
            } catch (SocketException e) {
                // ServerSocket closed during stop()
                break;
            } catch (IOException e) {
                if (!running.get()) {
                    break;
                }
            }
        }
    }

    private void broadcastLoop() {
        final long tickIntervalMs = 33; // ~30.3 Hz

        while (running.get()) {
            long startTime = System.currentTimeMillis();

            serverTick++;

            if (botMoving) {
                float dt = 1.0f / 30.0f;
                p2X += p2Vx * dt;
                if (p2X <= patrolLeft) {
                    p2X = patrolLeft;
                    p2Vx = 1.0f;
                    p2FacingRight = true;
                } else if (p2X >= patrolRight) {
                    p2X = patrolRight;
                    p2Vx = -1.0f;
                    p2FacingRight = false;
                }

                // Remote bot punches periodically (e.g. for 5 ticks every 60 ticks)
                long cycle = serverTick % 60;
                p2Punching = (cycle >= 25 && cycle <= 30);
            } else {
                p2Vx = 0.0f;
                p2Vy = 0.0f;
                p2FacingRight = false; // face left towards Alex
                p2Punching = false;
            }

            // Create current snapshot
            List<PlayerState> states = new ArrayList<>(2);
            states.add(new PlayerState(p1Id, "Alex", p1X, p1Y, p1Vx, p1Vy, 100, true, false, false, p1FacingRight));
            states.add(new PlayerState(p2Id, "Maurice", p2X, p2Y, p2Vx, p2Vy, 100, true, false, false, p2FacingRight));

            GameSnapshot snapshot = new GameSnapshot("mock-room", serverTick, "PLAYING", 60000L, states);
            NetworkPacket packet = NetworkPacket.of(MessageType.GAME_STATE, "server", snapshot);

            // Broadcast to all active clients
            for (ClientHandler client : clients) {
                if (client.isOpen()) {
                    client.send(packet);
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            long sleepTime = tickIntervalMs - elapsed;
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Cleanly stops the server, closing ServerSocket, client handlers, and stopping worker threads.
     */
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        // Close server socket to unblock accept()
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }

        // Close all clients
        for (ClientHandler client : clients) {
            client.close();
        }
        clients.clear();

        // Interrupt threads
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        if (broadcastThread != null) {
            broadcastThread.interrupt();
        }

        try {
            if (acceptThread != null) {
                acceptThread.join(500);
            }
            if (broadcastThread != null) {
                broadcastThread.join(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Handles an individual connected client session.
     */
    private class ClientHandler {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final AtomicBoolean open = new AtomicBoolean(true);
        private Thread readThread;

        public ClientHandler(Socket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }

        public void start() {
            readThread = new Thread(this::readLoop, "MockServer-Client-" + socket.getPort());
            readThread.setDaemon(true);
            readThread.start();
        }

        public boolean isOpen() {
            return open.get() && !socket.isClosed();
        }

        public synchronized void send(NetworkPacket packet) {
            if (!isOpen()) return;
            try {
                PacketFraming.writePacket(out, packet);
            } catch (IOException e) {
                close();
            }
        }

        private void readLoop() {
            try {
                while (running.get() && isOpen()) {
                    NetworkPacket packet = PacketFraming.readPacketOrNull(in);
                    if (packet == null) {
                        // EOF
                        break;
                    }
                    handlePacket(packet);
                }
            } catch (IOException ignored) {
            } finally {
                close();
            }
        }

        private void handlePacket(NetworkPacket packet) {
            if (packet == null || packet.getType() == null) return;

            switch (packet.getType()) {
                case PING:
                    send(new NetworkPacket(MessageType.PONG, "server", packet.getPayloadJson()));
                    break;

                case JOIN_ROOM:
                    // Acknowledge join by replying with PLAYER_READY
                    send(new NetworkPacket(MessageType.PLAYER_READY, "server", "{\"status\":\"JOINED\"}"));
                    break;

                case PLAYER_INPUT:
                    PlayerInput input = packet.parsePayload(PlayerInput.class);
                    if (input != null) {
                        // Echo/integrate input into Player 1 simulation
                        p1Vx = input.moveAxis() * 2.0f;
                        if (input.moveAxis() > 0.05f) {
                            p1FacingRight = true;
                        } else if (input.moveAxis() < -0.05f) {
                            p1FacingRight = false;
                        }
                        p1Punching = input.meleePressed();
                    }
                    break;

                default:
                    break;
            }
        }

        public void close() {
            if (!open.compareAndSet(true, false)) return;
            clients.remove(this);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            if (readThread != null) {
                readThread.interrupt();
            }
        }
    }
}
