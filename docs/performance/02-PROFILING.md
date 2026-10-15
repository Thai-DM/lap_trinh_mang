# 02 — Instrumentation và JFR

**DONE:** instrumentation, reset measurement, JFR thực. **PARTIAL:** overhead chưa có phép đo A/B kiểm soát.

`PerformanceMetrics` dùng `System.nanoTime()` và 20 histogram cố định, mỗi histogram 4096 bin (~640 KiB phần mảng/worker). Không lưu một object cho mỗi sample. Mỗi stage xuất count, mean, p50/p95/p99, max, số lần và tỷ lệ vượt 16.666.666 ns. Quantile là upper bound của bin, sai số bin tương đối <=1,57% trên 64 ns; count/mean/max dùng sample thực. Histogram có synchronized update; đây là chi phí cần đo, không được gọi là miễn phí.

| Nhóm | Stages |
|---|---|
| Input / monitor | INPUT_VALIDATION, INPUT_QUEUE_WAIT, INPUT_DEQUEUE, INPUT_LOCK_WAIT, TICK_LOCK_WAIT |
| Simulation | MOVEMENT, COMBAT, PROJECTILES, PHYSICS_STEP, COLLISION_CALLBACKS, POST_PHYSICS, SNAPSHOT_BUILD, ROOM_TICK |
| Network | SNAPSHOT_ENQUEUE, JSON_SERIALIZATION, NETWORK_WRITE, SEND_LOCK_WAIT |
| Scheduler | WORKER_CYCLE, SCHEDULING_LAG, ROOM_SCHEDULING_DELAY |

ROOM_TICK là toàn bộ tick runtime sau khi lấy monitor, gồm input, gameplay, physics và snapshot. PHYSICS_STEP chỉ `World.step`; collision callbacks nằm **bên trong** physics, không cộng hai duration để suy ra tổng. WORKER_CYCLE gồm update lifecycle, các room và after-cycle/broadcast. Scheduling lag so với lịch fixed-rate dự kiến; room scheduling delay là vị trí bắt đầu room trong cycle. `tickP99Ms` cũ vẫn là rolling **worker cycle** để không phá API; `performance.stages` là whole-window histogram mới. Per-room `roomTicks` cung cấp count/mean/max, chưa có p99 cho từng phòng.

Load generator đợi GAME_START, chạy gameplay warmup 10 s, chụp startup metrics, gọi endpoint có secret `/measurement/reset`, rồi đo 45 s. Reset không reset physics/gameplay. Histogram reset và records từ các thread không tạo một global stop-the-world transaction; sample biên có thể lệch một vài frame/cycle. Vì vậy các khoảng 45 s và throughput có jitter nhỏ. Clock wall time chỉ dùng cho timestamp/freshness và lọc event JFR; duration dùng nanoTime.

JFR baseline gốc được attach giữa lần đo thứ hai, có cả shutdown. Phân tích đúng là `baseline-original/jfr-analysis-v2.json`; bản `jfr-summary.json` sớm đã gộp native/execution, không dùng để kết luận CPU. Phép đo instrumented bật JFR từ startup; [steady summary](runs/20261015-221532/shared-ab/IDLE-4-baseline-1/jfr-steady-summary-v2.json) lọc event theo thời điểm bắt đầu measurement, không trộn warmup. Duration event cắt qua biên chưa được clip.

Trong cửa sổ này: 425 ExecutionSample, 1613 NativeMethodSample, 5158 allocation samples, 10 GC pause events, 1 monitor-enter event. Các native frame `Net.poll/accept/WEPoll.wait` thường là vị trí đang block; không diễn giải chúng thành tỷ lệ CPU. `PerformanceMetrics.record` có 28 execution top-frame samples: instrumentation là một yếu tố gây nhiễu cần so sánh bật/tắt trên máy đủ tài nguyên. Không suy ra phần trăm overhead từ số sample này.

`scripts/performance/analyze_jfr.py` dùng `jfr print`, đọc event tuần tự để không load export JSON hàng trăm MB vào RAM. Recording JFR gốc là bằng chứng chính; các bản export JSON ban đầu được giữ dưới build như mô tả ở [07](07-MEMORY-GC.md). Công cụ và flags tham chiếu [JDK 17 jfr](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jfr.html) và [Java launcher](https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html).
