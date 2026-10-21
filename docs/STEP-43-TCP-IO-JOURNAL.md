# Bước 43 — TCP I/O độc lập với wire format

Ngày kiểm chứng: 11/10/2026. Hoàn thiện phạm vi transport được người dùng xác nhận; **không tuyên bố toàn bộ framing/protocol của Bước 43 đã hoàn thành**. Không triển khai Bước 44.

## Contract đã kiểm tra và blocker

Đọc `docs/NETWORK-PROTOCOL.md`, `docs/PROTOCOL-CHANGELOG.md`, toàn bộ DTO/MessageType trong common, SessionChannel, MessageDispatcher, existing handlers và transport Bước 42. Không tìm thấy codec/serializer/framer hoặc protocol test implementation để tái sử dụng. Docs hiện ghi v0.1 là data shapes/message names, chưa chọn serialization/envelope/framing. Người dùng xác nhận chưa có specification/codec từ Member 3; thống nhất DTO v0.2 ngoài repository cũng không xác định wire format.

| Hạng mục | Kết quả xác minh |
|---|---|
| JSON/binary/định dạng khác | Chưa được chọn |
| Length-prefix/delimiter/framing khác | Chưa được chọn |
| Header size, byte order, string encoding | Chưa định nghĩa |
| Wire protocol version, envelope, requestId/correlationId | Chưa định nghĩa |
| Wire representation của MessageType | Chỉ có Java enum; chưa chọn text/ordinal hoặc format khác |
| Error payload/response | Có tên ERROR, chưa có payload/encoding |
| Maximum frame size chính thức | Chưa định nghĩa |
| SessionChannel | `send(Object)`, `close()`, `isOpen()` |
| JOIN_ROOM/PLAYER_READY payload hiện tại | String roomId và ReadyRequest nội bộ server, chưa phải wire DTO |

Không chỉnh sửa common hoặc protocol specification/changelog để hợp thức hóa một format tự chọn.

## Phần hoàn thiện

```text
Accept thread -> ConnectionManager / ClientSession duy nhất
  -> TcpClientConnection -> một read task tuần tự trên bounded pool
       -> [codec chính thức được inject sau này] -> MessageDispatcher -> existing handlers
  -> TcpSessionChannel -> khóa ghi riêng socket -> bytes do encoder cung cấp
Simulation thread -> lifecycle/MatchRuntime như trước, không đọc socket
```

### Per-client read và ownership

- Mỗi connection có InputStream, state giới hạn/deadline, reader codec và encoder codec riêng. Socket vẫn có đúng owner channel; không tạo registry TCP/session thứ hai.
- `ThreadPoolExecutor` có tối đa `maxConnections` read workers; `SynchronousQueue` không tích lũy read tasks. Quá capacity thì reject/close connection mới. Giới hạn TCP của Bước 42 vẫn được giữ.
- Các message đã decode được dispatch tuần tự trên reader của connection đó. Socket/codec không chọn ClientSession từ payload: dispatcher nhận chính session được server tạo/register. Không gán PlayerId từ IP/port hoặc tự tạo identity/binding.
- Codec factory phải cung cấp instance mới riêng cho mỗi reader và encoder; factory trả null hoặc dùng cùng một instance cho hai direction bị reject. Factory cũng phải bảo đảm không tái sử dụng parser state giữa connections.
- EOF, IOException hoặc decoder/handler RuntimeException kết thúc reader, đóng socket, đánh dấu disconnected và conditional unregister đúng session. Không biến disconnect thành player death, forfeit, room leave hoặc MatchResult. Các phòng/match vẫn do lifecycle gameplay cũ sở hữu; disconnect policy còn chờ team.
- Log lỗi read/decode/dispatch chỉ ghi session ID và tên exception class, không ghi payload hoặc exception message có thể chứa dữ liệu client không giới hạn.

### Khi chưa có codec

Các constructor GameServer cũ vẫn chạy được. Reader chờ EOF; nếu nhận byte application khi chưa cấu hình codec thì **fail closed**, đóng riêng connection đó. Không gom/discard bytes rồi giả báo đã xử lý, không parse JSON hoặc đoán packet boundary. Idle connection không bị đóng vì chưa gửi input.

`send(Object)` khi không có encoder vẫn ném UnsupportedOperationException, không gửi greeting hoặc ERROR tự tạo. Đây chưa phải server có thể chơi multiplayer qua wire.

### Boundary codec thay thế được

`TcpMessageCodec` chỉ là interface, không có implementation production:

