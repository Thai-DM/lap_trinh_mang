# Deploy server lên Azure VM Ubuntu

Bản server dùng TCP 54555, chạy trên VM Ubuntu với Java 17. Không cần giao diện desktop/OpenGL trên VM. Mỗi người chơi chạy client trên máy riêng và kết nối cùng IP VM.

## 1. Tạo VM trong Azure Portal

Tìm **Virtual machines → Create → Azure virtual machine**.

| Mục | Giá trị |
| --- | --- |
| Resource group | Tạo mới `smashprofs-rg` |
| Virtual machine name | `smashprofs-server` |
| Region | Region subscription cho phép, ưu tiên gần người chơi |
| Image | Ubuntu Server 22.04/24.04 LTS, x64 |
| Size | Một VM nhỏ B-series; ưu tiên RAM 2 GiB nếu hạn mức/credit cho phép |
| Administrator username | `azureuser` |
| Authentication | SSH public key; tải private key về máy |
| Public inbound ports | SSH 22; sau đó giới hạn nguồn SSH về IP máy quản trị |
| Public IP | Có Public IPv4 |

Xem giá trước **Review + create**. Azure không mặc định miễn phí mọi VM. Free account có ưu đãi theo thời gian/size/hạn mức; disk, public IP, bandwidth và dịch vụ phụ cần kiểm tra riêng trong subscription. Ảnh trang Resources không cho biết tài khoản còn ưu đãi hay không. Không cần cài thêm App Service, database hoặc load balancer cho một VM thử nghiệm.

