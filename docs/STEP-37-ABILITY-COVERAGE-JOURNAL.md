# Nhật ký Bước 37 — Character ability coverage và Landmine của Leo

## Phạm vi và cách đánh giá

Chỉ bổ sung **một** skill: Landmine (type nội bộ server `MINE`) của Leo.
Không sửa core/common/desktop/TMX, không thêm protocol, renderer, VFX/audio, thread, scheduler hay skill khác.
`COMPLETE` dưới đây là đủ activation, cooldown, native physics, hit/damage, lifecycle/cleanup và test
trong phạm vi gameplay authoritative của server. Không có nghĩa là đã port VFX/audio hoặc sao chép các bug
global cooldown/local-player contact flags của baseline. Các chuẩn hóa server đã có được ghi rõ bên dưới.

Source được đọc trực tiếp:

- [Player.java](../final-project/core/src/com/smashprofs/game/Actors/Players/Player.java),
  [CombatManager.java](../final-project/core/src/com/smashprofs/game/Helper/CombatManager.java),
  [B2dContactListener.java](../final-project/core/src/com/smashprofs/game/Helper/B2dContactListener.java),
  [PlayScreen.java](../final-project/core/src/com/smashprofs/game/Screens/PlayScreen.java).
- Năm class `Actors/Players/{Alex,Leo,Luca,Maurice,Viktor}.java`: chỉ chọn PlayerTypes và textures;
  không override damage/HP/knockback hoặc định nghĩa thêm skill riêng.
- `Actors/Projectiles/{Projectile,HomingMissile,Landmine,CircleStar,ThrowingStar,IBM}.java`,
  `Actors/GameObject.java`, `Helper/ShapeCreator.java`. Các đường dẫn tính từ
  `final-project/core/src/com/smashprofs/game/` ở root repository.
- Server: MatchRuntime, MatchState, ServerPlayer, InputQueue, ProjectileType, ProjectileManager,
  ServerProjectile, MeleeCombatController, StompCombatController, PlayerInputHandler và test hiện có.
  Không có ability handler/factory riêng khác phải tạo lại; mapping đã nằm trong ProjectileType.

## Bảng coverage năm nhân vật

| Character | Ability / loại | Original Behavior | Trước Bước 37 | Sau Bước 37 | Missing Parts |
| --- | --- | --- | --- | --- | --- |
| Alex | HomingMissile / Rocket — projectile | Offset facing 0.15 m, radius 0.03 m; target-vector lerp 0.05; velocity thực tế 0.5 m/s; cooldown khai báo 3 s; contact bất kỳ kết thúc. | MISSING | MISSING | Target selection cho 2–4 người, homing update, activation/cooldown/hit/cleanup và tests. |
| Leo | Landmine / Mine — trap có projectile body | Offset facing 0.15 m, radius 0.05 m; không vận tốc ban đầu; impulse gravity -0.098/update; cooldown khai báo 5 s; Tile không phá mìn, contact khác phá. | MISSING | COMPLETE | Không thiếu phần gameplay của skill đã chọn; VFX/audio cố ý không port. |
| Luca | CircleStar / Circle — orbit projectile | Offset spawn 0.20 m, radius 0.03 m, angular velocity -12; setTransform orbit radius 0.25 m quanh tâm sprite; cooldown khai báo 2 s; global counter tối đa 2. | MISSING | MISSING | Orbit không phụ thuộc rendering, ownership/cap cho multiplayer, lifecycle/hit/cleanup và tests. |
| Maurice | ThrowingStar / Star — projectile burst | Một edge sinh 4 hướng phải/trái/trên/dưới, offset 0.10 m, radius 0.03 m; velocity unit vectors, angular -12; cooldown khai báo 0.75 s; mọi contact kết thúc. | COMPLETE | COMPLETE | Không thay skill Bước 36; bộ test native/regression vẫn pass. |
| Viktor | IBM — projectile | Offset facing 0.15 m, radius 0.03 m; velocity ngang ±0.7 m/s, angular 4; cooldown khai báo 3 s; mọi contact kết thúc. | COMPLETE | COMPLETE | Không thay skill Bước 35; bộ test native/regression vẫn pass. |

