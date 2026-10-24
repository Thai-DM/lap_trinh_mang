# Bước 39 — Gameplay integration audit & một critical-gap fix

## 1. Phạm vi và kết luận

Audit ngày 11/10/2026 trên source/test thực tế, không dựa vào tên class hoặc lịch sử để suy ra coverage.
Gradle root là `final-project/` bên trong repository; server dùng `src/`, `test/`, Java 17,
JUnit 4.13.1 và Box2D native. Các đường dẫn dưới đây tính từ tài liệu này.

Luồng lobby/countdown/start/scheduler/input/physics/combat đã nối với nhau và có native tests.
Đây là end-to-end simulation phía server, **không phải một game online hoàn chỉnh**: chưa có transport,
serialization hoặc snapshot/broadcast. Chỉ sửa một lỗi HIGH: input điều khiển của player đã chết.
Không thêm skill Luca hoặc triển khai Bước 40.

## 2. Coverage năm nhân vật

COMPLETE: implementation được gọi trong runtime, có test native/hành vi tương ứng, trong phạm vi server đã port.
PARTIAL: hoạt động nhưng còn thiếu một phần hành vi source gốc.
MISSING: không có implementation server tương ứng.
NOT_APPLICABLE: không thuộc gameplay headless của server.
COMPLETE không có nghĩa clone mọi bug/singleton của source, hoặc đã có UI/network.

| Nhân vật | Ability gốc -> resolver server | Ability | Cooldown server | Damage contact thực tế | Tổng thể gameplay |
| --- | --- | --- | --- | --- | --- |
| Alex | HomingMissile -> ROCKET | COMPLETE | 180 tick / 3 s | 10 HP | PARTIAL |
| Leo | Landmine -> MINE | COMPLETE | 300 tick / 5 s | 10 HP | PARTIAL |
| Luca | CircleStar -> chưa có mapping/type | MISSING | Chưa implement | Chưa implement | PARTIAL |
| Maurice | ThrowingStar -> STAR, burst 4 | COMPLETE | 45 tick / 0,75 s | 10 HP mỗi projectile trúng | PARTIAL |
| Viktor | IBM -> IBM | COMPLETE | 180 tick / 3 s | 10 HP | PARTIAL |

Mọi nhân vật còn PARTIAL tổng thể vì horizontal movement chưa port đầy đủ damping/air-axis reduction.
Luca vẫn có cơ chế chung; chỉ special ability là MISSING, không phải cả nhân vật không chạy được.
Cooldown được lấy từ khai báo từng projectile, nhưng chuyển sang per-player/type và serverTick từ các bước trước;
không giữ bug static delay/global availability của client gốc. Metadata Mine=25/Star=15 trong constructor
không phải damage thực tế được consume trong contact path gốc; nhánh combat dùng 10. Không đổi balance ở bước này.

| Cơ chế | Alex | Leo | Luca | Maurice | Viktor |
| --- | --- | --- | --- | --- | --- |
| Selection -> MatchSetup -> ServerPlayer | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE |
| Native Body + spawn từ map gốc | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE |
| Horizontal movement so với đầy đủ source | PARTIAL | PARTIAL | PARTIAL | PARTIAL | PARTIAL |
| Jump/double jump | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE |
| Ground detection | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE |
| Fall recovery/penalty | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE |
| Melee/damage/cooldown server hiện có | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE |
| Blocking melee | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE |
| Profile knockback không có hệ số slot | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE |
| Stomp/landing damage | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE |
| Ability activation/physics/contact/damage/cooldown | COMPLETE | COMPLETE | MISSING | COMPLETE | COMPLETE |
| Cleanup Body/World/controller hiện có | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE |
| Texture/animation/audio/VFX/UI | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE |

