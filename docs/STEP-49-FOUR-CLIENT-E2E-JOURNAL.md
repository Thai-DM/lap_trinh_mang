# Bước 49 — Four-client TCP E2E và cách ly nhiều phòng

## Phạm vi và file

Chỉ thêm:

- `final-project/server/test/com/smashprofs/server/game/FourClientTcpMatchEndToEndTest.java`
- `docs/STEP-49-FOUR-CLIENT-E2E-JOURNAL.md`

Không sửa production, helper `TcpTestClient`, common/protocol, core, desktop, TMX, scheduler, physics hoặc combat. Không thêm subsystem, worker, public API hay message. Không triển khai Bước 50.

## Kỹ thuật kiểm thử

JUnit 4.13.1, Java 17. GameServer bind `127.0.0.1`, port 0; từng client có Socket độc lập và dùng JsonFrameCodec chính thức. TCP v1 vẫn JSON UTF-8 với header length signed int32 Big-Endian 4 byte, payload tối đa 1.048.576 byte.

Tái sử dụng `TcpTestClient`, `ManualScheduledExecutor` và clock monotonic injected có sẵn. Executor chỉ quyết định lúc chạy **Runnable production của FixedTickScheduler**: cập nhật lifecycle/countdown, tick runtime, publication sau cycle. Runtime và Box2D World/native/map là thật; không mock simulation, không gọi `tick()`/`submitInput()` trực tiếp trong E2E. Clock tiến 2.999 ms rồi 1 ms để kiểm tra trọn countdown 3 giây. Không thay scheduler production để test nhanh.

CONNECT lặp là barrier cùng TCP stream để xác nhận input trước đó đã được read worker xử lý; đây không phải success ACK mới cho PLAYER_INPUT. Socket read có deadline 5 giây, mỗi test có timeout 30 giây, join worker tối đa 5 giây. Cửa sổ quiet 150 ms là kiểm tra hữu hạn, không phải cam kết không bao giờ có message. Không dùng Thread.sleep hoặc Desktop Launcher.

Reflection chỉ đọc các field đã có `inputQueue`, `physicsWorld`, và đọc Body trên thread test khi manual cycle đang đứng yên; không cấp API mutable mới hoặc step World phụ. E2E xác nhận tick tăng một lần/cycle và read/dispatch không làm thay đổi Body/cached snapshot trước cycle. Số lần native `World.step()` được đếm qua seam đã có trong regression `MatchRuntimePhysicsTest` (bao gồm eachTickStepsWorldExactlyOnce/schedulerStepsPhysicsOncePerCycle); E2E không tự instrument World của room hay suy diễn tick counter là bộ đếm JNI riêng.

## Bốn test mới

### 1. fourClientsCompleteMatchWithAuthoritativeMovementStompAbilityAndTimeout

CONNECT → CREATE_ROOM/ROOM_CREATED → JOIN_ROOM → PLAYER_READY → countdown → GAME_START → PLAYER_INPUT/GAME_STATE → final GAME_STATE/GAME_END.

Mỗi thao tác request/input đi qua TCP; không tạo phòng hoặc bind session thủ công. PlayerId là UUID khác nhau do server cấp. Mapping theo thứ tự JOIN:

| Player | Slot | Nhân vật | Input |
|---|---:|---|---|
| P1 | 0 | Alex | moveAxis +1 |
| P2 | 1 | Leo | moveAxis -1 |
| P3 | 2 | Luca | stomp khi airborne |
| P4 | 3 | Viktor | ability IBM |

GAME_START của cả bốn client có cùng matchId, roomId, roster/slot/character đúng MatchSetup immutable. Không phát trước PLAYING, chỉ một START và đứng trước STATE đầu tiên.

Bốn input vào đúng InputQueue, chưa mutate Body trước simulation. Production cycles drain queue, P1/P2 di chuyển trái dấu; P3/P4 không bị gán movement của P1/P2. Stomp thực sự mất 0,1 HP, snapshot giữ float `100f - 0.1f`, không round int. IBM được production spawn từ ability TCP; wire projectileId/matchId/owner/type/spawnTick/position/velocity khớp cached authoritative view, không projectile giả. Không bắt buộc projectile trúng đối thủ.

STATE tick 3 rồi 6 theo cadence mặc định: simulation 60 Hz, publisher 20 Hz. Các client nhận cùng payload, players sort theo slot, tọa độ/vận tốc khớp cached snapshot và native Body; tick tăng nghiêm ngặt, không trùng. Đây là kiểm tra boundary manual có đọc giữa các batch, không đòi số frame chính xác trong thời gian thực.

