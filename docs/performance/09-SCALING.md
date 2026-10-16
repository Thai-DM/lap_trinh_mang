# 09 — Worker, admission và adaptive snapshot

**DONE:** kiểm tra allocator/reservation/freshness và regression. **BLOCKED:** benchmark RR/WLA, 1→2→3 worker và fixed/adaptive trên source cuối.

Control plane chọn owner theo region rồi reserve room/slot trong cùng synchronized operation. Số room/connection dùng max(reservation, report) tránh admission race trước heartbeat kế tiếp. Một room có một worker owner; ticket claim/replay/room binding được kiểm tra. Worker mất lease bị fence, room owner mất được đánh dấu mất/abort; không tự chuyển trận đang chơi sang worker khác khi chưa có checkpoint an toàn.

RR và WLA cùng loại worker không đủ capacity. Điều kiện: room còn chỗ, dự trữ thêm tối đa 4 connections, CPU <0,90, cycle p99 <16,667 ms, heapRatio <0,90, outbound depth <max(8, 2×connections). WLA score hiện có sáu thành phần chuẩn hóa:

```text
0.20 * CPU
+ 0.25 * cycleP99 / 16.666667
+ 0.20 * rooms / maxRooms
+ 0.10 * connections / maxConnections
+ 0.15 * heapRatio
+ 0.10 * outboundDepth / max(1, connections)
```

Trọng số là heuristic prototype, chưa được fit bằng benchmark. Không gọi WLA tốt hơn RR. HeapRatio không phải process memory headroom; RSS mới được runner đo ngoài process, chưa đưa vào score hay worker report. Estimated room cost, admission latency histogram và recent-overload incident ring chưa triển khai.

Freshness mới yêu cầu CPU, cycle p99, heap ratio, activeConnections, activeRooms, outboundQueueDepth, sampledAtMillis, metricsValidForMillis là số finite không âm. Tuổi metric phải >=0 và nhỏ hơn validity, validity tối đa 2500 ms. Missing/stale metric giữ heartbeat/liveness độc lập nhưng không nhận room mới; capacity gate sử dụng pressure bão hòa nội bộ, raw report vẫn giữ nguyên. Rejection có admission cooldown 3 s. Test kiểm tra missing report, fresh report, stale report và hồi phục sau cooldown. Trong cluster đa máy sẽ cần xử lý clock skew tốt hơn timestamp local prototype; hiện child/control cùng host.

Metric cycle cũ dùng rolling 4096 sample, khoảng 68 s ở 60 Hz. Timestamp mới không biến nó thành histogram 1 s; load window mới dưới `performance` tách riêng. Adaptive quan sát report khoảng mỗi giây, giảm sau 3 pressure observations, tăng sau 10 healthy observations; chỉ chọn snapshot rates là ước của 60, trong min/max config. Đầu vào hiện có queue pressure, send duration và cycle p99. Đây là hysteresis theo số quan sát, không có một wall-clock cooldown riêng hoặc feedback actual delivery/freshness. Simulation vẫn 60 Hz, start/end giữ nguyên. Chưa có bằng chứng thực adaptive giảm CPU/bytes hoặc giữ rendering consistency tốt hơn trên workload sau đổi.

DRAINING chặn room mới, trận hiện tại tiếp tục cho tới deadline shutdown/grace policy; stopEmpty chỉ khi worker không sở hữu room. Test deterministic kiểm tra ownership/drain; integration 3 JVM/16 bot ở lượt trước có bằng chứng trong `docs/scalability/verification.json`, không coi là đã chạy lại với thay đổi phiên này. Client integration mới chạy control+worker in-process để kiểm tra fresh metrics/ticket/TCP compatibility, không thay thế test JVM isolation.

Điều kiện công bố horizontal scaling: cùng tổng bot/room/workload, measurements đủ dài và lặp; tổng throughput và **worst worker** cycle/lag, distribution room, errors, CPU/RSS tổng và mỗi process, admission success/latency, generator headroom. Nếu máy phát bot nghẽn hoặc bất kỳ worker vi phạm correctness thì không tăng tải. Hiện chưa có số safe rooms/worker hay speedup factor được xác minh.