Nguồn: [Tạo Linux VM](https://learn.microsoft.com/en-us/azure/virtual-machines/linux/quick-create-portal), [Hạn mức dịch vụ miễn phí](https://learn.microsoft.com/en-us/azure/cost-management-billing/manage/create-free-services).

## 2. Mở cổng game

Trong VM → **Networking → Network settings → Create port rule → Inbound port rule** (tên nút có thể khác nhẹ):

| Mục | Giá trị |
| --- | --- |
| Source | Any (người chơi từ Internet) |
| Source port ranges | `*` |
| Destination | Any |
| Destination port ranges | `54555` |
| Protocol | TCP |
| Action | Allow |
| Priority | `310`, nếu chưa được dùng |
| Name | `Allow-SmashProfs-TCP` |

HTTP quản trị `19200` chỉ nghe loopback; không tạo ingress cho cổng này. Public IPv4 của VM phải ổn định để các client dùng cùng địa chỉ.

Nguồn: [Kết nối SSH và kiểm tra NSG](https://learn.microsoft.com/en-us/azure/virtual-machines/linux-vm-connect), [Quản lý NSG](https://learn.microsoft.com/en-us/azure/virtual-network/manage-network-security-group).

## 3. Đóng gói ở máy Windows

Chạy tại thư mục gốc repository:

```powershell
.\scripts\deploy\Package-Server.ps1
```

Có thể truyền `-JavaHome 'DUONG_DAN_JDK17'`. Script build source mới qua Gradle rồi tạo ZIP trong `final-project\build\deploy`. Cần dependency Gradle đã tải sẵn vì build offline. ZIP có `lib/*.jar`, `config/public.properties`, launcher Bash và service systemd; không chứa private key, mật khẩu hay log cá nhân.

Lệnh bên dưới giả định bạn đổi tên/copy ZIP vừa tạo thành `smashprofs-server.zip` trên máy Windows. Thay `IP_VM` bằng Public IPv4 thật, thay đường dẫn key bằng key vừa tải:

```powershell
scp -i "$HOME\Downloads\smashprofs-server_key.pem" .\smashprofs-server.zip azureuser@IP_VM:/home/azureuser/
ssh -i "$HOME\Downloads\smashprofs-server_key.pem" azureuser@IP_VM
```

## 4. Cài và chạy trên VM

Trong terminal SSH Ubuntu:

```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jre-headless unzip
java -version
mkdir -p "$HOME/smashprofs-server"
unzip "$HOME/smashprofs-server.zip" -d "$HOME/smashprofs-server"
cd "$HOME/smashprofs-server"
bash start-server.sh
```

Phải thấy `STANDALONE_READY 54555`. Cấu hình bundle dùng `tcp.host=0.0.0.0`; địa chỉ client nhập là Public IP VM, không nhập `0.0.0.0`.

Nếu UFW đang bật (`sudo ufw status`), thêm rule trước khi thử từ máy ngoài:

```bash
sudo ufw allow OpenSSH
sudo ufw allow 54555/tcp
```

Không cần bật UFW nếu chưa dùng. Kiểm tra listener trong terminal SSH khác:

```bash
ss -lnt | grep -E '54555|19200'
curl --fail http://127.0.0.1:19200/metrics
```

Để server tiếp tục chạy khi đóng SSH, dừng bản chạy foreground bằng Ctrl+C rồi cài service:

```bash
sudo cp "$HOME/smashprofs-server/smashprofs.service" /etc/systemd/system/smashprofs.service
sudo systemctl daemon-reload
sudo systemctl enable --now smashprofs
sudo systemctl status smashprofs --no-pager
```

Service giả định username `azureuser`, thư mục `/home/azureuser/smashprofs-server`. Nếu chọn user khác, sửa `User`, `WorkingDirectory`, `ExecStart` trong service trước khi cài. Xem log bằng `sudo journalctl -u smashprofs -n 100 --no-pager`; dừng bằng `sudo systemctl stop smashprofs`.

## 5. Kết nối hai client khác mạng

Trên mỗi máy Windows:

```powershell
Test-NetConnection IP_VM -Port 54555
.\scripts\gameplay\Run-Client.ps1 -ServerHost IP_VM
```

`TcpTestSucceeded` phải là True. Nếu client đã build từ source hiện tại, thêm `-SkipBuild`. Có thể nhập IP VM và cổng `54555` trực tiếp trong giao diện thay cho tham số. Chỉ lưu địa chỉ sau khi kết nối/join thành công; lần mở sau không cần nhập lại.

Máy A tạo phòng, gửi mã 4 ký tự. Máy B nhập mã và vào phòng; cả hai chọn nhân vật, sẵn sàng. Cùng server hỗ trợ PvP và Boss.

Server chỉ có game và map cần cho physics; sprite/âm thanh vẫn nằm trong client. Không upload desktop client làm server. Luồng cluster nhiều worker có cấu hình riêng và chưa được đổi sang public bởi gói triển khai này.

## 6. Khi không kết nối được

- `TcpTestSucceeded=False`: kiểm tra Public IP, NSG TCP 54555, UFW và log service. Kiểm tra có `0.0.0.0:54555` trong `ss -lnt`; bản cũ chỉ nghe `127.0.0.1` sẽ không nhận Internet.
- `Address already in use`: một server khác đã chạy. Dừng foreground hoặc service đang có; chỉ giữ một instance trên cùng cổng.
- TCP thông nhưng không thấy phòng: hai client phải dùng đúng cùng host/cổng và cùng phiên bản. Mã phòng nằm trong RAM; restart server sẽ xóa phòng cũ.
- Không có quyền tạo VM/size không dùng được: kiểm tra subscription, quota và region trong Azure Portal.

## Kiểm chứng và giới hạn

Gói được kiểm tra build JAR, map trên classpath và bắt tay TCP với hai client trên cùng máy. Việc nghe wildcard được kiểm tra qua địa chỉ IPv4 không phải loopback khi máy kiểm tra có interface phù hợp. Launcher Ubuntu/systemd được chuẩn bị cho Java 17; chỉ xác nhận deploy Azure hoàn tất sau khi VM thật chạy và client ngoài mạng kết nối thành công. Chưa có VM/IP/SSH của người dùng trong phiên làm việc này.