Clock tiến thêm 120 giây, production evaluator tạo TIME_LIMIT. Các player HP cao nhất bằng nhau → hòa, winnerId null theo luật hiện tại. Cả bốn nhận cùng result gồm bốn participant với HP/dead/slot/character chính xác. Final STATE FINISHED đứng ngay trước END; sau thêm cycle/barrier không có END lặp và result/snapshot cuối giữ cùng instance. Test này **không có fixture HP**.

### 2. duplicateInvalidAndSpoofedInputsCannotControlOtherThreePlayers

JOIN lặp không thêm duplicate roster. Client thứ năm nhận ROOM_FULL, roster vẫn bốn. Input sequence lặp nhận DUPLICATE_INPUT; spoof playerId của từng P2/P3/P4 nhận UNAUTHORIZED_PLAYER; moveAxis 2 nhận INVALID_PAYLOAD. Chỉ input hợp lệ của P1 vào queue; ba người còn lại không nhận movement của P1. Server vẫn chạy, fifth/unjoined không nhận START/STATE của room.

### 3. oneEliminationDoesNotEndFourPlayerMatchAndLastSurvivorWins

**Fixture test-scope được khai báo rõ:** sau start giảm HP P2/P3/P4 bằng package-private `ServerPlayer.applyDamage(99.95f)` trên test/simulation owner, khi cả ba vẫn sống. Nếu grounded thì jump qua TCP trước. Sau đó stomp qua TCP và production tick mới gây death. Không tạo result hoặc ép winner/phase bằng test.

P2 chết trước: vẫn PLAYING, result null vì còn ba survivor. P3/P4 tiếp tục stomp qua TCP; production evaluator tạo LAST_PLAYER_STANDING, P1 thắng. Cả bốn client (kể cả player đã chết) nhận cùng final snapshot/result và terminal ordering; END không lặp.

### 4. twoRoomsKeepWorldsQueuesProjectilesDamageAndResultsIsolatedAfterDisconnect

Một GameServer, sáu socket: ROOM_A4 có bốn player, ROOM_B2 có Alex/Leo ở slots 0/1. **Cả hai phòng đều CREATE/JOIN/READY bằng TCP**, cùng scheduler cập nhật từng runtime trong cùng cycle (không mutate World song song).

MatchSetup/matchId, native World và InputQueue tách biệt. A nhận stomp/IBM, B nhận movement riêng: pending input tách biệt; roster/state/projectile không xuyên room, B không có IBM hoặc mất HP bởi stomp ở A. Tất cả START/STATE đều xác minh đúng room/match/roster.

Đóng client P4 ở A không làm B lỗi. **Fixture HP riêng A:** applyDamage(100) cho P2/P3/P4 để kiểm tra terminal isolation nhanh, sau đó production cycle tự tạo result/phase/END; không coi đây là trận kết thúc hoàn toàn bằng input. Ba connection A còn mở nhận END cùng bốn participant; B không nhận END, vẫn PLAYING/result null, tick tiếp tục 4 → 6 → 9. Disconnect không tự loại player khỏi roster — giữ behavior hiện tại, không thêm forfeit/reconnect.

FINISHED hiện giữ native resources tới cleanup tường minh. Test gọi removeRoom(A) sau delivery: runtime A đóng, World A dispose, cached snapshot A null; B vẫn có World sống và tiếp tục tick/publish. Cuối cùng server.stop đóng B, dispose World B, clear registries và cached snapshots, ngừng executor; không tạo END giả cho B đang chơi. Các socket còn mở nhận EOF, sessions disconnected, TCP workers đã quan sát đều kết thúc sau bounded join. Helper không giữ Body hoặc gọi JNI sau dispose.

## Backpressure và giới hạn

Tái chạy nguyên `TcpSnapshotBackpressureTest` (13 test) thay vì tạo harness mới: native loopback write bị backpressure, pending snapshot cap 1/latest-wins, START cap 1 và terminal queue cap 4/control không latest-wins, START trước snapshot/terminal, final STATE + END không xen control, overflow chỉ đóng connection tương ứng, slow reader không chặn native simulation hoặc client khác, shutdown/invalid guards dọn queue và unblock writer.