Bằng chứng mới: `allFiveCharactersCanBeInitialized` đi từ GameRoom selection/ready/countdown/prepareMatch
đến runtime native trong 2–4 người, xoay năm nhân vật qua roster/slot và kiểm tra Body/HP/spawn.
`allFiveCharactersShareImplementedGroundJumpAndFallMechanics`, `meleeAndBlockingInteractCorrectly`,
`meleeAndKnockbackInteractCorrectly`, `jumpAndStompDoNotConflict`,
`allFiveCharactersCanLandStompDamageAgainstBlockingTarget` chạy cơ chế chung cho cả năm character.
Các test spawn/fixture/ground/cooldown/contact chi tiết từ các bước cũ vẫn được chạy toàn bộ.
Test resolver không fallback cho Luca chỉ chứng minh GAP; **không** chứng minh Circle COMPLETE.

## 3. Subsystem và source đã đối chiếu

- [GameRoom](../final-project/server/src/com/smashprofs/server/room/GameRoom.java),
  [RoomManager](../final-project/server/src/com/smashprofs/server/room/RoomManager.java), PlayerSlot/RoomPhase:
  roster tối đa bốn, chọn character hợp lệ, ready/countdown, cache MatchSetup, start runtime và registry.
- [GameServer](../final-project/server/src/com/smashprofs/server/network/GameServer.java),
  [FixedTickScheduler](../final-project/server/src/com/smashprofs/server/game/FixedTickScheduler.java):
  room lifecycle trước tick, đăng ký runtime, một simulation executor 60 Hz, unregister/stop trước cleanup.
- [PlayerInputHandler](../final-project/server/src/com/smashprofs/server/network/PlayerInputHandler.java),
  MessageDispatcher/ClientSession/ConnectionManager: boundary session -> đúng room/roster -> submitInput;
  không tự tick, chưa phải socket transport/authentication.
- [MatchRuntime](../final-project/server/src/com/smashprofs/server/game/MatchRuntime.java), MatchSetup,
  [MatchState](../final-project/server/src/com/smashprofs/server/game/MatchState.java),
  [ServerPlayer](../final-project/server/src/com/smashprofs/server/game/ServerPlayer.java): owner chính thức
  của simulation, metadata/HP/dead/block/facing. Position/velocity chỉ ở native Body, không có bản sao state thứ hai.
- [InputQueue](../final-project/server/src/com/smashprofs/server/game/InputQueue.java): validation,
  bounded buffers, sequence watermark, OR edges và latest held state.
- [PhysicsWorldFactory](../final-project/server/src/com/smashprofs/server/game/physics/PhysicsWorldFactory.java),
  HeadlessPhysicsWorld, TmxCollisionLoader/MapCollisionBuilder, PlayerPhysicsFactory/SpawnProvider:
  World riêng, map gốc 18 static collider, dynamic player Body/fixture và native ownership/rollback cleanup.
- [PlayerMovementController](../final-project/server/src/com/smashprofs/server/game/physics/PlayerMovementController.java),
  [PlayerJumpController](../final-project/server/src/com/smashprofs/server/game/physics/PlayerJumpController.java),
  GroundContactListener/ArenaBoundaryController: impulse/clamp, hai lần nhảy, support contact, gravity và recovery.
- [MeleeCombatController](../final-project/server/src/com/smashprofs/server/game/combat/MeleeCombatController.java),
  [StompCombatController](../final-project/server/src/com/smashprofs/server/game/combat/StompCombatController.java):
  target selection, damage/knockback, cooldown và pending landing riêng từng player.
- [ProjectileManager](../final-project/server/src/com/smashprofs/server/game/projectile/ProjectileManager.java),
  ServerProjectile và [ProjectileType](../final-project/server/src/com/smashprofs/server/game/projectile/ProjectileType.java):
  resolver/registry/cooldown/update/hit/cleanup đều thực sự được runtime gọi. **Không có class
  CharacterAbilityResolver riêng**; API thực tế là `ProjectileType.forCharacter`.
- Source gốc: [Player](../final-project/core/src/com/smashprofs/game/Actors/Players/Player.java),
  [CombatManager](../final-project/core/src/com/smashprofs/game/Helper/CombatManager.java), PlayerTypes,
  B2dContactListener và các projectile; core chỉ đọc để đối chiếu, không sửa hoặc gọi singleton gốc từ server.

