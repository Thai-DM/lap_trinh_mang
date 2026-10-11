# Bước 45 — GAME_END delivery & result synchronization

## Khảo sát và quyết định

- `MatchRuntime.tick()` đã gọi `MatchEndEvaluator` sau combat/physics và snapshot cuối, công bố đúng một immutable `AuthoritativeMatchResult`. Accessor thực tế là `getMatchResult()`; null khi chưa kết thúc, vẫn đọc được sau close. Không sửa hai class này.
- `GameRoom.refreshMatchCompletion()`/scheduler completion hiện có chuyển PLAYING sang FINISHED và unregister runtime. Hook after-cycle đã có trong Bước 44 vẫn quan sát phòng FINISHED trước CLOSED; không tạo scheduler/writer thứ hai.
- GAME_END v1 đã có schema đầy đủ. Dùng `winnerId`, `finishedAtTick`, `finishReason` và participant finalHp float/eliminated. Không dùng common.MatchResult có epoch timestamp, không invent score/rating hoặc tính lại winner.

## File tạo mới

Các source/test bên dưới nằm trong `final-project/server/` tương đối với Git root; source layout vẫn là `src/` và `test/`.

- `src/com/smashprofs/server/network/GameEndDelivery.java`
- `src/com/smashprofs/server/network/GameEndMessageMapper.java`
- `test/com/smashprofs/server/network/GameEndMessageMapperTest.java`
- `test/com/smashprofs/server/game/GameEndTcpDeliveryTest.java`
- `docs/STEP-45-GAME-END-JOURNAL.md` (tại Git root)

## File sửa

- `server/src/com/smashprofs/server/network/SessionChannel.java`: thêm non-blocking terminal boundary, default giữ snapshot-only cho channel cũ và không báo result đã enqueue.
- `server/src/com/smashprofs/server/network/ClientSession.java`: delegate terminal khi session còn open.
- `server/src/com/smashprofs/server/network/StateBroadcaster.java`: lấy immutable result hiện có, room-scoped terminal publication và recipient tracking/cleanup.
- `server/src/com/smashprofs/server/network/TcpClientConnection.java`: queue terminal bounded, ưu tiên bỏ pending snapshot cũ, giữ final state/end dưới cùng send lock; close/discard dọn cả queue.
- `server/test/com/smashprofs/server/network/StateBroadcasterTest.java`: thêm lifecycle/result enqueue-once, invalidation và recipient exclusion.
- `server/test/com/smashprofs/server/network/TcpSnapshotBackpressureTest.java`: thêm terminal ordering/backpressure/overflow/cleanup.
- `server/test/com/smashprofs/server/game/GameStateTcpBroadcastTest.java`: cập nhật expectation terminal để đọc GAME_END sau final state (không còn expectation Bước 44 “without GameEnd”).
- `docs/PROTOCOL_V1.md`, `docs/NETWORK-PROTOCOL.md`, `docs/PROTOCOL-CHANGELOG.md`: kích hoạt push schema v1 đã có, nêu guarantees/limits; không đổi schema/version/framing/golden fixture.

## Luồng dữ liệu và guarantees

`tick cuối → cached final snapshot + AuthoritativeMatchResult → FINISHED → StateBroadcaster → GameEndDelivery → outbound worker hiện có → final GAME_STATE → GAME_END`.

Recipient phải có session đăng ký đúng instance, connected/open, trusted PlayerId thuộc roster snapshot (được build từ MatchSetup), đúng room/runtime. Guard được kiểm tra trước encode ngoài queue lock. Không gửi cho unjoined/foreign player/room khác; client inbound GAME_END chỉ nhận INVALID_PAYLOAD.

Cursor gắn identity runtime và terminal gate bảo đảm mỗi session không bị enqueue lại khi callback/getPhase/đọc snapshot lặp; tập recipient lưu enqueue thành công, bỏ session disconnect/unregister. Xóa phòng/stop dọn cursor. Connection từ chối terminal trùng match hiện tại. Không tạo result mới; không application ACK/retry, không tuyên bố client xử lý exactly-once.

