# Client đầy đủ trên trình duyệt bằng TeaVM

Nhánh làm việc: `feature/teavm-web-test`. Đã merge toàn bộ `dev` đến commit `07284f4`, gồm hồ sơ người chơi, ELO và SQLite. Module mới là `final-project/web`; `teavm-probe` giữ vai trò bản thử ban đầu.

## Chạy trên máy

Mở PowerShell tại thư mục repository:

```powershell
cd E:\2026-2\lap-trinh-mang\smashprofs-integration
.\scripts\web\Run-Web.ps1
```

Cần JDK 17, Python 3 và Internet để tải dependency lần đầu. Script có tham số `-JavaHome` nếu JDK nằm ở chỗ khác. Lần đầu script build server, chạy test protocol và biên dịch client Java thành JavaScript. Mở URL `PLAY` được in ra, mặc định là `http://127.0.0.1:8088/` với endpoint WebSocket đính kèm.

Sau khi build thành công:

```powershell
.\scripts\web\Run-Web.ps1 -SkipBuild
```

Menu có chơi Local, Online, bảng xếp hạng, hồ sơ và cài đặt phím. Với Online, một người chọn PvP hoặc Boss rồi tạo phòng; người còn lại nhập mã phòng và bấm Vào phòng. Chọn nhân vật và mỗi người bấm Sẵn sàng. Giữ nguyên luồng màn hình tải, trận đấu, kết quả và đấu lại của client desktop.

Phím mặc định của fighter theo `dev`: A/D di chuyển, Space nhảy, W/S ngắm, J đánh/bắn, K đánh mạnh, L né/lướt, E nhặt, Q ném, F tuyệt chiêu, Tab túi đồ, H hướng dẫn. Cài đặt phím vẫn có trong menu. Chơi Local dùng luật và điều khiển local gốc; chế độ đó chưa được đồng nhất lại với luật online.

Test SwiftShader cho thấy bộ hiệu ứng TV/bloom/grain/motion blur của local rất nặng. Web mặc định dùng đường render nhẹ; có thể giữ toàn bộ hiệu ứng cũ bằng `?effects=full` hoặc `SMASHPROFS_FULL_EFFECTS=true` trong `web-config.js`. Desktop giữ preset cũ.

Nếu trùng cổng:

```powershell
.\scripts\web\Run-Web.ps1 -SkipBuild -Port 8089 -GatewayPort 8091
```

Ctrl+C dừng HTTP server và gateway do script này mở. Dữ liệu ELO của runner ở `final-project/web/build/run/ranking.db`; không xóa thư mục đó nếu cần giữ bảng điểm khi build lại. Hồ sơ, phím và thời gian chơi của web lưu trong storage của trình duyệt, riêng với desktop. Trình duyệt có thể yêu cầu thao tác bấm trước khi phát âm thanh.

## Phần code dùng lại và phần thay theo nền tảng

| Phần | Cách chạy trên web |
|---|---|
| Menu, hồ sơ, phím, phòng chờ, tải trận, bản đồ, HUD/túi đồ, Boss, kết quả | Biên dịch trực tiếp từ nguồn `core/src` |
| LobbyClient, ClientMatchSession, interpolation, input queue, prediction | Dùng code chung; transport là WebSocket |
| Protocol/model, kiểm tra roster, HP/mạng, Fighter/Boss/ranking payload | Dùng nguồn `common/src`, giữ các bộ kiểm tra dữ liệu |
| Box2D trên client | Bản Java thuần `gdx-box2d-gwt`, dùng cho local và prediction |
| TCP client | Cài đặt `NetworkClient` dành cho browser, cùng API với desktop |
| Jackson tree | Adapter tree nhỏ trên JsonReader; chỉ API tree mà protocol đang dùng, không phải ObjectMapper/POJO reflection |
| Gson MockServer DTO | Chỉ hỗ trợ trên desktop; web dùng protocol v1 và server thật |
| Log4j/file properties | Console và Preferences/storage của browser |
| VFX local | Giữ thư viện/hiệu ứng/shader; adapter GL dùng framebuffer handle thực của backend TeaVM |
| Gameplay và xếp hạng | Server Java gốc: Box2D native, room/tick/combat/Boss/ELO/SQLite |

Task `prepareSharedSources` sinh nguồn trong `web/build/shared-java`, chỉ đổi import JSON/logging sang adapter web và chọn các file dành cho nền tảng. Không sửa logic gameplay bằng bước sinh nguồn. Thay đổi gameplay/UI tiếp theo vẫn thực hiện ở `core/src` hoặc `common/src`; build web lấy lại nguồn đó. Không commit nguồn sinh, JS, cache hay asset bản sao trong `build`.

Module web pin libGDX 1.14.2, gdx-teavm 1.6.2, TeaVM 0.15.0, Java 17. Desktop/server giữ dependency hiện tại. Adapter framebuffer truy cập bảng handle của backend 1.6.2; cần kiểm tra lại adapter khi nâng backend.

Lớp kết nối web kiểm tra envelope v1, ACK/request ID và identity được server cấp; trả PONG và giới hạn buffer gửi. Các validator chung tiếp tục kiểm tra match/roster và snapshot. Cầu WebSocket giữ framing TCP ở phía server, giới hạn frame và số kết nối. Mặc định chỉ nhận browser từ localhost và tối đa 32 kết nối (mỗi phòng vẫn theo luật 2–4 người).