Không có skill `UNKNOWN` sau khảo sát. Không coi các class tồn tại đơn thuần là coverage hoàn chỉnh.
Rocket có field speed=0.7 nhưng update thực tế đặt speed=0.5; không lấy field unused làm gameplay.
Circle dùng `10 * Math.toRadians(body.getAngle())` làm góc orbit rồi cos/sin; source không có nhánh
destroy Circle trong contact listener và không giảm totalCircles trừ reset manager. Đây là các điểm cần
chốt khi làm Circle sau này, không tự sửa ở Bước 37.

## Hành động chung, damage và tương tác

Năm nhân vật đều kế thừa cùng Player:

| Hành động | Thông số/điều kiện gốc | Coverage server hiện tại |
| --- | --- | --- |
| Melee thường | Edge attack, khoảng cách tâm strict <0.2 m, damage 10; target blocking chặn toàn bộ; không cần grounded/facing. Gốc có Y bất đối xứng 0.2/2 theo local player. | Đã port và test; Y chuẩn hóa 0.2 ở Bước 33, cooldown anti-spam 9 tick là chính sách server đã có, không phải cooldown gốc. Không chỉnh ở bước này. |
| Stomp | Edge khi airborne, impulse xuống -5 + gravity, self-cost 0.1 HP; landing -> range <0.2 -> damage 10, knockback (±2,2+gravity); không bị block. | Đã port/test, không có cooldown riêng; landing event được consume một lần. |
| Jump/double jump | JumpForce 2.2, một extra jump; impulse Y = -0.8 * vy + 2.2. | Đã port/test trong controller hiện có; không phải skill riêng của một character. |
| Blocking | Held input, chặn melee; không chặn projectile/stomp. | Đã port/test; timeout held input là chính sách server đã có. |
| Movement/gravity | Walking impulse 0.055*axis, speed giới hạn 1; gravity thường -0.098; grounded damping 0.999 và giảm vx 30% khi đổi axis trên không. | PARTIAL so với toàn bộ source: damping/air-axis reduction chưa port đầy đủ. Không mở rộng movement ở bước này. |

Mọi projectile trên dùng DynamicBody, fixedRotation=false, density=0 (Box2D mass mặc định 1),
friction=0.2, restitution=0, sensor=false. IBM/Star/Mine/Rocket category=3; Circle category=4.
World gốc có gravity=(0,0). Mine tự áp gravity, IBM/Star không áp gravity thủ công.

Constructor khai báo damageOnHit: Rocket=10, Mine=25, Circle=15, Star=15, IBM=10.
Nhưng tìm toàn bộ core cho thấy field chỉ được gán, không được consume. B2dContactListener đánh dấu
PlayerOneGotShoot/PlayerTwoGotShoot -> CombatManager gọi overload private attackPlayer(Player,Player,1.5,2)
-> trừ target.getAttackDamage(), luôn bằng 10 trong năm class. Overload nhận damage tường minh
không được gọi bởi projectile path. Vì vậy giữ damage thực tế **10.0 HP**, không tự đổi Mine thành 25.

Hit projectile không kiểm tra blocking. Source không có owner immunity, arming delay hoặc AOE damage;
Mine có thể chạm owner. Explosion Mine/Rocket, electricZap IBM và sprite rotation/scale chỉ là VFX/render;
CombatManager gọi damage sound của target. Không suy diễn các hiệu ứng này thành skill gameplay mới.
Không tìm thấy lifetime/expiry riêng cho năm projectile; Circle còn có cap toàn cục kể trên.

## Vì sao chọn Mine

Không có skill riêng không-projectile đơn giản còn thiếu: các action chung đã được triển khai.
Mine có source rõ, không đòi hỏi target policy mới như Rocket hay orbit center phụ thuộc sprite như Circle.
Mine tăng từ 2 lên 3 character có special ability phía server, đồng thời kiểm thử được trap/ground lifecycle.
Tái sử dụng ProjectileManager; không tạo CharacterAbilityResolver/framework hoặc manager thứ hai.

## Resolver và activation

Luồng: PlayerInput.playerId -> roster MatchState -> ServerPlayer.characterId -> ProjectileType.forCharacter
-> ProjectileManager.spawnProjectile. Resolver hiện có được mở rộng Leo -> MINE và trả null cho character
không hỗ trợ/null; public lookup chỉ đọc, không có API để client chọn type. Viktor -> IBM và Maurice -> STAR giữ nguyên.

