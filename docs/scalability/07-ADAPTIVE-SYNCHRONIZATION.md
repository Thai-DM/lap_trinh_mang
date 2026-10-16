# Full snapshot baseline/adaptive và delta

`snapshot.mode=BASELINE` giữ 20 Hz theo `snapshot.hz`. `ADAPTIVE` dùng `AdaptiveSnapshotPolicy` cập nhật mỗi lần lấy metrics worker (khoảng 1s). Simulation vẫn 60 Hz. Snapshot rates phải là ước của 60 để tái sử dụng nhịp broadcaster hiện có.

Pressure nếu một trong các điều kiện: outbound queue >max(1,clients/2); lần send gần nhất chậm nhất >50ms; cycle p99 >12ms. Clients tham gia ngưỡng queue nên cùng một queue depth được diễn giải theo số recipient. Ba observation có pressure liên tiếp giảm một bậc; mười observation khỏe liên tiếp tăng một bậc. Rate nằm trong min–baseline; mặc định 5–20 Hz. Ví dụ giảm 20→15→12→10→6→5. Đây là hysteresis với khoảng phục hồi dài hơn giảm tải.

Mỗi connection chỉ có một GAME_STATE chưa ghi; snapshot mới thay snapshot cũ. GAME_START, heartbeat và cặp final GAME_STATE/GAME_END có bounded control slots riêng, không bị latest-wins xóa. Encoder và Socket.write chạy ngoài simulation thread. Không cắt ngang frame đang ghi; worker watchdog đóng connection nếu write quá 5s.

Theo [Java 17 Socket](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/Socket.html#setSoTimeout(int)), SO_TIMEOUT giới hạn read; vì vậy không dùng read timeout để giả định write có deadline. Prototype dùng maintenance đóng socket đang write quá hạn. Transport hiện tại giữ blocking I/O để tận dụng regression tests. NIO/Netty sẽ cần benchmark thread/stack pressure và partial-write state machine trước khi thay.

Full snapshot độc lập baseline nên TCP coalescing không làm hỏng state. Rate thấp hơn có thể tăng interpolation delay hoặc jitter hình ảnh; benchmark headless không xác minh chất lượng cảm nhận. Terminal pushes được giữ nguyên dù rate giảm.

## Delta: PROPOSED, chưa triển khai trên wire

Cần capability/version rõ ràng; mỗi delta có matchId, snapshotVersion và baseVersion. Client chỉ apply nếu có đúng base; thiếu baseline phải xin full, không merge với state tùy ý. Gửi full định kỳ và khi resume/resync; gắn baseline với chính frame được truyền sau latest-wins, không gắn với snapshot chỉ được offer rồi thay mất. Thiết kế ACK/baseline cần xét TCP ordered delivery nhưng queue local vẫn có thể coalesce trước encode.

Không tự lược fields v1, vì parser hiện tại yêu cầu full player/projectile state. Triển khai delta an toàn đòi hỏi client codec/buffer/negotiation tests riêng; prototype giữ full snapshots ở cả hai mode.
