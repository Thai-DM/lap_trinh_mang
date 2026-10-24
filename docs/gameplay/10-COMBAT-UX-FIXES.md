# Sửa dung nham, HUD và vũ khí/túi đồ

Người dùng đã chọn rõ: **chạm dung nham bật lên và trừ10HP; chỉ mất mạng khi HP về0**. Đây là thay đổi luật so với bản stock đầu, không phải giữ luật chạm dung nham mất ngay một mạng.

## Hành vi hiện tại

- TMX có object layer `lava_hazard` khớp bề mặt lava ở16px dưới đáy. Collision layer `obj` giữ nguyên18 solid shapes; hazard không tạo thêm Box2D body hoặc timer.
- Server phát hiện chân chạm lava trước/sau physics, set tốc độ lên2,6m/s, giảm gravity theo bản gốc và giữ lực bật khỏi walking clamp. Giới hạn10HP/lần trong60ticks để không trừ hai lần pre/poststep hoặc từng tick khi còn sát dung nham. Bảo vệ hồi sinh ngăn trừ HP nhưng vẫn bật lên. Block/shield vũ khí không vô hiệu hóa hazard và không tiêu shield.
- Nếu10HP cuối bị dung nham lấy hết: mất đúng một stock, RESPAWNING90ticks, hồi sinh100HP/bảo vệ120ticks, hoặc spectator nếu hết mạng. Kill attribution vẫn cần bằng chứng combat hợp lệ. Không phát reliable death event cho mỗi lần chỉ mất10HP; journal vẫn có giới hạn.
- Server script ưu tiên `assets` trước JAR cũ để metadata hazard mới thực sự được load. Cần restart server/client cũ để nhận bản sửa.

## HUD và hình vũ khí

HUD thường trực có player cards cao52px trên viewport1280×720, không kéo rộng theo nửa màn hình khi chỉ2người. Hiển thị tên/P1–P4, mạng, HP và RTT. Clock ở giữa. Không còn các đoạn hướng dẫn, trạng thái vũ khí và cooldown lặp lại cho mọi player trong bảng lớn.

Dải trang bị cao48px ở đáy chỉ hiển thị món của local player, số đạn, shield và F. Camera chừa một khoảng hẹp dưới sàn để dải này không đè nhân vật trên platform thấp. **Tab** mở/đóng túi trang bị; **H** mở/đóng hướng dẫn. Feed giữ2dòng mới nhất thay vì chiếm nhiều hàng. Spectator/error chỉ hiện khi cần.

`CombatIcons` vẽ pixel art bằng code, dùng cùng atlas cho đồ rơi, vũ khí cầm trên tay và túi đồ: shotgun, laser, bazooka, grenade, bình máu, shield; có hình pellet, missile và shuriken riêng. Laser là vệt sáng, explosion là vòng/sparks thay vì một hình tròn đặc che cả vùng. Không tạo texture mỗi frame/projectile. Scale súng/đồ rơi đã kiểm tra trong ảnh2/4người.

**Túi đồ phản ánh cơ chế hiện có:** một weapon đang cầm và một shield. Súng có6lần bắn, grenade1lần ném; nhặt weapon mới thay weapon cũ. Bình máu dùng ngay và không được giả làm một món còn cất trong túi. Không thêm kho nhiều súng hoặc cho client tự khai ammo/pickup.

Gameplay player snapshot có optional `ammo` do server xuất. Biên0–6; `weapon=NONE` không thể có ammo>0. Constructor/JSON cũ không có ammo vẫn đọc được dưới dạng unknown=-1, HUD không bịa số đạn. Shotgun mỗi lần bắn tốn1ammo và sinh3viên; hết đạn/death/disconnect thì weapon/ammo được clear. Legacy room schema không đổi.

## Kiểm chứng

- **198 tests PASS**, [log](runs/20261016-162338-432-combat-visual-final/tests.log), [suite list](runs/20261016-162338-432-combat-visual-final/verification.json). Các suite liên quan lava/stock/items, TMX, wall, ending/projectile, cache/backpressure/protocol, immutable wire, TCP2–4client, cluster và room UI flow.
- LavaReboundTest kiểm tra chạm bề mặt trước y<0, vận tốc lên/HP90/giữ3mạng, không trừ hai lần, biên60tick, lava lethal trừ một stock,90tickrespawn/bảo vệ, block/shield, đứng trên platform không bị nhận là lava.
- Kiểm tra ammo6→5→0/NONE, JSON optional compatibility và reject ammo ngoài giới hạn. TCP stock-death fixture dùng HP về0 thay vì coi mỗi lần xuống dưới đáy là mất ngay stock.
- Gradle `:desktop:dist --offline --no-daemon` BUILD SUCCESSFUL; [build log](runs/20261016-162338-432-combat-visual-final/client-build.log). Đã chạy native GL smoke bằng client classes/resources của JAR mới với2/4client và cả960×540.
- `GameplayVisualPreview` dùng TCP thật; manual scheduler trên GL thread cũng là simulation owner. Chỉ setup đồ/vị trí qua test seam trên đúng owner, không thêm endpoint cheat. Tự nhặt shotgun/shield, bắn và xác nhận ammo5 được gửi đến client; chạm lava xác nhận HP90. Xem [HUD4người](combat-previews/hud-four.png), [túi đồ](combat-previews/inventory.png), [hình vũ khí](combat-previews/weapon-art.png), [cửa sổ nhỏ](combat-previews/hud-small.png).
- Ảnh đã xem, không phải mô tả chưa render. Smoke dùng cửa sổ ẩn/audio tắt; chưa thay cho một phiên chơi tay/âm thanh đầy đủ. Không chạy lại load benchmark sau memory stop trước đó.

## Chạy bản mới

Bản client đã được build trên máy hiện tại. Tắt game cũ và Ctrl+C ở server cũ. Nếu PowerShell đang ở `...\smashprofs-integration\final-project`:

```powershell
# Terminal server:
..\scripts\gameplay\Run-Server.ps1

# Mỗi terminal client:
..\scripts\gameplay\Run-Client.ps1 -SkipBuild
```

Từ repo root thì dùng `.\scripts\...` thay cho `..\scripts\...`. `-SkipBuild` chỉ tái sử dụng bản JAR đã build; bỏ cờ này sau khi sửa source/pull code mới để build lại. Mỗi phòng mới vẫn tạo mã tự động. Bật item HIGH nếu muốn đồ rơi mỗi8giây. Manifest của lần sửa này: [combat-verification.json](combat-verification.json); manifest638/96test trước là historical evidence.
