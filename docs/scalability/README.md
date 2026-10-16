# Prototype server phân tán

Đây là prototype chạy trên một máy, có control plane và các GameServer ở JVM độc lập. Mục tiêu 100M CCU là mô hình thiết kế; chưa có kiểm thử chứng minh tải đó.

## Chạy trên Windows

Yêu cầu JDK 17, dependencies Gradle đã cache (hoặc bỏ `--offline` khi tải lần đầu), Python 3.9+ cho benchmark. Java mặc định của máy kiểm thử là Java 8; cần chọn JDK 17 rõ ràng.

```powershell
# Từ thư mục gốc repository. Thay đường dẫn JDK theo máy.
.\scripts\scalability\Start-Cluster.ps1 -JavaHome 'E:\2026-2\lap-trinh-mang\baitap'
Invoke-RestMethod http://127.0.0.1:8080/metrics | ConvertTo-Json -Depth 8
.\scripts\scalability\Stop-Cluster.ps1
```

`config/scalability/local.properties` chọn số worker, cổng, room/connection limit, snapshot mode và thuật toán. Mặc định: control 8080; TCP 9001–9003; admin 9101–9103; 3 JVM worker. Mỗi worker có file cấu hình và log riêng trong `final-project/build/scalability/workers/`. Secret phát sinh lúc chạy được truyền qua môi trường, lưu trong thư mục build để script stop sử dụng. HTTP và TCP cluster chỉ bind loopback.

Linux: `JAVA_HOME=/path/to/jdk17 sh scripts/scalability/start-cluster.sh`; dừng bằng `sh scripts/scalability/stop-cluster.sh`. Script Linux dùng cấu hình cổng mặc định và cần `curl`; chưa chạy trên Linux trong phiên này.

## Client desktop và bot

```powershell
cd final-project
$env:JAVA_HOME = 'E:\2026-2\lap-trinh-mang\baitap'
.\gradlew.bat :desktop:run --args='--control-url=http://127.0.0.1:8080 --create-room'
# Console in RoomId do control plane cấp. Dùng RoomId đó cho client thứ hai:
.\gradlew.bat :desktop:run --args='--control-url=http://127.0.0.1:8080 --join-room --room=r_ID'

# Bot thật: 16 client, warmup tới GAME_START, rồi đo 12 giây.
& "$env:JAVA_HOME\bin\java.exe" -Xmx512m -cp 'build\cluster\*' com.smashprofs.server.cluster.ClusterMain load http://127.0.0.1:8080 16 12 build/bots.json
```

Client cũ dùng `--server-host`, `--server-port`, `--room` vẫn hoạt động với server standalone. Listener worker yêu cầu capability `cluster-v1` và ticket; client cũ không có ticket không vào được worker. Desktop được compile và test boundary headless; chưa kiểm tra cửa sổ đồ họa bằng mắt trong môi trường này.

## Benchmark và kiểm thử

```powershell
# Từ final-project; chỉ các test thuộc boundary đã thay đổi và tích hợp cần thiết.
.\gradlew.bat :server:test --tests 'com.smashprofs.server.cluster.*' --offline --no-daemon
.\gradlew.bat :tests:test --tests 'com.smashprofs.tests.network.ClusterLobbyIntegrationTest' --offline --no-daemon

# Từ gốc repository, sau :server:installCluster.
python scripts/scalability/benchmark.py --java-home 'E:\2026-2\lap-trinh-mang\baitap' --max-clients 100 --seconds 12 --repeats 2
```

Runner dùng JVM mới cho mỗi điểm đo, tắt autoscaling để so sánh cố định, lưu JSON và CSV trong `results/`, và shutdown các tiến trình đã tạo. Chỉ chạy một benchmark runner tại một thời điểm vì cổng thử nghiệm cố định 18080/19000/19100/19200. Giới hạn 100 bot mặc định là lựa chọn an toàn; nâng bằng `--max-clients`, runner sẽ dừng tăng tải nếu lỗi hoặc thiếu RAM. Thử nghiệm ngắn chỉ kiểm chứng chức năng/thông lượng ngắn hạn.

Khi Gradle/test JVM cần quá nhiều RAM nhưng dependency JAR đã được đóng gói, có thể chạy `scripts/scalability/Verify-LowMemory.ps1 -JavaHome ...` từ gốc repo. Script compile source và các server tests bằng javac128MiB, chạy tập unit/TCP regression liên quan trong một JVM128MiB, lưu log dưới build/scalability/verification và cập nhật runtime JAR. Cần `rg`, JDK17 và JUnit đã có trong Gradle cache. Script không chạy benchmark hay test3worker.

## HTTP API local

| Endpoint POST | Credential | Nội dung |
|---|---|---|
| `/auth/guest` | không | Trả token, PlayerId, SessionId, expiry |
| `/rooms/create` | token trong `Authorization` | `{ "region": "local" }` → assignment |
| `/rooms/join` | token | `{ "roomId": "r_..." }` → assignment |
| `/matchmake` | token | `{ "region": "local", "size": 2..4 }` |
| `/assignment` | token | Poll QUEUED/IDLE/ASSIGNED |
| `/matchmake/cancel` | token | Hủy hàng đợi chưa được ghép |
| `/workers/heartbeat`, `/claim` | cluster secret | Nội bộ worker |
| `/workers/drain` | cluster secret | Ngừng cấp phòng mới cho workerId |
| `/metrics` | không; hỗ trợ GET | Tình trạng local, không có token/ticket |
| `/admin/shutdown` | cluster secret | Drain cluster rồi kết thúc có giới hạn thời gian |

Worker admin: `/metrics`; `/drain`, `/shutdown`, `/history` cần secret. `/shutdown` từ chối worker còn phòng. `/history` nhận `playerId`, `limit` 1–100; là API quản trị, không phải API tài khoản công khai.

Đọc tiếp: [audit](01-CURRENT-ARCHITECTURE.md), [kiến trúc đích](02-TARGET-ARCHITECTURE-100M-CCU.md), [10 chức năng](03-TEN-SERVER-FUNCTIONS.md), [benchmark](08-LOAD-TEST-RESULTS.md), [capacity](09-CAPACITY-PLANNING.md).
