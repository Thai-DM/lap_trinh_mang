# Nhật ký Bước 38 — HomingMissile/Rocket của Alex

## Khảo sát và lựa chọn

Đọc [coverage Bước 37](STEP-37-ABILITY-COVERAGE-JOURNAL.md) và đối chiếu code/test thực tế:
ProjectileType có IBM/STAR/MINE; ProjectileManager có activation/cooldown/physics/hit/cleanup tương ứng;
MatchRuntime gọi manager từ action edge; các bộ test IBM (53), Star (41), coverage/Mine (41) chứng minh
gameplay đã hoạt động. Alex/Rocket và Luca/Circle chưa có implementation server, không phải PARTIAL
chỉ vì class projectile tồn tại trong core. Không có ability UNKNOWN sau khảo sát.

Chọn đúng một skill: **HomingMissile/Rocket của Alex**, không chọn lại Mine của Bước 37.
Alex chưa có special ability authoritative. Rocket dùng được native projectile lifecycle hiện có;
Circle còn cần chốt orbit center phụ thuộc sprite và global maxCircles=2 trong source.
Không tạo CharacterAbilityResolver hoặc AbilityManager thứ hai: dùng ProjectileType.forCharacter hiện có.

Source gốc được đối chiếu:

- [Alex.java](../final-project/core/src/com/smashprofs/game/Actors/Players/Alex.java),
  [HomingMissile.java](../final-project/core/src/com/smashprofs/game/Actors/Projectiles/HomingMissile.java),
  [Projectile.java](../final-project/core/src/com/smashprofs/game/Actors/Projectiles/Projectile.java).
- [Player.java](../final-project/core/src/com/smashprofs/game/Actors/Players/Player.java),
  [CombatManager.java](../final-project/core/src/com/smashprofs/game/Helper/CombatManager.java),
  [B2dContactListener.java](../final-project/core/src/com/smashprofs/game/Helper/B2dContactListener.java).
- Server: MatchRuntime/MatchState/ServerPlayer/InputQueue, ProjectileType/ProjectileManager/ServerProjectile,
  các controller combat/physics và tests cũ. Không sửa các subsystem này ngoài file liệt kê bên dưới.

## Hai chính sách multiplayer đã được người dùng xác nhận trước khi sửa

CombatManager gốc truyền playerinactive duy nhất của trận 1v1 vào HomingMissile; chưa có luật cho 3–4 người.
Không tự suy diễn phần này thành gameplay gốc. Người dùng đã chốt:

1. Khi bắn, server chọn đối thủ còn sống gần **owner** nhất theo khoảng cách tâm Euclidean;
   bằng khoảng cách thì chọn PlayerId.value theo thứ tự chuỗi, không theo slot hoặc HashMap/packet order.
   Rocket khóa target riêng tại spawn, không tự đổi target khi người khác đến gần.
2. Nếu target chết sau spawn, Rocket **tiếp tục bám Body của target đã chết** cho đến khi contact hoặc match đóng;
   không hủy do target chết và không retarget. Roster/Body của runtime hiện được giữ đến match close.

Không có đối thủ sống tại lúc bắn: không tạo body và không tiêu cooldown.
Homing target chỉ điều khiển hướng bay; Rocket vẫn có thể chạm một player khác hoặc owner theo collision gốc.
Dead target không nhận thêm damage/knockback, nhưng contact với body đó vẫn kết thúc Rocket.
Owner chết không tự xóa Rocket đã bắn. Không tạo team/owner immunity hoặc hệ thống target global.

## Coverage trước và sau Bước 38

COMPLETE nghĩa là đủ activation, cooldown, native physics, hit/damage, cleanup và tests trong phạm vi
gameplay server, không bao gồm Texture/Sprite/VFX/Sound. Không sao chép các bug static cooldown/local-player flags.