Không phát hiện subsystem gameplay đã implement nhưng bị bỏ quên ngoài `tick()`.
Không có mutable gameplay singleton/static registry trong server: World, player, input, cooldown,
ground/stomp state, projectile và target/steering đều theo runtime. Static constants/immutable sets/logger
không phải shared gameplay state. PlayerOne/PlayerTwo chỉ còn ở comment đối chiếu source, không làm identity.
Ranking/reliability/metrics là package markers; không suy ra đã implement service/heartbeat.

## 4. Thứ tự tick thực tế sau sửa

1. Giữ khóa runtime, kiểm tra started/open và simulation owner; sai owner bị chặn trước drain.
2. Drain queue: newest moveAxis/blockHeld, OR các action edges. Giữ batch gốc để trả về;
   batch có thể apply được loại player đã chết. Xóa held input cũ của player chết.
3. Ghi held input của player sống; hạn 15 tick (250 ms). Chốt stomp eligibility **trước jump**
   để ground jump+stomp cùng tick không tạo combo ngoài source.
4. Blocking/facing, horizontal movement. Axis=0 ngừng gia tốc, không tự phanh;
   clamp ngang cũ vẫn chạy cho Body chết, không thêm freeze hoặc sửa policy clamp.
5. Jump edges, airborne stomp edges/self-cost; gravity impulse mỗi Body, rồi boundary/fall penalty.
   Hủy stomp của player chết hoặc đã recovery.
6. Theo slot order ổn định: melee edge rồi pending stomp landing. HP/dead có hiệu lực ngay;
   player chết trước lượt combat không đánh tiếp. Block chặn melee, không chặn stomp.
7. Update projectile đã có -> spawn từ ability edge -> resolve pending projectile hit của tick trước.
   Controller/manager vẫn tự kiểm tra alive khi action xảy ra, kể cả chết giữa tick.
8. **Một** `World.step(1/60, 6, 2)`. Không subsystem nào có step riêng hoặc dùng client deltaTime.
9. Đồng bộ grounded/recovery, capture stomp landing, collect projectile contacts,
   destroy collided projectile khi World đã unlocked. Callback chỉ ghi ID/contact facts, không destroy/damage.
10. Advance serverTick một lần; trả batch đã drain. Tick thất bại không advance, không hứa rollback state
    đã mutate; scheduler cô lập runtime lỗi, không tiếp tục tick runtime ấy.

Stomp landing và projectile contact sinh trong step được resolve damage ở **tick kế tiếp** giống đường
contact flags/update combat gốc. Đây không phải hit được xử lý hai lần: pending event được consume một lần,
projectile Body có thể đã destroy nhưng pending hit chỉ giữ ID/metadata. Không ép reorder để damage cùng step.
Jump/grounded và HP không phải hai nguồn position. Getter MatchState/public player fields riêng lẻ chưa bảo đảm
đọc nguyên một tick atomically; boundary snapshot sau này cần lấy native/state cùng khóa runtime.

## 5. Input correctness, fairness và room isolation

`moveAxis`, `blockHeld` là held; jump/melee/stomp/ability là edges. Queue reject invalid/unknown PlayerId,
axis không hữu hạn/out-of-range, sequence âm/stale/duplicate và overflow. Edges không tự replay hoặc auto-fire
khi cooldown hết; nhiều packet trong một drain OR edge một lần. clientTick không điều khiển simulation time.
PlayerInputHandler đối chiếu ID với session đã đăng ký, room hiện tại còn mở/PLAYING và roster match;
test cũ `spoofedPlayerIdIsRejected`, `playerNotInMatchIsRejected`, `inputDoesNotReachOtherRoom`,
`sessionAssignedToDifferentRoomMustBelongToItsRoster` và các race tests tiếp tục PASS.

Multiplayer policies **đã có**, không thay đổi ở Bước 39:

- Melee/stomp: gần nhất còn sống trong range strict <0,2 m, không self-hit; hòa khoảng cách phân xử theo slot.
  Nếu target gần nhất block thì melee hụt, không xuyên qua tìm người khác.
