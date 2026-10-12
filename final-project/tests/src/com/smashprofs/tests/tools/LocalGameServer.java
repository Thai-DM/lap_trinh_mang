package com.smashprofs.tests.tools;

import com.smashprofs.server.network.GameServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;

/** Local manual desktop smoke helper; reuses GameServer's public API, changes no server runtime. */
public final class LocalGameServer {
    private LocalGameServer() { }
    record Configuration(int port, String bindHost) { }

    static Configuration parseArguments(String[] args) {
        if (args.length > 2) { throw new IllegalArgumentException("Usage: LocalGameServer [port] [bindHost]"); }
        int port = args.length == 0 ? 54555 : Integer.parseInt(args[0]);
        String host = args.length < 2 ? "127.0.0.1" : args[1];
        if (port < 0 || port > 65535 || host.isBlank() || !host.equals(host.strip())) {
            throw new IllegalArgumentException("Port must be 0..65535; bindHost must be nonblank without surrounding spaces");
        }
        return new Configuration(port, host);
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        var configuration = parseArguments(args);
        String host = InetAddress.getByName(configuration.bindHost()).getHostAddress();
        var server = new GameServer(host, configuration.port(), 8);
        var shutdown = new Thread(server::stop, "local-server-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            server.start();
            System.out.println("Local GameServer listening on " + host + ":" + server.getBoundPort()
                    + " (configured host=" + configuration.bindHost() + "; type stop + Enter or Ctrl+C to stop)");
            var console = new BufferedReader(new InputStreamReader(System.in));
            String command;
            while ((command = console.readLine()) != null) {
                if ("stop".equalsIgnoreCase(command.strip())) { return; }
            }
            // A background/redirected launch with no stdin must stay alive until process shutdown.
            new CountDownLatch(1).await();
        } finally {
            server.stop();
            try { Runtime.getRuntime().removeShutdownHook(shutdown); }
            catch (IllegalStateException shuttingDown) { /* The shutdown hook is already running. */ }
        }
    }
}
