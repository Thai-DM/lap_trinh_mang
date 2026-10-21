# Bước 47 — CREATE_ROOM over TCP

## Khảo sát và quyết định

Contract v1 đã có CREATE_ROOM/ROOM_CREATED với đúng một field `roomId`. RoomId là string do client đề xuất, server validate `[A-Za-z0-9_-]{1,64}`, không server-generated UUID, không trim hoặc đổi ID. Creator không auto-join: phải JOIN_ROOM riêng, không có owner/host hoặc auto-ready/start.

RoomManager trước bước này chỉ có getOrCreateRoom, chưa có create-only API. Registry dùng ConcurrentHashMap và registryLock; GameRoom cố định tối đa 4 slot, match cần 2–4 người. Không có quota tổng số phòng hay expiration. Room cleanup hiện có là removeRoom/shutdown. JOIN vẫn get-or-create, không đổi sang join-existing-only.

## File tạo/sửa

Git root là `final-project/`; Gradle root/source nằm tại thư mục `final-project/` bên trong Git root.

Tạo mới:

- `final-project/server/test/com/smashprofs/server/game/CreateRoomTcpTest.java`
- `final-project/server/test/com/smashprofs/server/room/RoomManagerCreateTest.java`
- `docs/STEP-47-CREATE-ROOM-JOURNAL.md`

Sửa:

- `final-project/server/src/com/smashprofs/server/room/RoomManager.java`: thêm `createRoom(String)` nguyên tử, không ghi đè duplicate.
- `final-project/server/src/com/smashprofs/server/network/ProtocolV1Dispatcher.java`: thêm route CREATE_ROOM dùng manager hiện có, trả ROOM_CREATED/correlated ERROR.
- `final-project/server/test/com/smashprofs/server/network/TcpProtocolV1Test.java`: CREATE_ROOM không còn nằm trong nhóm handler NOT_IMPLEMENTED; các type còn lại giữ nguyên expectation.
- `final-project/server/test/com/smashprofs/server/game/TwoClientTcpMatchEndToEndTest.java`: room chính được tạo qua TCP, không RoomManager fixture; thay negative CREATE test cũ bằng kiểm tra create-only membership/start.
- `docs/PROTOCOL_V1.md`, `docs/NETWORK-PROTOCOL.md`, `docs/PROTOCOL-CHANGELOG.md`: ghi rõ CREATE_ROOM đã chạy và semantics/limits, không đổi schema hoặc version.

Không sửa TcpTestClient, common/codec/MessageType/DTO/golden vectors, Gradle, GameServer, ClientSession, GameRoom, physics/combat/MatchRuntime.tick(), GAME_STATE/END, core/desktop hoặc TMX.

## Wire và routing thực tế

```json
{"version":1,"type":"CREATE_ROOM","requestId":"create-1","payload":{"roomId":"arena"}}
{"version":1,"type":"ROOM_CREATED","requestId":"create-1","payload":{"roomId":"arena"}}
```

Vẫn JSON UTF-8, header length int32 Big-Endian 4 bytes, payload 1–1,048,576 bytes. JsonFrameCodec hiện có kiểm tra envelope/version/requestId trước dispatch; requestId bắt buộc, response echo nguyên ID. Golden CREATE_ROOM/ROOM_CREATED fixture hiện có dùng `r2`/`r`, header `0000004c`/`0000004d`, giữ nguyên vì schema không thiếu/không đổi.

ProtocolV1Dispatcher xác minh session open/registered đúng instance và đã CONNECT. Không nhận PlayerId từ JSON. Sau validation payload/roomId, gọi đúng RoomManager của lifecycle server, không manager thứ hai. ROOM_CREATED lấy ID từ GameRoom đã tạo. CREATE không sửa session.roomId, roster, ready hoặc phase PLAYING.

## Concurrency và failure

`createRoom` dùng cùng registryLock với get-or-create và shutdown. Validate trước, kiểm tra closed/duplicate dưới khóa, xây dựng GameRoom hoàn chỉnh rồi mới put. Hai CREATE cùng ID chỉ một thành công, request còn lại lỗi; các ID khác nhau đăng ký các instance riêng. Không room lock/Box2D/runtime initialization/socket write trong API create. Response gửi ngoài khóa routing/session/registry theo dispatcher hiện có.

Errors dùng các code sẵn có:

