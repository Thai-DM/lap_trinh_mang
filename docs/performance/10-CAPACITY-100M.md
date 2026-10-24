# 10 — Capacity model cho 100M CCU

**PROPOSED:** mô hình quy hoạch. **BLOCKED:** đo verified capacity để điền tham số. Không có tuyên bố hệ thống đã chịu 100M CCU.

Với 4 player/phòng, 100.000.000 player tương ứng 25.000.000 phòng đồng thời. Phép chia này chỉ đếm room; không chứng minh worker/process/control plane/database có thể vận hành ở quy mô đó. Phòng 2 player, lobby, spectators và churn làm số lượng tài nguyên khác đi.

Định nghĩa `R_safe`: số phòng/worker lớn nhất đo được dưới workload đại diện, trong đó authoritative correctness đạt, room/cycle/lag p99 trong SLO, frequency thực gần 60 Hz, memory/GC/thread/bandwidth còn headroom, và long soak không tăng tài nguyên vô hạn. Hiện **R_safe chưa biết**. Phép đo 1 idle room không xác định ngưỡng này; các điểm 32/64 client cũ vi phạm cycle budget không đủ để nội suy một ngưỡng production.

Sau khi có R_safe, có thể lập mô hình, với `u` là target occupancy sau khi giữ spare capacity:

```text
N_workers_plan = ceil(rooms_region / (R_safe * u))
egress_region = players_region * measured_bytes_per_player_second
connections_region = players_region + lobby/spectator connections
monthly_cost_model = compute + egress + storage + operations + reserve
```

Đây là công thức kế hoạch dùng các biến đo/giả định công khai, không phải benchmark hoặc dự báo chi phí đã xác minh. Phải đo riêng failure capacity khi mất node/zone, p99 end-to-end WAN và routing across regions. Không lấy snapshot bytes/s loopback của idle bots để áp làm egress COMBAT cho 100M player. Một broadcast payload vẫn phải đi qua mỗi TCP connection dù encode được chia sẻ.

Kiến trúc cần thêm trước production lớn:

1. Regional ingress/auth, rate limiting phân tán, TLS/credential lifecycle và routing theo latency.
2. Control plane được shard và có replicated/durable membership, atomic reservation/ownership với epoch/fencing; prototype hiện là một process memory.
3. Room workers có capacity envelope đã đo, per-region autoscaling và drain; live migration cần checkpoint và recovery protocol riêng.
4. Durable asynchronous result pipeline có retry/outbox/idempotency lâu dài, partitioned ranking/history, storage retention và restore drills. Local file hiện tại không đáp ứng yêu cầu này.
5. Telemetry ngoài process, overload budgets, admission/failure SLO, automated rollback, independent load-generator fleet và fault-injection nhiều máy/zone.

Mô hình thread hiện tại hai platform threads/connection là rào cản khi tăng connection density; NIO/event-loop hoặc JVM phù hợp hơn là hạng mục nghiên cứu cần đo. Nhiều JVM process không tự giải quyết tổng RAM, kernel sockets, port/resource limits, egress hoặc control-plane throughput. Delta snapshot và compression cũng chưa có compatibility/benchmark đủ để tính vào kế hoạch.

Việc hợp lý tiếp theo là xác định envelope của một worker trên máy server có RAM/CPU ổn định, sau đó kiểm tra scale-out 2/3 node với máy phát tải độc lập và failure tests. Không suy ra verified capacity từ phép nhân tuyến tính.
