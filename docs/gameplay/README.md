# Super Smash Profs — Platform Fighter

**Sửa bậc và cài đặt (24/10):** [Một lần S hai lần xuyên cả bậc, S+J đánh xuống, chỉnh âm lượng và kiểm tra WebGL](23-PLATFORM-AUDIO-WEBGL.md). Cài đặt C có âm lượng tổng/nhạc/hiệu ứng được lưu; giữ luật cầm súng chỉ bắn.

**Bảng xếp hạng và hồ sơ:** [Bố cục gọn, tên tiếng Việt và giữ điểm khi mở lại game](22-PERSISTENT-GUEST-RANKING.md). Server cấp hồ sơ khách được lưu trên trình duyệt, nhiều phiên cộng vào cùng hồ sơ; mã kết nối gameplay vẫn riêng biệt.

**AI Boss:** Boss ưu tiên đuổi người đang sống có HP thấp nhất, giữ mục tiêu khi bằng HP và bám nhanh hơn ở pha 2. [Chi tiết truy đuổi và chiêu](15-BOSS-MODE.md).

**Sảnh online:** [Ba lựa chọn chơi và hộp đổi máy chủ riêng](21-ONLINE-LOBBY-LAYOUT.md). Địa chỉ WSS dài không làm tràn khung; ghép nhanh, tạo phòng và nhập mã tách thành ba thẻ.

**Ghép trận online:** [PvP 1 đấu 1 / Boss hai người](20-ONLINE-MATCHMAKING.md). Online → Ghép trận nhanh → chọn nhân vật/chế độ → Tìm trận. Đủ hai người cùng tìm thì tự vào trận, có Hủy tìm; vẫn giữ phòng riêng bằng mã.

**Client đầy đủ trên web:** [TeaVM: cách chạy, kiến trúc và hosting](18-TEAVM-WEB-CLIENT.md). Chạy `scripts/web/Run-Web.ps1` để mở menu thật, Local, Online PvP/Boss và bảng ELO từ code `dev`.

**Tự cập nhật Azure:** [Thiết lập GitHub Actions deploy](19-AZURE-AUTO-DEPLOY.md). Push nhánh `feature/teavm-web-test` để test, build và triển khai, kiểm tra HTTPS/WSS và phục hồi bản trước nếu kiểm tra trên VM thất bại. Cần thiết lập hai Actions secrets một lần.

**Thử TeaVM trên web:** [Kết quả và cách chạy hai trình duyệt](17-TEAVM-PROBE.md). `scripts/web/Run-TeaVm.ps1 -Multiplayer -Boss` mở bản thử dùng map/sprite/Boss gốc và server Java thật qua WebSocket.

**Chơi khác mạng:** [Deploy server lên Azure VM Ubuntu](16-AZURE-DEPLOY.md). Đóng gói bằng `scripts/deploy/Package-Server.ps1`; client dùng `Run-Client.ps1 -ServerHost IP_VM` và nhớ máy chủ sau lần kết nối thành công.

**Chế độ Boss (22/10):** [Hướng dẫn tạo phòng, luật chơi, chiêu boss và kiểm thử](15-BOSS-MODE.md). Chọn **Hợp tác đánh Boss** phía trên nút Tạo trận đấu; đội 2–4 người cùng hạ Demon Slime, có súng và tự nạp đạn.

**Lướt ngắn (19/10):** Dash/Backdash còn 0,1 giây, khoảng nửa quãng cũ. Một lần bấm chỉ một nhịp; giữ nút né/lướt rồi đổi hướng không tự lướt thêm. Né đứng yên trên đất cũng dừng trượt sau 6 tick, vẫn giữ thời gian bảo vệ. [Chi tiết](13-UNARMED-MVP.md) · [Kiểm chứng](runs/20261019-short-dash/verification.json).

**Bản mới nhất:** [Combat và trang bị](13-UNARMED-MVP.md). Dùng **HP thống nhất**, giữ cả đánh tay không và hệ thống vũ khí/vật phẩm sẵn có. J/K/L (hoặc preset mũi tên + C/X/Z), hai Air Jumps, Recovery kiệt sức, Dodge/Dash riêng, Gravity Cancel, Chase Dodge, input buffer và F2 Show Stun. E nhặt, G thả, Q ném, Tab túi đồ; phòng mới mặc định rơi đồ mỗi 15 giây, chủ phòng có thể đổi. F6 đổi/lưu preset. Camera tiếp tục bám; dung nham bật lên, -10 HP; hết HP mất mạng/hồi sinh.

