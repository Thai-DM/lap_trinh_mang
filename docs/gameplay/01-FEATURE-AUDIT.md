# 01 — Audit gameplay

Đặc tả đã đọc: `E:\2026-2\lap-trinh-mang\tinhnang.md`, toàn bộ Phần I (9 tính năng chính thức), Phần II (6 đề xuất chưa chốt), và prompt triển khai đính kèm. Phần II không thuộc thay đổi này. Git bắt đầu tại `df0da3f` trên `integration/member2-check`; các build artifacts/log chưa commit được giữ nguyên.

| Tính năng | Trước thay đổi | Hướng tích hợp |
|---|---|---|
| Host settings | Room cố định 4 slot, 120 giây; chưa có host/rules UI | MatchRules bất biến, creator/host và host transfer ở lobby; capability riêng. |
| Stock/respawn/spectator | HP=0 là dead đến hết trận; fall chỉ trừ 10 HP và đẩy lên | Stock state machine chỉ cho gameplay-v1, 90/120 ticks; spectator còn kết nối. |
| Items/weapons | Có ability IBM/STAR/MINE/ROCKET; không có pickup/weapon inventory | Giữ ProjectileManager; thêm bounded ItemSystem cho drop/weapon ballistic shots. |
| F cooldown | Cooldown khác nhau theo ability; Luca chưa có ability online | Gate 600 ticks chung khi kích hoạt thành công; Luca dùng hai orbiting shuriken. |
| Knockback scaling | Impulse cố định và cộng gravity gốc | Hàm scale có cap, central damage validation; giữ impulse ngắn thay vì clamp ngay tick kế. |
| Wall movement | Ground contacts và double jump; chưa có wall state | Phân loại contact normal với static Tile; wall slide/jump authoritative. |
| Kill feed | Chưa có combat event channel | Journal có sequence và hàng đợi reliable riêng, client dedup/fade. |
| RTT | Heartbeat có timeout/nonce, chưa lưu RTT | Đo từ thời điểm writer gửi PING đến PONG bằng monotonic clock. |
| MVP/stats | Result có winner/HP/reason và bounded async persistence | Stats do damage/pickup/stock controller cập nhật; immutable result và UI danh hiệu. |

Luồng giữ nguyên: TCP decoder → identity/room validation → bounded InputQueue → MatchRuntime/Box2D trên một owner → immutable snapshot → StateBroadcaster → bounded per-session writer. Snapshot vẫn 60 Hz construction, broadcast 20 Hz, share encode giữa recipients. Không tạo GameServer mới, database synchronous, projectile thread hoặc timer OS/item.

Client cũ kiểm tra đúng số field của GAME_STATE/GAME_END và chỉ biết message types cũ. Do đó gameplay mới được thương lượng bằng `CONNECT.payload.gameplay="gameplay-v1"`; room legacy giữ hành vi/schema cũ. Client thiếu capability không được vào room gameplay. Đây là opt-in compatibility boundary; không âm thầm đổi nghĩa fall/dead của room legacy.

Các source chính: `common/.../MatchRules`, `GameplaySnapshot`, `MatchEvent`, `GameplayProtocol`; `server/.../GameplayMatch`, `ItemSystem`, `KnockbackScaling`, `MatchRuntime`, `GameRoom`; `network/.../ReliableDelivery`, `StateBroadcaster`, heartbeat; các model/screen online của `core`. Danh sách đầy đủ và hash source nằm trong verification manifest cuối.
