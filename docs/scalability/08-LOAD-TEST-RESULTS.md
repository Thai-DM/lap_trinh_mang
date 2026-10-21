# Kết quả load test thực tế — 15/10/2026

## Điều kiện và phương pháp

Windows10 Home, Intel Core i5-1135G7 2.40GHz, 8 logical processors, RAM khả dụng hệ điều hành khoảng7.77GiB, JDK17.0.12, Gradle7.3.3. Lúc bắt đầu còn khoảng1.97GB RAM trống. Bot và server cùng laptop, TCP loopback; không có WAN, TLS hay network shaping. Heap mỗi JVM giới hạn512MiB. Autoscaling tắt khi benchmark.

Mỗi điểm đo khởi động JVM mới; bot CONNECT/CREATE/JOIN/READY, đợi GAME_START (countdown3s), gửi input hợp lệ20Hz, đọc/giải mã full GAME_STATE, trả PING/PONG, rồi đóng TCP. Mỗi lần đo12s sau warmup; thực hiện2 lần/cỡ khi bộ nhớ cho phép. Bot đứng yên, không tạo projectile/combat load. Tick histogram gồm toàn cycle worker và có cả cold-start/JIT/GC; chưa reset riêng sau warmup. Số liệu không đủ để chứng nhận sustained capacity.

Nguồn: [environment](results/environment.json), [summary.csv](results/summary.csv), JSON từng lần trong `results/`. [runtime.json](results/runtime.json) ghi SHA256 JAR và CPU thực. Bộ benchmark hoàn tất trước bản sửa nhỏ về roster matchmaking; CREATE/JOIN benchmark không sử dụng roster gate mới. Source cuối đã được compile và hồi quy riêng.

## A — Một GameServer standalone

| Clients | Rooms | Số lần | Deliveries/s trung bình | Deliveries/client/s | Tick p99 xấu nhất (ms) | Tick vượt16.67ms / cycles | Heap worker cao nhất (MiB) |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 2 | 1 | 2 | 40.05 | 20.026 | 2.620 | 3/1942 | 28.68 |
| 4 | 1 | 2 | 80.31 | 20.078 | 2.203 | 4/1946 | 33.44 |
| 16 | 4 | 2 | 320.29 | 20.018 | 8.889 | 5/1927 | 42.90 |
| 32 | 8 | 2 | 642.78 | 20.087 | 24.005 | 16/1928 | 43.93 |
| 64 | 16 | 1 | 1277.78 | 19.965 | 38.886 | 29/1063 | 44.97 |

Tất cả9 lần đã hoàn tất có connectionFailures=0, protocolErrors=0, unexpectedDisconnects=0. Giá trị hơi trên20Hz do cửa sổ12s và frame ở biên thời gian; không có cấu hình snapshot trên20Hz.

Tại64 clients, outbound ứng dụng client quan sát khoảng1.417MB/s, gồm frame header và control messages; processCPU cao nhất trong samples khoảng40.1% tổng8 logical processors. Không bao gồm Ethernet/IP/TCP overhead và retransmission. Raw metrics có byte/message totals, input queue, outbound queue, heap, CPU, GC collection time và tick budget counts. Heap không phải RSS toàn process; runner mới có hỗ trợ RSS cho lượt đo sau, chưa có RSS trong9 lượt này.

Ở32/64 clients, snapshot delivery vẫn gần20Hz nhưng p99 cycle vượt16.67ms. Vì vậy không kết luận game tick đạt SLO chỉ từ snapshot throughput. Điểm16 clients vượt qua các điều kiện đo ngắn hạn ở cả2 lần; cũng chưa phải công suất worker production.

## B/C/D — Ba worker RR, WLA, adaptive

**BLOCKED về benchmark so sánh đầy đủ do RAM.** Runner dừng trước lượt64 thứ2 khi RAM trống dưới ngưỡng1.5GB; các scenario3worker chưa bắt đầu. Khi kiểm tra lại, chỉ còn khoảng500MB trống; lần kiểm tra điều kiện trước khi khởi động cũng dừng. Người dùng chọn ghi nhận giới hạn tài nguyên và dừng tăng tải.

[comparison/environment.json](results/comparison/environment.json) và các `*-safety-stop.json` ghi bộ nhớ/ngưỡng/status thực, không chứa throughput giả. 100/500/1000 clients chưa chạy. Không có kết luận WLA nhanh hơn RR hoặc adaptive tiết kiệm bao nhiêu băng thông.

Kiểm thử chức năng3 JVM đã chạy thành công trước khi RAM giảm: `ClusterProcessIntegrationTest` khởi3 worker PID khác nhau, chạy16 TCP bots trên cả3worker, kiểm tra mọi client START/snapshot, không protocol/connection error, rồi thử replay, cross-room, worker kill và CP loss. Đây là integration smoke với assertions, không thay cho benchmark RR/WLA/adaptive có lặp. Output Gradle trước đó BUILD SUCCESSFUL; test được cung cấp để chạy lại khi đủ RAM.

## Chưa đo và cách tiếp tục

- Input-to-state latency: chưa đo chính xác vì snapshot v1 không ACK input sequence; không lấy ping RTT thay cho latency authoritative.
- Per-connection RAM/native Box2D/stack/kernel buffers: chưa tách; không chia heap đơn giản cho số clients để công bố RAM/connection.
- GC pause p95/p99: chỉ có MXBean collection time cộng dồn, không phải pause histogram.
- Soak test, combat/projectile stress, WAN, nhiều máy và100M CCU: chưa thực hiện.

Khi có đủ tài nguyên, chạy runner với `--max-clients 100`,2+ repeats, sau đó tăng500/1000 nếu không có resource/SLO failures. Dùng thư mục output mới để giữ evidence cũ; mỗi datapoint cần warmup dài hơn và đo vài phút trước capacity conclusion. `--minimum-start-memory-mb` và `--minimum-run-memory-mb` cho phép chọn ngưỡng phù hợp; mặc định1500/750MB.
