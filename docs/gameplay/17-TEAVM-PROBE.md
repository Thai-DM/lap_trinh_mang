# Thử nghiệm TeaVM: map gốc, nhân vật và hai trình duyệt

Ngày 22/10/2026: **đã build và chạy được trong trình duyệt thật**. Hai tab Edge kết nối WebSocket đến gateway trên JVM, gateway chuyển tiếp đến `GameServer` TCP hiện có, vào chung phòng Boss và bắt đầu cùng một trận.

Đây là bằng chứng cho tính khả thi của phần hiển thị và kết nối. Bản thử chưa thay thế toàn bộ client desktop hoặc triển khai public.

## Chạy thử

Từ thư mục gốc `smashprofs-integration`, dùng JDK 17 và Python 3:

```powershell
.\scripts\web\Run-TeaVm.ps1 -Multiplayer -Boss
```

Lần đầu tải dependency từ Maven Central. Script build server hiện tại, build TeaVM, mở một gateway JVM ẩn và phục vụ web trên `127.0.0.1:8088`. Mở link `CREATE` mà script in ra một lần, sau đó mở link `JOIN` trong tab khác. Hai tab tự chọn Alex/Luca, sẵn sàng, xác nhận đã tải rồi đếm ngược vào trận.

Nếu đã build:

```powershell
.\scripts\web\Run-TeaVm.ps1 -SkipBuild -Multiplayer -Boss
```

Các link mặc định:

- Tạo: `http://127.0.0.1:8088/?mode=create&room=TEA1&boss=true`
- Vào: `http://127.0.0.1:8088/?mode=join&room=TEA1`

Tham số tùy chọn: `-JavaHome 'DUONG_DAN_JDK17'`, `-Port 8088`, `-GatewayPort 8090`, `-Room ABCD`. Bỏ `-Boss` để tạo phòng PvP. Chỉ để một script dùng cùng cổng hoạt động. Ctrl+C dừng HTTP server và gateway mà script đã tạo.

Điều khiển trong trận: **A/D di chuyển, W nhảy, J bắn, I/S ngắm lên/xuống, K đánh mạnh, L né/lướt, E nhặt, G thả, Q ném, F kỹ năng**. Sát thương, đạn, nhảy và chiêu Boss do Java server quyết định. Cầm Laser không chạy animation đấm khi bắn.

Nếu không dùng `-Multiplayer`, script mở một fixture đồ họa chạy cục bộ: A/D di chuyển, W nhảy, J xem animation, 1–5 đổi nhân vật, B đổi pose Boss, F thử âm thanh, R đặt lại vị trí. Fixture có va chạm đơn giản và không đại diện cho gameplay server.

Build mà không mở server:

```powershell
.\scripts\web\Run-TeaVm.ps1 -BuildOnly -Multiplayer
```

## Phần đã tái sử dụng trực tiếp

Project thử nghiệm độc lập nằm trong `final-project/teavm-probe`. Không sửa version libGDX của desktop hoặc thêm dependency TeaVM vào build chính.

| Thành phần | Cách dùng |
| --- | --- |
| `1/Map1New2Remake.tmx`, TSX và texture | Load bằng `TmxMapLoader`; không vẽ lại bằng HTML/ảnh chụp. |
| `OnlineVisualCatalog` | Lấy đúng đường dẫn sprite của cả năm nhân vật. |
| `OnlineBossAssets` | Compile và chạy lại đúng bộ vẽ/animation Boss từ core. |
| `BossSnapshot` | Compile lại record Java 17, dùng để render dữ liệu Boss nhận từ server. |
| `OnlineCameraController` | Camera bám người chơi trong web. |
| `CombatIcons` | Dùng lại bộ icon vũ khí/vật phẩm/projectile của desktop. |
| `FighterMovement` | Fixture cục bộ dùng lại công thức di chuyển. Multiplayer dùng vật lý thật trên server. |
| `GameServer`, `MatchRuntime`, `BossEngine`, Box2D | Chạy nguyên trên JVM bằng JAR hiện tại. |

Build riêng dùng **gdx-teavm 1.6.2, TeaVM 0.15.0, libGDX 1.14.2**, Java 17 và Gradle wrapper 7.3.3 hiện có. Browser build xuất JavaScript; chưa kiểm tra Wasm. `build.gradle` tham chiếu các source gốc, không fork implementation của renderer/model.

