# Bước 48 — GAME_START notification over TCP

## Khảo sát và integration boundary

GAME_START v1 đã có đầy đủ schema: `{matchId,roomId,players:[{playerId,slotIndex,characterId}]}`. Không có requestId cho push, tick/spawn/HP trong schema. Không đổi common DTO/codec/version/golden vectors.

Countdown hoàn tất trong GameRoom.updateCountdown; updateLifecycle gọi prepareMatch/startMatch. GameRoom chỉ publish runtime/PLAYING sau candidate.start và registration thành công; lỗi dọn candidate và không báo success. MatchSetup immutable đã chứa match/room identity và 2–4 participants duy nhất. Spawn authoritative do SpawnProvider/TMX/PlayerPhysicsFactory tạo trong start, không gửi thêm field chưa có contract.

Runtime state bắt đầu tick 0, completed snapshot đầu tiên tick 1. FixedTickScheduler hiện có gọi StateBroadcaster sau cycle; default snapshot 20 Hz tại tick 3/6/9, simulation vẫn 60 Hz. Bước 48 dùng chính hook này, không sửa scheduler hoặc tạo callback/polling thread/lifecycle thứ hai. Cursor mới quan sát runtime started/PLAYING với cached snapshot hoàn tất, thường ở tick 1, offer start trước snapshot cadence.

## File tạo/sửa

Tương đối với Git root `final-project/` (Gradle root nằm trong `final-project/` bên trong):

Tạo mới:

- `final-project/server/src/com/smashprofs/server/network/GameStartDelivery.java`
- `final-project/server/src/com/smashprofs/server/network/GameStartMessageMapper.java`
- `final-project/server/test/com/smashprofs/server/game/GameStartTcpTest.java`
- `final-project/server/test/com/smashprofs/server/network/GameStartMessageMapperTest.java`
- `docs/STEP-48-GAME-START-JOURNAL.md`

Sửa source:

- `final-project/server/src/com/smashprofs/server/game/MatchRuntime.java`: chỉ thêm getter `getMatchSetup()` trả đúng immutable setup đã có; không sửa start/tick/physics/combat.
- `final-project/server/src/com/smashprofs/server/network/StateBroadcaster.java`: start gate theo runtime, roster/room/session validation và non-blocking offer trước snapshot.
- `final-project/server/src/com/smashprofs/server/network/SessionChannel.java`, `ClientSession.java`: thêm boundary/delegate offerGameStart, không send fallback.
- `final-project/server/src/com/smashprofs/server/network/TcpClientConnection.java`: pending start bounded, worker priority/recheck và cleanup hiện có.

Sửa test:

- `final-project/server/test/com/smashprofs/server/network/StateBroadcasterTest.java`: start-once trước cadence, không tick thêm, guard invalidation.
- `final-project/server/test/com/smashprofs/server/network/TcpSnapshotBackpressureTest.java`: start trước pending state/terminal khi writer blocked thật, shutdown dọn start.
- `final-project/server/test/com/smashprofs/server/game/GameStateTcpBroadcastTest.java`, `GameEndTcpDeliveryTest.java`: consume notification mới thay vì coi GAME_START là frame bất ngờ; giữ assertions STATE/END.
- `final-project/server/test/com/smashprofs/server/game/TwoClientTcpMatchEndToEndTest.java`: nhận start thật và thay expectation GAP cũ; end-to-end nay đủ CREATE/JOIN/READY/start/input/state/end.

Sửa docs: `docs/PROTOCOL_V1.md`, `NETWORK-PROTOCOL.md`, `PROTOCOL-CHANGELOG.md`.

Không sửa GameServer/GameRoom/RoomManager/FixedTickScheduler, CREATE/JOIN handlers, TcpTestClient, common, core/desktop, TMX hoặc GAME_END schema/result/evaluator. Các thay đổi writer chỉ là integration scheduling, không luật gameplay.

## Wire, recipients và enqueue-once

```json
{"version":1,"type":"GAME_START","payload":{"matchId":"m1","roomId":"arena","players":[{"playerId":"p1","slotIndex":0,"characterId":"Alex"},{"playerId":"p2","slotIndex":1,"characterId":"Leo"}]}}
```

Mapper dùng đúng instance MatchRuntime.getMatchSetup, sort bản nhìn participants theo slot, không sửa list hay tạo snapshot giả. Official JsonFrameCodec/JsonTcpMessageCodec giữ UTF-8 và length int32 BE 4 bytes, payload tối đa 1 MiB. Player positions/HP chờ GAME_STATE, không đoán từ start.

Recipient có trusted PlayerId đã CONNECT, open/registered đúng session instance, room binding đúng và thuộc setup roster. Delivery guard kiểm tra publisher/room/runtime/session chưa đóng, identity/binding/registry còn đúng trước encode. Không spectator, không arbitrary client identity.

Cursor gắn runtime identity có startPublished gate; mỗi lifecycle chỉ một lượt enqueue tới các recipient hợp lệ, không gửi lại khi scheduler/getPhase/Ready/setup reads lặp. Connection cũng từ chối start trùng match hiện tại. Session disconnect không được catch-up qua connection mới; không ACK/retry/reconnect hoặc application processed exactly-once claim.