Leaderboard dùng cùng transport thay vì mở Socket ngay trong màn hình. CONNECT gửi profile đã chọn trước khi tạo/vào phòng. Bảng điểm trống/lỗi hiển thị đúng trạng thái máy chủ, không tự thay bằng tên/điểm mẫu.

Kiểm thử kết thúc Boss phát hiện `RatingEngine` mới của dev dereference `winnerId=null`. Bản tích hợp xử lý thắng/thua theo kết quả cả đội, cập nhật thống kê và giữ ELO khi chơi co-op; PvP tiếp tục dùng Elo như dev. Backend hiện vẫn dùng ID phiên trong bản ghi ranking như dev; việc gom điểm xuyên phiên theo ID hồ sơ chưa nằm trong phần chuyển client này.

## Build và kiểm tra

```powershell
.\scripts\web\Run-Web.ps1 -BuildOnly
python -m pip install websocket-client
python .\scripts\web\Verify-Web.py
python .\scripts\web\Verify-Web.py --pvp
python .\scripts\web\Verify-Web.py --local-only
```

Verifier dùng Edge/Chrome thật, WebGL với SwiftShader, profile thử riêng và server Java/SQLite thật. Thao tác qua bàn phím/chuột vào các màn hình gốc; không chèn HP, tọa độ, kết quả hoặc trạng thái match. `?debug=1` bật diagnostics chỉ đọc phục vụ kiểm tra; bình thường không tạo cây diagnostics mỗi frame. Có thể đặt `TEAVM_BROWSER` để chọn browser tương thích CDP.

Kết quả và ảnh được lưu tại:

- `runs/20261023-web-client/`: luồng online Boss.
- `runs/20261023-web-pvp/`: luồng online PvP.
- `runs/20261023-web-local/`: chọn nhân vật và vật lý local.

Chỉ `verification.json` có `status=PASS` của lần chạy tương ứng mới xác nhận case đã qua; các file `failure*` là bằng chứng của lần chạy lỗi trước. Test Java của module ở `web/src/test` kiểm tra round trip/copy JSON, envelope và parse những snapshot Boss đã ghi từ server thật. Headless không xác nhận âm thanh nghe được, tay cầm, điện thoại, Safari hoặc hiệu năng máy người chơi.

## Build tự động trên GitHub

Workflow `.github/workflows/web-build.yml` build khi push nhánh thử nghiệm hoặc `dev`, khi mở PR có thay đổi liên quan, hoặc bấm Run workflow. Workflow tải lên hai artifact theo SHA: client tĩnh `web-client-*` và thư viện server `java-game-gateway-*`.

Workflow build và lưu artifact. Nhánh `feature/teavm-web-test` còn tự triển khai lên VM Azure sau khi có hai Actions secrets; xem [hướng dẫn thiết lập deploy](19-AZURE-AUTO-DEPLOY.md). Không tạo máy chủ Azure. Chỉ xác nhận CI/deploy chạy thực tế sau khi push và xem kết quả Actions.

## Cho người ở mạng khác vào

Website cần HTTPS công khai; endpoint game cần WSS. Cấu hình một lần trong `web-config.js` của bản phát hành:

```javascript
window.SMASHPROFS_WS_URL = 'wss://game.example.com/ws';
```

Nếu frontend và gateway cùng tên miền, để trống thì client dùng `/ws` trên tên miền hiện tại. Tham số `?ws=...` có thể ghi đè để test. Người chơi dùng menu/mã phòng; không phải cài Java vào máy.

Sau khi chép các JAR trong `build/gateway/lib` vào thư mục `lib` trên máy chủ có Java 17:

```bash
java -Xmx256m -Dweb.origins=https://game.example.com \
  -cp 'lib/*' com.smashprofs.web.GameGateway 8090 data/ranking.db
```

`web.origins` là danh sách origin HTTP/HTTPS chính xác, ngăn cách bằng dấu phẩy, không có path hoặc dấu `/` cuối. Có thể dùng `-Dweb.bind.host=0.0.0.0` khi hạ tầng yêu cầu; mặc định bind loopback để reverse proxy cùng máy truy cập. TLS xử lý tại reverse proxy. Ví dụ Caddy trên máy có tên miền trỏ tới:

```caddyfile
game.example.com {
    handle /ws {
        reverse_proxy 127.0.0.1:8090
    }
    handle {
        root * /srv/smashprofs/webapp
        file_server
    }
}
```

Toàn bộ nội dung `build/dist/webapp/` được chép vào thư mục webapp. Website tĩnh có thể host riêng, nhưng GitHub Pages không chạy tiến trình server Java; khi đó đặt WSS endpoint của server trong `web-config.js` và đưa origin website vào `web.origins`.

Người dùng đã triển khai bản thủ công lên `https://smashprofs-thai.southeastasia.cloudapp.azure.com` và xác nhận mở được website. Các test hai browser trên cùng máy chưa thay thế kiểm tra hai máy ở hai mạng khác nhau, độ trễ Internet, reconnect dài hạn và tải nhiều phòng. Không có cam kết sức tải từ các smoke test này.

Tham khảo nền tảng: [gdx-teavm](https://github.com/xpenatan/gdx-teavm), [TeaVM runtime](https://www.teavm.org/docs/runtime/java-classes.html), [GitHub Pages là hosting tĩnh](https://docs.github.com/en/pages/getting-started-with-github-pages/what-is-github-pages).
