# Tự cập nhật game web từ GitHub lên Azure

Website hiện tại: https://smashprofs-thai.southeastasia.cloudapp.azure.com.
VM: `20.195.25.203`, tài khoản SSH `azureuser`, Ubuntu x64, Java 17, Caddy.

## Hoạt động

Push vào **`feature/teavm-web-test`** kích hoạt workflow **Build and deploy TeaVM game**.
Mỗi push đều build, kể cả thay đổi tài liệu. Nhánh `dev` và pull request chỉ build,
không cập nhật website. Để thay nhánh phát hành, đổi cả `on.push.branches` và
điều kiện `jobs.deploy.if` trong `.github/workflows/web-build.yml`.

Workflow chạy test tích hợp Boss/ranking phía server và test protocol web,
test health check trên TLS/HTTP/WebSocket thật và test đổi bản/rollback trong thư mục tạm,
build client TeaVM và gateway Java, tạo artifact theo SHA rồi chuyển qua SSH.
Các workflow cùng nhánh được tuần tự hóa, không hủy lần triển khai đang chạy.
Không cần cài Git hoặc Gradle trên VM.

VM giữ mỗi bản tại `/srv/smashprofs/releases/<sha>-<run>-<attempt>/`.
`current` trỏ tới bản đang dùng; `webapp` và `lib` trỏ qua `current`.
Lần đầu, script tự giữ hai thư mục cài thủ công thành một bản `manual-*`.
Chỉ sau khi giải nén và kiểm tra file xong mới đổi bản và restart service.
Kiểm tra HTTPS đúng chứng chỉ, đúng mã bản phát hành, handshake WSS qua Caddy và
nhận `CONNECT_ACK` thực từ server Java (không tạo phòng hay trận đấu).
Nếu kiểm tra trên VM thất bại, script phục hồi con trỏ trước đó và restart lại.
Sau đó runner còn kiểm tra website công khai; lỗi kết nối từ Internet làm job báo đỏ.

Không thay cấu hình Caddy/service đã cài. Không ghi/xóa/copy database
`/var/lib/smashprofs/ranking.db`. Giữ các bản cũ để quay lại; cần theo dõi dung lượng
`/srv/smashprofs/releases` khi triển khai nhiều lần. Bản đầu khoảng vài chục MB cho
client và vài chục MB cho JAR. Restart server ngắt các trận đang chạy.

## Thiết lập một lần

VM phải có `smashprofs.service`, `caddy.service`, Python 3 và `flock` (Ubuntu có
trong gói util-linux). Website hiện tại cần hoạt động bằng HTTPS. Tài khoản SSH
phải chạy được `sudo -n` như tài khoản quản trị Azure hiện tại. Runner GitHub phải
truy cập được SSH cổng 22; nếu NSG chỉ cho IP máy cá nhân, cần cấu hình thêm quyền
truy cập cho runner. Không tắt kiểm tra host key để chữa lỗi kết nối.

### 1. Secret khóa SSH

Trong GitHub repository `Thai-DM/lap_trinh_mang`, mở:

**Settings → Secrets and variables → Actions → New repository secret**.

Tên: **`AZURE_SSH_PRIVATE_KEY`**.

Giá trị: toàn bộ nội dung khóa riêng không có passphrase đã dùng SSH vào VM,
bao gồm dòng BEGIN và END. Có thể dùng khóa deploy riêng nếu đã thêm public key
tương ứng vào `~azureuser/.ssh/authorized_keys`. Không commit khóa vào Git.
Không gửi khóa vào chat, issue hoặc log Actions.

Trên PowerShell Windows, để chép khóa hiện tại vào clipboard mà không in ra màn hình:

```powershell
Get-Content -Raw -LiteralPath "E:\2026-2\lap-trinh-mang\smashprofs-web_key.pem" | Set-Clipboard
```

Dán vào ô Secret rồi bấm **Add secret**.

### 2. Secret nhận dạng máy Azure

SSH vào đúng VM như trước. Trong terminal Ubuntu đã xác thực, chạy:

```bash
awk '{print "20.195.25.203 " $1 " " $2}' /etc/ssh/ssh_host_ecdsa_key.pub
```

Đây là khóa **công khai** của máy chủ. Copy toàn bộ một dòng kết quả; tạo repository
secret **`AZURE_SSH_KNOWN_HOSTS`**, dán dòng đó vào giá trị. Workflow dùng
`StrictHostKeyChecking=yes`, không tự tin tưởng kết quả quét mạng.

Không cần gửi nội dung hai secret cho người khác. Repo không thể đọc ngược giá trị
secret từ giao diện sau khi lưu; có thể cập nhật secret nếu cần.

### 3. Địa chỉ đã có mặc định

Không cần thêm các biến sau cho VM hiện tại. Khi chuyển máy/tên miền, thêm vào
tab **Variables** của Actions:

| Variable | Mặc định |
|---|---|
| `AZURE_HOST` | `20.195.25.203` |
| `AZURE_USER` | `azureuser` |
| `GAME_DOMAIN` | `smashprofs-thai.southeastasia.cloudapp.azure.com` |

Đổi host phải cập nhật known-hosts secret; đổi domain phải đổi Caddy và
`-Dweb.origins` trong service trên VM. Workflow dùng SSH cổng 22.

### 4. Kích hoạt

Sau khi workflow/source đã push và hai secret đã lưu, push commit tiếp theo vào
`feature/teavm-web-test`. Nếu workflow đã chạy trước khi thêm secret và job deploy
báo thiếu secret, vào **Actions → lần chạy đó → Re-run failed jobs**.

Build lỗi sẽ không đụng tới VM. Lần chạy thành công có cả job `build` và `deploy`
màu xanh, kèm link website ở Summary. Mở `/version.json` để xem SHA đang phát hành.
Workflow chưa chạy thực tế trên GitHub chỉ từ việc kiểm tra source ở máy cá nhân.

## Quay về bản trước bằng tay

Trên VM, xem bản hiện tại và các bản đã lưu:

```bash
readlink -f /srv/smashprofs/current
ls -1 /srv/smashprofs/releases
```

Đặt `previous` bằng đường dẫn một bản tốt đã liệt kê, rồi chạy lúc không có deploy:

```bash
previous=/srv/smashprofs/releases/TEN_BAN_TOT
sudo ln -s "$previous" /srv/smashprofs/current.manual-rollback
sudo mv -Tf /srv/smashprofs/current.manual-rollback /srv/smashprofs/current
sudo systemctl restart smashprofs
```

## Khi job thất bại

- Configure SSH: kiểm tra đủ hai secret; khóa riêng không có passphrase.
- SSH timeout: kiểm tra IP VM, NSG, UFW và quyền truy cập cổng 22 của runner.
- Host key verification failed: đối chiếu khóa host trên VM, cập nhật secret nếu
  đã thay máy; không dùng `StrictHostKeyChecking=no`.
- `sudo: a password is required`: tài khoản deploy chưa có quyền sudo không tương tác.
- Health check: đọc `sudo journalctl -u smashprofs -u caddy -n 100 --no-pager`.
  Kiểm tra domain, chứng chỉ, `web.origins` và service gateway cổng 8090.

Tham khảo: [GitHub Actions secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets),
[workflow concurrency](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency).
