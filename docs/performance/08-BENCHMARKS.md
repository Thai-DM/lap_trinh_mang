# 08 — Phép đo và tái lập

**DONE:** hai baseline gốc, một baseline có measurement reset và JFR. **BLOCKED:** paired after, tăng tải, nhiều worker và toàn bộ ma trận workload.

Máy: Windows, i5-1135G7, 8 logical processors, tổng RAM khoảng 7,77 GiB; available thay đổi theo ứng dụng khác. JDK 17.0.12 được chọn bằng đường dẫn riêng vì Java trong PATH là 8. Bot và server chạy loopback trên cùng laptop, không đo WAN hoặc máy phát tải độc lập.

| Thí nghiệm | Warmup / cửa sổ | Clients / rooms | Deliveries/client/s | Cycle p99 | Ghi chú |
|---|---|---|---|---|---|
| Runtime gốc, lần 1 | Runner cũ, 30 s | 4 / 1 | 19,993 | 1,731 ms | Rolling worst observed; warmup histogram chưa tách. |
| Runtime gốc, lần 2 | Runner cũ, 30 s | 4 / 1 | 19,996 | 1,970 ms | JFR attach giữa run; CPU không so trực tiếp với lần 1. |
| Instrumented baseline, lần 1 | GAME_START + 10 s gameplay warmup; window 45,029 s | 4 / 1 | 20,105 | 1,671 ms | Whole-window histogram; cả ba tối ưu disabled. |
| Shared / indexed / final | Chưa chạy | — | — | — | Guard dừng trước shared; không có after sample. |

Nguồn: [original summary](runs/20261015-221532/baseline-original/summary.csv), [instrumented summary](runs/20261015-221532/shared-ab/IDLE-4-baseline-1/summary.json). Không dùng khác biệt giữa hai loại runner để tính phần trăm cải thiện. Hash binary/source, JDK, flags, config và Git status được ghi ở manifest từng run; source cuối có sửa tiếp sau phép đo.

Chi tiết cửa sổ instrumented baseline:

| Metric | Mean | p50 | p95 | p99 | Max / vượt budget |
|---|---|---|---|---|---|
| ROOM_TICK | 0,298 ms | 0,186 ms | 0,852 ms | 1,327 ms | 10,503 ms; 0/2702 |
| WORKER_CYCLE | 0,407 ms | 0,266 ms | 1,147 ms | 1,671 ms | 10,606 ms; 0/2702 |
| SCHEDULING_LAG | 6,598 ms | 5,702 ms | 15,073 ms | 16,384 ms | 30,761 ms; 10/2702 (0,370%) |

Actual cycle rate 60,004 Hz; 4/4 bot start và nhận snapshot, 0 connection failure, 0 protocol error, 0 unexpected disconnect. 3600 snapshot encodes, cache hits 0 vì shared disabled. Snapshot delivery count tại bot không nhất thiết bằng encode count: client/server boundaries lấy mẫu lệch nhau và có jitter/buffer. Thời gian nhận snapshot 20 Hz không tự chứng minh tick 60 Hz.

`performanceSlo: PASS` trong summary đã lưu chỉ kiểm tra cycle p99 <16,667 ms, frequency 59–61 Hz, correctness và trận chưa kết thúc trong warmup. Nó không khẳng định mọi deadline đạt; 10 scheduling-lag sample vượt budget vẫn tồn tại. Runner mới bổ sung trường giải thích scope và `deadlineSlo` riêng (lag p99 dưới budget và <=1% sample quá budget). Không sửa lại kết quả đã lưu để đổi điều kiện thành công.

[Safety stop](runs/20261015-221532/shared-ab/safety-stop.json): trước `IDLE-4-shared-1`, free=1.039.077.376 byte, nhỏ hơn ngưỡng startup 1500 MB; completedDataPoints=1. Run guard 750 MB vẫn giữ. Các trường còn thiếu được ghi BLOCKED, không gán 0 hoặc lấy số từ test thay benchmark. Không có safe-capacity result mới.

## Runner và profiles

`scripts/performance/run.py` dùng fresh JVM mỗi điểm, output directory duy nhất, alternating A/B order giữa repeats, heap 128 MiB mặc định, JFR cap 16 MiB, samples khoảng 1 s. Total gameplay warmup + measurement tối đa 100 s để tránh time-limit 120 s bị nhầm thành mất throughput. Đây chưa phải long soak hoặc đảm bảo JIT đã ổn định hoàn toàn.

Profiles: IDLE (input hợp lệ đứng yên), MOVEMENT (20 Hz đổi hướng/jump), COMBAT (movement/melee/stomp/block/ability), CHURN (nhiều phiên mới, cluster cleanup), SLOW_CLIENT (một bot/phòng đọc chậm, socket receive buffer nhỏ), IMBALANCED (phòng nặng xen phòng idle). Protocol unit test kiểm tra 100 input/profile; performance chỉ thực chạy IDLE trong phiên này. Combat có thể kết thúc trận sớm; `completedBeforeMeasurement` khiến điểm đó không đủ điều kiện SLO steady-state. CHURN lưu các rounds và tổng lỗi, nhưng throughput/worker metrics cấp trên hiện là round cuối; phải đọc từng round, không gọi đó là sustained churn capacity. Slow disconnect riêng được ghi là expected; các lỗi protocol/ownership vẫn là lỗi.

```powershell
# Chạy khi đủ RAM; không lặp lại để vượt guard.
python -B scripts/performance/run.py --java-home 'E:\2026-2\lap-trinh-mang\baitap' --clients 4,16,32 --profiles IDLE,MOVEMENT,COMBAT --variants baseline,shared,indexed,final --workers 1 --warmup 10 --seconds 45 --repeats 2 --jfr
python -B scripts/performance/run.py --java-home 'E:\2026-2\lap-trinh-mang\baitap' --clients 16,32 --profiles IMBALANCED,SLOW_CLIENT --variants final --workers 3 --algorithm ROUND_ROBIN --snapshot-mode BASELINE --repeats 2
python -B scripts/performance/run.py --java-home 'E:\2026-2\lap-trinh-mang\baitap' --clients 16,32 --profiles IMBALANCED,SLOW_CLIENT --variants final --workers 3 --algorithm LOAD_AWARE --snapshot-mode ADAPTIVE --repeats 2
```

Để phân biệt ảnh hưởng allocator và adaptive, chạy đủ bốn tổ hợp RR/WLA × fixed/adaptive, cùng nguồn bot và tổng tải. Các lệnh trên chỉ minh họa hai góc của ma trận, không phải phép so sánh chỉ một biến. Có thể thêm `--runtime <frozen-directory>`; luôn giữ hashes. Trong Windows runner chỉ quản lý PID đã tạo, shutdown qua admin trước, không kill Java theo tên. Safety/runtime failures được lưu failure/stop JSON, phải đọc status thay vì dựa vào exit code runner đơn thuần.