[Kiểm chứng bản khôi phục trang bị](runs/20261018-equipment-restored/verification.json) · [Túi đồ ở 960×540](runs/20261018-equipment-restored/four-players-small/weapon-inventory.png). Phải mở lại cả server và client sau khi cập nhật; phòng mới mặc định rơi đồ, phòng đã chọn Tắt cần chủ phòng đổi lại trước trận.

**Cầm súng chỉ bắn:** J/K hoặc C/X tạo projectile, không kèm đòn tay/animation đấm. Cooldown và viên cuối cũng không đánh tay thay thế. Bỏ súng hoặc hết đạn rồi nhập lần tiếp theo mới đánh tay không. [Kiểm chứng](runs/20261018-projectile-only/verification.json) · [Bắn trong trận 4 người](runs/20261018-projectile-only/four-players-small/weapon-fired.png).

[Bộ fixture combo tay không](runs/20261018-unarmed-build/combos.json) · [Manifest lịch sử Unarmed](runs/20261018-unarmed-build/verification.json). Chạy `.\scripts\gameplay\Run-Unarmed-Training.ps1`. Danh mục vẫn UNVERIFIED, không mặc định là true combo. Tài liệu 12 và các evidence dưới đây ghi lịch sử trước Unarmed.

Kiểm chứng mới: [JUnit](runs/20261017-085338-383-final-gameplay-regression/tests.log) · [30 kiểm tra cuối](runs/20261017-085538-830-fighter-final-lifecycle/tests.log) · [Build JAR](runs/20261017-fighter-build/build-packaged-final.log) · [HUD/bệ hồi sinh ở 960×540](runs/20261017-fighter-native/final-small-screen/respawn-platform.png) · [Podium](runs/20261017-fighter-native/final-two-players/result.png) · [Rematch 4 người](runs/20261017-fighter-native/final-four-players/rematch.png).

**Lịch sử FIX1:** [6 lỗi gameplay ở chế độ HP](11-FIX1.md). Camera mượt khi nhảy cao; bám tường; E nhặt / G thả, giữ nguyên đạn; bậc đá không nhận nhầm dung nham; bật dung nham nhẹ hơn; hồi sinh ngẫu nhiên. Bản đó có 686 tests PASS, gồm TCP với 2/3/4 người.

Kiểm chứng FIX1: [JUnit](runs/20261017-075241-496-final-gameplay-regression/tests.log) · [Build JAR](runs/20261017-fix1-build/build.log) · [HUD 4 người ở 960×540](runs/20261017-fix1-native/small-screen/compact-hud.png) · [Túi đồ](runs/20261017-fix1-native/two-players/inventory.png) · [Hồi sinh trên bệ cao](runs/20261017-fix1-native/four-players/respawn-protection.png).

Source server/client đã triển khai; bản gameplay ban đầu có **638 tests PASS**. Bản cập nhật [giao diện tiếng Việt](09-UI-REDESIGN.md) có **96 targeted tests PASS**, đã chạy native GL smoke với 2/4 người và xem ảnh màn hình. Âm thanh và chơi tay đầy đủ chưa kiểm chứng; so sánh hiệu năng vẫn bị chặn do RAM.

1. [Audit repository và phạm vi](01-FEATURE-AUDIT.md)
2. [Luật gameplay](02-GAMEPLAY-RULES.md)
3. [Server implementation](03-SERVER-IMPLEMENTATION.md)
4. [Protocol và tương thích legacy](04-PROTOCOL-CHANGES.md)
5. [Client UI và hướng dẫn chạy](05-CLIENT-IMPLEMENTATION.md)
6. [Integration tests và evidence](06-INTEGRATION-TESTS.md)
7. [Performance impact và memory blocker](07-PERFORMANCE-IMPACT.md)
8. [Final status](08-FINAL-STATUS.md)

[Source/test/script/config list](SOURCE-FILES.md) · [Manifest SHA-256](verification.json) · [JUnit cuối](runs/20261016-080639-267-final-gameplay-regression/tests.log)

Từ repo root, JDK17 hiện có:

```powershell
.\scripts\gameplay\Verify-Gameplay.ps1
.\scripts\gameplay\Run-Server.ps1
# Terminal khác (chạy lại lệnh này ở terminal thứ ba để mở người chơi thứ hai):
.\scripts\gameplay\Run-Client.ps1
```

Lệnh server chạy foreground trên TCP54555. Client mới mở thẳng màn hình Tạo phòng / Vào phòng, không cần nhập `demo` hay tham số phòng. Chủ phòng nhận mã ngẫu nhiên, người thứ hai nhập mã đó trên cùng máy chủ. Các script dựa vào dependency cache sẵn có. Báo cáo 01–08 và manifest `verification.json` ghi nhận bản gameplay ban đầu; tài liệu 09 ghi nhận thay đổi giao diện tiếp theo.
