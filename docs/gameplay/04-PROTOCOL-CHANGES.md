# 04 — Protocol compatibility

Giai đoạn 2: CONNECT `gameplay:"fighter-v1"` tạo chế độ fighter riêng. Rules có optional `fighter:true`, GAME_STATE/GAME_END có `gameplay.fighter` và PLAYER_INPUT có nhóm `fighter` cùng `matchId` bắt buộc trong chế độ này. LOAD_READY chặn countdown đến khi tất cả nạp xong; REMATCH_REQUEST/REMATCH_STATE xử lý phiếu đấu lại và runtime mới. HP/legacy vẫn giữ schema trước. Xem [kiến trúc và kiểm chứng](12-PLATFORM-FIGHTER.md).

FIX1: PLAYER_INPUT có `interactPressed` (E) và `dropPressed` (G), boolean tùy chọn, mặc định false. Encoder chỉ thêm field khi true; payload cũ vẫn đúng 8 field. Dispatcher từ chối field mới ở session chưa negotiate gameplay. Đạn của súng thả được server giữ trong drop, không nhận từ client. Xem [FIX1](11-FIX1.md).

UI update sau bản đầu: ROOM_SETTINGS_STATE có optional `players` (schema member như ROOM_STATE) để đồng bộ roster/nhân vật/Ready. `version` tăng thêm khi chọn nhân vật và đổi Ready; `rulesVersion` vẫn chỉ thay khi luật đổi. Xem [09](09-UI-REDESIGN.md); legacy room không nhận extension.

Giữ envelope version=1, 4-byte big-endian length prefix và frame budget hiện có. Capability opt-in:

```json
{"version":1,"type":"CONNECT","requestId":"c1","payload":{"gameplay":"gameplay-v1"}}
```

Cluster CONNECT vẫn có `capability:"cluster-v1"` và assignment ticket, thêm gameplay khi cần. CONNECT_ACK không đổi một field playerId. Standalone tạo gameplay room bằng CREATE_ROOM payload `{roomId,gameplay:true}`; creator phải đã negotiate capability. Cluster `/rooms/create` dùng gameplay boolean; assignment/claim trả flag và worker dùng creator do control plane cấp. Legacy constructors/client không tự opt-in.

| Message | Payload / delivery |
|---|---|
| ROOM_SETTINGS_UPDATE | Request bắt buộc requestId, `{roomId,rules:{maxPlayers,matchSeconds,lives,dropRate}}`; chỉ host và lobby phase hợp lệ. |
| ROOM_SETTINGS_STATE | `{roomId,version,rulesVersion,hostId,playerCount,rules}`; response correlated hoặc reliable push khi đổi rules/membership/host. |
| ROOM_STATE | Room gameplay thêm `settings` chứa cùng bản settings/revision tại thời điểm xác nhận JOIN/READY; legacy giữ đúng 3 fields. |
| GAME_START | Giữ schema roster cũ; MatchSetup phía server đã freeze rules. |
| GAME_STATE | Room gameplay thêm optional `gameplay:{rules,durationTicks,players,items,shots}`. Room legacy giữ đúng 7 base fields. |
| MATCH_EVENT | Reliable push `{matchId,roomId,sequence,serverTick,type,killer,victim,weapon,cause}`. Nullable killer/victim tùy kind. |
| RTT_STATE | Report tối đa 1 lần/s/room: `{matchId,roomId,players:[{playerId,connected,rttMs}]}`; rttMs=null khi chưa đo/mất kết nối. |
| GAME_END | Giữ fields winner/reason/HP và thêm gameplay immutable cùng final snapshot, gồm stats và duration. |

Progress mỗi player có lives/status, respawnTicks/invulnerableTicks/ultimateTicks, equipped weapon/shield, damage/items/weapons/kills/deaths và wallState. Các tọa độ item/shot theo mét, hướng Y lên. JSON validation kiểm tra rules enum/range, finite numeric values, roster bounds, item<=16, shot<=64 và life-state consistency. Không có message client thay HP/weapon/lives/result. Client mới vẫn parse legacy snapshots/results bằng constructor và schema cũ.

Snapshot là replaceable; events/settings/RTT dùng reliable queue riêng và shared immutable encoded bytes. Journal events được offer trước GAME_END; writer giữ final GAME_STATE/GAME_END liền nhau, events không bị snapshot coalescing xóa. Overflow fail-close session để không giả vờ delivery thành công. Client kiểm sequence để dedup/gap detection, kiểm match/room/player identity, và kiểm result thống kê bằng đúng final gameplay snapshot.

Client thiếu gameplay capability bị từ chối JOIN gameplay room (`GAMEPLAY_CAPABILITY_REQUIRED`), tránh gửi thêm fields/message type cho parser cũ. Room legacy giữ fall penalty, timers, cooldown và wire cũ. Khả năng tương thích ở đây là client cũ → server mới qua legacy room; không hứa client gameplay mới chạy trên binary server trước khi bổ sung capability.

`version` tăng với membership/host/settings; `rulesVersion` chỉ tăng khi luật thay đổi. READY confirmation mang revision mà server xác nhận. Client không hủy READY vì một settings push cũ đến muộn; nếu luật đã đổi sau confirmation thì chuyển về JOINED để ready lại. Thay đổi luật giữa ready request và response có thể trả roster ready=false; client gameplay xem đó là trạng thái cần ready lại thay vì lỗi framing/server.
