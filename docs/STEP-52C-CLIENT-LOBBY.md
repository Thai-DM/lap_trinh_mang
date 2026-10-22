# Bước 52C: client lobby và GAME_START

Production client dùng `LobbyClient` với `NetworkClient`/`JsonFrameCodec` v1 của Bước 52B.
Không thay wire schema hoặc backend handlers. Bước này dừng tại GAME_START; chưa chơi online.

## Chạy desktop

Tại `smashprofs-integration/final-project`, đặt JAVA_HOME của tiến trình thành JDK 17.
Server thật phải đang chạy và cung cấp TCP port; không tự khởi động MockServer ở chế độ online.

```powershell
$env:JAVA_HOME='E:\2026-2\lap-trinh-mang\baitap'
$env:Path="$env:JAVA_HOME\bin;$env:Path"

# Client A: tạo room rồi JOIN riêng.
.\gradlew.bat :desktop:run --args="--server-host=127.0.0.1 --server-port=5555 --room=arena --create-room" --no-daemon

# Client B: chạy ở terminal khác, cùng endpoint/room.
.\gradlew.bat :desktop:run --args="--server-host=127.0.0.1 --server-port=5555 --room=arena --join-room" --no-daemon
```

5555 là ví dụ; thay bằng port thực của server. Với LAN, thay host bằng LAN IP của server.
Không có online arguments thì giữ offline; `--mock` giữ demo cũ. Không trộn --mock với online options.
Host mặc định 127.0.0.1; port, room và đúng một mode là bắt buộc.
Room giữ nguyên case, không trim; chỉ `[A-Za-z0-9_-]{1,64}`.

UI online chỉ chọn nhân vật local. Sau JOIN, chọn bằng A/D hoặc mũi tên trên màn hình và bấm Ready/Enter.
Khi READY được xác nhận, khóa lựa chọn và chờ server. GAME_START hợp lệ hiển thị roster 2–4 người,
đánh dấu You bằng server-issued PlayerId và hiển thị slot/character authoritative.
UI cập nhật trong `CharacterSelectScreen.render()`, không trong callbacks mạng.
Không mở gameplay online ở bước này.

## Flow và state

- Host: CONNECT → CONNECT_ACK → CREATE_ROOM → ROOM_CREATED → JOIN_ROOM → ROOM_STATE.
- Joiner: CONNECT → CONNECT_ACK → JOIN_ROOM → ROOM_STATE.
- READY: payload `{roomId, characterId, ready:true}`, requestId UUID mới; chỉ xác nhận qua correlated ROOM_STATE.
- READY bị từ chối: giữ roster/selection đã xác nhận, hiển thị code/message và cho phép người dùng thử lại rõ ràng.
- CREATE/JOIN lỗi: hiển thị lỗi, không auto retry hoặc chuyển sang mode khác.
- GAME_START: validate matchId/roomId/roster, uniqueness của ID/slot, character và local membership/slot/selection.
- Chỉ GAME_START hợp lệ đánh dấu STARTED; duplicate cùng roster không publish lại state/trận.
- CONNECT và heartbeat vẫn do NetworkClient quản lý. Không có PLAYER_INPUT/RTT ping cũ trong production lobby.
- Chỉ một pending lobby request, state guards chống callback/click lặp; không dùng requestId làm PlayerId.
- TCP connect và cleanup chạy trên workers; UI đọc immutable volatile Snapshot.

`ROOM_STATE` hiện chỉ trả cho JOIN/READY, không broadcast khi peer join/leave hoặc countdown thay đổi.
Lobby roster có thể là snapshot cũ cho tới response tiếp theo; GAME_START cung cấp roster authoritative cuối cùng.
JOIN vẫn giữ semantics v1 get-or-create phía server, nên joiner vào room chưa tồn tại có thể tạo room.

## Kiểm thử

Các targeted tests mới: `LobbyClientTcpIntegrationTest`, `LobbyConfigurationTest`, `GameStartRosterTest`.
Integration dùng GameServer thật, Socket thật, bind localhost/port 0, scheduler/clock production và countdown 3 giây.
Không dùng Thread.sleep, không thay simulation. Test cleanup đóng clients/server và executor quan sát điều kiện.
Kiểm tra explicit host JOIN, start với hai/bốn client, mapping slot 0–3, duplicate callbacks/messages,
outsider không nhận start, heartbeat lobby idle, ROOM_FULL/duplicate room/READY error và disconnect hủy countdown.

```powershell
.\gradlew.bat :tests:test --tests 'com.smashprofs.tests.network.*' :server:test --tests '*CreateRoomTcpTest' --tests '*GameStartTcpTest' --tests '*TcpProtocolV1Test' --tests '*TcpHeartbeatIntegrationTest' :core:build :desktop:build :tests:build --no-daemon --console=plain --continue
```

Kết quả cuối: 78 networking tests (bao gồm 15 tests 52C mới) và 59 targeted server tests PASS,
0 failure/error/skipped. Build core/desktop/tests PASS với JDK 17.0.12 / Gradle 7.3.3.
Các test 52C gồm 8 TCP integration, 4 configuration và 3 roster/parser tests.
Chưa chạy kiểm thử đồ họa bằng hai desktop windows hoặc LAN hai máy; compile UI không chứng minh visual behavior.

Git giữ branch `integration/member2-check`, HEAD/MERGE_HEAD và staged merge không đổi;
không commit/push. Không thay server source, Protocol v1, JsonFrameCodec, Player, physics/combat/TMX,
Hud, WinScreen hoặc RankingGateway so với trước 52C.

## Chưa triển khai

PLAYER_INPUT v1, GAME_STATE DTO/rendering, interpolation 60 Hz, projectile presentation,
GAME_END/WinScreen, ranking và LAN hai máy. Adapter gameplay cũ trong PlayScreen chỉ hoạt động
với `NetworkClient.forMock()`; không thay listener production lobby hoặc gửi input trước GAME_START.
