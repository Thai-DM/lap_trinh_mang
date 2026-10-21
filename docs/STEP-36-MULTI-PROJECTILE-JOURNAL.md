# Nhật ký Bước 36 — IBM và ThrowingStar

## Khảo sát và quyết định

Đọc lại source gốc: `Actors/Players/Player.java`, năm character class,
`Actors/Projectiles/{Projectile,IBM,ThrowingStar,HomingMissile,Landmine,CircleStar}.java`,
`Helper/CombatManager.java`, `Helper/B2dContactListener.java`.
Các đường dẫn source này tính từ `final-project/core/src/com/smashprofs/game/` trong repository.
Đọc runtime/state/input/damage/knockback và 53 test projectile Bước 35 trước khi thay đổi.

| Character | Class / nhãn contact | Cooldown khai báo | Cơ chế gốc và kết thúc |
| --- | --- | --- | --- |
| Viktor | IBM / IBM | 3 s | Dynamic circle, bay ngang 0.7 m/s theo facing, angular velocity 4; phá khi contact bất kỳ. Đã port ở Bước 35. |
| Maurice | ThrowingStar / Star | 0.75 s | Bốn Dynamic circle, velocity bốn hướng đơn vị, angular velocity -12; phá khi contact bất kỳ. Chọn cho Bước 36. |
| Alex | HomingMissile / Rocket | 3 s | Dynamic circle, target-vector lerp 0.05, velocity thực tế 0.5 m/s; contact phá body và gọi VFX explosion. Chưa port. |
| Leo | Landmine / Mine | 5 s | Dynamic circle, gravity impulse thủ công -0.098; trap không bị phá do Tile, contact khác phá và gọi VFX explosion. Chưa port. |
| Luca | CircleStar / Circle | 2 s | Dynamic circle category 4, orbit bằng setTransform quanh tâm sprite; global maxCircles=2, không có nhánh destroy Circle trong listener. Chưa port. |

IBM/Star không có lifetime hoặc expiry ngoài arena trong source. Không suy diễn explosion VFX thành AOE damage.
Không chọn homing vì cần chính sách target; không chọn Mine vì ground/trap khác; không chọn Circle vì orbit phụ thuộc rendering.
Chỉ thêm một TYPE Star; bốn body trong burst không phải bốn loại skill.

## Damage thực tế khác với metadata khai báo

Constructor ghi `damageOnHit`: IBM=10, Star=15, Rocket=10, Mine=25, Circle=15.
Nhưng field này chỉ được gán trong Projectile, không được consume bởi đường combat đang chạy.
Listener nhận Star -> PlayerOneGotShoot/PlayerTwoGotShoot -> CombatManager gọi overload
`attackPlayer(Player, Player, 1.5, 2)` -> target mất `target.getAttackDamage()` (10, giống nhau ở năm character).
Overload nhận damage tường minh từ Vector2 không được gọi bởi projectile source.

Vì yêu cầu giữ gameplay thực tế, Star áp 10.0 HP, không tự sửa thành 15 hoặc tạo balance mới.
Profile damage vẫn chọn theo type để không lấy nhầm hằng số IBM; hai giá trị hiện bằng nhau đã được xác minh.
Giữ quy tắc Bước 35: một event mỗi projectile, không sao chép boolean toàn cục làm mất owner hoặc gộp mất hit.

## Thiết kế tối thiểu và file thay đổi

Tạo mới:

- `final-project/server/src/com/smashprofs/server/game/projectile/ProjectileType.java`: chỉ IBM/STAR, mapping Viktor/Maurice phía server.
- `final-project/server/test/com/smashprofs/server/game/MatchRuntimeMultiProjectileTest.java`: 41 test native/manual tick.
- `docs/STEP-36-MULTI-PROJECTILE-JOURNAL.md`: nhật ký này.

Sửa:

- `ServerProjectile.java`: type bất biến, velocity X/Y riêng từng instance; giữ ID/owner/match/spawnTick,
  active dựa trên handle, getter String và View hiện có. Body vẫn private/package-private, không public mutable getter.
- `ProjectileManager.java`: một registry chung, cooldown `(ownerPlayerId, ProjectileType)`, spawn theo type,
  common native creation có tham số radius/angular/velocity và pending hit lưu type.
- `MatchRuntime.java`: chỉ cập nhật chú thích; không thay game loop hoặc thứ tự tick.
- `MatchRuntimeProjectileTest.java`: bỏ Maurice khỏi danh sách ability chưa hỗ trợ trong một test;
  test mới xác nhận Maurice chỉ tạo Star và Viktor chỉ tạo IBM. Không xóa test IBM.

