# Sửa xuyên bậc, đánh xuống, âm lượng và khởi động web

Cập nhật 24/10/2026.

## Cách thử

1. Tải lại bản web. Trong menu, mở **Cài đặt [C]**. Ba thanh **Âm lượng tổng / Nhạc nền / Hiệu ứng** chỉnh từ 0–100%, có tác dụng ngay và được nhớ trên thiết bị. Mức tổng 0 tắt cả hai kênh. Nhãn nút ngắn để không cắt chữ ở màn hình nhỏ.
2. Vào trận online, lên bậc giữa có cầu nối và cây. Bấm **S hai lần** để xuyên bậc một lần, không cần bấm thêm hai lần. Sàn thật phía dưới vẫn đỡ nhân vật.
3. Khi tay không và đang trên không, **S+J** là đòn đánh xuống (DAIR). Trên sàn là DLIGHT. Đang cầm súng thì S+J chỉ ngắm/bắn xuống, không đánh tay. Rơi nhanh không ghi đè chuyển động trong lúc ra đòn hoặc bắn.
4. Giữ S vẫn rơi nhanh khi đang rơi tự do; **S+Space** vẫn xuyên sàn mềm. Hướng dẫn H giới hạn chiều rộng, tự xuống dòng.

## Nguyên nhân bậc bị tính thành nhiều tầng

TMX giữ nguyên hình ảnh và collision baseline. Cùng bậc nhìn thấy chứa object 20, 21 và 33; object 33 là lớp mỏng vắt qua hai khối còn lại. Lệnh drop cũ chỉ tạm bỏ va chạm với đúng một rectangle nên chạm ngay rectangle khác của cùng bậc.

`PlatformAssembly` xác định nhóm rectangle một chiều nối/chồng theo chiều ngang và chênh mặt trên tối đa nửa tile (8 pixel). Server và world dự đoán dùng cùng phép nhóm này, tạm bỏ qua cả nhóm trong nhịp drop. Không vô hiệu toàn bộ map: tầng thấp tách biệt và sàn đặc vẫn có va chạm.

Khi có ý định đánh/bắn, server không diễn giải double tap thành xuyên sàn. Hướng xuống còn dùng để chọn chiêu/ngắm, nhưng không ép vận tốc fast fall trong lúc đòn đang chạy. Điều này giữ S+J thành hành động chiến đấu rõ ràng.

## Âm thanh

Preferences `smashprofs-audio` lưu ba giá trị độc lập. Âm lượng nhạc thực = tổng × nhạc, hiệu ứng thực = tổng × hiệu ứng × mức riêng của âm đó. Cả hiệu ứng menu, local và các tiếng đánh/KO/Boss trong online đều đi qua cùng bộ điều chỉnh.

Nhạc đang phát đổi âm lượng ngay; vào trận online có nhạc nền gốc. `SoundManager` tái dùng cùng bản nhạc khi đấu lại, cache hiệu ứng để không tạo tài nguyên mới mỗi lần bấm; nhạc cũ và cache được dispose theo vòng đời ứng dụng.

## WebGL

Lỗi `Cannot read properties of null (reading 'pixelStorei')` xảy ra trước khi tạo game khi backend nhận context null. [Canvas.getContext có thể trả null](https://developer.mozilla.org/en-US/docs/Web/API/HTMLCanvasElement/getContext).

`boot.js` xin WebGL trên chính canvas của game trước khi gọi TeaVM, thử cấu hình mặc định / ít tiêu thụ điện và tên legacy. Backend dùng lại đúng context này; không tạo thêm canvas để dò GPU. Không có context thì dừng sớm với hướng dẫn bật tăng tốc đồ họa, không khởi tạo backend rồi báo TypeError. Khi mất context, dừng render và hướng dẫn tải lại. Các thao tác này không xóa hồ sơ hay điểm.

**Chế độ nhẹ vẫn cần WebGL hoạt động.** Có thể mở `?graphics=compat`; các tham số kết nối còn giữ nguyên. Nếu trình duyệt tắt WebGL do cấu hình/chính sách/driver, website không tự bật GPU được. Với Edge, kiểm tra tùy chọn tăng tốc tại `edge://settings/system`, khởi động lại trình duyệt, xem WebGL trong `edge://gpu`. [Hướng dẫn Microsoft](https://github.com/MicrosoftDocs/SupportArticles-docs/blob/main/support/microsoft-edge/performance/edge-high-cpu-memory.md).

## Kiểm tra

- **876 bài hồi quy qua:** server/common/core/desktop, gồm bậc thật tại ba vị trí, sàn bên dưới, S+J tay không, súng bắn xuống, tương thích gameplay/networking và hồ sơ.
- **19 bài tập trung cuối qua:** thêm kiểm tra nhạc đang phát đổi gain ngay, tái dùng/dispose nhạc, master mute, lưu Preferences và ánh xạ phím.
- **7 kiểm tra âm lượng trên Edge:** thao tác chuột, bố cục 1280×720 / 960×540 / 1024×768, reload giữ giá trị và master về 0.
- **6 kiểm tra WebGL trên Edge:** không hỗ trợ, ném lỗi, fallback low-power, fallback tên legacy, menu thật và mất context; dữ liệu trình duyệt giữ nguyên.
- **9 kiểm tra ghép trận thật:** PvP và Boss với hai browser context riêng; tải được trận sau khi thêm nhạc và chuẩn bị WebGL.

Chạy lại:

```powershell
.\scripts\gameplay\Verify-Gameplay.ps1
.\scripts\web\Run-Web.ps1 -BuildOnly
python scripts/web/Verify-WebGL.py
python scripts/web/Verify-Web.py --settings-audio
python scripts/web/Verify-Web.py --matchmaking
```

[876 bài hồi quy](runs/20261024-081624-652-final-gameplay-regression/verification.json) · [19 bài tập trung](runs/20261024-082020-754-final-platform-audio/verification.json) · [Âm lượng](runs/20261024-audio-settings/verification.json) · [WebGL](runs/20261024-webgl-startup/verification.json) · [PvP/Boss](runs/20261024-matchmaking-smoke/verification.json).
