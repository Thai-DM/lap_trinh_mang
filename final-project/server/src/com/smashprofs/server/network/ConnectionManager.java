package com.smashprofs.server.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Quản lý các client session mà server đang biết tới theo sessionId. */
public final class ConnectionManager {
    private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final Object registryLock = new Object();

    /** Đăng ký session mới; từ chối ID trùng thay vì ghi đè session đã có. */
    public void register(ClientSession session) {
        Objects.requireNonNull(session, "session không được null");
        String sessionId = validateSessionId(session.getSessionId());
        synchronized (registryLock) {
            if (sessions.putIfAbsent(sessionId, session) != null) {
                throw new IllegalArgumentException("sessionId đã tồn tại: " + sessionId);
            }
        }
    }

    /** Trả về session theo ID, hoặc null nếu không tồn tại. */
    public ClientSession get(String sessionId) {
        return sessions.get(validateSessionId(sessionId));
    }

    /** Xóa và trả về session theo ID, không đóng channel của session. */
    public ClientSession remove(String sessionId) {
        validateSessionId(sessionId);
        synchronized (registryLock) {
            return sessions.remove(sessionId);
        }
    }

    public boolean contains(String sessionId) {
        return sessions.containsKey(validateSessionId(sessionId));
    }

    public int size() {
        return sessions.size();
    }

    /** Xóa đúng instance; callback đóng cũ không được xóa session mới tái sử dụng cùng ID. */
    public boolean removeIfSame(ClientSession session) {
        Objects.requireNonNull(session, "session không được null");
        synchronized (registryLock) {
            return sessions.remove(validateSessionId(session.getSessionId()), session);
        }
    }

    /** Snapshot registry bất biến; không cung cấp socket hoặc collection mutable nội bộ. */
    public List<ClientSession> getSessions() {
        synchronized (registryLock) {
            return List.copyOf(sessions.values());
        }
    }

    /**
     * Xóa các session hiện tại khỏi manager và thử đóng từng session.
     * Session đăng ký sau khi lấy danh sách vẫn được giữ lại.
     * Việc đóng channel diễn ra ngoài khóa để không chặn các thao tác đăng ký.
     *
     * @throws IllegalStateException sau khi thử đóng tất cả, nếu có channel đóng bị lỗi
     */
    public void closeAll() {
        List<ClientSession> sessionsToClose;
        synchronized (registryLock) {
            sessionsToClose = new ArrayList<>(sessions.values());
            sessions.clear();
        }

        IllegalStateException closeFailure = null;
        for (ClientSession session : sessionsToClose) {
            try {
                session.close();
            } catch (RuntimeException failure) {
                if (closeFailure == null) {
                    closeFailure = new IllegalStateException("Không thể đóng tất cả session", failure);
                } else {
                    closeFailure.addSuppressed(failure);
                }
            }
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private static String validateSessionId(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId không được null");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId không được blank");
        }
        return sessionId;
    }
}
