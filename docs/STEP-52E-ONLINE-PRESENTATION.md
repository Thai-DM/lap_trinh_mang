# Bước 52E — Online authoritative presentation

Đã triển khai presentation online, interpolation và GAME_END. Dừng trước 52F. Không sửa server runtime, Protocol v1, JsonFrameCodec, physics/combat, TMX, RankingGateway hoặc database trong bước này.

## File tạo/sửa trong 52E

Đường dẫn dưới đây tính từ `final-project/`.

Sửa:

- `core/src/com/smashprofs/game/Game.java`
- `core/src/com/smashprofs/game/Screens/CharacterSelectScreen.java`
- `core/src/com/smashprofs/game/Screens/PlayScreen.java`
- `core/src/com/smashprofs/game/network/client/StateInterpolator.java`
- `core/src/com/smashprofs/game/network/client/ClientMatchSession.java`
- `core/src/com/smashprofs/game/network/client/LobbyClient.java`
- `tests/build.gradle`
- `tests/src/com/smashprofs/server/game/ClientMatchTcpIntegrationTest.java`

Tạo:

- `core/src/com/smashprofs/game/Scenes/OnlineHud.java`
- `core/src/com/smashprofs/game/Screens/OnlinePlayScreen.java`
- `core/src/com/smashprofs/game/Screens/OnlineResultScreen.java`
- `core/src/com/smashprofs/game/Screens/OnlineSpriteAssets.java`
- `core/src/com/smashprofs/game/network/client/OnlineMatchPresentation.java`
- `core/src/com/smashprofs/game/network/client/OnlineVisualCatalog.java`
- `core/src/com/smashprofs/game/network/client/WireMatchResult.java`
- `tests/src/com/smashprofs/game/Screens/OnlinePlayScreenBoundaryTest.java`
- `tests/src/com/smashprofs/game/network/client/OnlinePresentationTest.java`
- `tests/src/com/smashprofs/tests/tools/LocalGameServer.java`
- Báo cáo `docs/STEP-52E-ONLINE-PRESENTATION.md` tại gốc worktree.

Các file untracked từ 52C/52D vẫn được giữ nguyên trạng thái Git; danh sách trên chỉ ghi thay đổi của 52E.

## Kiến trúc và mapping

`CharacterSelectScreen` chuyển sang `PlayScreen` một lần sau GAME_START hợp lệ. Nhánh online của PlayScreen delegate sang OnlinePlayScreen và return trước khi tạo PlayerFactory, local World/combat/Hud/WinScreen. Online checkInput/update/render không chạy Player.update, CombatManager.update, World.step hoặc Hud.testWin. Nhánh offline giữ luồng gameplay hiện có; legacy mock adapter/interpolator được giữ lại.

OnlineMatchPresentation đọc snapshots immutable qua buffer thread-safe. Roster 2–4 được đối chiếu đầy đủ theo playerId/slotIndex/characterId; render theo slotIndex, local identity lấy từ CONNECT_ACK, không giả định slot 0. Sprite dùng asset gốc Alex/Luca/Leo/Viktor/Maurice; camera bao quát roster. Không vẽ spawn tự đặt trước snapshot đầu tiên. HP float, facing, grounded, blocking/dead lấy từ server; chỉ position được nội suy. GAME_START lặp không tạo session/actor mới.

OnlinePlayScreen thu phím A/D held, W jump edge, S stomp edge, V/Space melee edge, F ability edge, Left Ctrl block held rồi gọi LobbyClient/ClientMatchSession hiện có. Đã bỏ sender keyboard thứ hai ở Game.render. Input chỉ gửi trong match đang chạy, payload không có playerId; clientTick dùng thời gian monotonic theo 60 Hz. Queue/edge retention của 52D được giữ lại. Socket read/write chạy reader/writer thread.

## Interpolation và projectile

