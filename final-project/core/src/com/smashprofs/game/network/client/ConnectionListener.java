package com.smashprofs.game.network.client;

import com.smashprofs.common.protocol.NetworkPacket;

/**
 * Listener for network events.
 * <p>
 * THREADING CONTRACT:
 * All callbacks are invoked on the Reader Thread (network thread).
 * Callbacks must NOT perform OpenGL/graphics operations or directly mutate thread-confined game state.
 * Use thread-safe queues or buffers to pass data to the LibGDX render thread.
 * </p>
 */
public interface ConnectionListener {

    /**
     * Invoked when the TCP connection has been established successfully and the Reader Thread starts.
     */
    void onConnected();

    /**
     * Invoked when a complete and valid NetworkPacket is received from the remote peer.
     *
     * @param packet The deserialized packet.
     */
    void onPacketReceived(NetworkPacket packet);

    /**
     * Invoked when the connection terminates.
     * Guaranteed to be called AT MOST ONCE per connection lifetime, on the Reader Thread.
     *
     * @param reason Diagnostic explanation for the disconnection.
     */
    void onDisconnected(String reason);
}
