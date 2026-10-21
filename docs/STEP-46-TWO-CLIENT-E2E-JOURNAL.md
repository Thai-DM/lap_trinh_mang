# Bước 46 — Trận hai client qua TCP thật, end-to-end

## Phạm vi và file

Chỉ thêm test/helper/journal, không phát hiện lỗi cần sửa production.

File mới, tương đối với Git root `final-project/`:

- `final-project/server/test/com/smashprofs/server/game/TcpTestClient.java`
- `final-project/server/test/com/smashprofs/server/game/TwoClientTcpMatchEndToEndTest.java`
- `docs/STEP-46-TWO-CLIENT-E2E-JOURNAL.md`

Không sửa file source/test cũ, Gradle, common/protocol, core/desktop, TMX, physics/combat hoặc scheduler/runtime production. Thay đổi source từ những bước trước vẫn được giữ nguyên.

## Khảo sát API và GAP thực tế

- `GameServer(Supplier<FixedTickScheduler>, LongSupplier)` dùng transport mặc định TCP v1, bind `127.0.0.1:0`. `start()`, `getBoundPort()`, `getConnectionManager()`, `getRoomManager()` là API thực tế.
- `ProtocolV1Dispatcher`: CONNECT cấp UUID identity server-owned; JOIN_ROOM/PLAYER_READY trả ROOM_STATE; PLAYER_INPUT không success ACK. Không có SELECT_CHARACTER message riêng: characterId đi cùng PLAYER_READY.
- **GAP CREATE_ROOM wire:** hiện ERROR NOT_IMPLEMENTED. Test tạo room bằng `RoomManager.getOrCreateRoom("arena")` trước, nhưng JOIN của cả hai client vẫn là TCP thật. JOIN production hiện cũng get-or-create; không đổi luật thành join-existing-only. Negative test kiểm tra CREATE_ROOM không âm thầm thành công.
- **GAP GAME_START push:** enum/schema đã có nhưng server chưa gửi. Test xác định start qua production RoomPhase/MatchRuntime trong fixture và GAME_STATE đầu tiên qua TCP, không giả GAME_START response.
- Khi hai player Ready, scheduler gọi `RoomManager.updateLifecycles()` tự bắt đầu countdown và khởi tạo/register MatchRuntime. Test chạy đúng production cycle bằng `ManualScheduledExecutor` đã tồn tại; không gọi trực tiếp join/ready/startMatch/submitInput thay TCP.
- Clock inject kiểm tra đủ 3000 ms (2999 ms còn COUNTDOWN, thêm 1 ms mới PLAYING). Production vẫn 60 Hz, broadcast 20 Hz. Không rút ngắn countdown hoặc thêm scheduler test vào production.
- MatchRuntime công bố cached immutable snapshot sau tick, `getMatchResult()` authoritative sau elimination/120-second timeout. GAME_END dùng schema v1 hiện có, không tạo result/winner mới trong helper.

## Helper và determinism

`TcpTestClient` chỉ thuộc server test source set, package-private. Kết nối Socket thật với connect timeout 5 giây; send complete frames và decode bằng `JsonFrameCodec` chính thức. Không có serializer/framing khác hoặc background reader của test.

Helper correlate requestId, phân biệt type và buffer message chưa được assert; lưu history bất biến theo thứ tự nhận thực tế để kiểm tra terminal adjacency. Chờ response có deadline 5 giây. Kiểm tra không có message dùng socket read timeout 150 ms, không sleep. Đây là cửa sổ quan sát hữu hạn, không tuyên bố chứng minh không bao giờ có message ở tương lai.

Hai Socket/CONNECT được mở đồng thời bằng hai callable có start latch, Future timeout 5 giây; pool test shutdown/await hữu hạn. UUID identity khác nhau và session binding được đối chiếu trực tiếp với registry/roster. Không bind PlayerId bằng API nội bộ.

CONNECT lặp trên cùng TCP stream được dùng làm barrier xác nhận reader đã xử lý input trước đó. Đây là CONNECT_ACK idempotent hiện có, **không phải input success ACK** và không đổi protocol. Simulation đứng yên khi đang đợi network; sau barrier mới chạy manual cycle.