Không thêm manager mỗi player, entity/component framework, thread/timer, DTO/protocol hoặc skill thứ ba.

## Star end-to-end

- abilityPressed edge qua InputQueue, owner đúng roster, còn sống, characterId Maurice; PLAYING gate dùng handler hiện có.
- Một burst theo thứ tự source: phải `(x+0.1,y)`, trái `(x-0.1,y)`, trên `(x,y+0.1)`, dưới `(x,y-0.1)`.
- Không nhân facing: bốn hướng luôn có mặt. IBM vẫn lấy facing authoritative và offset 0.15 m như trước.
- Spawn tick velocity=0, từ tick sau đặt velocity `(1,0)`, `(-1,0)`, `(0,1)`, `(0,-1)`.
- Circle radius 0.03 m, DynamicBody, fixedRotation=false, angular velocity -12,
  category=3, mask=0xffff, group=0, sensor=false, density=0, friction=0.2, restitution=0.
- Không áp gravity riêng, không đổi World gravity, không dùng deltaTime của client.
- Cooldown Star=45 tick (0.75 s), IBM=180 tick (3 s). Giữ giải pháp Bước 35 lấy delay khai báo riêng,
  không lấy inherited static delay bị chia sẻ giữa các loại trong game gốc. Request bị chặn không kéo dài cooldown.
- Burst tạo đầy đủ trước publish/cooldown. Nếu native creation thất bại, dọn các body đã tạo, không đăng ký burst một phần.
- Damage qua API float hiện có, clamp HP>=0; blocking không chặn và self-hit được phép như source.
- Knockback `J=(signX*1.5, 2+gravityTarget)` theo vị trí owner/target, cùng X đẩy trái.
  Không có hệ số theo slot; test đổi vai/slot và hướng trái/phải trong trận 2–4 người.

## Quản lý, collision và isolation

ID tăng tuần tự trong match, type/owner không lấy từ client. World và registry/cooldown/pending hits riêng mỗi runtime.
Giữ common creation/update chỉ vì source IBM/Star đã xác minh cùng native defaults và setLinearVelocity;
không áp công thức này cho các type chưa port có gravity/homing/orbit khác.

Giữ listener ghép và pipeline Bước 35: update projectile cũ -> spawn -> resolve hit tick trước -> một World.step ->
collect contact -> deferred destroy -> tăng tick một lần. Callback chỉ ghi ID, nhận cả thứ tự fixture A/B.
Một projectile chọn một target contact còn sống gần nhất; tie theo slot. Event theo ID tăng dần, không phụ thuộc HashMap.
Không có AOE; Star chạm IBM thì hai body contact bị phá, không xóa cả burst. Ground/Tile không tự gây damage player.

Cleanup chỉ destroy ngoài World locked, release handle và remove đúng ID; event chỉ giữ ID/type, không giữ Body/Contact.
close lặp an toàn sau World.dispose, clear cả hai type/cooldown/event mà không destroy native hai lần.
Cleanup room A không đụng room B, kể cả các phòng sử dụng cùng PlayerId/projectileId nội bộ.

## Kết quả

Gradle wrapper, Java 17, JUnit 4.13.1, source layout `server/src` và `server/test` giữ nguyên.

- `.\gradlew.bat :server:test --no-daemon`: PASS, 922 test, 0 failure/error/skip.
- 41 test mới; 881 test trước đó, gồm 53 test IBM, vẫn PASS sau điều chỉnh mapping Maurice hợp lệ.
- `.\gradlew.bat :server:build --no-daemon`: PASS. Cảnh báo deprecated Gradle là cảnh báo baseline.
- Native World/TMX thật, manual tick, không Thread.sleep hoặc Desktop Launcher. Có test cooldown 44/45 và 179/180,
  mixed collision, bốn hướng, blocking/self-hit, HP float, slot symmetry, deferred cleanup, simulation owner, room isolation.
- Map SHA256 giữ nguyên: `366044B95702FA12EB4310ED227DA808489A5AA1AAE9E648B6D365F17458E708`.

## Giới hạn và nhật ký ngắn

Chưa có Rocket/Mine/Circle, abilities khác, visual/audio/client rendering, snapshot, broadcast, socket, ranking hoặc GAME_END.
Không thêm lifetime/safety cap ngoài source; projectile không contact có thể tích lũy tới khi match đóng.
Giới hạn bảo vệ tài nguyên cần quyết định riêng trước triển khai công khai, không ngầm thay gameplay ở bước này.

Audit source -> chọn Star -> thêm type và cooldown key -> tách spawn riêng, tái dùng lifecycle ->
test native và hồi quy -> build. Chỉ hoàn thành Bước 36, không làm Bước 37.
