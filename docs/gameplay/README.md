# Gameplay multiplayer — 9 tính năng chính thức

**Bản sửa mới nhất:** [dung nham bật lên, HUD gọn và vũ khí/túi đồ](10-COMBAT-UX-FIXES.md). Dung nham hiện trừ10HP và bật lên theo lựa chọn mới của người dùng; không mất ngay một mạng. Bản này có198targetedtestsPASS và native graphics smoke với2/4người.

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