- Lượt combat theo slot để không phụ thuộc HashMap/packet/roster order; lethal hit có hiệu lực ngay.
  Vì vậy tình huống hòa/simultaneous lethal vẫn có ưu tiên slot. Không khẳng định toàn bộ outcome invariant
  dưới mọi permutation; test invariant áp dụng cùng attack/điều kiện với target duy nhất.
- Melee profile giữ quyết định Bước 33: `J=(signX*1,5; 0,2 + gravityTarget)`.
  Từ đứng yên trên không, mass=1, gravity=-0,098 hai lần (update+hit), native vy=0,004.
  Stomp profile=(signX*2; 2+gravityTarget), projectile=(signX*1,5; 2+gravityTarget).
  Không có hệ số slot/PlayerOne/PlayerTwo, không đổi damage/cooldown hoặc character stats.
- Projectile giữ self-hit theo source; không tự thêm owner immunity/team rule. Block không chặn projectile.
  Guidance target của Rocket không phải target contact duy nhất được phép nhận damage.
- Rocket: chọn đối thủ sống gần owner nhất lúc bắn, hòa theo PlayerId; khóa target riêng cho từng Rocket;
  target chết thì tiếp tục bám Body tới contact hoặc match close, theo policy người dùng chốt ở Bước 38.
- Slot 2/3 spawn bổ sung từ platform lớn nhất TMX; khác vị trí spawn không phải khác sức mạnh knockback.

Tests mới dùng 2/3/4 người, các character và đổi slot/hướng tương đương. Hai runtime có cùng PlayerId
vẫn có World/Body/input/HP/block/cooldown/projectile/stomp riêng. Test room isolation kích hoạt melee+ability
ở cùng serverTick của cả hai room, chứng minh cooldown/queue không chia sẻ. Close A không ảnh hưởng B;
manual scheduler bỏ qua runtime đã closed. Không destroy trong World locked hoặc dùng native reference sau dispose.

## 6. Danh sách vấn đề và quyết định sửa đúng một lỗi

| Mức | Vấn đề thực tế | Quyết định Bước 39 |
| --- | --- | --- |
| CRITICAL | Chưa tái hiện crash/cross-room leak/native resource failure trong luồng bình thường được kiểm thử. Đây không phải chứng minh tuyệt đối không có crash. | Không tạo lỗi giả hoặc đổi subsystem. |
| HIGH | Runtime vẫn áp dụng movement/jump cho dead player; held movement cũ tiếp tục tới timeout. | **Sửa duy nhất vấn đề này.** |
| HIGH / GAP | Luca không có Circle authoritative. | Giữ MISSING: bước này cấm projectile/skill mới. |
| MEDIUM | Horizontal controller thiếu ground damping 0,999 và airborne axis-change reduction 30% của source. | Không tự đổi movement/balance. |
| MEDIUM | Tie target của melee/stomp/type cũ và simultaneous lethal combat dùng slot order. | Ghi rõ policy còn giới hạn, không redesign fairness. |
| MEDIUM | Projectile không có TTL/cap; trận dài không contact có thể tích lũy resource. | Chưa load-test hoặc chọn policy mới; không tự invent TTL. |
| MEDIUM | Scheduler quarantine runtime lỗi nhưng chưa có room error transition/automatic owner cleanup ngay lúc lỗi. World vẫn được room giữ tới remove/shutdown. | Không sửa cùng lỗi death; cần policy failure lifecycle trước công bố snapshot lỗi. |
| LOW | Một số comment lịch sử ở MatchState/physics còn nói chưa simulation. | Không refactor/naming ngoài phạm vi. |

### Reproduction và fix

Baseline trước sửa: 1.010 tests PASS. Thêm ba native regression tests rồi chạy trên production cũ:

- `deadPlayerCannotApplyFreshMovement`: expected vx=0, actual **0,055000003**.
- `deadPlayerCannotJumpFromGround`: expected jumpsUsed=0, actual **1**.
- `deathClearsPreviouslyHeldMovement`: expected vx=0, actual **0,055000003**.

Ba test FAIL chứng minh một lỗi tại input application boundary, không phải lỗi JNI hoặc giả định collision.
Melee/stomp/ability/block đã có dead gate, nhưng movement/jump thiếu cùng điều kiện trong runtime.