| Character | Ability | Original Behavior | Server Implementation | Status: trước -> sau | Test Coverage | Remaining Work |
| --- | --- | --- | --- | --- | --- | --- |
| Alex | HomingMissile / Rocket | Dynamic circle; lerp 0.05, speed thực tế 0.5; cooldown khai báo 3 s; contact bất kỳ kết thúc. | ROCKET trong resolver/manager, target ID và steering riêng mỗi projectile, hit API/cleanup hiện có. | MISSING -> COMPLETE | MatchRuntimeHomingMissileTest: native homing, targeting, cooldown, hit/cleanup, 2–4 player/isolation và regression. | Không thiếu phần gameplay của Rocket theo hai chính sách đã chốt; rendering/VFX/audio ngoài phạm vi. |
| Leo | Landmine / Mine | Gravity impulse -0.098; Tile không phá, contact khác phá; cooldown 5 s. | MINE, impulse update riêng, Tile rule và cleanup giữ nguyên. | COMPLETE -> COMPLETE | 41 coverage/Mine tests Bước 37 + regression trộn bốn loại. | Không đổi ở Bước 38. |
| Luca | CircleStar / Circle | Orbit radius 0.25 quanh tâm sprite; angular -12, cooldown 2 s; global cap 2; không có nhánh destroy Circle. | Chưa có type/handler/runtime server. | MISSING -> MISSING | Resolver không fallback sang skill khác; chưa có test chứng minh Circle end-to-end. | Orbit không phụ thuộc renderer, ownership/cap/lifecycle/hit và native tests. |
| Maurice | ThrowingStar / Star | Burst 4 hướng, velocity unit vectors, angular -12; cooldown 0.75 s, mọi contact kết thúc. | STAR giữ nguyên Bước 36. | COMPLETE -> COMPLETE | 41 multi-projectile tests + regression trộn bốn loại. | Không đổi ở Bước 38. |
| Viktor | IBM | Bay ngang ±0.7, angular 4; cooldown 3 s, mọi contact kết thúc. | IBM giữ nguyên Bước 35. | COMPLETE -> COMPLETE | 53 projectile tests + regression trộn bốn loại. | Không đổi ở Bước 38. |

Không có special ability chỉ có model/handler mà thiếu physics/damage/cooldown/cleanup được đánh dấu COMPLETE.
Movement tổng thể vẫn PARTIAL so với đầy đủ damping/air-axis logic của Player gốc; không sửa ở bước này.

## Constants và homing thực tế

- Activation từ abilityPressed edge, player thuộc roster và còn sống, characterId lấy từ MatchState là Alex,
  có đối thủ sống và cooldown hoàn tất. Không có yêu cầu grounded, facing đối thủ, self-cost hoặc owner không block.
- Cooldown: `180 tick = 3 s` ở 60 Hz, per `(PlayerId, ProjectileType)`, dùng serverTick;
  spam không gia hạn, duplicate/stale sequence bị InputQueue chặn, nhiều edge một batch gom một lần,
  không có edge mới thì hết cooldown cũng không auto-fire. Giữ giải pháp cooldown khai báo từng type
  đã có ở Bước 35–37, không dùng static delayInSeconds/Timer gốc bị chia sẻ giữa loại/player.
- Spawn: `(ownerX + facingSign * 0.15, ownerY)` vì player radius=5 px và multiplier=3, PPM=100.
  Spawn tick velocity=(0,0); update projectile cũ trước spawn đúng thứ tự CombatManager gốc.
- Dynamic circle radius=0.03 m, fixedRotation=false, angular velocity ban đầu=0; không bullet/sensor.
  Fixture category=3, mask=0xffff, group=0, density=0/mass mặc định=1, friction=0.2, restitution=0.
- World gravity=(0,0); Rocket không nhận Mine gravity, không dùng IBM velocity 0.7.
  Field speed=0.7 trong HomingMissile không được consume; update thật dùng `nor().scl(0.5)`.