StateInterpolator.V1 dùng serverTick 60 Hz, delay mặc định 6 ticks (100 ms), cấu hình được. Render tick tiến theo monotonic clock và clamp trong lịch sử đã nhận; không đo tick bằng số frame, không extrapolate khi mất snapshot. Tick 3 → 6 là cadence broadcast bình thường. Nội suy hai snapshots; không ghi ngược vào authoritative data. Discontinuity vị trí lớn hơn ngưỡng mặc định 1 m hoặc thay đổi dead/life được snap; FINISHED hiển thị final state ngay.

Projectile mapping theo projectileId, tạo/cập nhật/xóa visual theo membership của snapshot mới nhất. Dùng texture IBM/STAR/MINE/ROCKET gốc, không tạo local projectile gameplay/body/damage. Rocket direction lấy từ velocity server. Schema hiện không có animation tấn công, body angle/spin, explosion hoặc target; không tự dựng các giá trị đó. Player dùng animation đứng/chạy/trên không hiện có.

## HUD, kết quả, thread và cleanup

OnlineHud riêng hiển thị 2–4 players, slot/character/identity YOU, HP float, BLOCK/DEAD, phase và remainingTimeMs từ snapshot mới nhất. Không chạy countdown để quyết định thắng. Connection loss/error hiển thị từ LobbyClient; giữ hình cuối, ngừng input, không đoán forfeit. Không hiển thị RTT giả.

WireMatchResult parse đúng matchId, roomId, finishedAtTick, winnerId nullable, finishReason, participants. ClientMatchSession xác minh roster và final FINISHED snapshot/tick/HP/dead trước khi lưu immutable result và dừng input. Duplicate giống nhau không tạo transition mới; dữ liệu mâu thuẫn bị từ chối. winnerId null hiển thị DRAW; winner khác lấy đúng roster. OnlineResultScreen dùng asset kết quả gốc, không chờ RankingGateway. WinScreen offline không đổi trong 52E.

Network thread chỉ decode/publish model. Sprite, Texture, Stage và Screen chỉ tạo/cập nhật/dispose trên render thread. Presentation đóng không đóng session/TCP đang do Game/LobbyClient sở hữu; transition result chỉ một lần, screen đã đóng không nhận transition muộn. Screen dispose tài nguyên của mình; Game chịu trách nhiệm shutdown network. Online không khởi chạy luồng ghi thống kê offline vào game_info.properties.

## Kết quả kiểm thử và build

JDK 17.0.12 tại `E:\2026-2\lap-trinh-mang\baitap`. Không chạy toàn bộ suite.

| Nhóm targeted | Tests | Failure/error/skip |
|---|---:|---:|
| Client/network/mock/lobby/input/presentation + TCP match | 106 | 0/0/0 |
| Server TCP room/start/state/end/heartbeat/protocol/end-to-end | 96 | 0/0/0 |
| Tổng unique | **202** | **0/0/0** |

11 presentation tests kiểm tra mapping 2/3/4, local slot khác 0, HP 92.75, interpolation/time/delay/starvation, stale/duplicate, teleport/death/revival, projectile membership, result winner/draw/schema/identity/duplicate/lifecycle và asset gốc. Một boundary test dùng headless cùng GameServer/TCP thật chứng minh nhánh online không gọi local gameplay; test này không chứng minh hình ảnh OpenGL.

9 ClientMatchTcpIntegrationTest dùng GameServer và production NetworkClient thật trên localhost/port 0, gồm full CONNECT → room → ready → GAME_START → input/state → GAME_END, kết quả draw hai người và winner bốn người ở slot 3, HP float và dừng input sau end. Scheduler/clock injection có sẵn điều khiển countdown/simulation; transport và heartbeat thật. Các regression mock/network/lobby của bước trước PASS.

Build `:core:build :desktop:build :tests:build`: **PASS**. Sau thay đổi cuối (marker YOU trên nhân vật), chạy lại 106 client tests và cả ba builds: PASS. Server source không đổi; 96 server tests đã PASS trong lượt trước. Warnings hiện có: VFXObject unchecked, Gradle deprecated, RankingGateway chưa cấu hình khi trận kết thúc; không có test failure.

