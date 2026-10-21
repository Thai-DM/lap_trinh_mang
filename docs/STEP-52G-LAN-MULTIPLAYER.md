# 52G — LAN readiness và smoke hai máy

**Readiness đã kiểm tra trên Máy A; TWO-PHYSICAL-MACHINE SMOKE: PENDING MANUAL TEST.** Người dùng xác nhận chưa có Máy B. Không dùng kết nối qua IP của chính máy này để tuyên bố LAN hai máy PASS. Dừng ở 52G.

## Thay đổi tối thiểu

- Sửa `final-project/tests/src/com/smashprofs/tests/tools/LocalGameServer.java`: argument `[port] [bindHost]`, default 54555/127.0.0.1, kiểm tra cấu hình, log resolved host/actual bound port; nhận `stop` + Enter và shutdown hook Ctrl+C. Stdin EOF vẫn giữ server chạy, không busy loop.
- Sửa `final-project/tests/build.gradle`: thêm opt-in `-PbindHost` và `standardInput = System.in` cho task hiện có.
- Tạo `final-project/tests/src/com/smashprofs/tests/tools/LocalGameServerTest.java`: launcher process/TCP/heartbeat/shutdown regression.
- Tạo `scripts/Package-LanWorkingTree.ps1`: đóng gói working tree hiện tại, SHA-256 manifest và archive checksum.
- Tạo báo cáo này. ZIP/checksum nằm trong `final-project/build/lan52g/` ignored.

GameServer có constructor bindHost/bindPort; TcpServerTransport dùng `ServerSocket.bind(new InetSocketAddress(bindHost, bindPort))`. Default GameServer vẫn 127.0.0.1/port 0; không sửa server runtime/transport/TcpIoConfiguration, client, gameplay, physics/combat/TMX hoặc Protocol v1. TcpIoConfiguration là codec/dispatcher/frame-limit/deadline, không phải nơi cấu hình interface. Chỉ launcher 52F trước đây cố định loopback; nay LAN được chọn tường minh.

DesktopLauncher đã nhận `--server-host`, `--server-port`, `--room`, create/join; giữ nguyên đường production JsonFrameCodec/v1. Không cần RankingService/database để render hoặc kết thúc multiplayer; RankingGateway chưa cấu hình vẫn có warning như trước.

## Chuẩn bị hai Windows machines

1. Dùng cùng ZIP working tree mới nhất trên A/B, kiểm tra checksum/manifest như bên dưới. Không checkout origin/dev để lấy code chưa commit.
2. Cả hai có **JDK 17**, GPU/driver chạy được desktop libGDX. Tại `final-project`, mỗi terminal đặt JAVA_HOME đúng đường dẫn JDK của máy mình và kiểm tra:

```powershell
$env:JAVA_HOME='DUONG_DAN_JDK_17_TREN_MAY_NAY'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
java -version
```

3. Lần build đầu trên B cần truy cập repositories/dependencies Gradle. ZIP chứa Gradle wrapper và source/assets; không chứa JDK hoặc Gradle cache.
4. Hai máy trên mạng LAN tin cậy, tránh guest Wi-Fi/client isolation. Máy A chọn IPv4 của Wi-Fi/Ethernet đang kết nối router, không chọn VMware/WSL/VPN/loopback. Cùng SSID không bảo đảm được kết nối ngang nhau.

Lấy IP và profile trên A:

```powershell
Get-NetIPConfiguration | Select-Object InterfaceAlias,IPv4Address,IPv4DefaultGateway
Get-NetConnectionProfile | Select-Object InterfaceAlias,NetworkCategory
```

Tại lần kiểm tra này, Máy A có Wi-Fi IPv4 **192.168.1.3**, gateway 192.168.1.1, profile **Private**. VMware/WSL có các IP khác không dùng cho demo Wi-Fi. IP có thể đổi bởi DHCP; lấy lại lúc chạy. Không hardcode IP này trong source.

## Firewall — chỉ người dùng thao tác nếu cần