- Mỗi Rocket có targetVector ban đầu=(0,0). Mỗi tick:
  `delta = targetBody.position - rocketBody.position`;
  `targetVector = normalize(lerp(targetVector, delta, 0.05)) * 0.5`;
  `rocketBody.setLinearVelocity(targetVector)`.
  Vector2 gốc mutate cả nor/scl: vector đã scale 0.5 là history cho tick tiếp theo, không giữ raw direction.
  Khi vector bằng 0, giữ semantics Vector2.nor gốc, không thêm hướng fallback mới.
- Damage thực tế và constructor Rocket đều bằng **10.0 HP**. Hit dùng ServerPlayer.applyDamage hiện có,
  float HP clamp >=0/dead ngay, không tạo HP thứ hai.
- Knockback `J=(signX*1.5, 2+gravityTarget)`, sign theo ownerX/targetX, cùng X đẩy trái;
  không có hệ số slot. Từ đứng yên trên không với ordinary gravity cùng tick: native vy=1.804.
- Blocking không chặn Rocket như nhánh projectile gốc; melee vẫn bị block, không đổi stomp/movement/jump.

Không thêm duration/TTL, expiry ngoài arena, giới hạn range, arming time, AOE hoặc retarget.
Rocket kết thúc khi contact bất kỳ (Tile/player/projectile) hoặc runtime đóng. Explosion trong source chỉ là VFX.
Không có TTL giống nguồn đồng nghĩa projectile có thể tồn tại lâu nếu không contact; không tự thêm resource cap.

## Integration, fairness và resource ownership

Luồng cũ giữ nguyên: InputQueue -> authoritative character resolver -> validate/cooldown/spawn -> update/hit
trong MatchRuntime.tick. Không thêm game loop/thread hoặc đổi scheduler 60 Hz.
Thứ tự vẫn update projectile cũ -> spawn -> resolve hit tick trước -> một World.step(1/60,6,2)
-> collect contacts -> deferred cleanup -> advance serverTick.

Target của Rocket là PlayerId immutable, steering Vector2 private riêng mỗi instance; không giữ reference
Body/ServerPlayer của target, không expose mutable steering hoặc thay record View/protocol.
Homing tra vị trí target từ PlayerPhysicsBodies của đúng match mỗi tick, kể cả target đã chết theo lựa chọn người dùng.
Khi close, World dispose trước và projectile handle được release; inactive model chỉ giữ metadata ID, không giữ native body.

Native listener nhận cả thứ tự fixture A/B, chỉ ghi ID thuộc chính registry; không destroy Body hoặc sửa HP khi World locked.
Collect chọn tối đa một target contact sống gần projectile nhất; riêng Rocket tie theo PlayerId để tránh slot bias.
Các type cũ giữ tie theo slot đã có, không refactor combat của chúng. Guidance target không thay thế contact hit detection.
Deferred cleanup destroy sau step/unlocked, hit metadata resolve ở tick sau, không lặp damage sau cleanup.

Mỗi runtime có World/registry/cooldown/pending events riêng; target lựa chọn và contact đều thuộc roster của chính match.
Test dùng cùng PlayerId/toạ độ trong hai room; close hoặc hit phòng A không ảnh hưởng phòng B.
Network thread chỉ submit input; simulation owner hiện có chạy homing/native update. Không sửa dispatcher/handler.
Test thay slot của cùng identity/character trong 2–4 player và đảo attacker/target giữ cùng damage/knockback;
test tie target dùng IDs không theo slot, roster/packet order không quyết định mục tiêu.

## File tạo/sửa

Đường dẫn source/test dưới đây tính từ `final-project/server/` ở root repository.

Tạo:

- `test/com/smashprofs/server/game/MatchRuntimeHomingMissileTest.java`.
- `docs/STEP-38-HOMING-MISSILE-JOURNAL.md` (root repository).

Sửa:

- `src/com/smashprofs/server/game/projectile/ProjectileType.java`: Alex -> ROCKET; Circle vẫn chưa hỗ trợ.
- `src/com/smashprofs/server/game/projectile/ProjectileManager.java`: constants, target selection, spawn,
  native profile/homing update, cooldown/damage và tie cho contact Rocket.