Lệnh targeted đã chạy từ `final-project` (bỏ các task chỉ ghi classpath phục vụ smoke):

```powershell
$env:JAVA_HOME='E:\2026-2\lap-trinh-mang\baitap'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :tests:test --tests 'com.smashprofs.tests.network.*' --tests '*ClientMatchTcpIntegrationTest' --tests '*OnlinePresentationTest' --tests '*OnlinePlayScreenBoundaryTest' :server:test --tests '*CreateRoomTcpTest' --tests '*GameStartTcpTest' --tests '*GameStateTcpBroadcastTest' --tests '*GameEndTcpDeliveryTest' --tests '*TcpHeartbeatIntegrationTest' --tests '*TcpProtocolV1Test' --tests '*TwoClientTcpMatchEndToEndTest' :core:build :desktop:build :tests:build --no-daemon --console=plain --continue
```

## Smoke có cửa sổ thật và cách chạy lại

Đã mở hai desktop libGDX và GameServer thật: cùng lobby, ready/countdown, map gốc, hai sprite Leo/Viktor, HUD HP 100.0, local YOU đúng hai slot và trạng thái snapshot. Hai cửa sổ nhận TIME_LIMIT/DRAW với đúng participants sau hết giờ. Không có exception rendering trong lượt này. Screenshot tại thư mục ignored `final-project/tests/build/52e-smoke/`: `a-start.png`, `a-draw.png`, `b-draw.png`.

**Kiểm chứng GUI còn một phần:** thao tác movement/jump/stomp/melee/ability/block và projectile chưa được xác nhận bằng cửa sổ thật; thao tác thử sau khi trận đã hết không được tính PASS. Marker YOU trên actor thêm cuối đã build/test nhưng chưa xem lại trực quan. Lượt thử tiếp theo gặp connection refused do tiến trình server đã đóng. Theo yêu cầu chốt nhanh, đã dừng thử GUI thêm; tất cả tiến trình smoke được kiểm tra đã đóng. Không dùng headless tests để tuyên bố phần GUI này PASS.

Để chạy lại, mở ba terminal tại `final-project`, đặt JAVA_HOME/Path như trên ở mỗi terminal. Terminal server giữ chạy:

```powershell
.\gradlew.bat :tests:runLocalServer -Pport=54555 --console=plain
```

Terminal client A:

```powershell
.\gradlew.bat :desktop:run --args="--server-host=127.0.0.1 --server-port=54555 --room=demo52e --create-room" --console=plain
```

Sau khi A báo joined, chạy client B:

```powershell
.\gradlew.bat :desktop:run --args="--server-host=127.0.0.1 --server-port=54555 --room=demo52e --join-room" --console=plain
```

A/D chọn nhân vật ở lobby; Enter ready mỗi cửa sổ. Khi match bắt đầu dùng phím gameplay đã liệt kê. F9 ngắt kết nối để xem trạng thái lỗi; Esc thoát. Helper chỉ gọi public GameServer API, không đổi server runtime.

## Giới hạn trước demo LAN và Git

Chưa kiểm chứng LAN, visual bốn cửa sổ, độ mượt/performance, toàn bộ phím gameplay/projectile trong GUI hoặc offline GUI. Server hiện không tự forfeit player disconnect trong match; không thêm reconnect/prediction/reconciliation/ranking trong bước này. Các trường visual thiếu trong schema cần được xác định riêng nếu muốn mở rộng; không sửa contract trong 52E.

Branch giữ `integration/member2-check`; HEAD `8c739f98cd9e52028897d82ef42f434ef3b93cd7`, MERGE_HEAD `9dcd8b4735d94ee5bc4d0091e1a267049eed4ad1`. Đối chiếu staged object list không thay đổi. Không commit/push/stage/reset/clean/restore/checkout; không sửa feature/server-runtime/origin/dev. Artifacts từ build và thay đổi có sẵn được giữ lại, không stage. `git diff --check` sạch. **Dừng tại 52E, chưa triển khai 52F.**