Fixture chỉ đọc private field đã xác minh (`inputQueue`, `physicsWorld`) bằng reflection để quan sát queue count và Body position/velocity. Không submit/drain/mutate queue hoặc World. Body observations giữ nguyên trước/sau TCP enqueue, và wire snapshot khớp native Body sau tick. Mỗi production cycle tăng đúng một serverTick; regression Bước 45 cũng chạy test counter World.step native. Không mở public production API chỉ để test.

## Luồng E2E chính — không ép HP

1. Start GameServer thật, lấy port động, tạo room fixture.
2. Hai CONNECT đồng thời → hai CONNECT_ACK / hai UUID khác nhau.
3. JOIN_ROOM thật → hai ROOM_STATE, hai slot khác nhau, hai session cùng room.
4. PLAYER_READY chọn Alex/Leo → authoritative selection/ready; production countdown đủ 3 giây → một MatchRuntime PLAYING.
5. A gửi movement phải; B gửi movement trái + stomp khi đang airborne (nếu grounded thì jump qua TCP trước).
6. Qua barrier, queue có hai input, cached snapshot và native Body chưa thay đổi. Production scheduler cycle drain queue và áp dụng movement/stomp/Box2D.
7. A/B nhận cùng GAME_STATE, đúng room/match/player/character/native coordinates; tick tăng không trùng. Stomp activation production trừ 0.1 HP của B, wire giữ HP khoảng 99.9 (float, không round integer).
8. Manual clock tiến 120 giây; production tick/evaluator tạo TIME_LIMIT result, A thắng vì HP cao hơn B. Đây là kết thúc production theo timeout, **không tuyên bố chơi đủ 7200 native ticks hoặc hạ đối thủ bằng một đòn đánh**.
9. Mỗi client nhận cặp final GAME_STATE FINISHED → GAME_END liền nhau. Winner/participants/finalHp/eliminated/finishedAtTick khớp authoritative result, không payload giả.
10. Chạy thêm 9 scheduler callbacks: cached result/snapshot giữ identity, không GAME_END thứ hai hoặc regular GAME_STATE sau end. TCP enqueue-once không đồng nghĩa application processed exactly-once.

## Kết thúc bằng death — fixture được phân biệt rõ

Test `lowHpServerFixtureFinishesThroughRealTcpStompAndProductionDeathEvaluator` giảm HP B xuống khoảng 0.05 bằng damage API package-private hiện có, trên test/simulation owner. B vẫn sống và result vẫn null ở thời điểm thiết lập.

B gửi stomp thật qua TCP khi airborne; chính production runtime trừ activation cost 0.1 HP, clamp HP về 0, đánh dead, evaluator tạo LAST_PLAYER_STANDING và A thắng. Cả hai client nhận terminal/result thật. Không gọi trực tiếp death, không ép winner, không tự tạo GAME_END payload, không thêm public HP setter. Đây là **server-side low-HP fixture + gameplay input**, không phải trận elimination hoàn toàn từ HP 100 mặc định.

## Kết quả test thực tế

JDK 17, Gradle wrapper 7.3.3, JUnit 4.13.1. Hai lượt targeted test đều PASS; lượt cuối gồm:

| Class | PASS | FAIL / ERROR / SKIP |
|---|---:|---:|
| TwoClientTcpMatchEndToEndTest (mới) | 8 | 0 |
| GameEndTcpDeliveryTest | 14 | 0 |
| GameStateTcpBroadcastTest | 15 | 0 |
| Tổng targeted | 37 | 0 |

Tám test mới:

