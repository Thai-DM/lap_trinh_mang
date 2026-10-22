# Bước 44 — Authoritative GAME_STATE broadcast

## Kết quả và phạm vi

Server tự publish GAME_STATE cho các connection đã JOIN cùng room, 20 Hz mặc định trên simulation 60 Hz. Không tạo World/GameLoop/MatchState/Builder thứ hai, không sửa `MatchRuntime.tick()`, physics, combat, map hoặc client UI. Không phát GAME_END/MATCH_RESULT/ranking.

Đã đọc contract v1/common DTO và các API networking, room/runtime/scheduler hiện có trước khi triển khai. Legacy common.PlayerState có int HP, nhưng wire v1 đã quy định float từ Bước 43B: dùng adapter server thay vì phá DTO cũ. Không đổi wire version/schema/envelope/framing. Common chỉ cập nhật golden GAME_STATE và assertions tương ứng.

## Luồng và nguồn dữ liệu

```text
FixedTickScheduler (60 Hz)
  -> existing tick / existing GameSnapshotBuilder
  -> afterCycle: StateBroadcaster
  -> room/session validation + offer immutable GameStateDelivery
  -> bounded per-connection latest-wins slot
  -> outbound worker: GameStateMessageMapper
  -> existing JsonTcpMessageCodec -> common JsonFrameCodec
  -> existing TcpSessionChannel atomic frame write
```

Snapshot dùng nguyên `MatchRuntime.getLatestSnapshot()`. Dưới khóa runtime hiện có, publisher chụp reference bất biến, finished flag và remaining time nhất quán, rồi nhả khóa trước offer. Getter remaining time chỉ dùng nanoClock/matchStartedAtNanos/MATCH_DURATION_NANOS 120 giây hiện có; không thay timer hoặc đọc Body. Làm tròn lên ms; terminal do elimination có thể còn thời gian, timeout là 0.

Payload đúng v1: matchId, roomId, serverTick, phase, remainingTimeMs, players, projectiles. Player giữ HP float, ID/slot/character, tọa độ/vận tốc, grounded/blocking/dead/facingRight. Projectiles dùng active immutable View đã có. Adapter sort slotIndex/projectileId; không đưa session/native resources vào JSON.

World x/y meters, velocity meters/s, Y hướng lên, Body center; không thêm quy đổi tọa độ tại network. Core PPM và MapCollisionBuilder.PIXELS_PER_METER đều 100. Client dùng schema v1 và world-space, không DTO HP int cũ.

## Cadence, recipients và lifecycle

- Default 20 snapshots/s, gửi tick 3/6/9/... khi có snapshot hoàn tất. Cấu hình qua `GameServer(host, port, maxConnections, snapshotsPerSecond)`, validate ước số dương của 60; unit test cả 10/20/30/60 và stride 30 Hz. Không đổi scheduler period hay World.step.
- Deduplicate theo room/runtime identity + tick, không chỉ global tick. Không replay một snapshot nhiều lần.
- RoomManager cung cấp immutable registry snapshot. Recipient phải open, đúng registered instance, PlayerId nằm trong roster và session.roomId khớp room. Revalidate queued delivery trước encode; không nhận arbitrary recipient từ JSON, không spectator.
- WAITING/READY/COUNTDOWN không broadcast; PLAYING theo cadence; FINISHED offer terminal đúng một lần, ngoài cadence nếu cần, không tiếp tục gameplay vô hạn và không gửi GAME_END.
- Room/runtime close/delete làm delivery invalid; pending bị discard. Server stop vô hiệu publisher trước khi đóng transport, unblock sockets/queues, shutdown/join read/write pools rồi scheduler và native runtime. Restart có publisher, registries và connections mới.

## Backpressure / thread safety

Transport có pool outbound riêng, tối đa maxConnections, SynchronousQueue không backlog task; mỗi connection tối đa một long-lived writer. Không dùng read pool đang bị chiếm bởi blocking reads cho broadcast. Worker không gọi tick/JNI.

Mỗi connection tối đa một pending snapshot và một claimed/in-flight delivery/frame. Pending mới thay pending cũ; trước encode còn có thể lấy pending mới hơn cùng match. Không cắt ngang frame đã ghi. Những tick bỏ qua là coalescing, không giảm simulation frequency.

Control send giữ API đồng bộ cũ trên caller/read worker; không nằm trong snapshot slot và không bị drop. Fair send lock giữ encode+complete frame write, giúp control đang chờ được gửi trước snapshot tiếp theo. Không serialize/write trong simulation hook. Socket.close không cần send lock, nên unblock write/read đang nghẽn và clear pending.

Lỗi encode (kể cả oversized) hoặc I/O đóng connection đó, không chặn client/room khác. Không gửi raw JSON hoặc tự frame lần hai. Giới hạn payload 1 MiB/total frame 1 MiB+4 giữ nguyên v1.

## File tạo mới (đường dẫn source tính từ Gradle root)

- `server/src/com/smashprofs/server/network/GameStateDelivery.java`
- `server/src/com/smashprofs/server/network/GameStateMessageMapper.java`
- `server/src/com/smashprofs/server/network/StateBroadcaster.java`
- `server/test/com/smashprofs/server/network/StateBroadcasterTest.java`
- `server/test/com/smashprofs/server/network/TcpSnapshotBackpressureTest.java`
- `server/test/com/smashprofs/server/game/GameStateTcpBroadcastTest.java`
- `docs/STEP-44-GAME-STATE-JOURNAL.md` (docs tính từ Git root).

