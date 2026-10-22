package com.smashprofs.probe;

import com.smashprofs.server.network.GameServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import org.java_websocket.*;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/** Bounded loopback-only experiment: one WebSocket maps to one existing authenticated TCP session. */
public final class ProbeGateway extends WebSocketServer implements AutoCloseable {
    private static final int MAX_FRAME=1_048_576,MAX_CLIENTS=4;
    private final GameServer game;
    private final Map<WebSocket,Socket> peers=new ConcurrentHashMap<>();
    private final ExecutorService readers=Executors.newFixedThreadPool(MAX_CLIENTS);
    private final CountDownLatch done=new CountDownLatch(1);
    public ProbeGateway(int port) {
        super(new InetSocketAddress("127.0.0.1",port),2);
        game=new GameServer();game.start();setConnectionLostTimeout(15);
    }
    @Override public synchronized void onOpen(WebSocket browser,ClientHandshake handshake) {
        String origin=handshake.getFieldValue("Origin");
        if(!origin.matches("http://(127\\.0\\.0\\.1|localhost):[0-9]+") || peers.size()>=MAX_CLIENTS) {
            browser.close(1008,"Local browser trial only; maximum four clients");return;
        }
        Socket socket=new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1",game.getBoundPort()),2000);socket.setTcpNoDelay(true);
            peers.put(browser,socket);readers.execute(()->relay(browser,socket));
        } catch(Exception failure) {
            try { socket.close(); }catch(IOException ignored) { }
            browser.close(1011,"TCP connection failed");
        }
    }
    private void relay(WebSocket browser,Socket socket) {
        try {
            var input=new DataInputStream(socket.getInputStream());
            while(!socket.isClosed() && browser.isOpen()) {
                int length=input.readInt();if(length<1 || length>MAX_FRAME) { throw new IOException("Frame bound"); }
                byte[] payload=new byte[length];input.readFully(payload);
                if(browser instanceof WebSocketImpl impl && (impl.outQueue.size()>64 || impl.outQueue.stream().mapToLong(b->b.remaining()).sum()>MAX_FRAME)) {
                    browser.close(1013,"Browser is not draining snapshots");break;
                }
                browser.send(new String(payload,StandardCharsets.UTF_8));
            }
        } catch(IOException failure) {
            if(browser.isOpen()) { browser.close(1011,"TCP session closed"); }
        } finally { closePeer(browser); }
    }
    @Override public void onMessage(WebSocket browser,String message) {
        var socket=peers.get(browser);if(socket==null) { return; }
        byte[] payload=message.getBytes(StandardCharsets.UTF_8);
        if(payload.length<1 || payload.length>MAX_FRAME) { browser.close(1009,"Frame bound");return; }
        try {
            // WebSocket delivers messages in order; TCP authority still validates every envelope/input.
            var output=new DataOutputStream(socket.getOutputStream());
            output.writeInt(payload.length);output.write(payload);output.flush();
        } catch(IOException failure) { closePeer(browser);browser.close(1011,"TCP write failed"); }
    }
    @Override public void onClose(WebSocket browser,int code,String reason,boolean remote) { closePeer(browser); }
    private void closePeer(WebSocket browser) {
        var socket=peers.remove(browser);if(socket!=null) { try { socket.close(); }catch(IOException ignored) { } }
    }
    @Override public void onError(WebSocket browser,Exception failure) {
        if(browser!=null) { closePeer(browser); }else { System.err.println("GATEWAY_ERROR "+failure);done.countDown(); }
    }
    @Override public void onStart() { System.out.println("PROBE_GATEWAY_READY "+getPort()+" tcp="+game.getBoundPort()); }
    @Override public void close() {
        for(var browser:peers.keySet()) { closePeer(browser); }
        try { stop(1000); }catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        readers.shutdownNow();game.stop();done.countDown();
    }
    public static void main(String[] args) throws Exception {
        try(var gateway=new ProbeGateway(args.length==0?8090:Integer.parseInt(args[0]))) {
            Runtime.getRuntime().addShutdownHook(new Thread(gateway::close));gateway.start();gateway.done.await();
        }
    }
}
