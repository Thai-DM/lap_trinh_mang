# Bước 40 — Authoritative game snapshot builder

## 1. Khảo sát và lựa chọn contract

Ngày 11/10/2026. Đối chiếu MatchRuntime/MatchState/MatchSetup/ServerPlayer/PlayerSlot,
PlayerPhysicsBodies/PlayerJumpController, ProjectileManager/ServerProjectile/ProjectileType,
model common, protocol v0.1 và test runtime/physics/combat/ability/integration của các bước trước.
Tên resolver thực tế vẫn là `ProjectileType.forCharacter`; không có CharacterAbilityResolver riêng.

Đã có [common.GameSnapshot](../final-project/common/src/com/smashprofs/common/model/GameSnapshot.java)
và [common.PlayerState](../final-project/common/src/com/smashprofs/common/model/PlayerState.java).
Không tự tạo một DTO network thứ hai hoặc sửa những contract này:

- Common HP là `int`; authoritative ServerPlayer HP là `float`, có stomp self-cost 0,1.
  Convert ngay sẽ mất dữ liệu/đòi chọn rounding policy chưa thống nhất.
- Common thiếu slotIndex và projectile state.
- Common có `phase`/`remainingTimeMs`; phase ở GameRoom, runtime không có match timer.
  Countdown lobby không phải thời gian còn lại của trận. Không tự gán PLAYING hoặc 0 để che thiếu dữ liệu.

Theo phương án được yêu cầu khi contract chưa đủ, tạo **snapshot nội bộ server** tên
`AuthoritativeGameSnapshot`, không gọi nó là common.GameSnapshot hoặc GAME_STATE payload.
Không thêm adapter/cast HP, serialization hoặc protocol version. TODO phối hợp Member 3 nằm trong model.
Trước bước này chỉ có các view riêng lẻ JumpState/StompState/ServerProjectile.View,
chưa có snapshot whole-match hoặc getLatestSnapshot.

## 2. Files tạo/sửa

Các đường dẫn dưới tính từ repository root, có một thư mục `final-project/` là Gradle root.

Sửa:

- [MatchRuntime.java](../final-project/server/src/com/smashprofs/server/game/MatchRuntime.java):
  thêm volatile latestSnapshot, build/publish cuối tick, getter chỉ đọc cache, clear cache khi close.

Tạo:

- [AuthoritativeGameSnapshot.java](../final-project/server/src/com/smashprofs/server/game/AuthoritativeGameSnapshot.java):
  record cấp match và nested PlayerSnapshot.
- [GameSnapshotBuilder.java](../final-project/server/src/com/smashprofs/server/game/GameSnapshotBuilder.java):
  package-private boundary chuyển authoritative state sang giá trị bất biến.
- [MatchRuntimeSnapshotTest.java](../final-project/server/test/com/smashprofs/server/game/MatchRuntimeSnapshotTest.java):
  38 unit/native integration tests.
- `docs/STEP-40-SNAPSHOT-JOURNAL.md`.

Không sửa common/core/desktop/Gradle/TMX, controller, ProjectileManager, ServerProjectile hoặc test cũ.
Giữ những dirty changes có sẵn, không stage/reset hoặc chuyển sang feature tiếp theo.

## 3. Schema thực tế và nguồn authoritative

### AuthoritativeGameSnapshot — nội bộ server

| Field | Type | Nguồn |
| --- | --- | --- |
| matchId | String | MatchState, từ MatchSetup bất biến |
| roomId | String | MatchState, từ MatchSetup bất biến |
| serverTick | long | MatchState, sau advanceTick của tick hoàn tất |
| players | List<PlayerSnapshot> | Roster MatchState, sort slot tăng dần |
| projectiles | List<ServerProjectile.View> | ProjectileManager active registry sau cleanup |

### PlayerSnapshot

| Field | Type | Nguồn |
| --- | --- | --- |
| playerId | common.PlayerId | ServerPlayer, tạo từ roster MatchSetup |
| slotIndex | int | ServerPlayer, từ slot đã xác thực trong MatchSetup |
| characterId | String | ServerPlayer, từ selection/MatchSetup authoritative |
| x / y | float | Tâm native Body qua PlayerPhysicsBodies.getPosition; đơn vị mét |
| velocityX / velocityY | float | Native Body qua getVelocity; mét/giây |
| hp | float | ServerPlayer.getHp, không cast/round hoặc tính lại damage |
| grounded | boolean | JumpController đã synchronizeAfterStep |
| blocking | boolean | ServerPlayer.isBlocking, đã áp dụng held input/timeout/death gate |
| dead | boolean | ServerPlayer.isDead |
| facingRight | boolean | ServerPlayer.isFacingRight theo policy update facing hiện có |

