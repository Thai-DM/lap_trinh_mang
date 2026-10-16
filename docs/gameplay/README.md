# Gameplay multiplayer — 9 tính năng chính thức

Source server/client đã triển khai; **638 tests PASS**. Kiểm tra trực quan GUI/audio chưa chạy; so sánh hiệu năng bị chặn do RAM. [Trạng thái cuối](08-FINAL-STATUS.md) phân biệt phần hoàn tất và phần chưa kiểm chứng.

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
```

Lệnh server chạy foreground trên TCP54555. Mở hai game client từ menu Online hoặc launcher `--gameplay`; xem chi tiết tại tài liệu client. Các script dựa vào dependency cache sẵn có. Sau khi hoàn tất kiểm thử, người dùng đã yêu cầu commit/push source, tests và tài liệu gameplay lên nhánh `integration/member2-check`.
