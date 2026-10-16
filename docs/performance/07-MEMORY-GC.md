# 07 — Bộ nhớ, native resources và GC

**DONE:** JVM/RSS telemetry và repeated lifecycle regression. **PARTIAL:** chưa có long soak/heap dominators/native-byte leak accounting.

`JvmMetrics` xuất heap committed, nonheap used, metaspace, direct/mapped buffer bytes/count, live/peak thread count, live-thread allocated bytes và live Box2D World count. Worker/standalone đã có heap used/max và GC collection millis. Runner lấy process RSS bên ngoài bằng PID; RSS có cả heap resident và native/runtime/stacks, không cộng RSS với heap used để tính tổng.

`allocatedLiveThreadBytes` là tổng các counter của **thread còn sống lúc sample**. Delta có thể sai khi threads kết thúc/thay đổi tập; không gọi nó là chính xác process allocation rate. GC collection millis là counter cộng dồn MXBean, không phải pause p99. Pause statistics thực trong tài liệu này lấy từ JFR event riêng.

Trong cửa sổ instrumented baseline 45,029 s: RSS sample cao nhất 141.721.600 byte (~135,2 MiB), 10 JFR GC pause events, max/p99 quan sát 2,433 ms. JFR bắt đầu từ startup nhưng các số pause này đã lọc theo cửa sổ. Với chỉ 10 event, p99 quan sát chính là max, không phải cam kết latency dài hạn. Một monitor-enter event có duration 38,588 ms; không có SocketWrite event vượt điều kiện ghi nhận của recording, không suy ra write cost bằng 0.

`-XX:NativeMemoryTracking=summary` được bật trong runner, nhưng phiên này chưa thu `jcmd VM.native_memory summary` theo chu kỳ. Không có NMT bytes report để báo cáo. Ngay cả NMT cũng không đại diện đầy đủ mọi allocation bên trong thư viện Box2D bên ngoài JVM. Live World counter chỉ tăng khi World tạo thành công và giảm khi dispose thành công; nó không đo native heap bytes, allocator retention hoặc fragmentation.

`RepeatedRoomResourceTest` dùng production RoomManager/scheduler với clock/executor điều khiển: 30 trận 4 player, mỗi trận 180 tick có jump/melee/ability, cả 30 trận đều thấy projectile; kết thúc và remove phòng. Mỗi vòng kiểm tra room count về 0, input pending 0, latest snapshot null, World disposed và live World count về baseline. Đây là lifecycle test, không phải 30 vòng TCP churn. TCP result/history/cleanup/reuse được kiểm tra riêng qua integration tests. Chưa đo RSS slope qua nhiều giờ.

Phát hiện và sửa retention cụ thể: đóng runtime chưa finished trước đây giữ pending input queue; hiện clear trong close, có regression đóng hai lần. Roster/cache mới chỉ giữ dữ liệu immutable; frame cache không giữ guard lambda của recipient. Cache giữ một frame/phòng, cap 1024, có prune/close; số entry bị giới hạn không có nghĩa tất cả process memory đã được chứng minh không leak.

Ngưỡng benchmark giữ nguyên: khởi động tối thiểu 1500 MB available, và có thể cao hơn theo số worker/heap; trong run tối thiểu 750 MB. A/B đã dừng khi available=1.039.077.376 byte trước điểm `shared`. Không retry tải sau stop. Không tăng heap/đổi collector hoặc dùng forced GC để che pressure.

Các export JSON JFR ban đầu rất lớn (một bản ~350 MB), đã được chuyển nguyên vẹn vào `final-project/build/performance/jfr-exports/20261015-221532/`; không xóa dữ liệu. Recording `.jfr`, kết quả tổng hợp và samples nhỏ vẫn ở docs. Analyzer hiện đọc stream từ `jfr print`, không cần giữ full export trong RAM. Cần khi tái lập: lưu file JFR gốc; chỉ export JSON nếu cần debug.