Character/slot không nhận từ packet; vị trí không lấy từ client hoặc ServerPlayer giả.
Facing giữ đúng logic có sẵn: cập nhật từ vx trước movement của tick, vx=0 giữ hướng trước đó.
Không suy lại facing từ post-step vx để vô tình đổi gameplay/khác ability spawn policy.

### Projectile values — dùng lại ServerProjectile.View

Đã có record giá trị phù hợp, vì vậy **không tạo ProjectileSnapshot trùng chức năng**:

| Field | Type | Nguồn |
| --- | --- | --- |
| projectileId | long | ID riêng trong ProjectileManager của match |
| matchId | String | Metadata authoritative của projectile |
| ownerPlayerId | common.PlayerId | Owner được manager xác thực |
| projectileType | String | IBM / STAR / MINE / ROCKET từ resolver server |
| spawnTick | long | Tick server khi spawn, trước increment cuối tick đó |
| x / y | float | Native projectile Body, mét |
| velocityX / velocityY | float | Native projectile Body, mét/giây |

View là static nested record không giữ enclosing ServerProjectile instance hoặc Body/Vector2.
Không dùng ServerProjectile mutable làm item snapshot. Manager giữ active LinkedHashMap theo ID tăng dần;
removal không đổi thứ tự survivor, ID mới được thêm cuối. Player list sort theo slot, không theo HashMap/roster.
ProjectileId chỉ duy nhất trong một match, không phải global ID; scope được giữ bằng matchId/roomId.

Chỉ active registry **sau** collect/cleanup được xuất. Projectile đã contact và destroy ở tick N không có
trong snapshot N, dù pending damage của nó được resolve ở N+1 theo gameplay hiện có.
Spawn ở tick bắt đầu 0 xuất hiện trong snapshot hoàn tất tick 1 với spawnTick=0; đây là hai mốc khác nhau,
không off-by-one hoặc tự sửa spawnTick để trùng snapshot tick.

## 4. Những field không xuất / chưa hỗ trợ

- Phase/remainingTimeMs: chưa có nguồn whole-match thống nhất trong runtime; không giả lập.
- Common int HP và adapter/network schema: chờ Member 3/team agreement.
- Circle/Luca vẫn MISSING; snapshot xuất Luca player thật, không tạo Circle placeholder.
- Jump allowance, stomp active/pending, cooldown, Rocket target ID/steering có state nội bộ nhưng không nằm
  trong schema tối thiểu này; không expose internal collision event hoặc tự mở rộng protocol/render contract.
- Ready/session/connection/reconnect/ranking/leaderboard: không thuộc snapshot gameplay ở bước này.
- Sprite, Texture, animation/VFX/audio chỉ là presentation; không có nguồn authoritative headless tương ứng.

## 5. Builder và tick integration

`GameSnapshotBuilder.build` chỉ được MatchRuntime gọi trong production. Builder package-private,
không public một native-reader API cho MessageDispatcher/network thread. Runtime đã kiểm tra simulation owner
và giữ khóa tick trước khi gọi. Builder chỉ đọc state/body wrappers, tạo records/list và trả kết quả.
Không gọi step, applyDamage, setVelocity, spawn ability, create/destroy Body hoặc tạo native resource.

Thứ tự cũ được giữ:

`drain -> input/movement/jump/stomp -> gravity/boundary -> melee/stomp damage`
`-> projectile update/spawn/pending damage -> World.step(1/60,6,2)`
`-> grounded/recovery/landing/contact sync -> cleanup -> advanceTick`
`-> build immutable snapshot -> volatile publish`

Pending projectile/stomp contact tiếp tục resolve damage ở tick kế tiếp; không reorder combat để đáp ứng
một luồng tham khảo. Snapshot biểu diễn authoritative state tại **cuối tick**, không tính lại physics/damage.
HP đã resolve trong tick và Body đã step trong tick đều được lấy sau hoàn tất, không trộn pre-step position.

Tick convention:

