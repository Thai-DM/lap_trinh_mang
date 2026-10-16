# Giao diện online dễ sử dụng

**Đã có bản sửa tiếp theo:** [10-COMBAT-UX-FIXES.md](10-COMBAT-UX-FIXES.md). HUD lớn ở phiên bản này đã được thay bằng dải52px; vũ khí có hình riêng, túi đồ/đạn authoritative. Dung nham đổi sang -10HP và bật lên theo lựa chọn mới của người dùng. Ảnh và mô tả luật dung nham trong báo cáo09 là evidence của phiên bản trước.

Đã thay mặc định `demo` bằng mã phòng ngẫu nhiên 6 ký tự. Tạo phòng tự sinh mã; Vào phòng yêu cầu nhập mã. Địa chỉ máy chủ thu gọn, có hướng dẫn phân biệt hai cửa sổ trên cùng máy và chơi LAN. Không cần truyền room ID hoặc create/join qua CLI khi dùng script mới.

Sảnh duy nhất có roster tối đa 4 người, badge chủ phòng, trạng thái Ready, mã và nút sao chép, dropdown luật, Lưu/Hoàn tác, chọn nhân vật bằng ảnh và Sẵn sàng/Hủy sẵn sàng. Non-host không sửa luật. Hạ capacity dưới roster bị ngăn ngay ở UI và server vẫn validate độc lập. Đổi luật hủy Ready của mọi người. Server tự bắt đầu khi đủ ít nhất 2 người và tất cả Ready; không có nút Start giả chỉ tác động cục bộ.

HUD phân biệt thanh HP và số MẠNG, hiển thị cooldown F, trạng thái hồi sinh/xem trận, weapon/shield và RTT. Feed ghi rõ mất một mạng khi chết/rơi. Kết quả dùng tên nhân vật/P1–P4, bảng thống kê và MVP, có nút Chơi trận khác thay cho bắt buộc thoát app. Esc trong trận quay về màn hình vào phòng và đóng kết nối (forfeit).

## HP hay số lần rơi dung nham?

- Đòn đánh làm giảm HP. HP về 0: mất một mạng.
- Rơi xuống dung nham: mất một mạng ngay cả khi còn HP.
- Còn mạng: hồi sinh sau 90 ticks (1,5 giây) với100HP, bảo vệ120ticks (2 giây).
- Hết mạng: spectator. Còn một người có mạng: người đó thắng. Tất cả cùng hết mạng: hòa.
- Nếu có giới hạn thời gian: **hết giờ vẫn xét HP cao nhất trong những người còn mạng**, bằng HP thì hòa. Đây là policy của repo đã giữ từ trước; cập nhật UI không đổi sang xếp hạng theo số mạng còn lại.
- Muốn chỉ phân thắng bại theo người cuối cùng còn mạng: chọn **Thời gian → Không giới hạn**.

Các luật này được giải thích ngay trong sảnh; client không tự sửa kết quả.

## Mở game trên máy hiện tại

Từ `E:\2026-2\lap-trinh-mang\smashprofs-integration`, tắt server/client cũ trước khi dùng bản mới:

```powershell
# Terminal 1: server dùng source classes đã được verify.
.\scripts\gameplay\Run-Server.ps1

# Terminal 2: mở client thứ nhất.
.\scripts\gameplay\Run-Client.ps1

# Terminal 3: mở client thứ hai.
.\scripts\gameplay\Run-Client.ps1
```

Client script chọn JDK17, build `:desktop:dist` offline với Gradle heap256MiB, sau đó chạy game heap192MiB. Gradle thoát trước khi mở cửa sổ game nên client thứ hai không chờ một Gradle run task đang chạy. Mỗi client mở form tiếng Việt. Host bấm Tạo phòng, sao chép mã; peer nhập mã, bấm Vào phòng. Chọn nhân vật và Sẵn sàng trên cả hai. IP mặc định127.0.0.1, port54555. Muốn xem cùng menu qua Gradle: `:desktop:run --args='--online'`.

## Kiểm chứng

- **96 tests PASS**, [JUnit](runs/20261016-155200-158-room-ui-final/tests.log), [suite list](runs/20261016-155200-158-room-ui-final/verification.json). Compile toàn bộ core/desktop + các test room/network/client liên quan. Bản gameplay trước có638PASS; con số96 là bộ targeted của thay đổi này.
- OnlineRoomFlowTest dùng GameServer/TCP thật: mã tự sinh hợp lệ, roster/Ready đồng bộ, hủy Ready, chọn lại nhân vật, chuyển quyền chủ phòng, immutable member list. Readiness không đổi rulesVersion.
- Gradle `:desktop:dist` offline build thành công; archive chứa UI class, fonts, LWJGL backend và dependencies runtime.
- Native GL smoke bằng cửa sổ ẩn, server thật và2/4 TCP clients: connect → room → click chọn Luca/Ready → click hủy Ready → chọn lại Viktor/Ready → game → disconnect peers → result. Xác nhận nhân vật được server chấp nhận sau mỗi lựa chọn. Đã xem ảnh render1280×720 và960×540 của app; không chụp desktop của người dùng. Audio tắt trong phép smoke này; chưa thực hiện manual gameplay/audio session đầy đủ.
- Font BMFont có623 glyphs gồm tiếng Việt, bitmap raster từ logical system font. Không phân phối font TTF của hệ điều hành. Có thể tạo lại bằng `java scripts/gameplay/GenerateUiFont.java final-project/assets/ui/online`.
- So sánh performance vẫn BLOCKED_MEMORY theo báo cáo07; không chạy lại benchmark tải trong lần chỉnh giao diện này.

## Protocol bổ sung cho roster

ROOM_SETTINGS_STATE có optional `players` cùng schema member như ROOM_STATE. Version settings tăng khi chọn nhân vật/đổi Ready; rulesVersion chỉ tăng khi luật đổi. Client đọc roster immutable, không hủy Ready vì peer vừa Ready. Constructor Settings cũ được giữ và parser chấp nhận server không có roster extension. Chỉ gửi extension trong phòng capability gameplay-v1; legacy schema không đổi. PLAYER_READY ready=false được dùng để hủy Ready qua server, kể cả khi countdown chưa bắt đầu trận.

Ảnh bằng chứng tại [ui-previews/](ui-previews/); thông tin bản kiểm chứng tại [ui-verification.json](ui-verification.json). Manifest `verification.json` cũ ghi nhận hash trước cập nhật UI, không dùng để xác nhận source hiện tại.
