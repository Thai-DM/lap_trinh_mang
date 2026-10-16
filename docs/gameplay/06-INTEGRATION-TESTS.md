# 06 — Kiểm thử gameplay

Báo cáo dưới đây thuộc bản gameplay ban đầu. Sau cập nhật UI có thêm96targetedPASS và native GL smoke/ảnh giao diện2/4người tại [09](09-UI-REDESIGN.md); chưa thay bằng tuyên bố đã chơi tay/kiểm tra âm thanh đầy đủ.

Lượt cuối: **PASS 638 tests / 36 suites**, Java 17, một JVM JUnit `-Xmx128m`, thời gian JUnit 41,073 s. Đây là các suite liên quan gameplay/runtime/network/client/cluster, không phải toàn bộ suite của mọi module. Common, server, toàn bộ core và desktop source được compile bằng javac `--release 17`; chỉ các suite chỉ định được chạy.

- [JUnit output cuối](runs/20261016-080639-267-final-gameplay-regression/tests.log)
- [Danh sách suite, heap và kết quả](runs/20261016-080639-267-final-gameplay-regression/verification.json)
- [stderr được giữ nguyên](runs/20261016-080639-267-final-gameplay-regression/stderr.log)
- [Va chạm vũ khí: 22 tests PASS trước lượt cuối](runs/20261016-080454-352-swept-weapon-collision/tests.log)

| Phạm vi | Bằng chứng kiểm thử |
|---|---|
| Host/settings | HostRulesTest: chỉ host, validate tất cả lựa chọn, capacity, rule lock, ready reset; TCP non-host bị từ chối; settings đồng bộ. |
| 2/3/4 players | GameplayTcpIntegrationTest chạy GameServer thật và LobbyClient qua TCP với từng roster; host rules, F, respawn, spectator, RTT và cùng GAME_END. |
| Stock/lifecycle | StockGameplayTest: 1/2/3/5 lives, death một lần, biên 90/120 tick, simultaneous elimination, disconnect, INFINITE và timeout 3600 tick; giữ policy HP/draw hiện có. |
| Sáu items | ItemGameplayTest: health clamp, shield một hit, shotgun 3 viên, laser xuyên nhiều target không lặp, bazooka AoE, grenade gravity/fuse120, attribution/effective damage, pickup stats, interval/cap/cleanup. Shotgun bị chặn trước target sau tường; laser xuyên cùng tường. |
| Combat | F spam/biên600; knockback HP/cap/hướng/finite; block và immunity không tiêu shield hoặc nhận impulse/damage. |
| Wall | WallMovementTest dùng native Box2D contacts cả hai bên, slide/jump, rời tường, air spam và knockback. Các suite jump/movement cũ kiểm regression. |
| Reliable events | TcpSnapshotBackpressureTest: events đi trước END dù snapshots bị thay thế, bounded overflow đóng riêng session; client model dedup/fade4s; TCP kiểm sequence liên tục. |
| RTT | HeartbeatManagerTest: sample28ms từ thời điểm writer gửi, không cộng200ms chờ queue; TCP kiểm heartbeat RTT thật được publish; timeout suite cũ vẫn qua. |
| MVP/result | Server thống kê damage thực, K.O., pickups, deaths/lives; shared ties/no-zero-award; immutable wire roundtrip và GAME_END giống nhau giữa clients. |
| Nhiều phòng | Hai gameplay rooms khác luật chạy đồng thời; phòng kết thúc được giải phóng World mà phòng còn lại tiếp tục; không lẫn events/stocks. |
| Cluster | GameplayClusterIntegrationTest dùng control plane + worker thật trong cùng JVM, TCP clients; peer claim trước creator vẫn giữ đúng host; settings và cleanup. Không phải kiểm thử deployment nhiều máy. |
| Resource cleanup | World live count trở về baseline khi finish, trước khi client đóng; 12 trận stock nặng liên tiếp cleanup items/native abilities/snapshots; tự gỡ finished room khi session cuối đóng. Đây là kiểm tra lifecycle hữu hạn, chưa phải heap/JFR soak dài. |
| Compatibility | Các suite ProtocolV1, wire, lobby, game-end, cache, input và cluster legacy vẫn PASS; không opt-in giữ schema/luật cũ. |

TCP integration dùng socket/decoder/dispatcher/writer thật và native physics. Scheduler/tick clock được điều khiển trong test để kiểm tra đúng biên thời gian; teleport hazard chỉ là thiết lập môi trường trên simulation owner, không phải API damage từ client. Heartbeat trong phép đo RTT dùng monotonic clock thật.

Kiểm thử hai phòng từng phát hiện settings push đến muộn làm client bỏ trạng thái READY. Sửa bằng settings revision trong ROOM_STATE/READY ACK và rulesVersion tách membership. Các log thất bại trung gian được giữ trong `runs/`; kết quả cuối ở trên đã bao gồm bản sửa. Benchmark chẩn đoán từng phát hiện input trong lúc respawning: bot ngừng gửi khi inactive, server bỏ qua input hợp lệ còn trên đường truyền; TCP test xác nhận không tăng pending input hoặc báo ERROR.

**Chưa thực hiện manual GUI/audio smoke test.** Rendering, camera, màu/rotation wall animation, bố cục với 4 người và âm thanh F đã có source và compile; cần chạy hai cửa sổ game để đánh giá trực quan. Chưa có soak dài, profiler/JFR mới hoặc so sánh tải hoàn chỉnh; xem [performance](07-PERFORMANCE-IMPACT.md).

Chạy lại từ repo root:

```powershell
.\scripts\gameplay\Verify-Gameplay.ps1 -JavaHome 'E:\2026-2\lap-trinh-mang\baitap'
```

Script dùng dependency cache/`final-project/build/cluster` sẵn có, không tải mạng và không dùng common/server JAR cũ để thay source mới. Mỗi lượt ghi evidence vào thư mục timestamp riêng.
