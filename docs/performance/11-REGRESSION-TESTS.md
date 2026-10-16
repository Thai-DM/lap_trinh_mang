# 11 — Kiểm thử hồi quy

**DONE:** compile source/test Java 17 và regression dưới heap 128 MiB. **BLOCKED:** chạy lại suite 3 JVM sau khi benchmark đã chạm guard RAM.

Kết quả cuối ở [regression-final](runs/20261015-221532/regression-final):

| Nhóm | Kết quả | Phạm vi |
|---|---|---|
| Focused server | 311 tests PASS | Cluster/control, metrics, cache, token bucket, heartbeat, lifecycle, scheduler và TCP result/history/start/end. Đây là subset của số dưới, không cộng lại. |
| All server trừ 3-JVM suite | 1407 tests PASS | Physics, movement/jump/block/combat/stomp/abilities/projectiles/collision, snapshots, input ordering, room lifecycle, framing và concurrency. |
| Existing client interoperability | 17 tests PASS | ClusterLobbyIntegrationTest, LobbyClientTcpIntegrationTest, NetworkClientProtocolV1Test; TCP và parser/client session thực. |
| 3 independent worker JVMs | Không chạy lại | Bằng chứng phiên trước được giữ ở docs/scalability; không gán cho binary cuối. |

Tests mới có ý nghĩa cho thay đổi: shared encoding đồng thời đúng bytes và count 1/4, stale tick không đổi newer cache, cap/close cache; histogram whole-window/reset/budget và bin error; profiles sinh input v1 hợp lệ; token-bucket burst/refill/backward clock và input 60 Hz liên tục không bị chặn; stale/missing worker metrics không nhận capacity; recipient index >=4 room giữ isolation và invalidate binding; stalled writer chỉ đóng session liên quan; runtime abort clear input và close idempotent; 30 trận native có projectile trả World count/room/queue về baseline.

Hai lỗi trong quá trình kiểm chứng đã được xử lý rõ:

- Token refill dùng phép nhân với rate-per-nanosecond tạo sai số float khiến đúng 60 token sau 1 giây bị hụt rất nhỏ. Đổi sang elapsed seconds × rate, giữ nguyên assertion; focused/full suite sau sửa pass.
- Lần đầu chạy all-server bằng classpath chỉ chứa JAR có 1/1407 failure ở test yêu cầu resource `Path` trên filesystem (`TmxCollisionLoaderTest`), vì URL là `jar:`. Script mới thêm resource/assets directory trước JAR, đúng dạng classpath test của project; full rerun 1407 pass. Không sửa production map loader hay bỏ test đó.

Một lần compile toàn bộ client test-source gặp helper Mockito không nằm trong classpath thủ công. Verification scope đã sửa thành compile đúng ba interoperability suites nêu trên (không cần Mockito), rồi chạy 17 tests pass. Không khẳng định toàn bộ client/offline GUI suite đã được compile/chạy trong bước này.

```powershell
.\scripts\performance\Verify-Regression.ps1 -JavaHome 'E:\2026-2\lap-trinh-mang\baitap' -OutputDirectory '.\docs\performance\runs\a-new-regression'
```

Script compile toàn bộ server source/test bằng javac, chạy focused rồi full-server trong một JVM, loại duy nhất suite spawn 3 JVM, compile ba client suites và chạy. Output dir phải mới. Runtime packaged tại `final-project/build/cluster/server-1.0.jar`; không dùng tracked `server/build/libs` cũ để giả định binary đã được cập nhật bởi javac.

Các test dùng native Box2D và TCP thật ở integration boundary. Manual clock/executor chỉ dùng để điều khiển thời gian test deterministic; kết quả resource/operation-count không được thay benchmark CPU/p99. Chưa kiểm tra rendering GUI bằng mắt, WAN packet effects, long soak native bytes, chaos nhiều máy hoặc rendering consistency adaptive dưới tải.
