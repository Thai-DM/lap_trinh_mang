package com.smashprofs.server.network;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.ProtocolException;
import com.smashprofs.common.protocol.ProtocolMessage;

/** Optional cluster boundary. The legacy listener never installs this policy. */
public interface SessionAdmission {
    PlayerId connect(ClientSession session, ProtocolMessage request) throws ProtocolException;
    void authorize(ClientSession session, ProtocolMessage request) throws ProtocolException;
}