**Không thay đổi firewall trong 52G.** Chỉ đọc trạng thái: Domain/Private/Public đều Enabled ở Máy A. Nếu network đang Public, người dùng chỉ chuyển sang Private khi xác nhận đó là LAN tin cậy; không mở rule Public để thử vượt lỗi kết nối.

Nếu B không truy cập được TCP dù bind đúng, mở PowerShell Administrator trên A và tự chạy rule hẹp cho Private + LocalSubnet + port 54555. Thay địa chỉ dưới đây bằng IP A vừa kiểm tra:

```powershell
$ipA='IP_LAN_THUC_CUA_A'
New-NetFirewallRule -DisplayName 'SmashProfs 52G TCP 54555' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 54555 -LocalAddress $ipA -RemoteAddress LocalSubnet -Profile Private
```

Có thể thay LocalSubnet bằng IP B để giới hạn một máy. Không mở UDP, port range hoặc tắt firewall. Nếu thay port, rule/client/server phải cùng port. Kiểm tra rule khi cần:

```powershell
Get-NetFirewallRule -DisplayName 'SmashProfs 52G TCP 54555' | Get-NetFirewallPortFilter
Get-NetFirewallRule -DisplayName 'SmashProfs 52G TCP 54555' | Get-NetFirewallAddressFilter
```

Sau demo, người dùng có thể gỡ đúng rule mình đã tạo:

```powershell
Remove-NetFirewallRule -DisplayName 'SmashProfs 52G TCP 54555'
```

## Lệnh chạy thực tế

Các lệnh dưới đây chạy từ `final-project`, JDK 17 đã đặt ở mỗi terminal. Quote toàn bộ `-PbindHost=...` trong PowerShell Windows để giữ nguyên địa chỉ có dấu chấm.

Máy A, terminal server — ưu tiên bind riêng IP LAN đã chọn:

```powershell
$ipA='IP_LAN_THUC_CUA_A'
.\gradlew.bat :tests:runLocalServer -Pport=54555 "-PbindHost=$ipA" --console=plain
```

Hoặc chủ động mở listener tất cả interfaces IPv4 cho LAN:

```powershell
.\gradlew.bat :tests:runLocalServer -Pport=54555 '-PbindHost=0.0.0.0' --console=plain
```

Log phải có `Local GameServer listening on <resolved host>:54555 (configured host=...; type stop + Enter or Ctrl+C to stop)`. 0.0.0.0 là bind wildcard, **không dùng làm destination client**. Java/Windows dual-stack có thể báo listener `::`; lần chạy thực tế đã kết nối IPv4 thành công.

Giữ terminal server chạy trong suốt lượt. Nhập **stop** rồi Enter để shutdown sạch; Ctrl+C có shutdown hook nhưng Gradle có thể báo task interrupted. Lệnh stop đã kiểm tra exit 0 và giải phóng listener. Không dùng Stop-Process làm cách shutdown thông thường.

Kiểm tra listener trên A:

```powershell
Get-NetTCPConnection -LocalPort 54555 -State Listen | Select-Object LocalAddress,LocalPort,OwningProcess
```

Máy A, terminal Client A (dùng cùng LAN IP nếu server chỉ bind IP đó):

```powershell
$ipA='IP_LAN_THUC_CUA_A'
.\gradlew.bat :desktop:run --args="--server-host=$ipA --server-port=54555 --room=demo52g --create-room" --console=plain
```

Chờ A hiện **Joined demo52g**. Client tự CONNECT, CREATE_ROOM rồi JOIN_ROOM riêng theo v1; creator không tự được server join.

Máy B kiểm tra TCP trước:

```powershell
$serverIP='IP_LAN_THUC_CUA_A'
Test-NetConnection -ComputerName $serverIP -Port 54555 -InformationLevel Detailed
```

Chỉ tiếp tục khi `TcpTestSucceeded=True`; ping ICMP có thể thất bại dù TCP dùng được. Máy B, terminal Client B:

```powershell
.\gradlew.bat :desktop:run --args="--server-host=$serverIP --server-port=54555 --room=demo52g --join-room" --console=plain
```

