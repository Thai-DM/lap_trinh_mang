# Capacity planning hướng tới 100M CCU

Các phép tính sau là mô hình, không phải kết quả tải đã đạt. Benchmark local đo ở tài liệu 08; chưa có số đo per-connection RSS/native memory hoặc cực đại worker đủ tin cậy để cấp cấu hình 100M.

## Nhu cầu tất định của kịch bản

| Đại lượng | Công thức | Giá trị |
|---|---|---:|
| TCP gameplay connections | P | 100,000,000 |
| Phòng 4 người | P/4 | 25,000,000 |
| Room-ticks/s | P/4 ×60 | 1,500,000,000 |
| Snapshot deliveries/s | P ×20 | 2,000,000,000 |
| Input deliveries/s ở 20 Hz (giả định) | P ×20 | 2,000,000,000 |
| Room-state builds/s nếu mỗi room 20 Hz | P/4 ×20 | 500,000,000 |

Một room-tick gồm tất cả player, projectile/collision trong room; không phải tick từng player. Snapshot delivery tính mỗi recipient, khác với build state một lần cho room. Source hiện tại còn build immutable latest state theo tick; cần đo allocations/GC và shared encoded snapshot trước tối ưu tiếp.

## Băng thông

Gọi S là byte/full snapshot/recipient (bao gồm header 4 byte); I là byte/input frame; F_in là input Hz; A là bytes/s auth/control/heartbeat:

```text
egress_payload_bytes_s = P × snapshotHz × S
ingress_payload_bytes_s = P × F_in × I
egress_wire_bits_s = 8 × egress_payload_bytes_s × (1 + overhead_fraction)
```

Ví dụ giả định S=1,024 byte, I=200 byte, F_in=20: outbound 2.048 TB/s =16.384 Tb/s; inbound 400 GB/s =3.2 Tb/s, trước overhead/TLS/retransmission. Đây là ví dụ giả định; thay S/I bằng byte counters thực từ benchmark. Ứng dụng JSON, nội dung projectiles và số participant làm S thay đổi; không lấy kích thước phòng 2 người để tính phòng 4 người.

Mỗi tháng 30 ngày egress payload ví dụ là khoảng 5.31 exabyte nếu duy trì 100M CCU liên tục. Chi phí = GB tính phí × giá egress hợp đồng + compute/RAM + public-IP/LB + database + cross-region replication. Không đưa bảng giá cloud chưa xác minh hoặc một con số chi phí cố định.

## RAM, CPU và capacity một worker

Đo RSS khi idle/connected/lobby/playing bằng cùng workload và đủ lâu sau warmup, tách JVM heap khỏi native/stack/kernel:

```text
RAM_conn ≈ slope(RSS against idle authenticated connections)
RAM_room ≈ [RSS_playing − RSS_idle − connections×RAM_conn] / rooms
CPU_room_seconds_s ≈ (processCPU_fraction−idleCPU_fraction)×logicalCPUs / rooms
CPU_room_tick_seconds ≈ CPU_room_seconds_s / 60
```

Heap reported trong prototype không thay thế RSS. Tick histogram đo toàn cycle của worker, bao gồm lobby update và snapshot offers; không bằng CPU time/room-tick. P99 có thể chứa scheduler/GC pauses. Cần profile JFR/async-profiler và process RSS/native memory trước khi suy ra per-room RAM/CPU.

```text
C_cpu_rooms = CPU_budget_cores / measured_CPU_room_seconds_s
C_ram_rooms = (RAM_budget − JVM_base − native_reserve) / (RAM_room + 4×RAM_conn)
C_net_rooms = egress_budget_bytes_s / (4×snapshotHz×S)
C_conn_rooms = verified_connection_limit / 4
C_worker_rooms = floor(min(C_cpu_rooms,C_ram_rooms,C_net_rooms,C_conn_rooms,C_tick_SLO_rooms))
usable_rooms = floor(C_worker_rooms × (1−headroom))
workers = ceil(25,000,000 / usable_rooms)
```

SLO đề xuất để tìm capacity: worker cycle p99 ≤16.67ms, delivery rate đạt mục tiêu, connection/protocol failure bằng 0, đủ resource headroom, soak dài và fault test. Mức cao nhất vượt qua smoke test chỉ là điểm kiểm chứng ngắn hạn, không phải C_worker được chứng nhận.

Ví dụ thuần giả định C_worker=1,000 room, headroom30% →700 usable rooms, cần khoảng35,715 workers. Không suy ra C_worker=1,000 từ benchmark laptop. Nếu có10 region chia đều thì trung bình khoảng3,572 worker/region, cộng dự phòng và placement constraints; số region cuối cùng còn phụ thuộc ping, pháp lý, chi phí và failure domains.

Nếu muốn chịu mất một region trong R region cân bằng: tải bình thường mỗi region ≤(R−1)/R của capacity failover tổng, rồi còn headroom nội vùng. Với R=10 cần ≥11.1% capacity bổ sung cho tải vùng còn lại khi một vùng mất; dữ liệu/trận đang chạy ở vùng mất vẫn abort trong prototype. Không nhầm capacity nhận trận mới với phục hồi simulation.

## Nút thắt sau horizontal scaling

Một simulation thread/worker; thread/connection và stack; file descriptor/socket/TIME_WAIT/ephemeral ports; kernel buffers; GC/immutable state allocations; JSON encode mỗi recipient; WAN throughput, retransmission và TCP head-of-line; central auth/matchmaking/registry QPS; reconnect storms; match ingestion/ranking contention. Chia worker không tự giải quyết tất cả.

Benchmark trên cùng laptop không có thêm CPU/RAM khi tăng JVM. Tăng gấp đôi worker chỉ nhân capacity nếu có tài nguyên vật lý và control/persistence/network cũng đáp ứng; scale efficiency phải đo theo `throughput_N / (N × throughput_1)` tại cùng SLO, trên workload đủ để đạt giới hạn.