- Input phải hợp lệ, đúng roster và sequence mới; người chơi còn sống, character Leo.
- Không có yêu cầu grounded, không self-cost, không chặn ability khi owner đang block.
- Room PLAYING/session identity gate tiếp tục do PlayerInputHandler hiện có đảm nhiệm;
  không thêm logic trực tiếp vào network thread.
- Cooldown `(PlayerId, ProjectileType)` riêng trong mỗi runtime: Mine 300 tick = 5 s ở 60 Hz,
  IBM 180 tick, Star 45 tick. Sau lần tạo ở k, lần tiếp theo từ k+cooldown.
- Request bị cooldown reject không reset thời hạn; hit/cleanup không reset cooldown;
  duplicate/stale sequence bị queue chặn, nhiều edge cùng batch OR thành một activation, không có edge mới
  thì không tự tái kích hoạt khi cooldown hết. clientTick không quyết định thời gian.

Gốc dùng static Projectile.delayInSeconds cho mọi loại và Timer.schedule trước constructor cập nhật field,
đồng thời availability chung giữa người chơi. Bước 35/36 đã thay bằng cooldown khai báo đúng từng type,
per-owner server tick; Mine áp cùng giải pháp, không tái tạo bug static/timer này.

## Mine physics và hit lifecycle

- Spawn `(ownerX + facingSign * 0.15, ownerY)`, circle radius=0.05 m, angular velocity ban đầu=0,
  velocity ban đầu=(0,0), dynamic/fixedRotation=false. Native fixture category=3, mask=0xffff, group=0.
- CombatManager gốc update projectile trước shooting: spawn tick chưa áp gravity;
  từ tick sau mỗi update applyLinearImpulse `(0,-0.098)` ở world center, không setLinearVelocity
  ghi đè phản ứng va chạm. Khi airborne mass=1: vy -0.098, -0.196, ... nếu chưa có contact.
- Contact với `Tile`: giữ body/contact để solver cho mìn rơi và nằm trên map.
- Contact không phải Tile: ghi collided ID; nếu body đối diện là PlayerId thuộc MatchState thì ghi target.
  Contact với projectile khác phá các projectile chạm nhau nhưng không tạo AOE.
- Giữ pipeline: update projectile cũ -> spawn -> resolve hit tick trước -> một World.step(1/60,6,2)
  -> collect hits -> deferred destroy/clear handle -> tăng serverTick.
- Callback không destroy Body hoặc sửa HP; native body bị destroy sau step/unlocked,
  hit metadata được consume ở combat tick sau. Mỗi projectile chọn tối đa một target contact còn sống
  gần nhất, tie theo slot; hit thứ tự ID tăng dần như policy đã có, không lấy HashMap iteration order.
- Damage dùng API float ServerPlayer.applyDamage: HP clamp >=0/dead cập nhật ngay.
  Knockback `J=(signX*1.5, 2+gravityTarget)` với sign theo ownerX/targetX, cùng X đẩy trái.
  Độ mạnh không phụ thuộc slot; từ đứng yên trên không có ordinary gravity cùng tick, native vy=1.804.
- Không hủy mìn chỉ vì owner chết; dead target không nhận thêm hit. Close runtime dispose World
  và bỏ native handles an toàn, không destroy hai lần.

Không thêm lifetime, expiry ngoài arena, cap, AOE, team/owner immunity hoặc arming timer.
Giữ nguồn không có expiry đồng nghĩa số mìn/native body còn tồn tại có thể tăng ở trận dài;
giới hạn tài nguyên là quyết định tương lai, chưa tự áp chính sách gameplay mới.

## Isolation và thread safety

Mỗi MatchRuntime sở hữu World, ProjectileManager, registry, cooldown và pending events riêng.
Contact chỉ nhận body/model được chính manager đăng ký; target phải thuộc MatchState của trận.
Test cố ý dùng cùng PlayerId/toạ độ ở hai room: hit/close/cooldown phòng A không tác động phòng B.
Network thread chỉ submitInput -> queue. Activation/update/hit/cleanup giữ nguyên khóa runtime và simulation owner.
Không thêm thread/timer/loop, không mutate native World từ dispatcher. Scheduler 60 Hz không đổi.

## File của Bước 37

Tạo mới:

- `final-project/server/test/com/smashprofs/server/game/MatchRuntimeAbilityCoverageTest.java`.
- `docs/STEP-37-ABILITY-COVERAGE-JOURNAL.md`.

