# Tạo / vào phòng bằng giao diện

Từ `smashprofs-integration`, vào `final-project` và dùng JDK 17:

```powershell
cd .\final-project
$env:JAVA_HOME='E:\2026-2\lap-trinh-mang\baitap'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

Terminal server (giữ chạy; nhập stop + Enter để đóng):

```powershell
.\gradlew.bat :tests:runLocalServer -Pport=54555 --console=plain
```

Mỗi client chạy trong terminal riêng, cũng tại final-project với JDK 17:

```powershell
.\gradlew.bat :desktop:run
```

Qua intro, click **Online Multiplayer** ở menu chính hoặc bấm **O**.

- Nhập Server IP / hostname, TCP port và Room ID.
- Localhost: `127.0.0.1`, port `54555`. LAN: dùng IP máy chạy server và cấu hình bind/firewall theo STEP-52G-LAN-MULTIPLAYER.md.
- Client A chọn **Create room**; chờ màn chọn nhân vật báo Joined.
- Client B nhập cùng server/port/room, chọn **Join room**.
- Chọn nhân vật, bấm **Ready / Enter** ở cả hai; GameServer phát GAME_START.
- **Change room / server** quay về form và đóng connection cũ.
- Khi đang kết nối có **Cancel connection**; Esc có cùng tác dụng. Khi idle có **Back to menu**.
- Lỗi nhập liệu/server được hiển thị trên form; sửa và bấm Create/Join để thử lại. Không tự reconnect hoặc tạo phòng lại.

Server vẫn cần được chạy riêng; nút Create room tạo phòng trên server đã kết nối. Các tham số dòng lệnh online và --mock trước đây vẫn dùng được.

Form chỉ công bố LobbyClient đã JOINED sang Game. Network I/O dùng connector/reader/writer hiện có; render thread chỉ cập nhật Stage/Screen. Đóng form chỉ đóng connection chưa chuyển cho Game.

Kiểm chứng: 16 targeted tests PASS (form, configuration, lobby TCP, online screen boundary), build core/desktop/tests PASS trên JDK 17. Cửa sổ thật đã mở form, thấy lỗi connection refused, sửa port rồi Create thành công, Change room → Join thành công và Back về menu. Ảnh/log tại `final-project/tests/build/lobby-ui-smoke/` (ignored). Không chạy lại toàn bộ gameplay smoke/LAN hai máy trong thay đổi này.