Không dùng 127.0.0.1 ở B. Chọn Viktor trên một client để thử IBM hoặc Leo để thử mine, rồi Enter Ready ở cả hai. Thao tác ngay sau PLAYING, trước timeout 120 s. A/D move, W jump, S stomp, V/Space melee, F ability, Left Ctrl block. Esc thoát. F9 chỉ thử trong một lượt riêng mới đang PLAYING; sau trận dùng room ID mới.

## Chuyển working tree cho B

Tại gốc `smashprofs-integration` trên A:

```powershell
& .\scripts\Package-LanWorkingTree.ps1
```

Script tạo ZIP timestamp riêng + `.sha256.txt` tại `final-project/build/lan52g/`. Đọc filesystem nên bao gồm staged/unstaged/untracked source 52B–52G cùng assets; không dùng git archive. Loại `.git`, `.gradle`, build/bin/out/dist, IDE metadata, logs/temp/class, local.properties/.env, reparse points. Đóng gói `final-project/`, `docs/`, `scripts/`; không đóng gói thư mục cấu hình cá nhân ở gốc worktree. Không xóa/copy đè source trong A.

Copy ZIP và checksum qua USB hoặc kênh của người dùng sang B, giải nén vào thư mục mới riêng, ví dụ `C:\smashprofs-52g`. Đây là bản chạy thử không có Git metadata; không đặt chồng lên repo khác. So SHA-256 ZIP với file checksum trước khi giải nén:

```powershell
Get-FileHash -LiteralPath 'DUONG_DAN_FILE_ZIP' -Algorithm SHA256
```

Từ thư mục vừa giải nén chứa `LAN-PACKAGE-MANIFEST.json`, kiểm tra mọi file:

```powershell
$packageRoot=(Get-Location).Path
$manifest=Get-Content -LiteralPath LAN-PACKAGE-MANIFEST.json -Raw | ConvertFrom-Json
foreach($entry in $manifest.files) {
    $actual=(Get-FileHash -LiteralPath (Join-Path $packageRoot $entry.path) -Algorithm SHA256).Hash
    if($actual -ne $entry.sha256) { throw "Package mismatch: $($entry.path)" }
}
'Manifest PASS'
Set-Location final-project
```

Cả A/B dùng cùng bản source và Protocol v1 (`version=1`, length-prefixed UTF-8 JSON, requestId, payload object). Nếu A tiếp tục sửa code sau đóng gói, tạo ZIP mới và chuyển lại trước khi so gameplay. Không cần commit/push/merge để chuẩn bị copy này.

## Kết quả kiểm thử thật

| Kiểm tra readiness trên A | Kết quả |
|---|---|
| Mặc định launcher loopback/54555; tests loopback/port 0 giữ nguyên | PASS |
| Chọn bind wildcard tường minh, log actual port | PASS |
| Chính task Gradle wildcard/54555, TCP vào IP Wi-Fi A | PASS trên cùng máy; listener PID 28864, Windows báo ::/54555 |
| Hai NetworkClient production CONNECT qua Wi-Fi IP của chính A, IDs riêng | PASS trong launcher process/socket test |
| Idle nhận hai heartbeat server với auto PONG; đóng A rồi B vẫn PING/PONG | PASS trong targeted test, không phải hai máy |
| stop + Enter task Gradle | PASS, exit 0 / BUILD SUCCESSFUL |
| Shutdown launcher test giải phóng listener/worker | PASS |
| Firewall mới / truy cập từ B | UNTESTED; không sửa rule |
| ZIP working tree và manifest | Gói được tạo/kiểm tra ở bước hoàn tất; checksum đi kèm ZIP |
| FPS/RTT/latency, starvation/stutter định lượng | UNTESTED; snapshots/s không phải FPS/RTT |

Targeted JDK 17:

```powershell
.\gradlew.bat :tests:test --tests '*LocalGameServerTest' --tests '*NetworkClientProtocolV1Test' :core:build :desktop:build :tests:build --console=plain --no-daemon
```