WAITING/READY/COUNTDOWN, countdown hủy, startup failure hoặc closed/finished trước start publication không gửi start. Start đã được offer hợp lệ khi PLAYING vẫn được giữ trước terminal nếu trận FINISHED trong lúc socket chậm; guard không bỏ event đã hợp lệ chỉ vì phase tiến tới FINISHED. Không phát start hồi tố cho runtime lần đầu quan sát đã FINISHED. Nếu tick khởi đầu lỗi và chưa có cached snapshot hoàn tất, publisher không giả success notification; không sửa lifecycle lỗi ngoài phạm vi.

## Ordering/backpressure

Một pending start riêng mỗi connection, không latest-wins hoặc socket send trên simulation. Worker Bước 43–45 vẫn duy nhất, ưu tiên start rồi mới snapshot/terminal; recheck queue sau khi lấy sendLock tránh claimed delivery vượt start chưa ghi. Snapshot thường vẫn latest-wins; start/end không bị coalesce. Không requeue snapshot obsolete của match đã có terminal.

Đã ghi một frame thì hoàn tất, không cắt/xen byte. Start có thể phải chờ frame/control write trước nó; không ảnh hưởng simulation hoặc connection khác. Cặp final GAME_STATE → GAME_END vẫn liền nhau dưới cùng sendLock. Control replies khác có thể xen giữa start và regular snapshot, không bị đổi thành ACK gameplay mới.

Bounded pending: một start + một regular snapshot + tối đa bốn end bundles, ngoài các references hằng số worker đang claim. Lifecycle production hiện một trận/session. Start slot đầy bởi extension khác match thì close riêng connection, không queue vô hạn. Close/invalid guard dọn start/snapshot/end, socket close unblock writer; không thêm executor/thread/timeout production.

## Test/build thực tế

JDK 17, Gradle 7.3.3 wrapper, JUnit 4.13.1. Lượt đầu 87 tests PASS; lượt cuối sau rà race có thêm TCP lifecycle regression:

| Class | PASS | FAIL / ERROR / SKIP |
|---|---:|---:|
| GameStartTcpTest (mới) | 9 | 0 |
| GameStartMessageMapperTest (mới) | 2 | 0 |
| StateBroadcasterTest | 26 | 0 |
| TcpSnapshotBackpressureTest | 13 | 0 |
| TwoClientTcpMatchEndToEndTest | 8 | 0 |
| GameStateTcpBroadcastTest | 15 | 0 |
| GameEndTcpDeliveryTest | 14 | 0 |
| TcpIoLifecycleTest | 21 | 0 |
| Tổng targeted | 108 | 0 |

14 test mới. TCP thật localhost/port 0, TcpTestClient/JsonFrameCodec hiện có. 2/3/4-client tests xác nhận identity/roster/characters/slots, version/push và start trước state tick 3. Multi-room test cho hai room bắt đầu cùng lúc và một room hủy countdown; unjoined/disconnected bị cách ly; repeated Ready/phase/callback không duplicate.

Startup failure được inject qua clock seam đã có: countdown đọc clock thành công, chính MatchRuntime.start đọc clock khi native initialization rồi throw. Candidate cleanup/lifecycle failure production chạy thật, room.runtime vẫn null, client không nhận false start. Không thêm runtime factory hoặc public API để ép success.

Backpressure test giữ native socket write thật bị block bởi một frame match khác đã bắt đầu, rồi queue start/current snapshot/end; frame trước hoàn tất nguyên vẹn, tiếp theo START → final STATE → END. Shutdown test dọn start pending và unblock worker. E2E/regression tiếp tục kiểm tra movement/HP/combat/result/terminal pair; publisher test xác nhận đọc lặp không tăng native simulation tick.

```powershell
.\gradlew.bat :server:test --tests '*GameStartTcpTest' --tests '*GameStartMessageMapperTest' --tests '*StateBroadcasterTest' --tests '*TcpSnapshotBackpressureTest' --tests '*TwoClientTcpMatchEndToEndTest' --tests '*GameStateTcpBroadcastTest' --tests '*GameEndTcpDeliveryTest' --tests '*TcpIoLifecycleTest' --no-daemon
.\gradlew.bat :server:build -x :server:test --no-daemon
```

Targeted tests PASS; compile common/server/test source và server build PASS. Không chạy full 1250+ suite hoặc common tests; codec/schema/golden không đổi nên không giả các test chưa chạy là PASS. Gradle chỉ có warning deprecated features hiện hữu. Reports tại server/build/reports/tests/test.

## Giới hạn / Member 2

Member 2 nay có thể chuyển vào trận khi nhận GAME_START theo matchId/roomId/roster, đợi GAME_STATE cho vị trí/HP/tick. Notification không có spawn hoặc tick field. Countdown vẫn nội bộ, chưa countdown broadcast. Không kiểm thử desktop UI/LAN/latency hoặc thêm prediction/interpolation.

Enqueue-once không bảo đảm client nhận/xử lý; socket failure/slow client vẫn theo lifecycle cũ, không write-timeout/ACK/retry mới. CREATE/JOIN policy, countdown 3 giây, simulation 60 Hz, state 20 Hz và terminal result không đổi. Không heartbeat/reconnect/ranking/database/gameplay mới hoặc Bước 49.

TMX SHA-256 vẫn `366044B95702FA12EB4310ED227DA808489A5AA1AAE9E648B6D365F17458E708`. Tracked generated JAR/manifest/compile cache được backup và restore đúng bytes có sẵn sau verification, classes/reports mới giữ lại; tracked JAR đã restore không phải deployment artifact mới. Chỉ xóa backup tạm riêng của tác vụ.

Dừng ở Bước 48.
