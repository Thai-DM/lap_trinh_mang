# 04 — Source đã tối ưu

**DONE:** các thay đổi và regression. **BLOCKED:** xác nhận mức cải thiện hiệu năng bằng paired benchmark.

| Thay đổi | Hành vi / giới hạn |
|---|---|
| Shared snapshot encoding | Writer đầu map/encode immutable delivery; writer khác dùng cùng frame khi cùng snapshot identity, phase, remainingTime. Bytes private; không clone per-recipient ở đường shared. Socket write ngoài khóa frame. Custom codec giữ đường cũ. |
| Cache lifetime | Một frame mới nhất/room, cap 1024. Không giữ recipient guard/session/runtime. Writer cũ không thay newer tick trong cache và không nhận nhầm bytes tick mới. Prune và close clear; writer đến muộn sau close không tái tạo entry được giữ. |
| Recipient index | Lazy nhóm session một lần/broadcast có publication với >=4 room. Room nhỏ dùng scan cũ để hạn chế allocation của index. Kiểm tra ownership trước offer và guard trước send giữ nguyên. |
| Roster ổn định | `MatchRuntime`, `MatchState`, snapshot builder và physics bodies dùng thứ tự/ID bất biến đã chuẩn bị; không sort/copy lặp roster không đổi. Physics state và published snapshot vẫn tách biệt. |
| Lazy fall collection | Boundary controller chỉ cấp HashSet khi phát hiện fall; không đổi quy tắc damage hoặc respawn. |
| Resource cleanup | Close runtime clear queue đang chờ; clear latest snapshot, held inputs, dispose World và bỏ listener/body/projectile references. |
| Rate protection | Token buckets có clock monotonic và burst hữu hạn; kiểm soát input/control/create/auth/accept/byte/invalid-frame. Không có timer riêng cho từng bucket. |
| Stalled writer | Heartbeat maintenance đóng session có write vượt timeout; không thêm thread/connection. Worker vẫn có watchdog maintenance 200 ms từ prototype. |
| Metrics freshness | Admission từ chối capacity khi missing/invalid/stale metrics dù heartbeat còn sống; không diễn giải missing metrics là worker rảnh. |

Feature flags đọc lúc khởi tạo, mặc định true:

```text
-Dsmashprofs.performance.sharedSnapshots=true
-Dsmashprofs.performance.indexedRecipients=true
-Dsmashprofs.performance.stableRosters=true
```

Runner có variants `baseline` (cả ba false), `shared` (chỉ shared), `indexed` (shared + index), `final` (cả ba). Đây là baseline tắt ba tối ưu **trong cùng instrumented source**, không hoàn nguyên Git về runtime gốc. Instrumentation, rate protection và lifecycle fixes vẫn hoạt động ở mọi variant. Muốn đo overhead instrumentation cần một thí nghiệm riêng, chưa có switch toàn hệ thống tắt mọi timer.

Frozen runtime `build/performance/shared-ab-runtime` là binary dùng cho phép đo đã lưu, được tạo trước các sửa cuối về admission, rate và roster/index. Manifest benchmark ghi hash binary/source ở thời điểm chạy. Bản `build/cluster/server-1.0.jar` sau regression là source cuối; không dùng số baseline cũ như benchmark cho binary cuối.

Chưa dùng delta snapshots, compression, object pool hoặc đổi payload JSON v1. Delta cần state/baseline version, ACK ứng dụng, full-state recovery và capability/version negotiation; TCP write thành công chưa chứng minh client đã apply baseline. Trạng thái **PROPOSED**.
