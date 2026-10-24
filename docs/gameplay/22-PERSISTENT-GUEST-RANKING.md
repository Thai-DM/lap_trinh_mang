# Bảng xếp hạng và hồ sơ khách được ghi nhớ

Ngày cập nhật: 23/10/2026.

## Cách dùng

- Mở game tại cùng địa chỉ web bằng trình duyệt thường. Tên, avatar và hồ sơ khách được lưu bằng Preferences của libGDX, phía web dùng bộ nhớ trình duyệt.
- Chỉnh tên ở **Hồ sơ** trong menu. Mã hồ sơ do server cấp, chỉ đọc; đổi tên không tạo bảng điểm mới.
- Chơi online rồi mở **BXH**. Dòng của mình có chữ **BẠN**. Tải lại trang, mở tab mới hoặc mở lại trình duyệt vẫn dùng hồ sơ đã lưu nếu dữ liệu trang web còn nguyên.
- Trình duyệt khác, máy khác, chế độ ẩn danh hoặc xóa dữ liệu trang web tạo hồ sơ riêng. Đây là hồ sơ khách trên thiết bị, chưa phải tài khoản đăng nhập đồng bộ nhiều máy.
- **Hồ sơ mới** bắt đầu danh tính mới; điểm cũ vẫn ở database nhưng khóa truy cập cũ không còn được lưu trên thiết bị.

## Lỗi trước đây và thay đổi

Trước đây mỗi kết nối có một UUID gameplay mới, và `RankingService` dùng UUID đó làm khóa xếp hạng. Tên hiển thị cũng bị thay bằng UUID. Việc lưu tên/ID cục bộ trên trình duyệt không đủ để giữ điểm, vì server chưa ánh xạ kết quả trận sang hồ sơ lâu dài.

Client mới xin hồ sơ khách trong CONNECT bằng `guestProfile: true`. Server trả `profileId` và khóa ngẫu nhiên 256 bit `profileToken` trong CONNECT_ACK. Khi kết nối lại, client gửi khóa đã lưu cho đúng endpoint máy chủ. Server tra hash SHA-256 của khóa, không tin ID công khai do client tự khai.

Mỗi phiên vẫn có PlayerId gameplay riêng. SQLite thêm bảng `guest_profiles` cho ID, tên và hash khóa; `ranking_sessions` lưu ánh xạ PlayerId phiên sang hồ sơ. Ánh xạ còn tồn tại sau khi đóng socket và khởi động lại, để kết quả trận được giao bất đồng bộ vẫn ghi đúng người. Kết quả được đổi sang ID hồ sơ trước khi tính Elo và lưu giao dịch; chống cộng trùng matchId được giữ nguyên. Bảng xếp hạng trả tên đã lưu thay cho UUID.

CONNECT legacy vẫn nhận ACK chỉ có `playerId`. Server không cấu hình ranking vẫn cho chơi bình thường khi client chưa có khóa hồ sơ. Luật Elo PvP và thống kê Boss được giữ nguyên. Trận có hai phiên cùng một hồ sơ không được tính xếp hạng với chính mình.

Database cũ được mở rộng bằng CREATE TABLE IF NOT EXISTS, không xóa hay sửa điểm hiện có. Các dòng UUID cũ hiển thị dạng **Khách + mã rút gọn**. Không tự gộp chúng vào hồ sơ mới vì không có bằng chứng người sở hữu; ID công khai không đủ để nhận điểm của người khác.

## Giao diện

Bảng có sáu cột cùng kích thước giữa tiêu đề và dữ liệu. Tên được giới hạn trong cột, ID rút gọn, cỡ chữ điểm vừa phải. Danh sách bắt đầu từ phía trên, cuộn trong khung riêng. Nút Quay lại / Làm mới dùng nhãn ngắn; địa chỉ máy chủ dài nằm trong hộp đổi máy chủ. Tên tiếng Việt dùng bitmap font hiện có, không thêm font runtime.

## Kiểm tra

```powershell
.\scripts\gameplay\Verify-Gameplay.ps1
.\scripts\web\Run-Web.ps1 -BuildOnly
python scripts/web/Verify-Web.py --ranking-profile
python scripts/web/Verify-Web.py
```

- 863 kiểm tra hồi quy server/common/core/desktop qua; gồm SQLite khôi phục sau restart, TCP reconnect, kết quả sau đóng socket, cộng dồn và chống cộng trùng, không nhận hồ sơ bằng ID công khai, từ chối khóa sai, Preferences giữ khóa đúng máy chủ.
- 10 kiểm tra Edge qua, dùng database tạm riêng, tên tiếng Việt dài 64 ký tự và UUID cũ ở 1280×720, 960×540, 1024×768; cuộn thực bằng chuột, refresh, reload, tab mới và trình duyệt tách biệt.
- 18 kiểm tra trận Boss thực qua với hai browser context tách biệt, gồm ghi điểm vào hồ sơ, giữ tên và đánh dấu dòng của mình sau khi reload, kết quả trận và đấu lại.

Ảnh và kết quả Edge: [bảng xếp hạng](runs/20261023-ranking-profile/verification.json), [trận thật và hồ sơ sau reload](runs/20261023-web-client/verification.json).

Kết quả native: [863 bài hồi quy](runs/20261023-172142-931-final-gameplay-regression/verification.json), [kiểm tra cuối hồ sơ](runs/20261023-172451-350-final-guest-profile/verification.json).
