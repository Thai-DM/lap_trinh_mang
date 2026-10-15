package com.smashprofs.server.room;

import com.smashprofs.server.game.FixedTickScheduler;
import com.smashprofs.server.game.MatchRuntime;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Quản lý registry phòng an toàn khi truy cập từ nhiều thread.
 * Collection chỉ bảo vệ registry, không bảo vệ mutable game state trong phòng.
 * State của phòng trong tương lai phải do simulation thread hoặc serialized executor sở hữu.
 */
public final class RoomManager {
    private final ConcurrentHashMap<String, GameRoom> rooms = new ConcurrentHashMap<>();
    private final Object registryLock = new Object();
    private final FixedTickScheduler scheduler;
    private final LongSupplier nanoClock;
    private final java.util.function.Consumer<com.smashprofs.server.game.AuthoritativeMatchResult> onMatchResult;
    private boolean closed;
    private final int maxRooms=Integer.getInteger("smashprofs.server.maxRooms",1024);

    /** Giữ chế độ registry độc lập của các bước trước, không tự tạo scheduler/thread. */
    public RoomManager() {
        scheduler = null;
        nanoClock = System::nanoTime;
        onMatchResult = result -> { };
    }

    /** Scheduler do server sở hữu; clock được truyền xuống phòng để lifecycle dùng chung nguồn thời gian. */
    public RoomManager(FixedTickScheduler scheduler, LongSupplier nanoClock) {
        this(scheduler, nanoClock, result -> { });
    }

    /** Completion boundary phải offer non-blocking; không gọi DB/service dưới khóa room. */
    public RoomManager(FixedTickScheduler scheduler, LongSupplier nanoClock,
                       java.util.function.Consumer<com.smashprofs.server.game.AuthoritativeMatchResult> onMatchResult) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler không được null");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock không được null");
        this.onMatchResult = Objects.requireNonNull(onMatchResult);
    }

    /** Lấy phòng hiện có hoặc tạo và đăng ký phòng mới nguyên tử theo ID. */
    public GameRoom getOrCreateRoom(String roomId) {
        String id = GameRoom.validateRoomId(roomId);
        synchronized (registryLock) {
            if (closed) {
                throw new IllegalStateException("RoomManager đã shutdown");
            }
            if(!rooms.containsKey(id) && rooms.size()>=maxRooms) { throw new IllegalStateException("Room capacity reached"); }
            return rooms.computeIfAbsent(id, key -> new GameRoom(key, nanoClock,
                    runtime -> registerRuntime(key, runtime)));
        }
    }

    /** Tạo phòng mới, từ chối ID đã tồn tại; không join player hoặc khởi tạo gameplay. */
    public GameRoom createRoom(String roomId) {
        String id = GameRoom.validateRoomId(roomId);
        synchronized (registryLock) {
            if (closed) { throw new IllegalStateException("RoomManager đã shutdown"); }
            if (rooms.containsKey(id)) { throw new IllegalArgumentException("roomId đã tồn tại"); }
            if(rooms.size()>=maxRooms) { throw new IllegalStateException("Room capacity reached"); }
            // Constructor hoàn tất trước publish; dùng cùng registry lock với get-or-create/shutdown.
            GameRoom room = new GameRoom(id, nanoClock, runtime -> registerRuntime(id, runtime));
            rooms.put(id, room);
            return room;
        }
    }

    /** Gọi dưới khóa room; registry không giữ khóa khi chờ room hoặc tick. */
    private void registerRuntime(String roomId, MatchRuntime runtime) {
        synchronized (registryLock) {
            GameRoom owner = rooms.get(roomId);
            if (closed || owner == null) {
                throw new IllegalStateException("Không khởi động trận của phòng đã đóng");
            }
            if (scheduler != null) {
                runtime.setPerformanceMetrics(scheduler.getMetrics().performance);
                // Capture đúng instance phòng, không cập nhật phòng mới nếu roomId bị xóa/tạo lại.
                scheduler.register(runtime, completed -> {
                    owner.updateLifecycle();
                    var result = completed.getMatchResult();
                    if (result != null) { onMatchResult.accept(result); }
                });
            }
        }
    }

    /** Trả về phòng theo ID, hoặc null nếu chưa được đăng ký. */
    public GameRoom getRoom(String roomId) {
        return rooms.get(GameRoom.validateRoomId(roomId));
    }

    public boolean containsRoom(String roomId) {
        return rooms.containsKey(GameRoom.validateRoomId(roomId));
    }

    /** Đóng phòng, unregister/chờ tick rồi dispose runtime; không tạo MatchResult. */
    public GameRoom removeRoom(String roomId) {
        String id = GameRoom.validateRoomId(roomId);
        GameRoom room = rooms.get(id);
        if (room == null) {
            return null;
        }
        MatchRuntime runtime;
        synchronized (room) {
            synchronized (registryLock) {
                if (!rooms.remove(id, room)) {
                    return null;
                }
            }
            runtime = room.closeForServerShutdown();
        }
        closeRuntime(runtime);
        return room;
    }

    public int getRoomCount() {
        return rooms.size();
    }

    /** Snapshot registry cho publisher, không trả collection mutable hoặc giữ lock khi đọc room. */
    public List<GameRoom> getRooms() {
        synchronized (registryLock) { return List.copyOf(rooms.values()); }
    }

    /**
     * Mỗi chu kỳ xử lý một snapshot phòng, không giữ khóa registry trong updateLifecycle.
     * Room tự cô lập lỗi của mình. Room bị xóa sau snapshot đã có guard closed khi được cập nhật.
     * Đây chưa phải room-command queue: thread gọi API vẫn có thể sửa lobby dưới khóa room.
     */
    public void updateLifecycles() {
        List<GameRoom> snapshot;
        synchronized (registryLock) {
            if (closed) {
                return;
            }
            snapshot = List.copyOf(rooms.values());
        }
        for (GameRoom room : snapshot) {
            room.updateLifecycle();
        }
    }

    /** Đóng toàn bộ registry khi server dừng; không chờ tick trong khóa registry/room. */
    public void shutdown() {
        List<GameRoom> snapshot;
        synchronized (registryLock) {
            closed = true;
            snapshot = List.copyOf(rooms.values());
            rooms.clear();
        }
        RuntimeException firstFailure = null;
        for (GameRoom room : snapshot) {
            try {
                closeRuntime(room.closeForServerShutdown());
            } catch (RuntimeException failure) {
                // Một phòng lỗi cleanup không được làm bỏ qua native World của các phòng còn lại.
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        if (firstFailure != null) {
            throw new IllegalStateException("Lỗi khi đóng runtime của phòng", firstFailure);
        }
    }

    private void closeRuntime(MatchRuntime runtime) {
        if (runtime != null) {
            if (scheduler != null) {
                scheduler.unregister(runtime);
            }
            // Không giữ khóa room/registry; close dùng khóa runtime để chờ tick còn đang thực thi.
            runtime.close();
            // Remove/shutdown có thể thắng unregister notification của tick cuối; historical result vẫn hợp lệ.
            var result = runtime.getMatchResult();
            if (result != null) { onMatchResult.accept(result); }
        }
    }
}
