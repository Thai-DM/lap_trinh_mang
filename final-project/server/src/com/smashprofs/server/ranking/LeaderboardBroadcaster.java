package com.smashprofs.server.ranking;

import com.smashprofs.common.model.RankingEntry;
import com.smashprofs.common.protocol.ProtocolMessage;
import com.smashprofs.common.protocol.RankingProtocol;
import com.smashprofs.server.network.ClientSession;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Quản lý các client đăng ký nhận bảng xếp hạng và phát sóng (broadcast)
 * thông điệp {@code RANKING_UPDATED} một cách non-blocking khi có kết quả xếp hạng mới.
 */
public class LeaderboardBroadcaster implements RankingListener, AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(LeaderboardBroadcaster.class.getName());

    private final Set<ClientSession> subscribers = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final RankingService rankingService;

    /**
     * Khởi tạo với ExecutorService mặc định chạy trên daemon thread riêng biệt.
     */
    public LeaderboardBroadcaster() {
        this(null, null);
    }

    /**
     * Khởi tạo với ExecutorService tùy chỉnh để điều khiển luồng non-blocking.
     *
     * @param executor bộ thực thi bất đồng bộ (nếu null sẽ tạo executor mặc định)
     */
    public LeaderboardBroadcaster(ExecutorService executor) {
        this(null, executor);
    }

    /**
     * Khởi tạo và tự động gắn kết làm listener của RankingService.
     *
     * @param rankingService dịch vụ xếp hạng để lắng nghe sự kiện
     */
    public LeaderboardBroadcaster(RankingService rankingService) {
        this(rankingService, null);
    }

    /**
     * Khởi tạo đầy đủ với cả RankingService và ExecutorService.
     *
     * @param rankingService dịch vụ xếp hạng (tùy chọn)
     * @param executor       bộ thực thi bất đồng bộ (tùy chọn)
     */
    public LeaderboardBroadcaster(RankingService rankingService, ExecutorService executor) {
        this.rankingService = rankingService;
        if (executor != null) {
            this.executor = executor;
            this.ownsExecutor = false;
        } else {
            this.executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "LeaderboardBroadcaster-Worker");
                thread.setDaemon(true);
                return thread;
            });
            this.ownsExecutor = true;
        }

        if (this.rankingService != null) {
            this.rankingService.addRankingListener(this);
        }
    }

    /**
     * Đăng ký một phiên client vào danh sách nhận cập nhật bảng xếp hạng realtime.
     *
     * @param session phiên kết nối của client
     */
    public void register(ClientSession session) {
        if (session != null && session.isOpen()) {
            subscribers.add(session);
        }
    }

    /**
     * Bí danh của {@link #register(ClientSession)}.
     */
    public void subscribe(ClientSession session) {
        register(session);
    }

    /**
     * Hủy đăng ký phiên client khỏi danh sách phát sóng.
     *
     * @param session phiên kết nối của client
     */
    public void unregister(ClientSession session) {
        if (session != null) {
            subscribers.remove(session);
        }
    }

    /**
     * Bí danh của {@link #unregister(ClientSession)}.
     */
    public void unsubscribe(ClientSession session) {
        unregister(session);
    }

    /**
     * Kiểm tra xem một phiên client đã đăng ký nhận cập nhật hay chưa.
     *
     * @param session phiên client cần kiểm tra
     * @return true nếu đã đăng ký và đang tồn tại trong danh sách
     */
    public boolean isSubscribed(ClientSession session) {
        return session != null && subscribers.contains(session);
    }

    /**
     * Bí danh của {@link #isSubscribed(ClientSession)}.
     */
    public boolean isRegistered(ClientSession session) {
        return isSubscribed(session);
    }

    /**
     * Trả về số lượng client đang đăng ký nhận thông báo.
     *
     * @return số lượng subscriber
     */
    public int getSubscriberCount() {
        return subscribers.size();
    }

    /**
     * Trả về tập hợp các phiên client đang đăng ký (chỉ đọc).
     *
     * @return tập hợp client session bất biến
     */
    public Set<ClientSession> getSubscribers() {
        return Collections.unmodifiableSet(subscribers);
    }

    /**
     * Xóa sạch toàn bộ client đã đăng ký.
     */
    public void clearSubscribers() {
        subscribers.clear();
    }

    /**
     * Thực hiện phát sóng danh sách xếp hạng mới tới toàn bộ subscriber một cách non-blocking.
     *
     * @param leaderboard danh sách xếp hạng mới nhất
     * @return CompletableFuture hoàn tất khi việc gửi kết thúc
     */
    public CompletableFuture<Void> broadcast(List<RankingEntry> leaderboard) {
        List<RankingEntry> entries = leaderboard != null ? leaderboard : Collections.emptyList();
        ProtocolMessage message = RankingProtocol.createUpdatedMessage(entries);

        return CompletableFuture.runAsync(() -> {
            for (ClientSession session : subscribers) {
                if (session == null || !session.isOpen()) {
                    if (session != null) {
                        subscribers.remove(session);
                    }
                    continue;
                }
                try {
                    session.send(message);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Lỗi khi gửi RANKING_UPDATED tới session " + session.getSessionId(), e);
                }
            }
        }, executor);
    }

    /**
     * Tiếp nhận sự kiện bảng xếp hạng được cập nhật từ RankingListener và phát sóng non-blocking.
     *
     * @param leaderboard danh sách xếp hạng mới
     */
    @Override
    public void onRankingUpdated(List<RankingEntry> leaderboard) {
        broadcast(leaderboard);
    }

    /**
     * Dọn dẹp tài nguyên và tắt executor nếu do instance này sở hữu.
     */
    @Override
    public void close() {
        if (rankingService != null) {
            rankingService.removeRankingListener(this);
        }
        subscribers.clear();
        if (ownsExecutor) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