- MatchState khởi tạo serverTick=0; start chỉ init, chưa có completed tick snapshot.
- Tick đầu tiên step/sync/cleanup thành công rồi advanceTick -> 1, build snapshot mang serverTick=1.
- Mỗi successful tick chỉ advance một lần và publish một snapshot. Getter không build/rebuild.
- Before start, after start-before-first-tick, failed first step: latestSnapshot=null.
- Trong tick đang chạy, reader tiếp tục thấy completed snapshot trước đó.
- Step lỗi: giữ completed snapshot trước đó, không publish HP/position đang dở hoặc tăng snapshot tick.
  Scheduler cô lập runtime như trước; cached snapshot không phải dấu hiệu runtime vẫn tiến triển.
- Nếu build/sync/cleanup ném lỗi thì không publish bản dựng dở; không hứa rollback những mutation hoặc
  tick increment đã xảy ra. Runtime error lifecycle vẫn cần policy riêng như audit Bước 39.
- Close: volatile closed guard + clear latestSnapshot; getter trả null, không truy cập World đã dispose.
  Snapshot caller giữ từ trước close vẫn là historical value hợp lệ, không đổi khi block state live được reset.

Dead player vẫn có Body đến match close theo policy Rocket Bước 38/39, vì vậy vẫn được xuất với hp=0/dead=true
và position/velocity native thật. Thiếu Body/handle đóng sẽ fail qua getter hiện có; không xuất tọa độ (0,0).
Initialization chỉ publish runtime sau khi mọi Body sẵn sàng, tránh roster có player không có native Body.

## 6. Immutability, publication và isolation

PlayerSnapshot và ServerProjectile.View chỉ chứa primitive/String/immutable PlayerId.
AuthoritativeGameSnapshot dùng List.copyOf cho **cả hai** list, không giữ caller mutable list hoặc mutable
ServerPlayer/Body/World/Fixture/session/socket. Đọc snapshot cũ sau tick hoặc World.dispose không cần JNI.

`private volatile AuthoritativeGameSnapshot latestSnapshot` là instance field của từng runtime.
Simulation owner dựng hết object graph rồi mới gán reference; volatile publish bảo đảm reader thấy graph đầy đủ.
Getter không synchronized, không đọc MatchState/Body/manager, không chờ native step giữ runtime monitor.
Reader phải giữ **một local reference** cho một lần sử dụng; gọi getter hai lần có thể gặp hai tick khác nhau:

```java
AuthoritativeGameSnapshot snapshot = runtime.getLatestSnapshot();
if (snapshot != null) {
    // Đọc serverTick/players/projectiles từ cùng object này; chưa serialize hoặc gửi network.
}
```

Các volatile getters MatchState/live ServerPlayer vẫn không phải atomic snapshot API.
Không trộn live getters với cached snapshot rồi coi là cùng một tick.
Concurrent test dừng simulation ngay sau native step thứ hai (runtime vẫn giữ monitor), để ba reader đọc
snapshot tick 1 hai trăm lần mỗi reader rồi mới cho tick 2 publish. Reader chỉ đọc cached values,
không step/read/mutate World. Sau publish thấy toàn bộ tick 2, không player mới với projectile cũ.
Worker/latch test dùng helper concurrency hiện có, timeout hữu hạn; không có thread/executor mới trong production.

Hai runtime có World/state/input/projectile/cache riêng, kể cả trùng PlayerId. Test A/B dùng character/roster
khác nhau, gây damage/spawn A rồi kiểm tra B, tick A không thay latestSnapshot B và close A không ảnh hưởng B.
Không global/static latestSnapshot hoặc buffer xuyên phòng.

## 7. Chi phí và ownership

Builder chỉ duyệt tối đa bốn player và active projectile; không quét TMX collider, copy World,
serialize JSON, tải asset hoặc tạo texture/native resource. Player sort tối đa bốn phần tử;
projectile values đi theo ordered registry có sẵn, không thêm sort hoặc cache phức tạp.
Độ phức tạp O(P log P + Q), P<=4, Q=active projectiles; mỗi tick cần records tương ứng.
Giữ đúng một latest snapshot; không giữ history/buffer tăng vô hạn trong runtime.
Caller giữ snapshot cũ là quyền sở hữu của caller, không phải runtime lưu lịch sử.

Build nằm trong khóa tick **đã có**, chỉ thêm copy giá trị giới hạn theo P/Q, không tạo khóa dài riêng hoặc I/O.
Không tuyên bố benchmark/load scalability: Q chưa có resource cap/TTL theo audit Bước 39, cần quyết định riêng.

## 8. Tests và kết quả thực tế

