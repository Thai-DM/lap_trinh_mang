# Kiểm chứng hiệu năng server

Ngày 15/10/2026, JDK 17.0.12, Windows, Intel i5-1135G7 (8 logical processors), TCP loopback. Server và bot cùng máy. Trạng thái chung: **PARTIAL**. Source, instrumentation và regression đã triển khai; so sánh hiệu năng sau tối ưu bị chặn bởi RAM.

Một cửa sổ baseline sau warmup đo được 60,004 cycle/s, worker-cycle p99 1,671 ms, scheduling-lag p99 16,384 ms, 4 client/1 phòng. Đây là phép đo tải nhỏ, chưa xác định sức chứa worker và chưa chứng minh cải thiện trước/sau.

| Nội dung | Tài liệu |
|---|---|
| Audit source, luồng thread và tài nguyên | [01-AUDIT](01-AUDIT.md) |
| Instrumentation, histogram, JFR | [02-PROFILING](02-PROFILING.md) |
| Bằng chứng và giả thuyết bottleneck | [03-BOTTLENECKS](03-BOTTLENECKS.md) |
| Các thay đổi và feature flags | [04-OPTIMIZATIONS](04-OPTIMIZATIONS.md) |
| Concurrency và scheduling | [05-CONCURRENCY](05-CONCURRENCY.md) |
| TCP, serialization và backpressure | [06-NETWORK-PERFORMANCE](06-NETWORK-PERFORMANCE.md) |
| Heap, RSS, GC và Box2D | [07-MEMORY-GC](07-MEMORY-GC.md) |
| Kết quả và lệnh tái lập | [08-BENCHMARKS](08-BENCHMARKS.md) |
| Worker allocation, adaptive snapshot | [09-SCALING](09-SCALING.md) |
| Mô hình 100M CCU và giới hạn | [10-CAPACITY-100M](10-CAPACITY-100M.md) |
| Regression và bằng chứng kiểm thử | [11-REGRESSION-TESTS](11-REGRESSION-TESTS.md) |
| Trạng thái bàn giao | [12-FINAL-SUMMARY](12-FINAL-SUMMARY.md) |

Kết quả gốc tại [runs/20261015-221532](runs/20261015-221532), kiểm thử cuối tại [regression-final](runs/20261015-221532/regression-final). [verification.json](verification.json) liên kết manifest của source cuối. Kết quả cũ trong `docs/scalability/` được giữ nguyên.

Git lưu source, cấu hình, JSON/CSV tổng hợp và log kiểm thử. Các recording `.jfr` thô được giữ cục bộ và bỏ qua khi commit vì có thể chứa environment/system properties của máy chạy; có thể tạo recording mới bằng runner với `--jfr`.

## Chạy lại

Từ repository root, sau khi `:server:installCluster` đã chuẩn bị dependencies:

```powershell
.\scripts\performance\Verify-Regression.ps1 -JavaHome 'E:\2026-2\lap-trinh-mang\baitap' -OutputDirectory '.\docs\performance\runs\my-new-regression'
python -B scripts/performance/run.py --java-home 'E:\2026-2\lap-trinh-mang\baitap' --clients 4,16 --profiles IDLE --variants baseline,shared,indexed,final --warmup 10 --seconds 45 --repeats 2 --jfr
```

Chọn thư mục output mới cho mỗi lần chạy. Runner kiểm tra RAM trước mỗi điểm đo và trong quá trình chạy, dừng ngay khi thiếu tài nguyên. Không hạ các ngưỡng để hoàn tất ma trận.
