# Bước 42 — TCP listener và multi-client accept loop

Ngày kiểm chứng: 11/10/2026. Chỉ thực hiện Bước 42, không triển khai reader/framing của Bước 43.

## Khảo sát baseline

Repository Gradle nằm tại `final-project/` bên trong repository root. Server dùng Java 17, source `server/src`, test `server/test`, JUnit 4.13.1 và Gradle wrapper 7.3.3. Không cần thêm dependency hoặc sửa Gradle.

- `GameServer` đã sở hữu `FixedTickScheduler`, `RoomManager` và restart bằng lifecycle mới. `start()` trước bước này chỉ start scheduler; `stop()` stop scheduler rồi shutdown rooms. Constructor inject scheduler/clock được giữ nguyên.
- `FixedTickScheduler` có một simulation thread chung 60 Hz, registration/unregistration và shutdown có timeout. MatchRuntime giữ simulation owner; không được tick từ network thread.
- `ConnectionManager` đã là registry `ConcurrentHashMap<String, ClientSession>`, từ chối ID trùng, hỗ trợ remove không close và `closeAll()` thử đóng mọi session ngoài registry lock.
- `ClientSession` giữ session ID, player/room metadata và channel. PlayerId/roomId ban đầu null. `close()` đánh dấu disconnected trước khi gọi channel ngoài session lock.
- `SessionChannel` chỉ có `send(Object)`, `close()`, `isOpen()`. `MessageDispatcher` và các JoinRoom/PlayerReady/PlayerInput handler chỉ xử lý object đã được giải mã, không sở hữu TCP socket.
- Chưa có socket transport, encoder/decoder hoặc framing contract trong server/common hiện tại. Không tự phát minh protocol để gửi được Object.

Baseline trước chỉnh sửa: **1.123 test PASS** (`:server:test --no-daemon --rerun-tasks`). Không có lỗi baseline cần sửa.

## Kiến trúc và API

```text
GameServer
  -> TcpServerTransport: bind và một blocking accept thread
  -> ConnectionManager: registry ClientSession duy nhất
       -> ClientSession: UUID sessionId; chưa bind player/room
            -> TcpSessionChannel: sở hữu accepted Socket, đóng idempotent
  -> FixedTickScheduler / RoomManager: lifecycle simulation cũ, không nhận Socket
```

`new GameServer()` mặc định bind **127.0.0.1:0**, tối đa **64 connections**. Constructor cấu hình:

```java
GameServer server = new GameServer("127.0.0.1", 0, 64);
server.start();
int actualPort = server.getBoundPort();
// ...
server.stop();
```

`bindHost` không được null/blank hoặc có whitespace ở hai đầu; không tự trim IP/hostname. `bindPort` từ 0 đến 65535; 0 do OS cấp. `maxConnections` phải > 0. Muốn LAN phải cấu hình `0.0.0.0` tường minh; bước này không bind LAN trong test, mở firewall/router hoặc public Internet.

`getBoundPort()` trả -1 trước bind hoặc sau close. Restart với port 0 phải đọc lại actual port. `getConnectionManager()` trả registry của lifecycle hiện tại; restart phải lấy reference mới, tương tự RoomManager. `getAcceptFailure()` trả IOException accept bất thường, null nếu không có lỗi.

## Start và accept

1. `GameServer.start()` serialize dưới lifecycle monitor cũ.
2. Bind ServerSocket trước; bind lỗi trả `UncheckedIOException` với host/port và original cause, không báo running.
3. Start scheduler hiện có.
4. Start một accept thread (`smashprofs-tcp-accept`), rồi công bố running. Gọi start lặp không tạo listener/thread mới.
5. Accept thread block trong `ServerSocket.accept()`, không polling hoặc sleep.
6. Sau accept, kiểm tra closing/running và connection limit trước register dưới transport lifecycle lock. Một accept thread là writer đăng ký TCP duy nhất; các close callback đồng thời chỉ giảm count. Không giữ ConnectionManager lock qua simulation/native code.
7. Tạo UUID sessionId, TCP channel và ClientSession; register bằng ConnectionManager có sẵn. Không gán PlayerId/roomId, tạo room, dispatch message hoặc start trận.
8. Vượt limit: đóng socket mới; existing clients không bị ảnh hưởng. `connect()` phía client có thể đã thành công ở tầng TCP trước khi server reject bằng close — chưa có error message wire protocol.
9. Khởi tạo/register lỗi: cleanup socket/session vừa tạo, không xóa session khác. Fatal Error được cleanup rồi rethrow, không biến thành một accept retry loop bận.