- `readNext(InputStream, maxMessageBytes)` trả message đã được codec chính thức ghép/giải mã, hoặc null ở EOF sạch.
- `encode(Object, maxMessageBytes)` trả toàn bộ bytes đã encode và frame theo contract được duyệt; không trực tiếp ghi socket.
- Nested `DecodedMessage(MessageType, Object)` là giá trị nội bộ sau decode, **không phải wire envelope hoặc DTO common mới**.

`TcpIoConfiguration` inject factory, dispatcher, byte guard và incomplete-message timeout. GameServer có constructor opt-in nhận codec factory/guards; dispatcher và các handler JOIN_ROOM/PLAYER_READY/PLAYER_INPUT được dựng lại theo registry hiện tại mỗi lifecycle/restart, không viết lại handlers. Session mới vẫn chưa bind PlayerId nên existing handlers sẽ reject gameplay request cho đến khi có trusted binding từ một quy trình được nhóm thống nhất.

### Guards độc lập với format

- Byte guard giới hạn số byte tiêu thụ trong mỗi `readNext()` call; encoded bytes được kiểm tra trước clone/write. Đây là transport guard cấu hình được, **không phải maximum frame size/payload size chính thức**.
- Input wrapper không tích lũy buffer hoặc cấp phát theo header client. Guard không thay thế validation của codec: codec chính thức phải validate header/length/overflow trước allocation và quản lý bounded internal buffers/prefetch. Không tuyên bố đã giải quyết allocation từ header khi parser chưa tồn tại.
- Deadline partial bắt đầu khi byte đầu tiên được đọc trong decode call; idle trước byte đầu tiên không tính. SO_TIMEOUT (tối đa 1 giây, hoặc nhỏ hơn theo partial timeout) là điểm kiểm tra deadline, không tự coi idle là disconnect. Timeout/byte limit là cấu hình transport, không heartbeat hoặc luật gameplay.
- Không có send queue, nên không tạo unbounded outbound queue. Write hiện tại là synchronous và có thể chặn **caller của connection đó**; không dùng nó trên simulation thread. Gameplay hiện tại không gọi socket send, chưa có broadcast. Thiết kế async broadcast/backpressure sau này không được tự thêm trong bước này.

### Outbound và close

- `TcpSessionChannel.writeEncodedBytes()` chỉ nhận bytes đã được cung cấp; không tự thêm header, delimiter, byte order hoặc charset. Copy có giới hạn và ghi/flush dưới write lock riêng mỗi socket. `TcpClientConnection.send()` giữ khóa encode + write để các send cùng connection không đảo thứ tự/xen bytes.
- Codec/encode limit hoặc write IOException dẫn đến close/unregister riêng connection. TCP write lỗi có thể đã truyền một phần bytes; không giả hứa atomic delivery toàn message qua mạng và không retry cùng message trên stream bị lỗi.
- Close không lấy write lock: shutdown vẫn đóng Socket để unblock một writer chậm. Close idempotent và không đóng connection khác.
- `GameServer.stop()` đóng listener và sockets, kết thúc accept/read loops, shutdown I/O pool, chờ bounded termination rồi giữ thứ tự stop scheduler/rooms cũ. Không self-join hoặc tự interrupt I/O worker đang gọi graceful stop. Nếu pool không kết thúc sau 5 giây, fallback interrupt và báo lỗi; không chờ vô hạn.
- Restart tạo pool/socket/session/codec/handlers mới; không dùng lại sockets hoặc registries của lifecycle cũ.

## File tạo/sửa

Đường dẫn source/test tính từ Gradle root `final-project/` trong repository.

Tạo mới:

- `server/src/com/smashprofs/server/network/TcpMessageCodec.java`
- `server/src/com/smashprofs/server/network/TcpIoConfiguration.java`
- `server/src/com/smashprofs/server/network/TcpClientConnection.java`
- `server/test/com/smashprofs/server/network/TcpIoLifecycleTest.java`
- `server/test/com/smashprofs/server/network/TcpSocketWriteTest.java`
- `docs/STEP-43-TCP-IO-JOURNAL.md` (repository root)

Sửa:

- `server/src/com/smashprofs/server/network/TcpServerTransport.java`: bounded readers, connection integration và I/O shutdown.
- `server/src/com/smashprofs/server/network/TcpSessionChannel.java`: serialized raw-byte writes và write guards.
- `server/src/com/smashprofs/server/network/GameServer.java`: opt-in codec wiring theo từng lifecycle, giữ constructor cũ.
- `server/test/com/smashprofs/server/network/TcpServerTransportTest.java`: một test EOF Bước 42 nay chờ auto cleanup bằng latch; helper thêm close notification. Không bỏ test hoặc giữ giả định cũ rằng peer EOF chưa được phát hiện.