**11 tests PASS (3 launcher + 8 NetworkClientProtocolV1), 0 failure/error/skip; build core/desktop/tests PASS, 33 s.** Wildcard test chọn IP Wi-Fi qua env `SMASHPROFS_SMOKE_HOST`; output xác nhận same-machine IP 192.168.1.3. Không chạy full suite/GUI mới trong 52G.

Lượt đầu test heartbeat đặt wait 8 s và thất bại: maintenance mặc định mỗi 5 s, CONNECT vừa sau pass có thể chờ đến pass 10 s. Sửa deadline test thành 12 s, giữ server policy nguyên; lượt sau PASS. Không phải lỗi LAN/protocol. Task command không quote `-PbindHost=0.0.0.0` bị PowerShell tách `.0.0.0` thành task; quote toàn argument đã chạy thành công. Không sửa gameplay hoặc contract để che lỗi.

## Checklist hai máy — PENDING MANUAL TEST

Tất cả 15 mục dưới đây **PENDING MANUAL TEST** cho hai máy vật lý; kết quả localhost 52F không được tính LAN PASS. Ghi ảnh/log từ cả A/B và timestamp/phòng/nhân vật:

1. Server A listen đúng IP/54555, log bound host/port.
2. B Test-NetConnection TcpTestSucceeded=True.
3. A CONNECT, PlayerId do server cấp.
4. B CONNECT qua IP LAN A, PlayerId khác.
5. A CREATE_ROOM demo52g thành công.
6. Cả hai JOIN_ROOM cùng demo52g.
7. Cả hai Ready → cùng GAME_START/roster, YOU đúng slot.
8. Input hai cửa sổ độc lập.
9. A/B movement/jump đều thấy từ hai máy.
10. Melee ở khoảng cách gần làm HP giảm giống nhau.
11. Ability có projectile: cả hai thấy xuất hiện và biến mất.
12. HP float authoritative giống nhau, không ép integer.
13. Cùng GAME_END winner; lượt đứng yên mới cho DRAW.
14. Đứng yên ít nhất 30 s vẫn Connected/snapshots và không heartbeat timeout.
15. Lượt riêng F9 A trong PLAYING: A Disconnected, B/server còn sống; không tự forfeit.

HUD snapshots/s có thể ghi đúng tên đó. Không có FPS/RTT meter đo hợp lệ trong bước này, không ghi số đo suy đoán. Ảnh tĩnh không chứng minh stutter hoặc leak dài hạn.

## Phân loại lỗi và bàn giao Git

- Bind lỗi Address already in use: xác định PID đang dùng port; đổi cùng port server/client/firewall hoặc người dùng đóng đúng tiến trình. Không kill ngẫu nhiên.
- Bind IP không tồn tại/Can't assign requested address: lấy lại IP của A; không dùng IP B/adapter đã disconnect.
- TCP B thất bại trong khi listener đúng: kiểm tra IP/profile/rule/route và guest Wi-Fi isolation; chưa kết luận protocol/gameplay lỗi.
- TCP thành công nhưng CONNECT ERROR: kiểm tra log v1/client copy/port đúng server, không dùng mock/legacy JSON.
- INVALID_ROOM_STATE/room missing: chờ creator Joined, dùng cùng room ID và phòng mới cho lượt mới.
- GAME_START có nhưng không input/state: thu log và ảnh phase/error/snapshots, kiểm tra phiên bản code giống nhau; không sửa physics để che vấn đề mạng.

Branch giữ `integration/member2-check`, staged merge + HEAD/MERGE_HEAD không thay đổi. Không commit/push/merge/reset/clean/restore/stage, không sửa feature/server-runtime/origin/dev. Các file untracked/thay đổi/artifacts có sẵn giữ nguyên. Giải nén ZIP trên B không giải quyết merge của A; việc review/stage/resolve/commit sau demo là nhiệm vụ riêng của người dùng. **52G readiness hoàn tất; smoke hai máy còn PENDING MANUAL TEST theo xác nhận chưa có Máy B.**
