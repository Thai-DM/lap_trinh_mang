# 07 — Ảnh hưởng hiệu năng

**Trạng thái so sánh tải: BLOCKED_MEMORY. Chưa đủ bằng chứng kết luận không regression CPU/memory/tick p99 khi bật items.** Không có phép đo mới về năng lực 100 triệu CCU hoặc khả năng mở rộng nhiều máy từ thay đổi gameplay này.

Các giới hạn đã có source và kiểm thử: player<=4; items<=16; weapon/orbit/explosion shots<=64; native ability projectiles<=64; bounded event journal128 và reliable pending128/session; TTL hữu hạn. Không có timer/thread riêng mỗi item/projectile, synchronous database hoặc disk I/O mới trong simulation. Ballistic weapon shots không tạo native body. Snapshot cache tiếp tục chia sẻ frame encode giữa recipients; reliable frame cũng encode một lần dùng lại.

Fixture snapshot có 2 players, 16 items và 64 weapon shots đã roundtrip ở **6446 bytes**, encodeCount=1 cho4 recipients ([output](runs/20261016-080639-267-final-gameplay-regression/tests.log)). Đây là một fixture tải lớn có giới hạn, không phải kích thước byte lớn nhất của mọi roster/tên/số thống kê. Snapshot thực tế thay đổi theo trạng thái; framing budget vẫn áp dụng.

## Evidence chẩn đoán và lý do không dùng để so sánh

Lượt COMBAT 4 clients, items NONE trước bản sửa inactive input: [summary](runs/20261016-074314-165-performance/none/COMBAT-4-final-1/summary.json), [failure](runs/20261016-074314-165-performance/none/COMBAT-4-final-1/failure.json). **Correctness FAIL**, 523 protocol errors, 0 disconnects, 4 GAME_END. Room kết thúc trước khi hết cửa sổ đo. Runner đã dừng, không chạy HIGH.

| Số đo chẩn đoán | Giá trị |
|---|---:|
| Cửa sổ worker | 20,0341 s |
| Room ticks / worker cycles | 524 / 1202 |
| Worker cycle Hz | 59,9907 |
| Room tick p99 | 0,786431 ms |
| Worker cycle p99 | 1,032191 ms |
| Scheduling lag p99 / max | 29,097983 / 65,126162 ms |
| Peak process RSS | 131.387.392 bytes (~125,3 MiB) |
| Snapshot encodes / cache hits | 175 / 525 |
| Deliveries/client/s | 8,7448 |

Không dùng các giá trị này làm kết quả tải steady-state hoặc so sánh bản sửa cuối: room chỉ active một phần cửa sổ, bản code khác, correctness không đạt. Nguyên nhân input lúc respawning đã được sửa và có TCP regression test. Runner hiện yêu cầu trận active suốt measurement window để tránh tính idle worker như thành công.

Lần chạy lại sau sửa đã bị guard chặn **trước khi khởi động JVM benchmark**: [safety-stop.json](runs/20261016-075156-731-performance/none/safety-stop.json). Available RAM=1.197.531.136 bytes; minimumStartMB=1500, minimumRunMB=750; completedDataPoints=0. Giữ nguyên guard và dừng toàn bộ matrix; không chạy thử tiếp sau memory stop. Functional tests dùng một JVM128MiB được thực hiện riêng.

## Những số đo còn thiếu

| So sánh bản cuối NONE ↔ HIGH | Trạng thái |
|---|---|
| CPU process, memory/RSS | BLOCKED |
| Room tick p99, scheduling lag, actual tickHz | BLOCKED |
| Snapshot byte distribution dưới workload | BLOCKED; chỉ có fixture serialization ở trên |
| Soak / allocation / native memory profiling | NOT STARTED cho bản gameplay cuối |

Script chuẩn bị sẵn: `scripts/gameplay/Benchmark.ps1`, mặc định warmup5s, measurement8s, 4 COMBAT clients, một worker, NONE rồi HIGH; đóng gói source classes đã verify thành runtime timestamp riêng. Có thể chạy sau khi có đủ RAM, giữ guard và dừng khi correctness/safety fail. Nếu trận kết thúc sớm, phép đo không đạt active-window requirement; không bỏ điều kiện đó để lấy số đẹp. Chưa có baseline/final pair hợp lệ để báo phần trăm tăng/giảm.
