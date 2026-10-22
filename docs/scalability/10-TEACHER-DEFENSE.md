# Gợi ý bảo vệ và giới hạn kết luận

**Vì sao server đơn phình to?** Connection threads/buffers, room Worlds, tick cost, snapshot JSON fanout và persistence cùng tăng theo số người/phòng. Tăng heap không giải quyết bottleneck simulation thread hoặc network.

**Phân tán 100M thế nào?** 100M connections →25M rooms 4 người; chia region/control cell/worker. Room là shard giữ một authority. CP cấp assignment, client đi TCP trực tiếp tới worker. Mô hình này cần hạ tầng, HA và benchmark quy mô lớn chưa có ở đây.

**10 chức năng đặt ở đâu?** Auth/session/matchmaking/registry/allocation ở CP; TCP/room/physics/sync ở worker; result đi async qua RankingGateway. Metrics và provisioning là boundaries riêng. Xem bảng DONE/PARTIAL/BLOCKED/PROPOSED trong tài liệu03.

**Code thật nằm ở đâu?** Package `server.cluster`, `ServerMetrics`, `SessionAdmission`, `AdaptiveSnapshotPolicy`, factory client cluster, process launcher và load generator. GameLoop/Box2D/combat hiện có được tái sử dụng. Các tests và output JSON/CSV có thể chạy lại bằng README.

**Điểm kỹ thuật mới?** Atomic load-aware room reservation, worker ownership/lease fail-stop, capability-based single-use admission, bounded latest snapshots cùng critical events, pressure/hysteresis thay rate, local process autoscaling abstraction và benchmark thực. Không tuyên bố thuật toán nghiên cứu mới chỉ từ weighted score.

**Baseline khác cải tiến?** RR so với weighted score có CPU/tick/rooms/connections/heap/queue, chung health/capacity gates. Fixed20Hz so với adaptive5–20Hz; cả hai gửi full snapshots tương thích. Khi tải đồng đều/thấp, RR có thể tương đương hoặc tốt hơn vì metrics/JIT noise; kết quả phải đọc từ bảng đo.

**Benchmark chứng minh gì?** Các cỡ bot, duration và điều kiện đã ghi; TCP thật, simulation thật, số JVM/PID thật, throughput và metrics thực. Không đo chất lượng WAN hoặc chứng nhận production capacity bằng smoke test. Không có số đo input-to-state latency chính xác vì v1 không ACK input sequence trong snapshot.

**Worker chết giữa trận?** TCP đóng; CP đánh LOST/ABORTED sau lease, giải phóng assignment metadata; không tạo kết quả thắng/thua giả và không migrate World. Client có thể xin trận mới. Checkpoint/replay/reconnect chưa triển khai.

**Control plane chết?** Worker không gia hạn được lease nên tự đóng server. Đây là consistency-first fail-stop prototype; production cần replicated authority để tăng availability. Chỉ tạo thêm CP process độc lập sẽ gây split-brain.

**Persistence đã có ranking chưa?** Có adapter file idempotent qua interface Member3 và lịch sử local; chưa tích hợp DB/ELO. Publisher bounded có failure states, không có durable outbox/retry bảo đảm không mất kết quả khi crash. MatchId unique không thay thế giao dịch DB.

**Nếu tăng gấp đôi worker?** Trên cùng laptop, CPU/RAM không tăng. Trên nhiều máy, còn fanout mạng, CP, DB và regional skew. Phải đo scale efficiency với cùng SLO; không khẳng định tăng đúng2 lần.

**Demo đề xuất:** start3worker → xem PID/metrics → chạy16bots hoặc2 desktop → so RR/WLA ở raw data → kill một worker qua process handle thử nghiệm → thấy LOST/ABORTED và các worker khác còn chạy → drain/shutdown. Không kill process bằng tên chung hoặc reset repo để demo.
