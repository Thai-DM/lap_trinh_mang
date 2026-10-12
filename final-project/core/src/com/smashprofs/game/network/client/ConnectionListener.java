package com.smashprofs.game.network.client;

import com.smashprofs.common.protocol.NetworkPacket;
import com.smashprofs.common.protocol.ProtocolMessage;

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
     * Production: invoked after CONNECT_ACK, with the server-issued PlayerId already stored.
     * Mock: invoked when the TCP connection has been established.
     */
    void onConnected();

    /**
     * Invoked when a complete and valid NetworkPacket is received from the remote peer.
     *
     * @param packet The deserialized packet.
     */
    void onPacketReceived(NetworkPacket packet);

    /**
     * Receives the complete v1 envelope, including requestId and the object payload.
     * Existing listeners receive a payload-preserving compatibility view through onPacketReceived.
     * Override this method when correlation or other v1 envelope fields are needed.
     */
    default void onProtocolMessageReceived(ProtocolMessage message) {
        onPacketReceived(new NetworkPacket(message.type(), null, message.payload().toString()));
    }

    /**
     * Invoked when the connection terminates.
     * Guaranteed to be called AT MOST ONCE per connection lifetime, on the Reader Thread.
     *
     * @param reason Diagnostic explanation for the disconnection.
     */
    void onDisconnected(String reason);
}