1. `twoClientsConnectWithUniquePlayerIdsAndJoinSameRoom`: simultaneous CONNECT, trusted identities, hai slot/roster/session binding.
2. `twoClientsReadyAndStartExactlyOneMatchAfterFullCountdown`: một Ready chưa start, selection/ready đúng, 2999+1 ms, một runtime/scheduler; GAME_START là GAP.
3. `completeMatchThroughTcpMovementStompFractionalHpAndProductionTimeout`: toàn bộ pipeline không ép HP, native/wire state, monotonic ticks, immutable snapshot, winner/result/order/enqueue-once.
4. `duplicateAndImpersonatedInputsCannotControlOtherPlayer`: DUPLICATE_INPUT/UNAUTHORIZED_PLAYER, queue không nhận input giả, chỉ A có velocity ngang.
5. `lowHpServerFixtureFinishesThroughRealTcpStompAndProductionDeathEvaluator`: low-HP fixture còn sống → stomp qua TCP → production death/elimination/winner → hai GAME_END.
6. `unjoinedAndOtherRoomClientsReceiveNoForeignStateDamageOrEnd`: room thứ hai PLAYING khi room A FINISHED, HP 100 và đúng roster trong snapshot của B; unjoined không nhận gameplay message.
7. `closingClientDoesNotCrashMatchAndShutdownReleasesSocketsAndWorkers`: disconnect không crash/forfeit/remove roster, client còn lại nhận state; shutdown close sockets, clear registries, dispose runtime, stop executor và join worker threads đã quan sát với timeout.
8. `createRoomWireRemainsExplicitlyNotImplemented`: correlated ERROR NOT_IMPLEMENTED, không tạo phòng qua wire.

Không có test E2E chính BLOCKED. CREATE_ROOM/GAME_START vẫn là GAP sản phẩm; không đánh dấu tính năng chưa implement là PASS. PASS negative CREATE_ROOM chỉ có nghĩa server từ chối đúng.

Lệnh thực tế tại Gradle root `final-project/final-project/`:

```powershell
$env:JAVA_HOME='E:\2026-2\lap-trinh-mang\baitap'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :server:test --tests '*TwoClientTcpMatchEndToEndTest' --tests '*GameEndTcpDeliveryTest' --tests '*GameStateTcpBroadcastTest' --no-daemon
.\gradlew.bat :server:build -x :server:test --no-daemon
```

Compile common/server/test source PASS; server build PASS. Test task excluded ở build vì targeted tests đã chạy riêng; không chạy full 1250+ suite hoặc common test task. Chỉ warning Gradle deprecated features hiện hữu. Reports giữ tại `server/build/reports/tests/test/`.

## Member 2 và các giới hạn trước demo online

- Member 2 cần dùng official frame codec, correlate requestId mà không bỏ push xen kẽ; giữ UUID từ CONNECT_ACK, không gửi arbitrary PlayerId với input.
- Chọn character bằng PLAYER_READY; JOIN trả ROOM_STATE và hiện có thể tạo room. Không gửi CREATE_ROOM hoặc chờ GAME_START như thể chúng đã implement. MatchId/roster authoritative hiện nhận qua GAME_STATE đầu tiên.
- Snapshot HP float, tọa độ Box2D meters, ticks có thể nhảy vì latest-wins; không giả một TCP read là một message. Client cần đồng bộ/render trên thread phù hợp, xử lý terminal GAME_END đúng match sau final state.
- Client networking/synchronization/render/victory UI chưa được kiểm thử desktop trong bước này. Lobby roster/phase chưa push cho toàn bộ client; GAME_START push chưa có. Đây là dependency tích hợp/demo, không được mở rộng handler trong Bước 46.
- Chỉ kiểm chứng TCP loopback với server/simulation thật được điều khiển manual. Không xác minh hai máy LAN, firewall/latency, reconnect, authentication/TLS, ranking hoặc match dài chạy theo wall clock thật. Existing real scheduler broadcast regression vẫn PASS; không gọi manual fixture là kiểm thử stress 60 Hz thời gian thực.
- No ACK/retry; disconnect có thể mất terminal delivery. Không thêm deadline write, prediction/interpolation, gameplay balancing hoặc Bước 47.
- TMX SHA-256 giữ nguyên: `366044B95702FA12EB4310ED227DA808489A5AA1AAE9E648B6D365F17458E708`.
- Tracked generated JAR/manifest/compile cache được backup trước Gradle và restore đúng bytes có sẵn; classes/reports mới vẫn giữ. JAR tracked sau restore không phải artifact deployment mới. Chỉ xóa thư mục backup tạm riêng của tác vụ, không xóa source/user changes.

Dừng ở Bước 46.