Fix chỉ ở MatchRuntime: lọc `actionableInputs` theo alive tại tick, dùng cho held/edges/combat/ability;
xóa held của dead player kể cả không có packet mới. Giữ batch drain gốc và queue/sequence API:
`submitInput == ACCEPTED` nghĩa transport đã enqueue, **không** đảm bảo dead player được action.
Input enqueue lúc còn sống nhưng chết trước drain cũng không được apply.

Không deactivate/delete/teleport/freeze dead Body; giữ gravity/boundary, native inertia trong policy clamp cũ,
roster và Rocket target contract. Nếu chết trong combat sau movement của cùng tick, movement đã thực hiện khi
còn sống không rollback; tick tiếp theo chặn control và xóa held. Không cho dead player revive qua input.

## 7. Integration tests bổ sung

File [MatchRuntimeGameplayIntegrationTest](../final-project/server/test/com/smashprofs/server/game/MatchRuntimeGameplayIntegrationTest.java)
có **33 JUnit tests**, dùng World/TMX thật và seam native đã tồn tại. Đặt transform/velocity chỉ để bố trí
fixture hoặc cô lập impulse trong test; không fake HP hit/contact/jump state. Damage regression trực tiếp
applyDamage chỉ là fixture death; có thêm test chết do **melee runtime thật** để xác minh chuỗi production.
Không dùng Thread.sleep, GUI, socket hoặc simulation thread mới.

| Kịch bản được yêu cầu | Bằng chứng mới / kết quả |
| --- | --- |
| twoPlayerMatchCanSimulateMovementAndCombat | PASS |
| threePlayerMatchMaintainsIndependentPlayerStates | PASS |
| fourPlayerMatchMaintainsIndependentPlayerStates | PASS |
| allFiveCharactersCanBeInitialized | PASS; selection/countdown/setup, 15 roster cấu hình 2–4 người |
| selectedCharacterMapsToCorrectAbility | PASS cho bốn ability đã implement; **GAP Circle/Luca**, không báo skill PASS |
| meleeAndBlockingInteractCorrectly | PASS, năm character |
| meleeAndKnockbackInteractCorrectly | PASS, năm character |
| jumpAndStompDoNotConflict | PASS, năm character |
| projectileAndMeleeCanCoexist | PASS |
| multipleProjectilesCanCoexist | PASS, bốn ability / bảy projectile |
| fallDamageAndCombatDamageRemainConsistent | PASS, fall + stomp self-cost + melee, không lặp penalty |
| duplicateInputDoesNotDuplicateActions | PASS, trước/sau drain |
| edgeActionsDoNotRepeatAcrossTicks | PASS kể cả vượt cửa sổ melee cooldown |
| slotPermutationDoesNotChangeEquivalentCombat | PASS, 2–4 người, đổi slot và hướng, target duy nhất |
| roomIsolationIsPreserved | PASS, cùng PlayerId vẫn tách native/state/queue/cooldown |
| closingOneMatchDoesNotAffectAnother | PASS, qua scheduler manual, closed match không step lại |
| resourcesAreDisposedSafely | PASS, bốn type, close hai lần, models bỏ Body, closed tick/input reject |
| worldStepsExactlyOncePerServerTick | PASS, dt/iterations/step counter native thật |
| serverTickIsMonotonic | PASS, thành công + không tăng sau close |
| existingServerTestsStillPass | Xác nhận bằng toàn suite **1.010 tests cũ**, không tạo assertTrue giả |

14 tests còn lại tăng độ phủ death gate (chín tests), cơ chế chung năm character, held timeout,
stomp landing/damage, ability cooldown và native projectile damage/blocking.
Death matrix quét năm character qua mọi vị trí roster 2–4 người; kiểm tra player sống không bị chặn nhầm.
Rocket/dead Body regression giữ target ID/native identity và tốc độ homing đúng.
Native contact damage và cleanup được kiểm tra bằng chờ tối đa tám **manual ticks**, không sleep.

## 8. Files và kết quả build/test