## File chỉnh sửa trong bước này

- `server/src/com/smashprofs/server/network/SessionChannel.java` — additive default non-blocking snapshot boundary.
- `server/src/com/smashprofs/server/network/ClientSession.java` — delegate offer/discard, không gameplay state.
- `server/src/com/smashprofs/server/network/TcpClientConnection.java` — bounded snapshot slot, worker, fair send lock, cleanup.
- `server/src/com/smashprofs/server/network/TcpServerTransport.java` — bounded outbound pool/start/shutdown.
- `server/src/com/smashprofs/server/network/GameServer.java` — configure rate, own publisher từng lifecycle.
- `server/src/com/smashprofs/server/game/FixedTickScheduler.java` — additive afterCycle hook; period/tick không đổi.
- `server/src/com/smashprofs/server/game/MatchRuntime.java` — chỉ thêm readonly remaining-time getter, không sửa tick.
- `server/src/com/smashprofs/server/room/RoomManager.java` — readonly getRooms snapshot.
- `common/test/com/smashprofs/common/protocol/JsonFrameCodecTest.java` — golden HP/projectile assertions.
- `common/test-resources/protocol-v1-vectors.txt` — chỉ đổi GAME_STATE fixture: HP 99.5/91.25 và IBM, 632 bytes, header 00000278.
- `docs/PROTOCOL_V1.md`, `docs/NETWORK-PROTOCOL.md`, `docs/PROTOCOL-CHANGELOG.md`.

Các file Gradle/MessageType/common protocol source có Git changes từ Bước 43B, không sửa thêm trong Bước 44. Giữ dirty worktree và các source/report có sẵn, không git reset/stage/clean. Build artifacts tracked được khôi phục byte-for-byte về trạng thái đầu bước, không phát hành binary mới trong Git; source/classes/test reports mới được giữ.

## Kiểm thử

JDK 17: `E:\2026-2\lap-trinh-mang\baitap`, Gradle root `final-project/final-project`, wrapper 7.3.3.

```powershell
$env:JAVA_HOME = 'E:\2026-2\lap-trinh-mang\baitap'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :common:test :server:test :server:build --no-daemon
```

- Common: **27 PASS**, 0 fail/error/skip.
- Server: **1.250 PASS**, 0 fail/error/skip, bao gồm toàn bộ **1.208 test cũ**.
- Test mới: **42 PASS** = StateBroadcasterTest 21 + GameStateTcpBroadcastTest 15 + TcpSnapshotBackpressureTest 6.
- Build: **PASS**, chỉ warning Gradle deprecated hiện có.

Coverage: envelope/frame/float HP/IDs/tick/deterministic ordering/immutability; cadence và cấu hình; timer/terminal/invalid binding/dispose; TCP 2–4 clients, increasing ticks, movement, fractional damage, active IBM, nhiều room, unjoined/disconnect, automatic real-scheduler publication, server stop/restart. Source không có Thread.sleep.

Slow-client tests dùng socket loopback thật, SO_SNDBUF/SO_RCVBUF nhỏ và latch chỉ **quan sát native write**; xác nhận write chưa hoàn tất khi peer chưa đọc. Trong lúc socket bị nghẽn, native runtime vẫn tick 30 lần, client khác nhận frame; offer 2.000 snapshots chỉ giữ một pending. Đọc lại stream xác nhận nguyên frame đang ghi + latest pending, control ERROR không bị drop/xen byte. Close unblock writer/clear pending; oversized encode không gửi partial frame và không ảnh hưởng connection khác. Large projectile list ở stress test là fixture wire/transport, không tuyên bố runtime tạo 2.500 projectile gameplay.

Lượt targeted đầu có 1 assertion mới fail do Java FloatNode/DoubleNode equality sau JSON decode; đã thay bằng framed-byte roundtrip + giá trị numeric, không đổi HP/gameplay để làm pass. Các test cũ không có failure baseline cần sửa. Không đánh dấu push/handler chưa triển khai là PASS.

TMX SHA256 giữ nguyên: `366044B95702FA12EB4310ED227DA808489A5AA1AAE9E648B6D365F17458E708`.

## Thông báo Member 2/3 và giới hạn

Không breaking wire/API common DTO change. GAME_STATE giờ thực sự push qua v1, không requestId; có thể nhảy tick khi coalesce, nhưng không nhận lại/cũ trong cùng match. Đọc HP number/float, slot/projectiles theo spec; PlayerState int/GameSnapshot v0.1 không phù hợp. Golden GAME_STATE đã đổi content/header, không đổi schema. Additive API server: rate constructor, afterCycle hook, getRooms/getRemainingTimeMillis và offer/discard channel.

Chưa có write deadline eviction: slow client có thể giữ writer riêng đến khi đọc/đóng/shutdown; memory/thread vẫn bounded. Control của chính slow client có thể chờ writer. Terminal chỉ offer một lần, không có receipt/ACK/retry sau disconnect. Không broadcast GAME_START/GAME_END/MATCH_RESULT, ranking/database/heartbeat/reconnect/authentication, interpolation/prediction, adaptive rate hay luật gameplay mới. Lobby slot sau disconnect theo policy có sẵn, không tự forfeit/remove player.

Dừng ở Bước 44, không triển khai Bước 45.
