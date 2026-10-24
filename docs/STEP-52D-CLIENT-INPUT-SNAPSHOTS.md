# Bước 52D — PLAYER_INPUT v1 và GAME_STATE v1

Hoàn thành input/snapshot boundary của client production. Gameplay online **chưa đủ để chơi/hiển thị**; dừng trước bước 52E.

## File của riêng bước 52D

Paths dưới `final-project/`, trừ tài liệu này:

| Tạo/sửa | File | Mục đích |
|---|---|---|
| Tạo | `core/src/com/smashprofs/game/network/client/ClientMatchSession.java` | Session cho một GAME_START: identity, input queue, tick metadata, snapshot buffer, cleanup. |
| Tạo | `core/src/com/smashprofs/game/network/client/WireGameSnapshot.java` | Immutable model/parser đúng GAME_STATE v1, HP float và projectiles. |
| Sửa | `core/src/com/smashprofs/game/network/client/LobbyClient.java` | Tạo session một lần sau start hợp lệ, route GAME_STATE, input gate và cleanup. |
| Sửa | `core/src/com/smashprofs/game/network/client/NetworkClient.java` | Clear outbound queues khi disconnect; serialize enqueue/cleanup để tránh enqueue sau clear. |
| Sửa | `core/src/com/smashprofs/game/network/client/OutboundInputQueue.java` | Thêm transaction enqueue, giữ head/edge khi bị từ chối. Legacy flush/poll/drop-oldest API giữ nguyên. |
| Sửa | `core/src/com/smashprofs/game/network/client/SnapshotBuffer.java` | Thêm typed `V1` buffer, bounded/thread-safe, từ chối duplicate/stale. Legacy buffer API giữ nguyên. |
| Sửa | `core/src/com/smashprofs/game/network/client/NetworkMetricsTracker.java` | Cadence cấu hình; production dùng 3 simulation ticks/snapshot. PING timestamp cũ chỉ dùng mock. |
| Sửa | `core/src/com/smashprofs/game/network/client/StateInterpolator.java` | Chú thích rõ domain 30 Hz là legacy mock; production v1 chưa dùng interpolator này. |
| Sửa | `core/src/com/smashprofs/game/Game.java` | Thu keyboard online sau GAME_START, qua LobbyClient; màn roster vẫn hiển thị, không chạy PlayScreen simulation. |
| Tạo | `tests/src/com/smashprofs/server/game/ClientMatchTcpIntegrationTest.java` | 7 TCP tests production. Package cho phép dùng scheduler injection có sẵn, không sửa API server. |
| Tạo | `tests/src/com/smashprofs/tests/network/ProtocolV1InputBufferTest.java` | 5 tests enqueue retention, cleanup, cadence, stale/duplicate và concurrency. |
| Tạo | `tests/src/com/smashprofs/tests/network/WireGameSnapshotTest.java` | 2 tests codec/parser, roster 2–4, HP float, 4 projectile types, malformed values và immutability. |
| Tạo | `docs/STEP-52D-CLIENT-INPUT-SNAPSHOTS.md` | Báo cáo và cách kiểm tra. |

Không sửa server source, Protocol v1, common/legacy DTO, Player, PlayScreen, CharacterSelectScreen, HUD, WinScreen, TMX, physics, combat, RankingGateway hoặc MockServer trong bước 52D. Các thay đổi có từ bước trước vẫn được giữ nguyên.

## Wire/input policy

NetworkClient tiếp tục dùng `JsonFrameCodec`/`ProtocolMessage`; framing là int32 BE length + UTF-8 JSON. Ví dụ envelope client tạo (clientTick minh họa):

```json
{"version":1,"type":"PLAYER_INPUT","payload":{"sequence":1,"clientTick":8,"moveAxis":1.0,"jumpPressed":true,"stompPressed":false,"meleePressed":false,"abilityPressed":false,"blockHeld":true}}
```

Production dùng `ProtocolV1.inputPayload(PlayerInput)`: chỉ 8 payload fields, không playerId/sessionId/roomId/target/damage. `requestId` được bỏ ở đường gửi input bình thường; protocol cho phép thêm để correlate ERROR, không có success ACK per-input. DTO input nội bộ dùng PlayerId từ CONNECT_ACK; identity trên server được lấy từ TCP session.