Không sửa ClientSession, ConnectionManager, MessageDispatcher/handlers, common, core, desktop, gameplay/scheduler/room source, TMX, Gradle hoặc dependency. Không thêm auth, heartbeat/reconnect, broadcast, ranking/DB hoặc protocol mới.

## Test đã chạy và những mục BLOCKED

Real sockets dùng 127.0.0.1/port 0; connect/read/Future/latch/termination đều có timeout. Không Thread.sleep, busy-poll hoặc GUI.

- `TcpIoLifecycleTest`: **21 PASS**, gồm EOF/half-close, nhiều readers độc lập, worker giới hạn, read không chạy accept/simulation thread, idle không disconnect, IOException/RuntimeException isolation, stream byte/deadline guard, trusted session boundary, dispatch sequencing, graceful/self-stop, restart và codec-less reject.
- `TcpSocketWriteTest`: **9 PASS**, gồm supplied bytes không bị thay đổi, concurrent raw writes không xen bytes, socket isolation, byte limits, close/write race và encoder-adapter cleanup.
- Scripted codecs trong test chỉ tiêu thụ opaque trigger bytes rồi cung cấp object đã chuẩn bị để kiểm tra boundary. Chúng không được coi là giao thức tạm hoặc codec chính thức. Các mẫu raw bytes kiểm tra write locks cũng không định nghĩa response format.
- Hai native matches/isolation và network không tự step World tiếp tục được kiểm chứng bởi tests Bước 42 cùng toàn suite simulation/gameplay cũ.

**BLOCKED — không có JUnit giả PASS hoặc @Ignore để thay thế các mục này:**

1. Cả 11 framing tests yêu cầu: complete/fragmented/multiple/mixed frames, empty-payload policy, malformed header, oversized frame theo protocol, truncated EOF, invalid encoding, frame order và decoder buffer isolation theo wire contract.
2. End-to-end valid JOIN_ROOM/PLAYER_READY/PLAYER_INPUT qua TCP, duplicate sequence và identity spoof qua payload thật: cần codec/schema và quy trình trusted player binding. DTO/handler tests hiện có vẫn chạy, nhưng không được đổi tên thành wire tests.
3. Partial writes/multiple messages trong một TCP write theo framing chính thức; response encoding/error payload và concurrent response frames đúng contract. Chỉ raw-write/boundary isolation đã PASS.
4. Oversized/malformed payload và negative/overflow header không gây allocation quá lớn theo schema thực tế: cần parser/codec thực tế. Byte guard không chứng minh header parser chưa có an toàn.

## Kết quả build

- Baseline trước sửa: **1.151 PASS**.
- `.\gradlew.bat :server:test --no-daemon`: **1.181 PASS, 0 failures/errors/skipped** (baseline + 30 test mới).
- `.\gradlew.bat :server:build --no-daemon`: **BUILD SUCCESSFUL**; test task up-to-date sau full suite.
- JDK 17 tại `E:\2026-2\lap-trinh-mang\baitap`, wrapper 7.3.3, JUnit 4.13.1. Cảnh báo Gradle deprecated có sẵn không được xử lý bằng nâng dependency trong bước này.
- Report: `server/build/reports/tests/test/index.html`; XML: `server/build/test-results/test/`.

Trong lần kiểm chứng đầu, test self-stop phát hiện setup-caused self-interrupt khi accept cleanup cancel reader/shutdownNow. Đã sửa bằng Socket.close unblock, cancel không interrupt và graceful shutdown; test không bị xóa/nới lỏng. Baseline không có failure.

Build artifacts tracked (6 jar/manifest/compile metadata) được giữ đúng bản byte trước kiểm chứng để không ghi đè dirty changes của người dùng; classes và reports vẫn giữ lại. Nếu cần JAR đóng gói mới, chạy `:server:build --rerun-tasks`.

## Dependency còn chờ Member 3/team

Chốt serialization, framing/header/byte order, encoding, wire version/envelope, representation MessageType, request/correlation ID, error/response payload, official maximum frame size, codec allocation/partial-frame/prefetch policy, trusted identity/binding và disconnect-room policy. Sau team agreement mới cung cấp codec thật, cập nhật common/spec/changelog nếu cần và chạy các test BLOCKED.

**Dừng ở phần transport an toàn của Bước 43. Không triển khai Bước 44.**
