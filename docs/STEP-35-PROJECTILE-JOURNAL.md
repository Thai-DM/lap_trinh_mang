# Nhật ký Bước 35 — Authoritative IBM projectile

## Phạm vi

Chỉ tích hợp IBM của Viktor, quản lý riêng theo MatchRuntime. Không port rendering/audio,
không tạo socket, snapshot, protocol, GAME_END, ranking hoặc các skill khác.
Source layout giữ nguyên `server/src`, `server/test`, Java 17 và JUnit 4.13.1.

## Khảo sát source gốc

Các đường dẫn dưới đây tính từ Gradle root `final-project/` bên trong repository.

| Character | Class projectile / nhãn contact | Cơ chế được xác minh |
| --- | --- | --- |
| Alex | `HomingMissile` / Rocket | Dynamic circle, homing bằng lerp 0.05, velocity thực tế 0.5; cần chọn target. |
| Leo | `Landmine` / Mine | Dynamic circle, không initial velocity, gravity impulse thủ công; không phá khi chạm Tile. |
| Maurice | `ThrowingStar` / Star | Một skill spawn bốn hướng; velocity từng hướng, angular velocity -12. |
| Luca | `CircleStar` / Circle | Orbit bằng setTransform, category 4, tâm orbit phụ thuộc sprite; global maxCircles=2. |
| Viktor | `IBM` / IBM | Dynamic circle bay ngang, không homing/orbit/gravity thủ công; chọn cho bước này. |

Nguồn: `core/src/com/smashprofs/game/Actors/Projectiles/{Projectile,IBM,HomingMissile,Landmine,ThrowingStar,CircleStar}.java`,
`Helper/{CombatManager,B2dContactListener,ShapeCreator}.java`, `Actors/Players/Player.java`,
năm class character và `Screens/PlayScreen.java`.

Không mặc định `damageOnHit` trong constructor là damage thực tế: CombatManager gốc dùng
contact flags rồi gọi nhánh attackPlayer với damage của target (10). IBM khai báo 10 nên hai giá trị khớp;
Star/Circle khai báo 15 và Mine khai báo 25 không được port trong bước này.

## Quy tắc IBM đã port

- Ability edge hợp lệ, owner thuộc roster, còn sống và characterId chính xác `Viktor`.
- Network boundary PlayerInputHandler hiện có kiểm tra phòng PLAYING rồi chỉ enqueue. Runtime test có thể tick thủ công.
- Hướng authoritative cập nhật từ vx của player trước walking, đúng thứ tự Player.managePlayerInput.
  Khởi đầu hướng phải; vx=0 giữ hướng trước đó. Không nhận hướng, vị trí, target hoặc damage từ client.
- Spawn tại `(owner.x + facing * 0.15, owner.y)`; circle radius 0.03 m; DynamicBody,
  fixedRotation=false, angular velocity=4, categoryBits=3, maskBits=0xffff, groupIndex=0,
  sensor=false; density/friction/restitution giữ defaults 0/0.2/0.
- CombatManager gốc update projectile trước shooting: spawn tick có velocity 0;
  từ tick kế tiếp đặt velocity `(facing * 0.7, 0)` mỗi tick. Không áp gravity riêng, không thay World gravity.
- Cooldown khai báo IBM là 3 giây, chuyển thành 180 tick ở 60 Hz, riêng từng owner.
- IBM bị phá khi contact player/map/projectile. Không có AoE, lifetime, expiry ngoài map hoặc active cap trong source IBM.
- Self-hit được phép: listener/filter gốc không có owner exclusion. Không tự thêm immunity hoặc team mode.
- Target còn sống nhận 10.0 HP qua ServerPlayer.applyDamage hiện có. HP float, clamp 0, chết có hiệu lực ngay.
- Blocking không chặn damage/knockback projectile trong CombatManager gốc.
- Knockback `J=(signX * 1.5, 2 + gravityImpulseTarget)`, signX theo vị trí owner so với target;
  cùng X đẩy trái như source. Profile riêng cho projectile, giống nhau với mọi slot;
  không thay profile melee 1.5/0.2 hoặc stomp 2/2 của các bước trước.
- Với mass=1, vy ban đầu=0, gravity=-0.098: sau ordinary gravity và hit impulse, vy=1.804
  trước ảnh hưởng contact. Solver non-sensor có thể thay đổi vận tốc thực tế khi contact.

## Chuẩn hóa lỗi trạng thái gốc, không sao chép singleton