`LobbyClient.recordFrameInput(...)` chỉ hoạt động ở STARTED, có match session và connected identity khớp. Sequence bắt đầu 1, tăng cho mỗi input mới; retry chưa enqueue thành công giữ cùng sequence. Một session mới cho match mới có queue mới. Không tự reconnect/resume hoặc restart match trên cùng session.

`clientTick` là elapsed local match time tính từ GAME_START trong domain **60 Hz**, dùng monotonic nano clock; không phải frame counter, broadcast counter hoặc thời gian simulation của server. Sequence/tick là integer64 không âm. Axis phải finite trong [-1,1].

Input collection trên render thread chỉ tạo values và non-blocking enqueue. Socket write/codec encode chạy NetworkClient-Writer; socket read/codec decode/parser chạy NetworkClient-Reader. Không tạo Box2D objects từ JSON.

Keyboard sau start: A/D movement held; W jump edge; S stomp edge; V/Space melee edge; F ability edge; Left Ctrl block held. Lobby vẫn hiện roster; không bật local online simulation.

### Enqueue đầy và edge retention

`OutboundInputQueue.trySendAccumulatedInput` peek head rồi gọi non-blocking enqueue; chỉ poll head/reset accumulator khi enqueue trả true. Nếu false, head vẫn ở cùng queue. Các frame tiếp theo OR jump/stomp/melee/ability; movement/block lấy giá trị mới nhất. Không flush/drop oldest trong đường retry production. Production giữ tối đa một head chưa enqueue và một accumulation window, không có input queue thứ hai.

Test capacity 1 từ chối 1001 attempts chứng minh tất cả edge còn lại, cùng sequence 1, latest axis/block; input kế tiếp sequence 2 không lặp edge. TCP test dùng subclass quan sát của production NetworkClient để cưỡng bức enqueue trả false tại boundary; toàn bộ accepted messages vẫn dùng transport thật. Đây là kiểm tra nhánh queue-rejection có kiểm soát, không phải phép đo socket congestion.

Retention bảo đảm trước khi enqueue vào NetworkClient thành công. V1 không có input success ACK; không tự resend input đã enqueue khi server trả DUPLICATE_INPUT/QUEUE_FULL hoặc khi connection mất. Disconnect xóa pending input/snapshot và lobby identity/match/roster. Dữ liệu immutable của một session đã đóng chỉ còn là reference lịch sử nếu caller giữ reference đó.

## GAME_STATE và serverTick

`WireGameSnapshot.parse` đọc đúng mapper/schema hiện tại:

