# Merge dev vào bản web — 24/10/2026

Nguồn: `origin/dev` tại `b8ac628a0ebabcf0ad5b04fb6eccf494bfe0b79e`.
Đích: `feature/teavm-web-test`, sau bản sửa `cbbba33`.

- Tiếp nhận hiển thị/cập nhật Elo từ dev, ranking cho standalone, giảm nửa lực đẩy đòn mạnh và thay đổi màn hình kết quả/đấu lại.
- Ghép trận: chọn chế độ → tìm đủ hai người → phòng chọn nhân vật → cả hai bấm Sẵn sàng → tải trận. Test browser được cập nhật theo luồng mới; không còn chọn nhân vật ở màn hình tìm trận.
- Hai xung đột `FighterPhysics` và `LocalFighterPrediction` được giải quyết bằng cách giữ nhóm collision của một bậc, kết hợp ngưỡng tiếp xúc mới 6 pixel từ dev. Cách này không tắt va chạm với mọi bậc một chiều phía dưới.
- Giữ âm lượng tổng/nhạc/hiệu ứng, S+J không bị ép fast fall khi ra đòn, hồ sơ khách và khởi động WebGL tương thích của nhánh web.
- Test chọn nhân vật chờ phản hồi máy chủ trước khi kiểm tra tên; việc gửi lựa chọn không đồng nghĩa server đã xác nhận ngay trong cùng lời gọi.

Kiểm chứng: build Java gateway và TeaVM thành công; **877 bài hồi quy qua**, **9 kiểm tra Edge với hai browser context qua** (hủy/tìm lại, chọn nhân vật, ready, PvP, Boss, tách hàng chờ và rời trận).

[Hồi quy](runs/20261024-103121-754-final-gameplay-regression/verification.json) · [Hai trình duyệt](runs/20261024-dev-merge-matchmaking/verification.json).
