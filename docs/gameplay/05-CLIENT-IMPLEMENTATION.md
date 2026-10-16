# 05 — Client và giao diện

Online form Create/Join tự bật gameplay-v1. Luồng room mới: OnlineLobbyScreen → HostLobbySettingsScreen → CharacterSelectScreen → PlayScreen online branch → OnlineResultScreen. Host chỉnh draft Max Players/Time/Lives/Drop, Apply gửi request; màn hình luôn phân biệt draft với luật server đang áp dụng. Non-host xem player count/rules nhưng controls disabled. Character select có nút quay về Room rules. Offline/mock flow không tạo GameplayMatch hay dùng các screen/packet bổ sung này.

LobbyClient lưu settings immutable và xử lý correlated response, membership push, rules revision. Revision trong READY ACK ngăn settings đến muộn làm mất trạng thái ready. ClientMatchSession vẫn là một owner cho input buffer/snapshot/result; progress inactive ngừng record/flush input. Spectator vẫn nhận snapshot và reliable event. Server độc lập từ chối điều khiển inactive; input hợp lệ đang trên đường truyền khi chết được bỏ qua để không tạo ERROR storm.

WireGameSnapshot/WireMatchResult giữ constructors và schema legacy, chỉ parse extension khi có gameplay. Snapshot interpolation chỉ nội suy tọa độ; lives, timer, shield, equipped weapon và statistics đọc trực tiếp authoritative state. Result phải bằng final snapshot về HP, elimination và toàn bộ gameplay stats.

Online HUD hiển thị lives/status, respawn countdown, invulnerability, weapon/shield và `[F: READY!]` hoặc số giây ceil(ticks/60); inactive hiển thị LOCKED. Readiness đổi màu, có sound cue khi chuyển ready; Sound thuộc screen và dispose cùng screen. Player invulnerable nhấp nháy theo timer server. Wall slide/jump tái sử dụng air sprites với rotation/tint; không thêm bộ art mới. Items được vẽ có màu/nhãn type; shotgun/laser/rocket/grenade/orbit/explosion có render từ shot state server. Không có client-side damage, pickup, respawn hoặc chiến thắng.

Spectator có banner đỏ `NO LIVES LEFT - SPECTATING`, camera bỏ các player inactive khi còn người active để theo dõi trận. KillFeedModel nhận sequence reliable, bỏ duplicate, phát hiện gap, giữ tối đa 12 dòng, hết hạn sau 4 s, fade trong giây cuối. WEAPON_KILL không tạo thêm một dòng chết trùng với PLAYER_KILLED/FELL đã mang weapon.

RTT HUD dùng RTT_STATE do server đo từ PING writer đến PONG, không trừ wall clocks hai máy. Hiển thị unknown trước sample đầu, disconnected khi peer mất kết nối, Lag khi RTT >100 ms. Đây là application heartbeat RTT (gồm encode/OS/network/PONG handling), không phải one-way ping hoặc input-to-state latency. Report giới hạn 1 Hz; heartbeat policy/nonce/timeout gốc được giữ.

Result screen dùng winner server làm Champion; hiển thị damage, all-item/weapon pickups, K.O., deaths, remaining lives, duration và shared MVP awards. Nội dung dài có ScrollPane. **Chưa thực hiện manual GUI/audio smoke test** trong môi trường hiện tại. Core/desktop source được javac compile, client models và TCP flow được kiểm thử; không thay việc này bằng tuyên bố đã xem GUI.

## Chạy thực tế

JDK 17 và cached dependencies như dự án hiện tại. Từ repo root:

```powershell
.\scripts\gameplay\Verify-Gameplay.ps1 -JavaHome 'E:\2026-2\lap-trinh-mang\baitap'
.\scripts\gameplay\Run-Server.ps1 -JavaHome 'E:\2026-2\lap-trinh-mang\baitap'
```

Server chạy foreground trên TCP 54555; Ctrl+C dừng. Mở game qua launcher/menu Online Create/Join, cùng room ID. Nếu dùng Gradle launcher từ `final-project`, chọn JDK17 rõ ràng:

```powershell
$env:JAVA_HOME='E:\2026-2\lap-trinh-mang\baitap'
.\gradlew.bat :desktop:run --no-daemon -Dorg.gradle.jvmargs=-Xmx256m --args='--server-host=127.0.0.1 --server-port=54555 --room=demo --create-room --gameplay'
# Client khác: thay --create-room bằng --join-room, giữ --gameplay.
# Cluster đã build binary mới:
.\gradlew.bat :desktop:run --no-daemon -Dorg.gradle.jvmargs=-Xmx256m --args='--control-url=http://127.0.0.1:8080 --create-room --gameplay'
```

Cluster Join tự bật capability nếu assignment là gameplay room; `--gameplay` không bắt buộc cho Join. Không có flag này, CLI cũ tạo legacy room. Không dùng JAR server/common cũ trong `server/build/libs` để thay cho source/classes mới đã verify. File history chạy qua RankingGateway hiện có; không có thao tác DB trên render/simulation thread.
