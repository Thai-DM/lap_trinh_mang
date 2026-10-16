# Allocation và autoscaling

`RoomAllocator` có hai implementation policy trong cùng một boundary:

- ROUND_ROBIN: xoay đều trên worker eligible được sắp ổn định theo ID.
- LOAD_AWARE: chọn score thấp nhất; tie-break theo ID.

```text
score = wCPU × processCPU
      + wTick × tickP99Ms / 16.666667
      + wRooms × reservedRooms / maxRooms
      + wConnections × reservedConnections / maxConnections
      + wHeap × usedHeap / maxHeap
      + wOutbound × outboundQueue / max(1, connections)
```

Mặc định weights lần lượt `.20,.25,.20,.10,.15,.10`; cấu hình `allocation.weights` gồm 6 số không âm, tổng >0. CPU là process load do JVM cung cấp, normalized trên logical processors; giá trị chưa có được worker báo 0, chưa phải đo đầy đủ saturation từng core. Heap là một phần bộ nhớ, không gồm native Box2D, thread stack và kernel sockets.

Capacity = max(reservation control plane, số quan sát trên worker), tránh báo cáo trễ làm cấp vượt room/connection limit. Một room mới reserve khả năng tối đa 4 connections. Admission tại worker kiểm tra local room capacity lần nữa. RR cũng chịu cùng health/capacity gates để so sánh an toàn.

Worker prototype giới hạn cấu hình max.rooms trong1–1024 để giữ room quota phù hợp bounded result tracking. activePlayers đếm roster còn được simulation quản lý, gồm cả player mất TCP giữa trận; activeConnections là số kết nối hiện tại. Heap/queue metrics không thay thế host RSS hoặc native memory accounting.

Gates: worker healthy, region đúng, không draining; rooms < limit; còn ít nhất 4 connection slots; CPU <90%; tick p99 <16.67ms; heap <90%; outbound depth <max(8,2×connections). Worker bị gate chặn có cooldown 3s trước khi được xem lại. Tất cả worker bị chặn → NO_CAPACITY, không âm thầm tạo room quá tải. Startup/JIT hoặc GC có thể làm p99 tăng và giảm admission tạm thời; cần tune bằng soak test, không bỏ qua các rejection trong benchmark.

## AutoScalingController

Sử dụng max(room utilization, CPU, tick budget ratio, heap ratio) theo worker. Ba observation liên tiếp mean utilization >70% → tăng một worker. Mười observation <20% và số ready >minimum → chọn worker ít room nhất để drain. Cooldown 30s giữa actions. Chỉ stop worker đang drain và có 0 reservation/room; provisioner kiểm tra CP và worker lại trước shutdown.

`WorkerProvisioner` tách start/drain/stopEmpty khỏi quyết định. `ClusterMain.LocalWorkers` triển khai bằng ProcessBuilder, tạo config riêng, PID/process handle, readiness timeout, log riêng và quản lý child JVM. `autoscaling.enabled=false` mặc định để demo/benchmark 3 worker cố định; bật true, đổi `workers.min/max` để thử controller.

Publisher cũ giữ tối đa 4096 MatchId tombstones/lifecycle. Worker chủ động drain trước khi hết quota đó; cần process recycling hoặc persistence outbox dài hạn để chạy liên tục production.

Tests: weighted placement tránh worker nóng, từ chối full/slow, concurrent RR reservations; fake-provisioner kiểm tra sustained load/cooldown/drain/stop-empty; process integration kiểm tra worker JVM độc lập và owner loss. Benchmark so sánh dữ liệu thực trong tài liệu 08; không mặc định WLA luôn tốt hơn RR khi tất cả worker đồng tải.