Chỉnh sửa (đường dẫn từ module server):

- `src/com/smashprofs/server/game/projectile/ProjectileType.java`: MINE và resolver Leo/null guard.
- `src/com/smashprofs/server/game/projectile/ProjectileManager.java`: profile, spawn, gravity và Tile rule của Mine.
- `src/com/smashprofs/server/game/MatchRuntime.java`: chỉ sửa chú thích các skill hỗ trợ; flow tick không đổi.
- `test/com/smashprofs/server/game/MatchRuntimeProjectileTest.java`: Leo không còn trong danh sách chưa port.
- `test/com/smashprofs/server/game/MatchRuntimeMultiProjectileTest.java`: cùng cập nhật Leo; test distinct
  kiểm tra IBM/Star và hai type thật trong scene thay vì khóa tổng enum ở 2.

Không tạo lại ServerProjectile, không sửa damage/movement/combat controller, handlers, Gradle hoặc DTO.
Các thay đổi có sẵn trong worktree không thuộc Bước 37 được giữ nguyên.

## Kiểm thử và kết quả

JUnit 4.13.1 theo module hiện tại, Java 17, Box2D/TMX native thật, manual ticks; không Thread.sleep/Desktop.
41 test mới kiểm tra coverage/resolver/unknown character, activation airborne/grounded/dead, cooldown 300,
spam/duplicate/OR edge/no auto-cast, cooldown tách owner và giữ sau hit, fixture/native impulse/facing,
Tile persistence ở TMX gốc/collider trước-sau, hit/damage/block/self-hit/no-AOE, lethal float HP,
dead target/owner, deferred cleanup/close, IBM/Star/melee/stomp regression, movement không đổi,
2–4 player/18 phép đổi attacker-slot/hướng, hai room và đúng một World.step/tick.
Test concurrency dùng helper bounded timeout của InputQueueTest; worker chỉ enqueue, không step World.

Test mới ban đầu có 9 failure do giả định contact ngay sau setTransform. Sửa test chờ tối đa 8 manual ticks
cho broadphase/contact native; không sửa production collision để ép test pass. Đây là lỗi test mới,
không phải lỗi baseline. `existingServerTestsStillPass` được xác nhận bằng chạy toàn bộ suite, không tạo
unit test giả chỉ assertTrue để tuyên bố test khác pass.

Kết quả cuối:

- `.\gradlew.bat :server:test --no-daemon`: **PASS**, 963 test, 0 failure/error/skipped,
  gồm 41 test mới và toàn bộ 922 test trước đó. Bộ IBM Bước 35 có 53 test và bộ multi-projectile
  Bước 36 có 41 test đều pass; không loại bỏ các test này.
- `.\gradlew.bat :server:build --no-daemon`: **BUILD SUCCESSFUL**; compile/package/check pass,
  test task trong build up-to-date từ lượt chạy toàn bộ ngay trước đó.
- Dùng JAVA_HOME `E:\2026-2\lap-trinh-mang\baitap` (JDK 17) chỉ trong tiến trình lệnh,
  không sửa cấu hình Java của repository hoặc hệ thống. Wrapper Gradle 7.3.3 vẫn báo deprecated features;
  warning có sẵn, không phải compile/test failure của Bước 37.
- TMX giữ nguyên SHA-256 `366044B95702FA12EB4310ED227DA808489A5AA1AAE9E648B6D365F17458E708`.
- Các JAR/cache build đã được Git track được khôi phục đúng bytes trước tác vụ để giữ nguyên thay đổi
  có sẵn của người dùng. Test reports/classes được giữ; JAR trong worktree không phải bản mới để phát hành.

## Tóm tắt cho báo cáo đồ án

Khảo sát đầy đủ năm character và đường hit thực tế, phát hiện damage metadata không được consume.
Mở rộng resolver authoritative hiện có bằng một skill trap, giữ cooldown per-player/type bằng serverTick.
Mine dùng native gravity/contact của source, đứng trên TMX và kết thúc bằng deferred cleanup,
không mở rộng combat hay gameplay client. Hai skill còn thiếu là Rocket/Alex và Circle/Luca;
movement toàn bộ vẫn PARTIAL. Dừng ở Bước 37, không triển khai Bước 38.