- `src/com/smashprofs/server/game/projectile/ServerProjectile.java`: target ID bất biến, steering riêng,
  homing update package-private; giữ constructor cũ cho các type cũ, không đổi View hoặc expose Body.
- `src/com/smashprofs/server/game/MatchRuntime.java`: chỉ cập nhật chú thích ability hỗ trợ.
- `test/com/smashprofs/server/game/MatchRuntimeProjectileTest.java`,
  `test/com/smashprofs/server/game/MatchRuntimeMultiProjectileTest.java`: Alex không còn là skill chưa port.
- `test/com/smashprofs/server/game/MatchRuntimeAbilityCoverageTest.java`: cập nhật coverage Alex và assert
  Alex tạo Rocket, không tạo Mine. Không xóa các test này hoặc giảm test count.

Coverage Bước 37 được giữ như nhật ký lịch sử; bảng ở tài liệu này và test coverage là trạng thái cập nhật.
Không sửa core/common/desktop/TMX/Gradle/combat controllers/scheduler/network handler, không triển khai Bước 39.

## Kiểm thử và kết quả

Java 17, wrapper Gradle 7.3.3, JUnit 4.13.1 theo layout `server/test`, native Box2D thật, manual ticks.
Không Thread.sleep hoặc Desktop. Concurrency test dùng helper bounded timeout đã có; worker chỉ enqueue input.

Baseline được thực thi lại bằng `:server:test --no-daemon --rerun-tasks`: 963 tests, PASS trước khi sửa.
Rocket tests bao gồm resolver/validation/ground-air-block activation, nearest sống/tie/lock/target chết,
history homing đúng nhiều tick, fixture/gravity/facing, cooldown/spam/dedup/OR edge/no auto-fire,
cooldown riêng owner, native flight/contact với player/TMX/projectile, damage/block/self-hit/không AOE,
lethal float HP/dead target/dead owner, lifetime/close/deferred cleanup, rooms, 2–4 player/slot permutations,
old IBM/Star/Mine/melee/stomp/block/movement/jump regression và đúng một World.step/tick.
`previousServerTestsStillPass` được xác minh bằng chạy toàn suite, không viết assertTrue giả để tuyên bố test khác pass.

Kết quả cuối:

- `.\gradlew.bat :server:test --no-daemon`: **PASS**, 1.010 test, 0 failure/error/skipped,
  gồm 47 test Rocket mới và toàn bộ 963 test trước đó. Các bộ IBM 53, multi-projectile 41,
  coverage/Mine 41 đều vẫn pass. Không có baseline failure cần sửa, không xóa test cũ.
- `.\gradlew.bat :server:build --no-daemon`: **BUILD SUCCESSFUL**; compile/package/check pass.
  Test task trong build up-to-date từ lượt chạy toàn bộ ngay trước đó.
- Dùng JAVA_HOME `E:\2026-2\lap-trinh-mang\baitap` (JDK 17) chỉ trong tiến trình lệnh;
  không sửa Java hệ thống hoặc cấu hình Gradle. Deprecated-feature warning Gradle có sẵn vẫn xuất hiện,
  không phải lỗi Bước 38.
- TMX giữ nguyên SHA-256 `366044B95702FA12EB4310ED227DA808489A5AA1AAE9E648B6D365F17458E708`.
- JAR/cache build được Git track được khôi phục đúng bytes trước tác vụ để không ghi đè artifact/thay đổi
  có sẵn của người dùng. Source/classes/test reports được giữ; không bàn giao JAR phát hành mới.

## Tóm tắt cho báo cáo đồ án

Hoàn thiện Rocket/Alex từ source, giữ nguyên physics/homing thực tế và contact/damage path.
Chốt rõ extension target cho multiplayer với người dùng trước khi code; target và cooldown riêng mỗi player/projectile,
không global target hoặc mutable CombatManager. Tái sử dụng authoritative runtime và deferred native cleanup,
không mở rộng protocol/rendering. Bốn character có special ability server; chỉ Circle/Luca còn MISSING.
Dừng ở Bước 38.
