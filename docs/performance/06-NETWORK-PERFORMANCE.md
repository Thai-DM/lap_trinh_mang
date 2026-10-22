# 06 — TCP và backpressure

**DONE:** giữ framing v1, shared encoding, bounds và timeout. **PARTIAL:** chưa chạy full SLOW_CLIENT/COMBAT benchmark sau đổi.

Snapshot construction ở simulation tạo bản sao immutable; writer đầu của cùng room mới map/encode JSON. Vì vậy encode-once không chuyển chi phí serialization sang game loop. Đường shared chỉ dùng với `JsonTcpMessageCodec` cuối cùng đã biết; codec tùy biến vẫn encode riêng. Từng session có send lock và channel write lock, không xen byte giữa frame/control/final state. Frame bytes không exposed ra caller, không dùng chung Jackson mutable tree.

`SharedSnapshotCacheTest` xác minh bốn recipient đồng thời nhận bytes bằng chính xác encode v1 cũ, snapshotEncodes=1, cacheHits=3. Đây là bằng chứng giảm số lần encode trong tình huống xác định, chưa phải số % giảm CPU hoặc toàn bộ allocation. Writer đến muộn với tick cũ nhận frame tick đó; không lấy bytes của tick mới hơn. Terminal ordering và final snapshot/GAME_END vẫn do writer quản lý.

| Hàng đợi / tài nguyên | Giới hạn và chính sách |
|---|---|
| Input | 32/player; input vượt queue/sequence không vào simulation. |
| Snapshot pending | 1/session; thay snapshot chưa gửi bằng snapshot mới. Không cắt frame đang ghi dở. |
| Heartbeat / start | Slot riêng; không bị cơ chế replace periodic snapshot loại bỏ. |
| Terminal deliveries | Tối đa 4; không silently drop critical end để nhận thêm state. |
| I/O executors | Tối đa maxConnections readers và writers; SynchronousQueue reject khi đầy. |
| Shared room frames | Tối đa 1024 entries cộng các frame writer đang sử dụng; mỗi entry một frame mới nhất. |
| HTTP / result handoff | HTTP queue 128; ranking queue 128, tracking 4096; phản ánh reject/failure. |

Thêm token buckets, mỗi bucket dùng memory cố định:

| Loại | Refill / burst mặc định | Cấu hình JVM |
|---|---|---|
| PLAYER_INPUT/session | 240/s, 480 | `smashprofs.network.inputsPerSecond` |
| Control/session | 128/s, 256 | `smashprofs.network.controlsPerSecond` |
| CREATE_ROOM/session | 8/s, 16 | `smashprofs.network.createsPerSecond` |
| Inbound bytes/session | 2.097.152/s, 4.194.304 | `smashprofs.network.inboundBytesPerSecond` |
| Invalid application frames/session | 8/s, 32 | Hằng số trong connection |
| TCP accepts/transport | 128/s, 256 | Hằng số trong transport |
| Guest auth/control plane | 100/s, 200 | Hằng số trong control plane |

Các property chỉ đổi refill, burst giữ nguyên. Sau 8 request-rate violations session bị đóng; invalid JSON/envelope flood vượt bucket cũng đóng. Oversized/corrupt framing vẫn đóng theo codec. Đây là bảo vệ tài nguyên cho prototype loopback, chưa phải chống DDoS hoặc rate limit phân tán theo account/IP. RoomManager có cap mặc định 1024 (`smashprofs.server.maxRooms`); worker còn có reservation/cap riêng.

Heartbeat maintenance kiểm tra write timeout mặc định 5000 ms, property `smashprofs.network.writeTimeoutMillis`. Với interval heartbeat 5 s, standalone có thể phát hiện trong khoảng 5–10 s tùy pha; không hứa deadline chính xác 5 s. Worker có watchdog khoảng 200 ms với timeout 5 s. Close socket giải phóng writer bị block, ảnh hưởng riêng session. Test channel bị chặn và TCP backpressure kiểm tra simulation tiếp tục; soak slow-reader nhiều phút còn **BLOCKED** vì tài nguyên.

Không đổi protocol version, framing, gameplay input schema hoặc chế độ offline. Chưa dùng compression/binary encoding; thay đổi đó phải có thương lượng tương thích và đo CPU/bytes/freshness riêng.