ThreadFactory và callback notification package-private chỉ là seams cho test start failure/latch; production callback no-op. Không tạo per-client reader hoặc per-client thread.

## Channel, session và registry ownership

`TcpSessionChannel` không giả báo gửi thành công: `send()` ném `UnsupportedOperationException` khi open vì chưa có framing/encoder, và `IllegalStateException` khi closed. Không viết welcome/greeting, JSON hoặc bytes vào socket.

Channel close dùng `AtomicBoolean.compareAndSet` để socket được đóng một lần về mặt hiệu lực. `ClientSession` thêm constructor callback nội bộ, giữ nguyên constructor/API cũ. Sau close, callback `ConnectionManager.removeIfSame(ClientSession)` chỉ xóa nếu ID còn trỏ tới đúng instance. Callback cũ/repeated close không xóa một session mới tái sử dụng ID. Tên method riêng cũng tránh làm lời gọi API cũ `remove(null)` bị ambiguous. Registry snapshot `getSessions()` trả `List.copyOf`, không expose Socket hoặc map mutable.

Server-side close chuẩn là `session.close()`: disconnected -> socket close -> unregister. `closeAll()` clear registry và thử close mọi session như trước; callback remove sau clear là no-op an toàn. API legacy `remove(String)` vẫn **không close**, giữ nguyên contract; caller dùng API đó nhận lại ownership session đã remove và phải tự close. Không dùng raw remove để đóng TCP connection trong transport.

Không có registry socket/session thứ hai hoặc static global registry. Socket chỉ thuộc channel, không đi vào MatchState/PlayerPhysicsFactory/Box2D.

## Shutdown, restart và lỗi

`GameServer.stop()` đánh dấu not running, đóng ServerSocket để unblock accept, join accept thread tối đa 5 giây (không self-join), đóng/unregister accepted sessions, stop scheduler và shutdown rooms theo lifecycle cũ. Cleanup thử mọi thành phần và giữ lỗi gốc/lỗi suppressed, không bỏ qua rooms vì transport lỗi. Gọi stop lặp an toàn.

Lifecycle lock transport ngăn registration mới sau closing. Join diễn ra ngoài transport lock; accept thread không lấy GameServer monitor để đăng ký. Close callback chỉ làm registry metadata, không gọi room/native simulation. Không thêm lock vòng GameServer -> ConnectionManager -> RoomManager.

Restart tạo mới listener, registry session, scheduler và room registry; không hồi sinh match/session cũ. Đã kiểm chứng cả port 0 và port cố định sau một kết nối thật trên Windows. Không thêm reuse-address policy hoặc cho phép duplicate listener trên port đã có process khác.

SocketException do chủ động stop listener không bị log như accept failure. IOException bất thường lúc accept được lưu qua `getAcceptFailure()`, log SEVERE và kết thúc loop; không busy retry. `isRunning()` trả false nếu listener không còn accepting. Nếu accept loop bất thường kết thúc, scheduler không bị accept thread tự ý dừng giữa gameplay: caller cần explicit `stop()` rồi `start()` để thay lifecycle; start lặp trong lifecycle đã lỗi sẽ từ chối rõ ràng. Không triển khai auto reconnect/retry trong bước này.

## Giới hạn cố ý để Bước 43

