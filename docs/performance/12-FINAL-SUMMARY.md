# 12 — Bàn giao

Trạng thái tổng thể **PARTIAL**, do bằng chứng hiệu năng sau tối ưu bị giới hạn bởi RAM. Source và verification hiện có thể review và chạy lại.

| Hạng mục | Trạng thái | Bằng chứng / phần còn thiếu |
|---|---|---|
| Audit và instrumentation | DONE / PARTIAL | 20 stage histograms, warmup/reset, JFR thực; overhead và per-room p99 chưa xác minh đủ. |
| Shared encoding và giảm copies | DONE | Byte-equivalence/concurrency test, encode 1 lần/4 recipient; CPU/p99 improvement chưa đo. |
| Roster/index và input cleanup | DONE | Regression gameplay, >=4-room ownership và abort cleanup. |
| Backpressure/rate protection | DONE / PARTIAL | Bounded slots/executors, rate buckets, stalled-write watchdog và TCP tests; chưa chạy soak slow client sau đổi. |
| Load generator | DONE / PARTIAL | 6 profiles, startup/window separation, ownership/tick/result checks; performance mới chỉ thực chạy IDLE, CHURN aggregate còn giới hạn. |
| Allocation/admission | DONE / PARTIAL | Missing/stale metrics fail closed; weights chưa calibrated, RSS chưa tham gia admission. |
| Concurrency/fair scheduling | PARTIAL | Duration/lag/lock instrumentation; shared owner/fixed-rate catch-up vẫn có hạn chế, chưa có fairness proof. |
| Native/GC | PARTIAL | 30 lifecycle rounds, World counter và JFR GC; chưa có native byte leak/long soak. |
| Before/after, RR/WLA, adaptive, scale-out | BLOCKED | Dừng tại free RAM 1,039 GB trước after point; không hạ startup guard 1500 MB/run guard 750 MB. |
| Delta, NIO migration, HA, 100M production | PROPOSED | Chưa thay protocol v1; chưa có safe worker capacity hoặc production verification. |

Kiểm thử cuối: **1407 server tests PASS** (bao gồm 311 focused, không cộng hai số), **17 client interoperability tests PASS**. Không chạy lại suite 3 JVM; bằng chứng cũ giữ riêng. Không tự commit/push/reset/merge/rebase, không thay benchmark evidence cũ trong docs/scalability.

Baseline có tách warmup đo 4 client/1 room, 45,029 s: cycle 60,004 Hz, room tick p99 1,327 ms, worker-cycle p99 1,671 ms, scheduling-lag p99 16,384 ms, 10 lag samples vượt tick budget. 20,105 deliveries/client/s và 0 lỗi là kết quả ở workload nhỏ đó. Không gọi đây là capacity limit hoặc kết quả của binary tối ưu cuối.

Các thay đổi source cuối mặc định bật shared snapshots/index/stable roster; feature flags cho phép thử tắt từng nhóm. Chưa có paired A/B nên mọi % CPU/RAM/latency cải thiện đều chưa xác định. Đề nghị đo tiếp bằng runner đã chuẩn bị trên máy còn đủ RAM, với process/source hashes đúng và mỗi output directory mới; không retry trên máy hiện tại sau safety stop.

Xem [README](README.md), [kết quả đo](08-BENCHMARKS.md), [regression](11-REGRESSION-TESTS.md) và [verification manifest](verification.json).