Nguồn API: [gdx-teavm](https://github.com/xpenatan/gdx-teavm), [TeaVM](https://www.teavm.org/docs/intro/overview.html), [Java-WebSocket 1.6.0](https://github.com/TooTallNate/Java-WebSocket).

## Mạng thử nghiệm

`BrowserRoom` là adapter bất đồng bộ mới cho trình duyệt. Dùng WebSocket browser, `JsonReader` của libGDX và schema hiện có; không đưa `java.net.Socket`, thread TCP hoặc Jackson của client desktop vào TeaVM.

`ProbeGateway` chạy riêng trên JVM với Java-WebSocket 1.6.0. Mỗi WebSocket map tới một TCP session thật. Giới hạn bốn client, frame tối đa 1 MiB và ngắt peer nếu hàng gửi vượt 64 buffer/1 MiB. Browser vẫn phải bắt tay nhận player ID, tạo/join phòng, gửi `LOAD_READY`, kèm `matchId` trong input và trả lời heartbeat. Gateway không tạo player giả, không tick mô phỏng, không tự tính sát thương.

Gateway và HTTP thử nghiệm chỉ bind loopback. Chưa cấu hình TLS/WSS, domain, Azure hoặc GitHub Actions; không coi link localhost là link chơi Internet.

## Kiểm chứng

Đã chạy Edge headless với WebGL/SwiftShader, chụp ảnh và kiểm tra trạng thái bằng Chrome DevTools Protocol:

- [Kiểm tra đồ họa và điều khiển](runs/20261022-teavm-browser/verification.json): map gốc, năm bộ sprite, renderer Boss, A/D, W nhảy, đổi nhân vật, pose và resize 960×540; không có JS exception hoặc request asset lỗi.
- [Hai tab và server thật](runs/20261022-teavm-network/verification.json): ID riêng, cùng match ID, roster hai người, Boss thật, di chuyển tab A xuất hiện ở snapshot tab B, J trừ đạn Laser và không tạo đòn tay; không có lỗi protocol/JS.
- [Ảnh map và Boss trên web](runs/20261022-teavm-browser/01-map-boss.png).
- [Ảnh trình duyệt thứ nhất](runs/20261022-teavm-network/01-boss-peer-1.png).
- [Ảnh trình duyệt thứ hai](runs/20261022-teavm-network/02-boss-peer-2.png).
- [Peer thấy chuyển động](runs/20261022-teavm-network/03-peer-sees-movement.png).

Chạy lại sau khi build:

```powershell
python -X utf8 .\scripts\web\Verify-TeaVm.py
python -X utf8 .\scripts\web\Verify-TeaVm-Network.py
```

Verifier cần module Python `websocket-client` và Edge tại đường dẫn mặc định, hoặc biến `TEAVM_BROWSER` trỏ tới Chrome/Edge. Các browser profile kiểm tra nằm trong thư mục build, không dùng profile duyệt web cá nhân. Âm thanh đã gọi play sau phím bấm; chưa xác nhận nghe bằng tai.

## Phần còn lại để thành bản web đầy đủ

- Chuyển sảnh tạo/join bằng giao diện, chọn nhân vật, cài đặt phím, HUD/túi đồ, màn kết quả và đấu lại.
- Hoàn thiện animation/combat presentation, cảnh báo đòn và các projectile của kỹ năng. Bản thử hiện mới vẽ các sprite/icon cơ bản.
- Thêm interpolation/prediction trên web. Hiện vị trí mạng render từ snapshot server, chưa chuyển `LocalFighterPrediction` Box2D sang trình duyệt; cảm giác chưa mượt như desktop.
- Kiểm tra timeout, reconnect, tab mất focus, trận dài, 3–4 client, gameplay PvP và độ trễ mạng thật.
- Cấu hình endpoint WSS/HTTPS, origin cho domain public, hosting server rồi mới thêm workflow tự build/deploy từ GitHub.

Kết luận của thử nghiệm: **TeaVM chạy được những phần đồ họa/model đã chọn và multiplayer cơ bản với backend hiện có.** Đây chưa phải bằng chứng rằng toàn bộ `core` desktop đã tương thích TeaVM hoặc toàn bộ gameplay web đã được kiểm chứng.