Tests E2E bốn người không giả rằng vài snapshot nhỏ chắc chắn làm đầy TCP buffer. Kiểm tra slow-reader được thực hiện riêng ở boundary thực sự block write trong suite đã có; không tuyên bố đã stress/load test cả hệ thống 4 người. Production vẫn dùng worker theo connection, không worker mới cho mỗi snapshot; Bước 49 không sửa cơ chế này.

Chưa có write-timeout eviction: client không đọc có thể chiếm writer riêng tới khi đọc/disconnect/shutdown. Chưa heartbeat/reconnect, LEAVE_ROOM handler/forfeit, ranking/database, room quota/expiration hoặc soak test mạng WAN. CONNECT identity là session identity MVP, không authentication hoàn chỉnh. Đây là các giới hạn hiện hữu, không tự triển khai trong Bước 49.

## Test và build

Chạy tại Gradle root `final-project/final-project`, JAVA_HOME là JDK 17 (môi trường PATH mặc định Java 8 không phù hợp):

```powershell
.\gradlew.bat :server:test --tests '*FourClientTcpMatchEndToEndTest' --tests '*TwoClientTcpMatchEndToEndTest' --tests '*GameStartTcpTest' --tests '*GameStateTcpBroadcastTest' --tests '*GameEndTcpDeliveryTest' --tests '*TcpSnapshotBackpressureTest' --tests '*MatchRuntimePhysicsTest' --no-daemon
.\gradlew.bat :server:build -x :server:test --no-daemon
```

| Suite | PASS | FAIL / ERROR / SKIP |
|---|---:|---|
| FourClientTcpMatchEndToEndTest | 4 | 0 / 0 / 0 |
| TwoClientTcpMatchEndToEndTest | 8 | 0 / 0 / 0 |
| GameStartTcpTest | 9 | 0 / 0 / 0 |
| GameStateTcpBroadcastTest | 15 | 0 / 0 / 0 |
| GameEndTcpDeliveryTest | 14 | 0 / 0 / 0 |
| TcpSnapshotBackpressureTest | 13 | 0 / 0 / 0 |
| MatchRuntimePhysicsTest | 29 | 0 / 0 / 0 |
| **Tổng targeted** | **92** | **0 / 0 / 0** |

Test mới cũng đã chạy riêng PASS 4/4 trước lần regression cuối. Không chạy lại toàn bộ suite 1.250+ tests và không báo PASS cho phần chưa chạy. Không phát hiện lỗi production cần sửa. Gradle có cảnh báo deprecated features cho Gradle 8, không phải compile/test failure.

Build server: **PASS**, wrapper `:server:build -x :server:test --no-daemon` trả exit code 0. Sáu build artifacts đã tracked (common/server JAR, compilation cache và manifest) được phục hồi đúng bytes trước task và xác minh SHA-256 để không ghi đè thay đổi của người dùng; reports/classes sinh mới giữ lại. Chỉ thư mục backup tạm riêng Bước 49 đã được xóa sau restore; không xóa source/report hoặc dữ liệu người dùng. JAR được phục hồi không phải artifact deployment của lần build này. TMX giữ SHA-256 `366044B95702FA12EB4310ED227DA808489A5AA1AAE9E648B6D365F17458E708`.

## Member 2 và demo

Không có blocker cho phạm vi test Bước 49. Demo có client/render thật chưa được kiểm chứng ở bước này; cần Member 2 tích hợp:

- CONNECT identity/correlation, CREATE riêng rồi JOIN/READY, không giả creator tự join.
- GAME_START lấy match/room/roster, STATE lấy position/velocity/HP và projectile; không suy luận spawn từ START.
- Input sequence tăng, edge/held semantics, không gửi playerId/hit/damage authoritative.
- Decode TCP partial/coalesced frames bằng codec v1; render/sync theo PlayerId cho 2–4 người, không PlayerOne/PlayerTwo.
- Bỏ snapshot cũ theo match/tick; xử lý final STATE trước END và winnerId null là hòa.
- UI connection/error/disconnect rõ ràng; không hứa reconnect/heartbeat khi server chưa có.

Nhật ký đồ án: xác nhận path production hoàn chỉnh bốn người bằng loopback TCP và native physics, hai room trên một scheduler giữ tài nguyên/roster/result riêng, START/STATE/END giữ ordering, result authoritative có hòa và last-survivor, cleanup explicit không ảnh hưởng match còn lại. Các fixture HP và phạm vi backpressure được tách khỏi gameplay TCP không fixture để báo cáo trung thực.
