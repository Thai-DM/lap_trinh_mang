# 03 — Server implementation

`MatchSetup.rules` giữ luật immutable từ GameRoom; constructor ba tham số cũ vẫn tạo LEGACY. `GameplayMatch` chỉ được tạo cho rule gameplay-v1 và nằm trong MatchRuntime hiện có. `beforeTick` xử lý disconnect/respawn; combat dùng damage gate; `afterTick` chốt stock loss một lần. Các cooldown/timer đều trong miền server ticks. Box2D mutation và item update chạy trong tick, không từ network callback.

ServerPlayer vẫn là nguồn HP/dead/block/facing. Progress giữ lives, respawn/invulnerability/ultimate timers, inventory và stats. Dead trong periodic base snapshot biểu thị body không active; extension phân biệt RESPAWNING và ELIMINATED. MatchEndEvaluator dùng lives cho stock match; result giữ cả gameplay snapshot bất biến. RankingGateway/MatchResultPublisher vẫn nhận result qua bounded asynchronous handoff, không ghi DB trong tick.

ItemSystem dùng platform TMX có margin; kiểm tra không overlap terrain trước spawn, tối đa 16 lần tìm vị trí mỗi drop interval. Pickup tự động khi player ACTIVE cách item <=0,25 m, quyết định bởi vị trí server; tranh chấp xử lý theo slot ổn định. Không có request client tự khai pickup/damage. Item despawn sau 1800 tick. Inventory một weapon, súng 6 phát, grenade 1; V/Space dùng weapon nếu đang trang bị, ngược lại melee. Fire cooldown 24 tick.

| Weapon/item | Cơ chế đã code |
|---|---|
| Shotgun | 3 projectile, góc -0,20/0/+0,20 rad, tốc độ 4 m/s, 12 HP/viên, swept segment hit. |
| Laser | Tốc độ 40 m/s, xuyên terrain/mục tiêu toàn chiều ngang; hit-set ngăn damage lặp cùng target, 24 HP/hit. |
| Bazooka | Tốc độ 2 m/s; contact target/terrain nổ bán kính 0,85 m, 32 HP và impulse diện rộng. |
| Grenade | Ném với vx=1,8, vy=2,2 m/s, gravity 3 m/s², fixed-step 1/60 s; bounce terrain; nổ sau 120 tick hoặc contact đối thủ. |
| Health | +30 HP, clamp 100; không hồi sinh người đã dead. |
| Shield | Chặn một valid hit; không tiêu cho hit đã bị block/invulnerability. |

Weapon projectiles dùng ballistic integration và swept collision với geometry cùng map; không tạo thêm Box2D body, thread hoặc timer. Box2D vẫn quyết định vị trí player và nhận knockback impulse. Tối đa 64 weapon/orbit/explosion visuals, TTL 180 tick (grenade nổ tại 120); effect explosion khoảng 0,2 s. Ability Projectiles tái sử dụng ProjectileManager/native contact, cap 64 và TTL 600 tick trong room gameplay. Tổng tối đa 128 projectile giữa hai registry, không phải 128 native bodies. Legacy ability rules không bị thay.

F gate 600 tick chạy ngoài cooldown gốc, chỉ consume khi spawn thành công. Bốn ability IBM/STAR/MINE/ROCKET giữ code cũ; Luca có 2 orbiting defense shuriken trong 180 tick, mỗi target bị hit tối đa một lần/shuriken. Dead/respawning/spectator không kích hoạt. Item/ability projectiles của owner inactive bị cleanup/không được gây damage mới.

Combat journal tối đa 128 events/trận, sequence tăng từ 1, chỉ chứa giá trị immutable. Với tối đa 4×5 stock, số lifecycle events hữu hạn dưới bound đó. Reliable queue 128 pending/session; đầy thì đóng riêng session, không drop âm thầm. Shared reliable frame encode một lần trên writer đầu, không encode/socket write trên simulation. Khi close/remove: bỏ queue input, latest snapshot, items/shots/progress/journal và native references; việc dispose World là idempotent.

Trong gameplay-v1, ngay khi finish đã dispose World và native bodies trên simulation owner. Final snapshot/result/journal immutable vẫn giữ để writer gửi terminal frames; chỉ khi close/remove room mới clear final delivery state. Room đã finished và không còn session mở được gỡ khỏi registry; không phải đợi người dùng khởi động lại server để giải phóng native World. Frame guards vẫn hợp lệ trong thời gian gửi kết quả.

Cluster claim mang gameplay flag và creator identity từ control plane; worker cấu hình room trước khi join, giữ host đúng kể cả creator đến sau peer. Heartbeat report capacity theo room settings; worker vẫn kiểm tra room limit authoritative nếu control report trễ. Không thêm live migration, distributed persistence hoặc thay allocator.
