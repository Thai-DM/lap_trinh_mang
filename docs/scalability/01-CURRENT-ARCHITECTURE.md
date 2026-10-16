# Audit repository và ranh giới thay đổi

Audit ngày 15/10/2026, nhánh `integration/member2-check`. Không có `MERGE_HEAD`. Trước chỉnh sửa có thay đổi chưa commit ở JAR/cache build common/server và các thư mục report/test-result chưa track; không có source đang sửa được Git báo ở thời điểm audit. Không reset, merge, commit hoặc push. Việc build cập nhật artifact do Gradle sinh; không xóa/reset thay đổi người dùng.

## Hệ thống đã tồn tại trước nâng cấp

| Module | Bằng chứng source thực tế | Quyết định |
|---|---|---|
| common | `JsonFrameCodec`, `ProtocolV1`, immutable DTO | Tái sử dụng codec JSON, UTF-8, length BE; không sửa schema |
| server/network | `GameServer`, TCP transport, dispatcher, heartbeat | Giữ lifecycle và transport; thêm admission tùy chọn và counters |
| server/room | `RoomManager`, `GameRoom`, 4 player slots, READY/countdown | Giữ room runtime và ownership scheduler |
| server/game | `FixedTickScheduler`, `MatchRuntime`, Box2D, combat/projectile | Tái sử dụng 60 Hz; chỉ thêm quan sát metrics/input depth |
| synchronization | `StateBroadcaster` 20 Hz, latest pending snapshot/session, bounded critical pushes | Tái sử dụng backpressure; thêm chọn rate tùy áp lực |
| persistence | `RankingGateway`, bounded `MatchResultPublisher` | Đã có async boundary; chưa có DB/ranking implementation |
| core/desktop | v1 NetworkClient, lobby, snapshots/interpolation, online screen | Thêm factory cluster/CLI; không sửa offline physics/combat |
| metrics/ranking/reliability | một số package chỉ có `package-info.java` | Không tính là chức năng đã triển khai |

Server cũ là một JVM, một scheduler simulation, registry session/room cục bộ. JOIN có khả năng get-or-create room tại worker; vì vậy chỉ chạy nhiều server cũ không tạo thành cluster an toàn. Trước nâng cấp CONNECT chỉ cấp UUID cho connection, không có credential tài khoản hay assignment phân tán. LEAVE_ROOM vẫn trả NOT_IMPLEMENTED; bot rời bằng đóng TCP và cleanup có sẵn.

## Rủi ro và thay đổi tối thiểu

Một room phải tiếp tục có một Box2D World và simulation owner. Không gọi tick từ HTTP, test bot hoặc control plane. Giữ `RankingGateway` của Member 3, thêm adapter file qua interface đó. Client Member 2 giữ lobby/input/parser và giao thức cũ; chỉ thêm kết nối opt-in.

Các thay đổi chung cần hồi quy: CONNECT strict fields, CREATE/JOIN/READY, input identity/sequence, countdown, terminal-state/end ordering, framing partial reads, snapshot backpressure, close/restart và native disposal. Test mới kiểm tra ticket single-use, worker lease loss, lựa chọn allocation, session expiry, matchmaking, persistence idempotency và desktop lobby với secured listener.

## Các phase đã thực hiện

| Phase | Source/bằng chứng |
|---|---|
| Audit/boundaries | Tài liệu này; SessionAdmission và package cluster tách biệt |
| Observability | ServerMetrics; connection counters; worker/standalone metrics |
| Distributed runtime | ControlPlane, RoomAllocator, WorkerRuntime, ClusterMain; test 3 JVM |
| Scaling | Hai allocator, AutoScalingController/WorkerProvisioner, local process manager |
| Networking | Tái sử dụng bounded queues; write watchdog; AdaptiveSnapshotPolicy |
| Services | Guest auth, central session/ticket index, regional FIFO matchmaking, FileMatchHistory |
| Benchmark/capacity | Python runner + LoadGenerator + dữ liệu thực + tài liệu 08/09 |
| Verification | Targeted unit/integration tests; hướng dẫn README và trạng thái ở tài liệu 03 |

Các file mới chủ yếu ở `server/cluster`, `server/metrics`, test tương ứng, `scripts/scalability`, `config/scalability`, `docs/scalability`. Các chỉnh sửa gameplay chỉ là thêm getter thống kê; thuật toán Box2D, damage, movement, projectile và tick rate được giữ nguyên.

GameRoom có thêm lobby gate minimumPlayers cho group matchmaking, mặc định2 giữ hành vi cũ. Gate chỉ được cấu hình trước khi player join; có test hồi quy lobby/room và group4.