- Chưa CONNECT/open/registered hợp lệ: INVALID_SESSION.
- Envelope/requestId/payload/roomId lỗi hoặc capacity/settings field thừa: INVALID_PAYLOAD.
- Client tự gửi playerId/sessionId: UNAUTHORIZED_PLAYER.
- Duplicate ID (kể cả retry requestId): INVALID_ROOM_STATE.
- Registry shutdown/lỗi tạo phòng nội bộ: INTERNAL_ERROR cố định, không stack trace.

Không có request-id cache hoặc retry protocol. Validation/duplicate/closed-registry failure không leak room hoặc xóa phòng khác. Sau commit, disconnect/response write failure có thể làm mất ACK nhưng phòng đã tạo hoàn chỉnh vẫn tồn tại; không rollback phòng có thể đã được client khác join. Socket lỗi dùng cleanup hiện có, không crash server. Không tự expire phòng rỗng khi creator disconnect.

## Kiểm thử/build thực tế

JDK 17, Gradle wrapper 7.3.3, JUnit 4.13.1:

```powershell
.\gradlew.bat :server:test --tests '*CreateRoomTcpTest' --tests '*RoomManagerCreateTest' --tests '*RoomManagerTest' --tests '*TcpProtocolV1Test' --tests '*TwoClientTcpMatchEndToEndTest' --tests '*JoinRoomHandlerTest' --no-daemon
.\gradlew.bat :server:build -x :server:test --no-daemon
```

| Class | PASS | FAIL / ERROR / SKIP |
|---|---:|---:|
| CreateRoomTcpTest (mới) | 12 | 0 |
| RoomManagerCreateTest (mới) | 6 | 0 |
| RoomManagerTest | 11 | 0 |
| TcpProtocolV1Test | 27 | 0 |
| TwoClientTcpMatchEndToEndTest | 8 | 0 |
| JoinRoomHandlerTest | 37 | 0 |
| Tổng targeted | 101 | 0 |

18 test mới. TCP thật loopback/port 0, official JsonFrameCodec và TcpTestClient hiện có; không sleep/desktop/fake networking. Negative requestId fixtures cố tình viết bytes của envelope lỗi vì official encoder không cho encode chúng, rồi đọc ERROR bằng official codec; đây không phải codec mới.

TCP coverage: requestId correlation, ID giữ đúng contract, empty WAITING room, explicit join của creator/client thứ hai, payload/identity/capacity/requestId rejection, chưa CONNECT, duplicate không thay room/membership, hai client tạo ID khác nhau đồng thời, cạnh tranh cùng ID, registry-shutdown error/reader tiếp tục CONNECT, giới hạn bốn slot, binding room cũ giữ nguyên, create không start/ảnh hưởng room khác, disconnect rồi explicit cleanup/recreate. Unit registry kiểm tra publication, duplicate, null/blank, shutdown, recreation và race create với get-or-create.

E2E hiện kiểm tra CREATE_ROOM thật → JOIN → READY/countdown → INPUT → STATE → END; winner/physics/combat giữ nguyên. Không gọi room creation nội bộ cho room chính trong E2E. Không có test mới FAIL/BLOCKED, không giả server sinh random RoomId là PASS. Không chạy full 1250+ suite hoặc common test task; golden/schema không sửa, existing TCP protocol regression đã chạy.

Compile common/server/test source PASS và server build PASS với test task excluded vì targeted tests chạy riêng. Gradle chỉ cảnh báo deprecated features hiện hữu. Reports tại `server/build/reports/tests/test/`.

## Giới hạn / Member 2

Member 2 gửi CONNECT trước, rồi CREATE_ROOM với roomId hợp lệ/requestId, đọc ROOM_CREATED đúng correlation, sau đó JOIN_ROOM riêng. Không đưa capacity/playerId/host settings vào payload. Nếu trùng ID thì chọn ID khác hoặc JOIN phòng đã có theo flow ứng dụng; không coi requestId là cache retry.

Chưa có quota/rate-limit tổng số phòng, TTL/expiration, room owner/password/discovery, authentication/TLS, reconnect hoặc reliable ACK replay. GAME_START vẫn chưa push, không triển khai Bước 48. Đây là tạo phòng tối thiểu, không room lifecycle/refactor/gameplay mới.

TMX SHA-256 vẫn `366044B95702FA12EB4310ED227DA808489A5AA1AAE9E648B6D365F17458E708`. Tracked generated JAR/manifest/compile cache được backup và restore đúng bytes có sẵn sau verification; reports/classes mới giữ lại. Không coi tracked JAR đã restore là deployment artifact mới; chỉ xóa backup tạm riêng của tác vụ.

Dừng ở Bước 47.