- Root: matchId, roomId, serverTick, phase PLAYING/FINISHED, remainingTimeMs, players, projectiles.
- Player: playerId **string**, slotIndex, characterId, x/y/velocityX/velocityY/**hp float**, grounded/blocking/dead/facingRight. Giữ cả 2–4 players, không chỉ local và một remote.
- Projectile: projectileId, matchId, ownerPlayerId string, projectileType IBM/STAR/MINE/ROCKET, spawnTick, x/y/velocityX/velocityY.

Parser kiểm tra fields/types, integer64/ranges, finite values, IDs/slots/projectile IDs duy nhất, projectile match/owner/type. Session đối chiếu matchId/roomId và toàn bộ identity/slot/character với GAME_START trước khi buffer. Tọa độ giữ world meters; không nhân/chia PPM. Lists và các records bất biến.

`SnapshotBuffer.V1` giữ tối đa 32 snapshots theo serverTick thật. Vì TCP có thứ tự, chỉ nhận tick **lớn hơn latest accepted**; duplicate/stale bị từ chối. Evict oldest khi capacity vượt giới hạn. Mọi producer/consumer operation synchronized; copies của retained list immutable.

Simulation 60 Hz, broadcast mặc định 20 Hz: tick 3 → 6 → 9 là bình thường. Metrics production dùng interval 3; không ghi hai drops cho 3 → 6. Gap estimate `(deltaTicks - 1) / 3` chỉ áp dụng khi delta > 3, biểu thị missed broadcasts (cũng có thể do latest-wins), không suy luận mất simulation tick/TCP packet. Duplicate/stale không cập nhật metrics. Final FINISHED có thể ngoài cadence, ví dụ tick 3 → 4.

Legacy `StateInterpolator`/mock buffer vẫn hoạt động ở mock domain cũ; production không ép WireGameSnapshot về legacy GameSnapshot/PlayerState (HP int) và chưa interpolate/render. FINISHED snapshot làm ngừng gửi input và clear pending input, giữ final snapshot; không thêm GAME_END/WinScreen integration.

## Kiểm chứng

JDK `E:\2026-2\lap-trinh-mang\baitap` — Java 17.0.12. Chạy từ `final-project`:

```powershell
$env:JAVA_HOME='E:\2026-2\lap-trinh-mang\baitap'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :tests:test --tests 'com.smashprofs.tests.network.*' --tests '*ClientMatchTcpIntegrationTest' :server:test --tests '*PlayerInputHandlerTest' --tests '*InputQueueTest' --tests '*GameStateTcpBroadcastTest' --tests '*TwoClientTcpMatchEndToEndTest' --tests '*CreateRoomTcpTest' --tests '*GameStartTcpTest' --tests '*TcpProtocolV1Test' --tests '*TcpHeartbeatIntegrationTest' :common:test --tests '*JsonFrameCodecTest' :core:build :desktop:build :tests:build --no-daemon --console=plain --continue
```

Sau thay đổi cuối ở enqueue/disconnect và thêm tests cleanup/3-player, chạy lại phần client/network và builds:

```powershell
.\gradlew.bat :tests:test --tests 'com.smashprofs.tests.network.*' --tests '*ClientMatchTcpIntegrationTest' :core:build :desktop:build :tests:build --no-daemon --console=plain --continue
```

| Group | Tests | Failures/errors/skipped |
|---|---:|---:|
| Client/network/mock/lobby/input/snapshot | 92 | 0/0/0 |
| Server targeted input/state/lobby/protocol/heartbeat | 159 | 0/0/0 |
| Common JsonFrameCodec protocol vectors | 27 | 0/0/0 |
| Tổng unique targeted tests | **278** | **0/0/0** |

Common protocol task dùng kết quả PASS `UP-TO-DATE`; source/codec/vectors không đổi. Build `:core:build`, `:desktop:build`, `:tests:build`: **PASS**. Không chạy toàn bộ test suite.

7 TCP tests mới dùng GameServer thật, NetworkClient production, localhost/port 0; full CONNECT → CREATE/JOIN → READY → GAME_START. Scheduler/clock injection có sẵn điều khiển countdown/ticks trên cùng simulation owner; heartbeat dùng real executor/clock. Không MockServer và không Thread.sleep trong tests mới; tất cả wait/test có bounded timeout.

Kết quả TCP: A/B có trusted IDs riêng; input A làm A di chuyển, không làm B di chuyển; B có input riêng; duplicate sequence trả DUPLICATE_INPUT, payload giả playerId của B trả UNAUTHORIZED_PLAYER, connection vẫn dùng được. Cả hai nhận cùng snapshot; 2/3/4-player rosters đủ slot. Fixture damage 7.25 ở simulation owner cho HP **92.75**, ability Viktor tạo projectile thật có mọi field khớp server immutable View. Tick tăng 3/6/9/12, metrics không false drops. Repeated GAME_START không tạo session/input loop mới. Local close và transport disconnect đều clear pending input/buffer/lobby state. 12 heartbeat PING được xử lý trong match khi không gửi gameplay input; connection và broadcast vẫn hoạt động. Final state tick 4 ngoài cadence vẫn parse và ngừng input.

Warnings còn thấy: unchecked operations sẵn có trong VFXObject, Gradle 7.3.3 deprecated features và RankingGateway chưa cấu hình khi fixture finish trận. Không failure của bước 52D; không sửa các phần ngoài phạm vi để xóa warnings.

## Git và giới hạn tiếp theo

Branch giữ `integration/member2-check`, HEAD `8c739f98cd9e52028897d82ef42f434ef3b93cd7`, MERGE_HEAD `9dcd8b4735d94ee5bc4d0091e1a267049eed4ad1`. Staged object list được đối chiếu không thay đổi; edits bước này chỉ nằm working tree, files mới untracked. Không commit/push/stage/reset/clean/restore/abort/checkout. Không sửa feature/server-runtime/origin/dev. Existing generated build artifacts và thay đổi của bước trước được giữ lại.

Chưa có authoritative player presentation, remote/projectile rendering, interpolation v1, online HP/HUD, prediction/reconciliation, GAME_END/WinScreen hoặc ranking integration. Chưa kiểm tra UI trực quan/LAN. Màn roster tiếp tục hiện sau start dù input/snapshot đang trao đổi. **Dừng tại 52D; không tuyên bố online gameplay đã chơi được.**