`Projectile.delayInSeconds` gốc là static dùng chung các loại; CombatManager đặt timer trước khi tạo projectile,
nên lần đầu có thể lấy 0 hoặc delay của loại khác. Server dùng đúng delay IBM khai báo (3 giây),
không coi lỗi inherited static delay là một cooldown gameplay riêng. Gate ibmAvailable toàn cục được thay bằng gate mỗi owner.

PlayerOneGotShoot/PlayerTwoGotShoot gốc không lưu owner/projectileId, chỉ xử lý đúng một thứ tự fixture,
và có thể gộp mất nhiều projectile hit cùng tick. Server ghi event theo projectileId, nhận cả thứ tự A/B.
Mỗi projectile chỉ tạo một hit; hai projectile khác nhau có thể gây hai hit riêng biệt.
Không dùng boolean toàn cục để suy đoán owner là "người chơi còn lại".

IBM bị đổi userData thành Destroyed trong callback gốc; updateProjectiles sau đó chuyển active=false rồi
update tiếp mới destroy body. Server dùng deferred cleanup ngay sau step, tránh giữ body vô hiệu thêm hai update
hoặc double-destroy. Damage vẫn xử lý ở combat tick kế tiếp như contact flags gốc.

## Tích hợp và tính xác định

1. Runtime drain queue và áp dụng movement/jump/stomp/gravity/boundary/melee như trước.
2. Update IBM đã có; spawn từ ability edge theo slot order.
3. Consume projectile hits từ step trước; áp damage/knockback trên simulation owner.
4. World.step đúng một lần, tham số 1/60, 6, 2; ground/stomp sync giữ nguyên.
5. Thu contact events, chọn target, deferred destroy body, tăng serverTick đúng một lần.

Một IBM có nhiều contact trong cùng step chọn một target sống có tâm gần projectile nhất sau step;
bằng khoảng cách chọn slot nhỏ hơn. Chỉ xét các player thực sự có native contact, không dò toàn roster để tạo range/AoE.
Event xử lý theo projectileId tăng dần. Map/projectile-only contact phá body nhưng không tạo damage.
Owner chết sau khi bắn không tự hủy IBM. Target đã chết trước resolve không nhận thêm knockback/damage.

ProjectileId tăng tuần tự và duy nhất trong match, danh tính đầy đủ là `(matchId, projectileId)`.
Body private/package-private trong model; runtime chỉ trả List<View> bất biến. Event chỉ giữ ID, không giữ Contact/Body đã destroy.
Một manager, World, cooldown và event queue riêng mỗi runtime; không static mutable state, không manager mỗi player.

World dùng listener ghép để giữ GroundContactListener. Không bật player-player collision (group -1/mask 7 giữ nguyên).
Không destroy khi World locked. close lặp an toàn: runtime dispose World trước, manager chỉ clear/release references sau dispose.
Không có thread/timer mới trong production; network không giữ khóa InputQueue qua World.step hoặc mutate projectile.

## Kiểm thử và giới hạn

Native Box2D thật, TMX gốc và manual ticks; không Thread.sleep/Desktop. Test bao phủ spawn/facing/physics,
cooldown biên tick 179/180, duplicate/batch edges, 2–4 owner, slot symmetry, blocking/self-hit,
map/projectile/multi-target contacts, damage float/dead, landing/jump/stomp/melee, room isolation,
simulation ownership, deferred destruction và close có pending hit.

Kết quả bằng Gradle wrapper với JDK 17, tại Gradle root `final-project/final-project` từ workspace:

- `.\gradlew.bat :server:test --no-daemon`: PASS, 881 tests, 0 failure/error/skip.
- Trong đó 53 test projectile mới và 828 test trước Bước 35. Không sửa test cũ.
- `.\gradlew.bat :server:build --no-daemon`: PASS. Cảnh báo Gradle deprecated đã có từ baseline.
- SHA256 TMX vẫn `366044B95702FA12EB4310ED227DA808489A5AA1AAE9E648B6D365F17458E708`.

Chưa triển khai Rocket, Mine, Star, Circle hoặc các ability khác; ability của các character đó không tạo IBM.
Không thêm lifetime/safety cap ngoài source. IBM không contact có thể tồn tại và tích lũy tới khi match đóng;
giới hạn bảo vệ tài nguyên cần quyết định riêng trước khi triển khai server công khai.

Nhật ký: audit source -> chọn IBM -> tách metadata/registry/event riêng runtime -> ghép listener ->
port physics/cooldown/damage -> test native và hồi quy. Dừng tại Bước 35.