Sửa duy nhất production:

- `final-project/server/src/com/smashprofs/server/game/MatchRuntime.java`.

Tạo mới:

- `final-project/server/test/com/smashprofs/server/game/MatchRuntimeGameplayIntegrationTest.java`.
- `docs/STEP-39-GAMEPLAY-INTEGRATION-AUDIT.md`.

Không sửa common/core/desktop/Gradle/TMX hoặc test cũ. Giữ nguyên dirty changes có sẵn của người dùng.

Kết quả thực tế bằng wrapper Gradle 7.3.3 / JDK 17 (`JAVA_HOME` chỉ đặt trong tiến trình lệnh):

- Baseline `:server:test --no-daemon --rerun-tasks`: **PASS 1.010**, 0 failure.
- Reproduction trước fix: **3 tests, 3 FAIL**, số đo ghi ở mục 6.
- Integration sau fix: **33 PASS**, 0 failure.
- `.\gradlew.bat :server:test --no-daemon`: **PASS 1.043**, 0 failure/error/skipped; gồm toàn bộ 1.010 cũ + 33 mới.
- `.\gradlew.bat :server:build --no-daemon`: **BUILD SUCCESSFUL**; compile/assemble/check pass;
  test task trong build up-to-date sau full suite ngay trước đó.
- Report: `final-project/server/build/reports/tests/test/index.html`, XML trong `build/test-results/test/`.
- Warning deprecated Gradle features có sẵn; không phải compile/test failure của bước này.
- TMX giữ SHA-256 `366044B95702FA12EB4310ED227DA808489A5AA1AAE9E648B6D365F17458E708`.
- Sau build, chỉ các artifact JAR/cache đã được Git track được khôi phục đúng bytes trước tác vụ để
  giữ nguyên thay đổi người dùng; giữ classes/reports. JAR đang nằm trong worktree không phải bản mới để phát hành.

## 9. Blocker / quyết định trước GameSnapshot

1. Thiết kế một snapshot boundary lấy Body position/velocity + HP/dead/block/facing + grounded + serverTick
   dưới cùng khóa runtime/simulation owner. Không đọc riêng các volatile field rồi gọi đó là atomic tick snapshot.
2. `ServerPlayer.hp` là float (stomp cost 0,1), nhưng `common.PlayerState.hp` là int: cần thống nhất rounding
   hoặc protocol change với cả nhóm; không silently cast hoặc sửa common ở Bước 39.
3. Phase thuộc GameRoom, tick thuộc runtime; cần quy định frame/remaining time/phase và hành vi runtime lỗi/closed.
   Không publish state của tick step thất bại như một tick thành công. Pending damage tick kế tiếp cần tài liệu rõ.
4. Nếu yêu cầu đủ cả năm ability trước snapshot/playtest thì Circle/Luca là blocker coverage;
   có thể thiết kế snapshot của state đã có nhưng không tuyên bố full game coverage.
5. Chưa có snapshot representation cho projectile IDs/type/owner/position, hoặc định dạng/framing transport.
   Thiết kế cần team agreement, không tự thêm field/message/model trong bước này.
6. Cần đánh giá workload 2–4 người/nhiều room trên một simulation executor và resource projectile dài hạn;
   cap/TTL/tie fairness/damping là các policy cần quyết định riêng, không chặn kỹ thuật tạo DTO tối thiểu nhưng
   là giới hạn trước công bố production-ready gameplay.

Không triển khai bất kỳ mục nào ở trên trong Bước 39.

## 10. Nhật ký kỹ thuật ngắn

Đọc current implementation và đối chiếu source gốc; xác minh resolver thực tế và bốn ability end-to-end,
ghi Luca MISSING thay vì suy diễn từ lịch sử. Chạy baseline sạch về test, tái hiện lỗi dead-input bằng
ba native regressions trước khi fix. Chặn control ở runtime boundary, giữ native ownership/passive death
và Rocket policy; không đổi controller hoặc balance. Thêm 33 tests integration/regression trên World thật,
chạy lại toàn bộ 1.043 tests và server build thành công. Dừng ở Bước 39.
