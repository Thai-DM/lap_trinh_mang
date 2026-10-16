# 03 — Bằng chứng và giả thuyết bottleneck

**DONE:** tìm vị trí allocation và lặp công việc. **PARTIAL:** chưa xác định bottleneck tại tải 32–64 client sau warmup chuẩn.

Trong JFR của runtime gốc, sampled allocation weights theo application frame đầu tiên gồm `JsonFrameCodec.decodeJson` 33.436.336 byte, `GameStateMessageMapper.map` 29.019.560 byte, `ProtocolMessage.<init>` 11.788.672 byte, `ArenaBoundaryController.applyBoundaries` 9.982.560 byte. Đây là trọng số ước lượng của sample, không phải tổng allocation chính xác và các nhóm không đại diện riêng simulation CPU. `HttpJson.close` 18.272.592 byte là shutdown artifact, không chọn làm mục tiêu steady-state.

Trong JFR baseline đã có instrumentation, sau khi lọc warmup: mapper ~14,48 MB sampled weight, JSON encode ~14,97 MB, writeEncodedBytes ~2,79 MB; JSON decode ~23,55 MB. Số liệu hai recording khác cửa sổ/duration/cấu hình, **không** lấy phép trừ để gọi là cải thiện allocation.

| Phát hiện | Cơ sở | Quyết định / độ chắc chắn |
|---|---|---|
| Mapping/encode lặp cho recipient cùng room | Source gọi writer encode riêng; JFR thấy mapper/codec allocation | Shared immutable frame; test 4 recipients chỉ encode 1 lần. Lợi ích CPU/p99 toàn hệ thống chưa đo. |
| Registry scan room × session | Source broadcaster lặp full registry | Index khi >=4 room; regression kiểm tra đúng room/identity. Chưa có profile nhiều phòng sau đổi. |
| Roster sort/copy khi roster không đổi | Source + samples tại boundary/getPlayerIds/tick | Cache immutable order/IDs, lazy fall set. Kiểm tra gameplay, không công bố % allocation giảm. |
| Scheduling delay đáng kể dù processing ngắn | Cửa sổ 45 s: cycle p99 1,671 ms, lag p99 16,384 ms, lag max 30,761 ms | Delay scheduler/OS/JIT/GC cần đo thêm. Không quy mọi delay cho Box2D. |
| Monitor blocking | Steady JFR thấy 1 event, duration 38,588 ms | Có blocking quan sát được; sample ít, chưa chứng minh lock nào là bottleneck kéo dài ở mọi tải. |
| Chi phí instrumentation | 28 top-frame samples ở PerformanceMetrics.record | Cần A/B disabled/enabled và kiểm tra sampling bias; chưa định lượng overhead. |
| World hoặc queue retention | Source close giữ input pending; test abort tái hiện | Clear pending input đã sửa. Balanced World lifecycle không chứng minh native allocator không phân mảnh/leak. |

Các số p99 cũ tại 32/64 client trong `docs/scalability` là cycle histogram ngắn, có warmup/startup. Chúng chứng minh đã quan sát vượt budget ở cấu hình đó, chưa chứng minh physics là thủ phạm. Một core simulation có thể nghẽn dù process CPU tính trên 8 logical CPUs còn thấp; đây là giả thuyết kiến trúc, chưa có saturation curve mới.

Không chuyển sang NIO/Netty, đổi GC hoặc sharding simulation threads chỉ từ các sample tải nhỏ này. Các thay đổi đó cần đo thread stacks, CPU/core, allocation và fairness ở COMBAT/IMBALANCED trước.