JUnit 4.13.1 / Java 17 / Gradle wrapper 7.3.3. Native World/TMX thật; fixture setTransform/velocity chỉ ở test,
damage melee/projectile/fall/stomp đi qua MatchRuntime. Không sleep/Desktop/GUI/socket.

25 kịch bản yêu cầu đã được bổ sung đúng tên:

- Tick, 2/3/4-player count, ID/slot/character, native position/velocity/HP.
- Melee/fall/movement/blocking, active/destroyed projectile.
- Deterministic player order, immutable lists, old snapshot stability, latest progression.
- Room isolation, concurrent readers, read-only state, không step thêm, closed lifecycle.

`existingServerTestsStillPass` được xác nhận bằng chạy **toàn suite**, không fake test assertTrue.
13 test bổ sung: before-first-tick, step lỗi/snapshot cũ, failed first tick, dead native Body, grounded/facing,
pending projectile damage N+1, defensive copy, same-tick reference, non-owner guard, value-only models,
projectile order, failed initialization và close-before-first-tick. Tổng **38 test mới**.

Lượt đầu: 35 tests, một test mới FAIL vì fixture đặt target tại IBM spawn bên phải, nhận thêm contact damage
ở tick sau (HP=80 thay vì 90). Chỉnh test đặt target bên trái để cô lập snapshot immutability;
không sửa damage/collision production. Đây không phải pre-existing failure.

Kết quả cuối:

- Baseline `:server:test --no-daemon --rerun-tasks`: **PASS 1.043 tests**.
- Snapshot suite sau chỉnh fixture: **38 PASS, 0 failure**.
- `.\gradlew.bat :server:test --no-daemon`: **PASS 1.081 tests**, 0 failure/error/skipped;
  gồm toàn bộ 1.043 test cũ (Bước 39 cũng còn 33 tests) và 38 mới.
- `.\gradlew.bat :server:build --no-daemon`: **BUILD SUCCESSFUL**;
  compile/assemble/check pass, test task up-to-date từ full suite ngay trước đó.
- JDK 17 dùng `JAVA_HOME=E:\2026-2\lap-trinh-mang\baitap` chỉ trong tiến trình lệnh; không đổi Java của hệ thống.
- Warning Gradle deprecated features có sẵn, không phải compile/test failure của bước này.
- Reports giữ tại `final-project/server/build/reports/tests/test/index.html` và `build/test-results/test/`.
- TMX SHA-256 không đổi: `366044B95702FA12EB4310ED227DA808489A5AA1AAE9E648B6D365F17458E708`.
- Artifact JAR/cache đã Git-track được khôi phục đúng bytes trước tác vụ để không ghi đè dirty changes của user;
  classes/reports mới được giữ. JAR trong worktree sau restore không phải artifact mới để phát hành.

## 9. Phối hợp Member 3 và giới hạn còn lại

Trước chuyển internal snapshot thành shared/network contract, cần team agreement:

1. HP float/fixed-point/int rounding; không silently bỏ stomp self-cost 0,1.
2. Có bổ sung slotIndex/projectile model/matchId/spawnTick vào common hay dùng adapter khác;
   schema trong tài liệu này là **nội bộ**, không tự thay common v0.1.
3. Phase/remaining time phải có authoritative nguồn và tick boundary thống nhất với GameRoom,
   không thêm lock runtime -> room trái thứ tự khóa room -> runtime đang có.
4. Policy stale snapshot khi runtime lỗi, closed match và kết thúc trận; bước này chưa có GAME_END/MatchResult.
5. Serialization/framing/transport và dữ liệu render cần thiết nếu thật sự có contract; chưa chọn trong bước này.

Mọi common/protocol change sau này cần cập nhật NETWORK-PROTOCOL.md và PROTOCOL-CHANGELOG.md cùng PR.
Không cập nhật hai file ấy trong Bước 40 vì chưa có protocol change.

## 10. Nhật ký kỹ thuật ngắn

Khảo sát thấy common snapshot tồn tại nhưng chưa biểu diễn float HP/slot/projectile và thiếu runtime phase/timer.
Chọn model nội bộ nhỏ, giữ PlayerId và dùng lại immutable projectile View. Thêm builder chỉ đọc trên owner
sau cleanup/increment; publish một volatile reference, getter không JNI/lock. Kiểm tra native values,
immutability/room isolation, thread readers và các lifecycle lỗi; toàn bộ 1.081 tests và build pass.
Không đổi gameplay, scheduler 60 Hz, networking, protocol hoặc ability; dừng tại Bước 40.