Không có socket read/EOF detection. Client đóng phía mình chưa làm server tự nhận ra disconnect ngay; registry vẫn sở hữu local connection đến explicit session close hoặc server stop. Count/limit phản ánh local connections đang được quản lý, không phải số peer chắc chắn online. EOF reader sau này phải đi qua session close/unregister hiện có; không tự biến disconnect thành room leave/death/forfeit.

Chưa có framing, serialization, JSON parsing, gameplay message read, dispatch từ socket, GAME_STATE/GAME_END broadcast, heartbeat, reconnect, authentication, TLS, UDP, ranking, database hoặc UI. Encoder/envelope/framing phải phối hợp Member 3; không sửa common protocol để tiện gửi Object.

## File tạo/sửa

Tạo mới (đường dẫn source/test tính từ Gradle root):

- `server/src/com/smashprofs/server/network/TcpServerTransport.java`
- `server/src/com/smashprofs/server/network/TcpSessionChannel.java`
- `server/test/com/smashprofs/server/network/TcpServerTransportTest.java`
- `docs/STEP-42-TCP-LISTENER-JOURNAL.md` (tính từ repository root)

Sửa:

- `server/src/com/smashprofs/server/network/GameServer.java`: cấu hình và TCP start/stop/restart lifecycle.
- `server/src/com/smashprofs/server/network/ClientSession.java`: callback close nội bộ; constructor cũ giữ nguyên.
- `server/src/com/smashprofs/server/network/ConnectionManager.java`: conditional identity remove và immutable registry snapshot.

Không sửa bất kỳ test cũ, common/core/desktop, gameplay/scheduler/room source, TMX, Gradle config hoặc dependencies. Những thay đổi đã có của người dùng trong working tree được giữ nguyên.

## Kiểm chứng

Test loopback thật, ephemeral port 0; riêng test restart port cố định lấy một available port bằng probe trước. Socket connect/read có timeout, acceptance dùng CountDownLatch 5 giây, thread join/Future có giới hạn; không Thread.sleep, busy-poll hoặc GUI. Reader phía **test client** chỉ kiểm tra EOF/no bytes, không phải reader server hoặc wire protocol mới.

28 test mới PASS: bind/actual port, accept 1/2/4 clients, independent connections và unique IDs, registration/count, peer-close deferred cleanup, explicit session close, limit, repeated start/stop, listener/accepted sockets close, accept unblock, bind/partial thread-start failure cleanup, no accept-driven World.step, isolation hai native matches, unsupported send/no greeting, restart fresh lifecycle, fixed-port restart, validation, identity remove, concurrent connection limit, self-stop và shutdown-registration race.

Physics boundary tests dùng hai MatchRuntime/TMX/Box2D thật và unregister khỏi scheduler trước tick đầu tiên để manual simulation owner là test thread. Sau accept, cả hai vẫn ở tick 0; manual tick/close một trận không làm thay đổi trận còn lại. Không tuyên bố đây là load/throughput benchmark. Toàn bộ scheduler integration tests cũ cũng chạy với TCP-enabled GameServer mà không sửa fixture.

- `.\gradlew.bat :server:test --no-daemon`: **1.151 PASS, 0 failures/errors/skipped** (1.123 baseline + 28 TCP tests).
- `.\gradlew.bat :server:build --no-daemon`: **BUILD SUCCESSFUL** (test task up-to-date sau full suite ở trên).
- JDK 17: `E:\2026-2\lap-trinh-mang\baitap`. Thay JAVA_HOME/PATH chỉ trong tiến trình chạy lệnh, không sửa cài đặt môi trường máy.
- Report: `server/build/reports/tests/test/index.html`; XML: `server/build/test-results/test/`.

Các jar/manifest/compile metadata đã được Git theo dõi được khôi phục đúng byte bản trước kiểm chứng để không ghi đè dirty artifacts của người dùng; classes/test reports vẫn giữ lại. Vì vậy JAR tracked trong working tree không được coi là release artifact mới của Bước 42; nếu cần đóng gói lại, chạy `:server:build --rerun-tasks`.

**Dừng sau Bước 42.** Không triển khai Bước 43.