Latest-wins chỉ áp dụng snapshot thường, không GAME_END. Terminal bundle xóa snapshot obsolete cùng match, không cắt frame đang ghi. Worker dùng sendLock sẵn có cho cả final state và result, không control frame chen giữa. Tối đa một pending snapshot, bốn pending terminal bundle và một claimed delivery mỗi connection; lifecycle hiện tại chỉ một trận/session nên thông thường chỉ một terminal bundle. Nếu extension làm đầy queue thì close riêng connection, không drop âm thầm hay queue vô hạn.

Simulation chỉ offer references bất biến; không encode, Socket.write, chờ sendLock hoặc World.step trong delivery. Reader/pool/writer/EOF/error/shutdown dùng lại Bước 43–44. Không thêm thread/executor production. Socket failure/stop đóng connection và unblock writer, không thay winner/forfeit/roster.

## Kiểm thử thực tế

JDK 17, wrapper Gradle 7.3.3. Chạy có chọn class (không full 1.250+ suite):

```powershell
.\gradlew.bat :server:test --tests '*GameEndMessageMapperTest' --tests '*GameEndTcpDeliveryTest' --tests '*StateBroadcasterTest' --tests '*TcpSnapshotBackpressureTest' --tests '*GameStateTcpBroadcastTest' --tests '*TcpProtocolV1Test' --tests '*TcpIoLifecycleTest' --tests '*TcpSocketWriteTest' --tests '*MatchRuntimeEndingTest' --no-daemon
.\gradlew.bat :server:build -x :server:test --no-daemon
```

| Test class | PASS | FAIL / ERROR / SKIP |
|---|---:|---:|
| GameEndMessageMapperTest | 6 | 0 |
| GameEndTcpDeliveryTest | 14 | 0 |
| StateBroadcasterTest | 25 | 0 |
| TcpSnapshotBackpressureTest | 11 | 0 |
| GameStateTcpBroadcastTest | 15 | 0 |
| TcpProtocolV1Test | 27 | 0 |
| TcpIoLifecycleTest | 21 | 0 |
| TcpSocketWriteTest | 9 | 0 |
| MatchRuntimeEndingTest | 42 | 0 |
| Tổng targeted | 170 | 0 |

29 test mới, một expectation cũ cập nhật. TCP tests dùng loopback/port 0, manual clock/executor và timed latch/future/socket reads, không Thread.sleep. Bao gồm 2/3/4 client, winner, TIME_LIMIT HP phân số, draw simultaneous elimination, room/unjoined isolation, inbound spoof end, enqueue-once, immutable result trước/sau close, concurrent control frames và shutdown. Native test đếm World.step thật, mapper đọc lặp không step thêm; backpressure test giữ socket write thật bị block trong khi native runtime vẫn tick và connection khác gửi được.

Compile common/server/test source PASS; server build PASS với test task excluded vì targeted tests đã chạy riêng. Không chạy common test task hoặc full server suite, không giả các test chưa chạy là PASS. Gradle chỉ cảnh báo deprecated features hiện hữu.

## Giới hạn và bảo toàn workspace

- Không deadline write mới. Client không đọc có thể giữ writer riêng tới khi đọc/đóng/shutdown. Bỏ obsolete backlog không đồng nghĩa có thể vượt frame đang ghi hoặc cam kết delivery trong thời hạn hữu hạn.
- Disconnect giữa hai frame có thể mất GAME_END; không ACK/retry/reconnect. Không có ranking gateway, GAME_START push, victory UI hoặc Bước 46.
- Không sửa common DTO/codec, gameplay, physics/combat/MatchRuntime.tick(), core/desktop hoặc TMX. SHA-256 map vẫn `366044B95702FA12EB4310ED227DA808489A5AA1AAE9E648B6D365F17458E708`.
- Các tracked generated artifacts JAR/manifest/compile cache được backup trước Gradle và khôi phục đúng bytes ban đầu sau verification để không ghi đè thay đổi có sẵn của người dùng. Test report/classes mới giữ lại; tracked JAR trong worktree không được coi là artifact deployment mới.

Dừng ở Bước 45.
