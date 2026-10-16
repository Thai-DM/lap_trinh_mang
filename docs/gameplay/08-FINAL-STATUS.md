# 08 — Trạng thái cuối

Đã triển khai 9 tính năng chính thức trong `E:\2026-2\lap-trinh-mang\tinhnang.md` vào server authoritative, Protocol v1 có capability và client online. DONE bên dưới nghĩa là có source server+client và kiểm thử tự động quan trọng; không có nghĩa đã kiểm duyệt GUI hoặc chứng nhận hiệu năng production.

| Tính năng | Trạng thái | Phần đã có và evidence |
|---|---|---|
| 1. Host Lobby Settings | DONE | UI host/non-host; validate/lock/version; capacity/time/lives/drop; HostRulesTest và TCP/cluster. |
| 2. Stock, Respawn, Spectator | DONE | 90/120 ticks, stock loss một lần, elimination/input gate, camera/banner; StockGameplayTest, TCP2–4, simultaneous/disconnect/end. |
| 3. Weapons, Grenades, Items | DONE | 6 loại, pickup/drop, swept collision/AoE/gravity, caps/TTL/stats; ItemGameplayTest và wire/render source compile. |
| 4. Ultimate Cooldown | DONE | 600-tick authoritative gate; HUD countdown/ready/sound; spam/boundary và state TCP. |
| 5. Knockback Scaling | DONE | Config finite/capped, HP scaling, central defense gate, giữ impulse; native/unit checks. |
| 6. Wall Slide / Wall Jump | DONE | Actual contact normals, fall limit/jump/latch, authoritative wall state, existing sprite animation/tint/rotation; native left/right/airspam/knockback test. |
| 7. Kill Feed | DONE | Reliable journal/queue độc lập snapshot, sequence/dedup, scroll/fade4s, weapon/kill attribution; backpressure/client/TCP tests. |
| 8. RTT HUD | DONE | Monotonic actual-send PING/PONG, peer reports1Hz, unknown/disconnected/Lag; heartbeat measurement và TCP tests. |
| 9. MVP / Match Statistics | DONE | Authoritative damage/pickups/K.O./deaths/lives/duration, champion/shared ties; immutable final result, result UI và client/server tests. |
| Manual GUI/audio smoke | NOT STARTED | Source core/desktop compile thành công; chưa mở game để kiểm tra trực quan/âm thanh. |
| Performance comparison NONE/HIGH | BLOCKED | RAM guard chặn chạy lại; chưa có pair so sánh hợp lệ. |
| Xác nhận phát hành tổng thể | PARTIAL | Functional automation PASS; GUI/audio, performance validation và soak còn thiếu. |

**638/638 tests PASS**, 36 suites, Java17, heap128MiB; [log cuối](runs/20261016-080639-267-final-gameplay-regression/tests.log). GameServer/TCP thật với2/3/4 clients, hai gameplay rooms đồng thời, control-plane/worker claim, reliable delivery, disconnect và native World cleanup đã kiểm tra. Phạm vi/giới hạn chi tiết tại [06](06-INTEGRATION-TESTS.md) và [07](07-PERFORMANCE-IMPACT.md).

Tại thời điểm hoàn tất kiểm thử, chưa thực hiện commit/reset/merge/rebase/push; HEAD nền là `df0da3f757f3c71f4c76599d349f4e76b242904a`. Sau đó người dùng yêu cầu commit/push code lên nhánh hiện tại `integration/member2-check`. Các build artifacts/log đã có được giữ ngoài commit; evidence gameplay trong tài liệu đi cùng source. Không coi JAR cũ trong module build/libs là binary gameplay đã xác minh.

Danh sách đường dẫn source/test/config/script thay đổi và SHA-256 ở [verification.json](verification.json); danh sách đọc trực tiếp ở [SOURCE-FILES.md](SOURCE-FILES.md). Manifest không bao gồm build artifacts/log chưa commit có từ trước. Các thay đổi `StandaloneMetrics` nằm trong danh sách diff hiện tại và được ghi nhận, không suy luận tất cả dirty artifacts là do tác vụ này tạo.

Để kiểm tra trực tiếp: [hướng dẫn chạy server và client](05-CLIENT-IMPLEMENTATION.md). Phòng gameplay cần capability `gameplay-v1`; CLI cũ không có flag vẫn tạo legacy room. Không có live migration hoặc thay thế kiến trúc sharding/network hiện hữu.
