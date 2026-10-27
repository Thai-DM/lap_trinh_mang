# TCP Wire Protocol v1 — Super Smash Profs Multiplayer

Đây là tài liệu chuẩn cho Member 1, Member 2 và Member 3 theo quyết định của chủ dự án ở Bước 43B. Các thay đổi wire/schema phải được nhóm đồng ý, cập nhật tài liệu này, `NETWORK-PROTOCOL.md`, `PROTOCOL-CHANGELOG.md` và golden tests cùng PR. Không tự coi một codec/format khác là v1.

## 1. Transport và framing

TCP, JSON, UTF-8 **không BOM**, không newline delimiter, không Java Object Serialization, không UDP:

```text
[length: signed int32, 4 bytes, Big-Endian][exactly length bytes UTF-8 JSON]
```

- `length` chỉ tính JSON, không tính header: `1 <= length <= 1_048_576`.
- Tổng frame tối đa `1_048_580` byte. `TcpIoConfiguration.maxMessageBytes` tính **cả header**, vì vậy cấu hình mặc định dùng `ProtocolV1.MAX_FRAME_BYTES`.
- Kiểm tra length **trước allocation**. 0, số âm, quá limit, EOF giữa header/body, deadline incomplete hoặc I/O lỗi: đóng connection đó, không dò/resync stream và không cố gửi ERROR.
- EOF trước byte đầu header: disconnect bình thường. Không giả định một `read()` tương ứng một frame: đọc đủ header/body, giữ thứ tự, xử lý các frame nối liền.
- Writer encode nguyên frame rồi ghi dưới khóa của connection. Không có xen bytes giữa các writer cùng socket. Gửi đồng bộ có thể block thread gọi; không được gọi socket send từ simulation thread. Bước này không broadcast.
- Partial-frame deadline server mặc định 5 giây kể từ byte đầu tiên; timeout socket idle là kiểm tra lifecycle, không phải heartbeat và không đóng idle connection chỉ vì chưa có message.

## 2. Envelope

```json
{"version":1,"type":"JOIN_ROOM","requestId":"req-123","payload":{"roomId":"arena"}}
```

| Field | Quy tắc |
|---|---|
| `version` | Bắt buộc, JSON integer chính xác `1`; không nhận string hoặc `1.0`. |
| `type` | Bắt buộc, string tên uppercase case-sensitive trong bảng MVP. Không dùng enum ordinal. |
| `requestId` | Optional ở envelope tổng quát; nếu có phải là string không rỗng, tối đa 64 Unicode code points, không nhận null. |
| `payload` | Bắt buộc, JSON object; `{}` được phép nếu schema quy định, không nhận array/null. |

Không nhận field envelope lạ. Không duplicate keys ở **bất cứ** object nào, kể cả payload nested. Một frame chỉ có một JSON root, không cho JSON nối đuôi. JSON chuẩn: không comments, trailing commas, NaN/Infinity, coercion string thành số/boolean. Property order và whitespace của message nhận không có ý nghĩa; encoder tạo compact JSON theo thứ tự `version,type,requestId,payload`. UTF-8 hỏng, JSON hỏng/duplicate keys/envelope hỏng là application error sau khi đã tiêu thụ **hết** frame, có thể gửi ERROR rồi tiếp tục frame kế tiếp. Không phục hồi requestId từ JSON chưa parse được đáng tin cậy.

Các giới hạn parser ngoài frame: depth tối đa 32, tên field tối đa 64 UTF-16 code units, giá trị string tối đa 4096 UTF-16 code units, token number tối đa 32 ký tự. Những giới hạn này cũng áp dụng encoder chuẩn. `roomId` stricter: `[A-Za-z0-9_-]{1,64}`, không trim hoặc tự đổi ID. Player/match IDs là opaque strings do server cấp; client không suy luận từ IP/port/slot.

## 3. Types, hướng và correlation

| Type | Hướng | Phản hồi / trạng thái Bước 43B |
|---|---|---|
| CONNECT | C → S | CONNECT_ACK; **đã implement** |
| CONNECT_ACK | S → C | Correlated với CONNECT |
| CREATE_ROOM | C → S | ROOM_CREATED; đã implement từ Bước 47 |
| ROOM_CREATED | S → C | Correlated với CREATE_ROOM |
| JOIN_ROOM | C → S | ROOM_STATE; **đã implement** |
| ROOM_STATE | S → C | Correlated JOIN_ROOM/PLAYER_READY; future LEAVE_ROOM; chưa broadcast roster |
| LEAVE_ROOM | C → S | ROOM_STATE roster sau leave; **contract only**, hiện ERROR NOT_IMPLEMENTED |
| PLAYER_READY | C → S | ROOM_STATE; **đã implement** |
| GAME_START | S → C | Push, đã implement từ Bước 48 |
| PLAYER_INPUT | C → S | **đã implement** enqueue; không success ACK per-input |
| GAME_STATE | S → C | Push, đã broadcast từ Bước 44 |
| GAME_END | S → C | Push, đã broadcast từ Bước 45 |
| ERROR | S → C | Cùng requestId nếu request có ID hợp lệ; nếu không, bỏ requestId |
| PING | C ↔ S | PONG cùng requestId; heartbeat server đã implement từ Bước 50 |
| PONG | C ↔ S | Phản hồi PING; payload `{}`, echo đúng requestId |

CONNECT, CREATE_ROOM, JOIN_ROOM, LEAVE_ROOM, PLAYER_READY và PING **bắt buộc requestId**. PONG khi được implement phải giữ requestId của PING. PLAYER_INPUT có thể không có ID; ID nếu có chỉ dùng correlate ERROR, không tạo success ACK mới. Push có thể bỏ requestId. Không dùng requestId làm PlayerId, authorization token hoặc input sequence. V1 không có request cache/exactly-once: requestId chỉ correlation. CONNECT lặp giữ cùng identity; JOIN lặp dùng idempotence handler có sẵn khi phase cho phép. Duplicate/stale input theo sequence bị queue từ chối.

`MATCH_RESULT` và `RANKING_UPDATED` vẫn tồn tại trong common enum để không phá API v0.1 nhưng **không thuộc TCP v1 MVP**; wire codec từ chối bằng UNKNOWN_MESSAGE_TYPE. `MessageType` được bổ sung các tên v1, không xóa/đổi tên các giá trị cũ. Client gửi các type S → C là INVALID_PAYLOAD, không thực thi gameplay.

## 4. Payload schemas

Trừ khi ghi optional/nullable, field bắt buộc và đúng JSON type. Inbound đã implement từ chối field thừa; đặc biệt `playerId`/`sessionId` của client bị UNAUTHORIZED_PLAYER. Các schema contract-only dưới đây không có gameplay handler; parser envelope không giả là đã validate đầy đủ outbound/business schema.

### CONNECT / CONNECT_ACK

CONNECT payload `{}`. Không nhận playerId, display name, auth token hoặc reconnect token ở v1.

CONNECT_ACK payload `{"playerId":"<server-generated UUID>"}`. Response giữ requestId CONNECT. TCP session do server tạo lúc accept; CONNECT cấp UUID PlayerId lần đầu, bind dưới khóa session. CONNECT lặp cùng connection trả chính ID cũ, kể cả requestId khác. Không rebind theo JSON. Kết nối mới nhận ID mới, không reconnect/resume.

### CREATE_ROOM / ROOM_CREATED (đã implement từ Bước 47; schema v1 không đổi)

Request `{"roomId":"arena"}`. Response `{"roomId":"arena"}`. Chỉ đúng field `roomId`, bắt buộc string `[A-Za-z0-9_-]{1,64}`; không trim hoặc đổi hoa/thường. Đây là **ID client đề xuất và server validate/register**, không phải UUID server sinh. Thiếu/null/blank/sai type/ký tự/length hoặc field thừa (capacity/maxPlayers/settings) trả INVALID_PAYLOAD; arbitrary playerId/sessionId trả UNAUTHORIZED_PLAYER.

Session phải open, registered đúng instance và đã CONNECT; nếu chưa CONNECT trả INVALID_SESSION. CREATE_ROOM bắt buộc requestId hợp lệ theo envelope v1; ROOM_CREATED echo nguyên requestId. Duplicate roomId, kể cả retry cùng requestId, trả INVALID_ROOM_STATE và không thay phòng cũ. RequestId chỉ correlation, không request cache hoặc exactly-once operation.

**Create-only, explicit JOIN:** creator không tự join, không có host/owner mới, không tự Ready/Start. Phòng mới WAITING, roster rỗng, runtime null. Creator và các client khác phải gửi JOIN_ROOM riêng. Session đang ở room khác vẫn có thể tạo phòng rỗng; binding cũ không thay đổi và JOIN vào room khác vẫn tuân theo hạn chế membership hiện có.

Registry `RoomManager.createRoom(String)` kiểm tra duplicate/closed và publish GameRoom đã xây dựng hoàn chỉnh dưới cùng registry lock với get-or-create/shutdown; hai request cùng ID chỉ một thành công, các ID khác nhau không ghi đè nhau. Constructor/publish không truy cập World hoặc khởi tạo MatchRuntime. Encode/socket send nằm ngoài khóa session/room/registry.

Player capacity cố định tối đa 4 (trận cần 2–4), không nhận capacity cấu hình qua payload. Chưa có giới hạn tổng số room/rate limit tạo room; không invent quota hay code lỗi mới. Registry đã shutdown/lỗi nội bộ trả INTERNAL_ERROR với message cố định, không stack trace, không đăng ký dở dang.

Sau commit, socket đóng/send thất bại có thể làm mất ROOM_CREATED nhưng phòng vẫn hoàn chỉnh trong registry: không rollback/xóa phòng mà client khác có thể đã join, không retry tự động. Phòng rỗng không tự expire hoặc bị xóa khi creator disconnect; cleanup hiện có là removeRoom/server shutdown. Không tạo room expiration/reconnect ở bước này.

```json
{"version":1,"type":"CREATE_ROOM","requestId":"create-1","payload":{"roomId":"arena"}}
{"version":1,"type":"ROOM_CREATED","requestId":"create-1","payload":{"roomId":"arena"}}
```

Golden vectors CREATE_ROOM/ROOM_CREATED giữ nguyên header `00 00 00 4c`/`00 00 00 4d` cho fixture requestId `r2`, roomId `r`; không đổi codec/schema/version.

### JOIN_ROOM / ROOM_STATE

Request `{"roomId":"arena"}`; identity lấy từ session, không từ payload. Handler hiện có get-or-create room được giữ nguyên: JOIN có thể tạo phòng nếu chưa có, nên hiện **không cần CREATE_ROOM trước JOIN**. Không âm thầm đổi semantics baseline sang join-existing-only.

ROOM_STATE:

```json
{"roomId":"arena","phase":"WAITING","players":[{"playerId":"p1","slotIndex":0,"characterId":null,"ready":false}]}
```

`players` list snapshot 0–4 member, tăng dần slotIndex, PlayerId/slot không trùng. Slot integer 0–3. Character nullable nếu chưa chọn, còn lại một trong `Alex`, `Luca`, `Maurice`, `Leo`, `Viktor` (đúng GameRoom hiện tại). Ready boolean. Phase tên RoomPhase hiện có: WAITING, READY, COUNTDOWN, PLAYING, FINISHED. Đây không phải GameSnapshot/gameplay player state. JOIN/READY response là snapshot lấy dưới khóa room; phase có thể đã tiến triển bởi scheduler, không buộc WAITING. Không có broadcast cho các client khác ở bước này.

### LEAVE_ROOM (contract only)

Request `{}` rời phòng đã bind, không nhận arbitrary PlayerId/roomId. Future response ROOM_STATE với roomId cũ và roster sau leave (không có player vừa rời). Không tự thêm leave trong PLAYING, disconnect forfeits hoặc cleanup gameplay ở bước này. Hiện ERROR NOT_IMPLEMENTED; cần chốt lifecycle leave sau khi trận chạy ở bước triển khai handler.

### PLAYER_READY

```json
{"characterId":"Alex","ready":true}
```

- `ready`: bắt buộc boolean.
- `roomId`: optional, nếu có phải ID hợp lệ và khớp room đã bind; vắng mặt dùng session.roomId.
- `characterId`: optional string thuộc danh sách đã nêu, vắng mặt giữ lựa chọn cũ; không dùng null để xóa selection.
- Sẵn sàng true yêu cầu character hợp lệ. Existing handler xử lý selection/ready nguyên tử; đổi nhân vật vẫn reset ready theo logic GameRoom rồi áp dụng ready yêu cầu.
- Success: ROOM_STATE cùng requestId. Không khởi động trận từ read thread; lifecycle/countdown đã có vẫn do scheduler hiện có quản lý.

### GAME_START (đã implement từ Bước 48; schema v1 không đổi)

```json
{"matchId":"m1","roomId":"arena","players":[{"playerId":"p1","slotIndex":0,"characterId":"Alex","displayName":"Alice"},{"playerId":"p2","slotIndex":1,"characterId":"Leo","displayName":"Bob"}]}
```

Từ MatchSetup, 2–4 participants, ID/slot duy nhất. Không có HP/physics/Body hay message do client yêu cầu start.

Nguồn là instance bất biến `MatchRuntime.getMatchSetup()` đã dùng để khởi tạo runtime, không tạo setup/snapshot khác. Payload chỉ có `matchId`, `roomId`, `players`; mỗi player có `playerId`, `slotIndex`, `characterId`, `displayName` (≤12 ký tự, server đã làm sạch; client cũ 3 field không còn tương thích), sort theo slot. Không tự thêm serverTick hoặc spawn. Spawn thực tế đã do SpawnProvider/map và PlayerPhysicsFactory tạo trong runtime.start; vị trí authoritative gửi trong GAME_STATE. Runtime tick nội bộ bắt đầu 0, snapshot hoàn tất đầu tiên tick 1, default snapshot cadence tick 3/6/9.

Hook `StateBroadcaster` sau cycle scheduler hiện có quan sát runtime started/room PLAYING thành công ở cycle đầu, thường sau tick 1, và offer GAME_START trước bất kỳ offer snapshot nào. Không callback/polling thread hoặc lifecycle mới; không sửa countdown/start/tick. WAITING/READY/COUNTDOWN, countdown hủy, runtime start lỗi hoặc room/runtime closed/finished trước publication không phát start. Runtime chưa khởi tạo thành công không được báo PLAYING/start.

Recipient open/registered đúng instance, có trusted PlayerId đã CONNECT, room binding đúng và thuộc MatchSetup roster. Guard revalidate trước encode; room deletion/stop/disconnect invalidates pending. Cursor theo runtime có startPublished gate, connection từ chối start trùng match hiện tại. Không enqueue lại ở mỗi tick/getPhase/Ready/đọc setup; không spectator/reconnect catch-up. Enqueue-once không phải client processed exactly-once, không ACK/retry mới.

Mỗi connection có một pending start riêng, không latest-wins. Writer hiện có ưu tiên start trước pending snapshot/terminal pair; recheck dưới queue lock sau khi lấy sendLock để xử lý delivery đã claim nhưng chưa ghi. START không bị snapshot thay thế, không cắt frame đang ghi, không đổi final GAME_STATE → GAME_END liền nhau. Queue start đầy do extension vi phạm một-match/session thì close riêng connection. Simulation chỉ offer immutable references, không encode/chờ sendLock/Socket.write. Không thêm worker/executor hoặc write-timeout.

Với session hợp lệ, luồng là GAME_START → GAME_STATE đầu tiên → các state tiếp theo → final GAME_STATE → GAME_END. Control replies khác có thể xen giữa start/state, nhưng không xen trong terminal pair. Nếu socket lỗi/đóng, delivery không được bảo đảm. Start đã enqueue khi PLAYING có thể vẫn được gửi khi runtime sau đó FINISHED trước lúc writer ghi, để giữ start trước terminal; không phát start mới cho runtime lần đầu quan sát đã FINISHED.

Golden GAME_START fixture đã có, giữ nguyên; codec/schema/version không đổi. Member 2 có thể dùng GAME_START để nhận match identity/roster và chuyển vào trận; đợi GAME_STATE cho tọa độ/HP, không giả spawn/tick từ start payload.

### PLAYER_INPUT

```json
{"sequence":0,"clientTick":8,"moveAxis":1,"jumpPressed":true,"stompPressed":false,"meleePressed":false,"abilityPressed":false,"blockHeld":true}
```

Tất cả 8 field bắt buộc; không có `playerId`, roomId, targetId, vị trí hoặc damage.

- sequence và clientTick là JSON integers trong `[0, 9223372036854775807]`. Sequence tăng nghiêm ngặt cho mỗi player/match (gaps hợp lệ); duplicate/stale trả DUPLICATE_INPUT. `clientTick` chỉ metadata, không quyết định simulation time. JS client phải tránh mất chính xác integer (dùng giá trị safe hoặc parser hỗ trợ integer64).
- moveAxis finite number trong [-1,1], chuyển float cho DTO hiện có.
- jump/stomp/melee/ability là edge flags; blockHeld là trạng thái giữ phím. Không đổi aggregation/input semantics hiện có: queue gom edge bằng OR và lấy movement/block mới nhất.
- Server dựng `common.model.PlayerInput` với **session.playerId**. Routing vẫn kiểm tra session registry, room binding, PLAYING, roster, sequence và bounded queue. Không chấp nhận arbitrary playerId dù bằng ID của chính client.
- Accepted nghĩa là enqueue, chưa có nghĩa applied tick. Không ACK riêng. ERROR khi bị từ chối không đóng kết nối chỉ vì duplicate input.

### GAME_STATE (đã broadcast từ Bước 44; schema v1 không đổi)

Payload fields: `matchId:string`, `roomId:string`, `serverTick:integer64>=0`, `phase:"PLAYING"|"FINISHED"`, `remainingTimeMs:integer64` trong 0–120000, `players:list[2..4]`, `projectiles:list` (giới hạn tổng frame và server runtime).

Player: `playerId:string`, `slotIndex:integer0..3`, `characterId:string`, `x,y,velocityX,velocityY:finite numbers`, `hp:finite number 0..100`, `grounded,blocking,dead,facingRight:booleans`. HP **float**, không round thành int. Players gồm cả người đã chết; ID/slot không trùng, ordering theo slot.

Projectile: `projectileId:integer64>=0`, `matchId:string` khớp match, `ownerPlayerId:string` thuộc roster, `projectileType:"IBM"|"STAR"|"MINE"|"ROCKET"`, `spawnTick:integer64>=0`, `x,y,velocityX,velocityY:finite numbers`. Các field này khớp `ServerProjectile.View` hiện có; không thêm Body, damage, client target hay Rocket steering vào wire. Không invent target field vì view hiện không chứa target.

Publisher chỉ đọc immutable cached `MatchRuntime.getLatestSnapshot()` sau các tick hoàn tất. Chụp snapshot/finished/remaining cùng khóa metadata/tick hiện có, nhả khóa trước offer và không encode/Socket.write dưới khóa runtime. `phase` lấy từ lifecycle RoomPhase/runtime result, `remainingTimeMs` lấy từ monotonic nanoClock và matchStartedAtNanos/MATCH_DURATION_NANOS hiện có của runtime (getter chỉ đọc, làm tròn lên millisecond). Không giả thời gian từ clientTick/serverTick. Snapshot nội bộ không cần đổi constructor hay thêm gameplay state.

Đơn vị player/projectile x/y là **Box2D world meters**, vận tốc meters/second; trục Y hướng lên. Giữ nguyên tọa độ Body, không nhân/chia PPM tại adapter. Baseline `Player.PPM = 100` và server `MapCollisionBuilder.PIXELS_PER_METER = 100`; renderer/map gốc dùng world-space qua PPM. Member 2 cần chuyển sang pixel chỉ ở presentation nếu UI cần. Players sort tăng dần slotIndex; projectiles sort tăng dần projectileId.

#### Cadence, backpressure và lifecycle Bước 44

- Simulation vẫn 60 Hz. Default broadcast 20 Hz: tick 3,6,9,...; hỗ trợ cấu hình số snapshot/s là ước số dương của 60 (ví dụ 10/20/30/60), không phụ thuộc FPS/client time.
- `FixedTickScheduler.setAfterCycle` gọi publisher sau các tick, không tạo loop/timer khác. Hook chỉ offer immutable delivery; JSON encode/write chạy trên outbound worker riêng mỗi connection, pool tối đa maxConnections, không chặn simulation.
- Mỗi TCP connection giữ tối đa **1 snapshot chờ** và **1 frame/delivery đã claim đang gửi**. Snapshot mới thay pending cũ (latest-wins). Trước encode, worker có thể lấy bản mới hơn đang pending. Không hủy/chèn vào frame đang viết; client có thể bỏ qua một số ticks nhưng tick nhận luôn tăng trong match.
- CONNECT_ACK/ROOM_STATE/ERROR và các control send giữ đường gửi đồng bộ trên caller I/O thread; **không** đi vào snapshot slot và không bị latest-wins drop. Fair per-connection send lock giữ encode + write nguyên frame, cho control đang chờ cơ hội trước snapshot tiếp theo. Control của một slow client có thể chờ write của chính nó; không ảnh hưởng simulation hoặc connection khác. Không thêm unbounded control queue.
- Recipient phải open/registered, có trusted PlayerId trong snapshot roster và room binding khớp room/runtime hiện tại. Revalidate metadata trước khi encode, không spectator. Runtime/room deleted/closed hoặc session disconnected làm pending invalid và bị discard. Frame đã bắt đầu gửi có thể hoàn tất; không retarget frame sang room khác.
- WAITING/READY/COUNTDOWN không publish gameplay state. PLAYING theo rate; từ Bước 45 FINISHED offer cặp final snapshot/GAME_END đúng một lần (kể cả tick không chia hết interval), thay pending nonterminal cùng match. Không bảo đảm terminal delivery nếu socket bị đóng/lỗi hoặc client không đọc.
- EOF/error/oversized encode đóng đúng connection, xóa pending và unblock worker; server stop đóng publisher trước transport/sockets, chờ reader/writer pools rồi dừng scheduler/native runtime. Restart tạo publisher/registries/workers mới, không giữ session cũ. Bounded memory/thread không đồng nghĩa có write deadline: slow socket có thể chiếm writer riêng đến khi đọc/đóng/shutdown; deadline eviction là việc sau này, không heartbeat mới.

### GAME_END (đã broadcast từ Bước 45; schema v1 không đổi)

Payload: `matchId:string`, `roomId:string`, `finishedAtTick:integer64>=0`, `winnerId:string|null`, `finishReason:"LAST_PLAYER_STANDING"|"ALL_PLAYERS_ELIMINATED"|"TIME_LIMIT"`, `participants:list[2..4]`.

Participant: `playerId:string`, `slotIndex:integer0..3`, `characterId:string`, `displayName:string` (<= 12 ký tự, optional ở parser client để tương thích server cũ), `finalHp:finite number0..100`, `eliminated:boolean`. ID/slot duy nhất, winner nếu có thuộc roster; null là hòa. Khớp `AuthoritativeMatchResult`, không tạo timestamp epoch giả, không tính ranking. Timeout 120 giây/HP tie và elimination giữ nguyên gameplay hiện có.

#### Delivery, ordering và idempotency Bước 45

- `GameEndMessageMapper` chỉ ánh xạ `MatchRuntime.getMatchResult()` đã công bố sau tick cuối. Không tính winner lần nữa, không tạo result khác. Push không có requestId. Golden vector đã có giữ nguyên: `00 00 01 46` = 326 UTF-8 bytes, TIME_LIMIT draw, participants p1/Alex/99.5 và p2/Leo/99.5.
- Hook `StateBroadcaster` sau chu kỳ scheduler hiện có lấy result/cached final snapshot cùng version dưới khóa runtime rồi nhả khóa trước enqueue. Chỉ gửi khi authoritative result tồn tại và room đã FINISHED, không gửi vì server.stop.
- Recipient phải connected/open, còn đăng ký đúng instance trong ConnectionManager, có trusted PlayerId thuộc roster và đúng room/runtime. Guard revalidate trước encode; không spectator, không tin identity trong JSON. Client inbound GAME_END vẫn INVALID_PAYLOAD, không sửa runtime.
- Cursor theo identity runtime đánh dấu publication terminal và lưu session enqueue thành công. Callback/getPhase/snapshot đọc lặp không enqueue lại. Connection cũng từ chối terminal trùng match hiện tại. Đây là **server enqueue-once trong lifecycle**, không phải client processed exactly-once. Không ACK, retry, reconnect delivery hoặc replay.
- `GameEndDelivery` ghép final GAME_STATE và GAME_END thành một gói riêng khỏi latest-wins. Worker hiện có hoàn tất frame đã bắt đầu, bỏ snapshot cũ chờ cùng match, rồi gửi **final GAME_STATE → GAME_END** dưới cùng sendLock; control reader không chen giữa hai frame. Snapshot thường sau terminal cùng match bị từ chối.
- Queue terminal tối đa 4 gói/connection cộng tối đa một delivery đang claim. Lifecycle hiện tại chỉ một trận/session nên thực tế nhiều nhất một gói cuối, chưa có rematch. Không coalesce/drop GAME_END. Nếu extension làm đầy queue thì đóng riêng connection, không backlog vô hạn. Snapshot thường vẫn chỉ một pending latest-wins slot.
- Simulation không encode/ghi socket/chờ sendLock. Writer/pool/EOF/I/O cleanup dùng lại Bước 43–44. Disconnect, room deletion hoặc stop invalidates pending; close xóa queue và đóng socket để unblock write. Không tự forfeit hay chuyển kết quả sang session mới.
- Không thêm write-timeout: peer không đọc vẫn có thể giữ writer riêng đến khi đọc/đóng/shutdown. Terminal bỏ backlog obsolete snapshots nhưng không thể vượt/cắt frame đang ghi hoặc bảo đảm nhận trong thời hạn hữu hạn. Disconnect giữa final state và end không tự retry.
- Channel cũ không override `offerGameEnd` chỉ giữ snapshot qua default non-blocking boundary và trả false, không giả GAME_END đã enqueue. TCP v1 production gửi đủ cặp terminal.

### PING / PONG (đã implement từ Bước 50; schema không đổi)

Payload chính thức vẫn `{}`, requestId chung cho probe/response. **Không thêm pingId hoặc timestamp vào payload**: requestId chính là correlation token đã được contract v1 quy định. Ví dụ:

```json
{"version":1,"type":"PING","requestId":"4c39db69-6b5e-4f73-ae9a-4f73738a5813-0","payload":{}}
{"version":1,"type":"PONG","requestId":"4c39db69-6b5e-4f73-ae9a-4f73738a5813-0","payload":{}}
```

- Heartbeat chỉ active sau CONNECT_ACK gửi thành công. CONNECT lặp, PLAYER_INPUT, PING từ client hoặc read activity bất kỳ không refresh heartbeat.
- Mặc định interval 5 giây, timeout 15 giây; cấu hình bằng HeartbeatConfiguration (Duration, chuyển nanos có kiểm tra overflow), timeout > interval > 0. Đồng hồ quyết định là server monotonic nanoTime, có injection cho test, không thời gian client/epoch.
- Lần probe đầu sau interval kể từ activation; một outstanding PING mỗi session. Token gồm UUID nonce riêng session và sequence không tái sử dụng trong lifecycle (tối đa 56 ký tự, dưới envelope limit 64). Không dùng identity/IP/port hoặc ID do client tự khai báo để đối chiếu session khác.
- Timeout tính từ **lúc enqueue PING**, không từ lúc flush socket, cũng không tính trực tiếp từ last PLAYER_INPUT. Sau enqueue không retry/reset deadline trong lúc chờ. Với mặc định, probe đầu ở 5 giây và deadline ở 20 giây sau activation nếu không có PONG; maintenance quét mỗi interval nên có thể đóng muộn thêm tối đa một interval cộng scheduling delay.
- PONG hợp lệ: session còn open/registered đúng instance, heartbeat active, requestId khớp outstanding của chính session và đến **trước** deadline. Chỉ khi đó cập nhật lastValidNanos và clear outstanding. PONG trùng/cũ/sai/không outstanding/thiếu requestId/đến deadline hoặc sau deadline trả ERROR INVALID_PAYLOAD khi còn gửi được, không refresh. Field playerId/sessionId trong payload trả UNAUTHORIZED_PLAYER; mọi field thừa kể cả pingId/time đều INVALID_PAYLOAD.
- Client chủ động PING `{}` sau CONNECT được trả PONG cùng requestId; không đổi heartbeat server hoặc trusted identity. PONG hợp lệ không có ACK riêng; PONG của client không được chuyển thành gameplay input.
- Một maintenance scheduler/network lifecycle, không scheduler/thread mỗi client và không chạy trong FixedTickScheduler/MatchRuntime.tick. State thuộc ClientSession, chỉ dùng ConnectionManager hiện có. Maintenance chỉ offer reference control bất biến, không encode/chờ sendLock/Socket.write. Writer có một pending heartbeat slot riêng, không snapshot latest-wins; START và terminal pair vẫn ưu tiên, không xen PING giữa final STATE và END. Queue heartbeat đầy/offer thất bại đóng riêng connection theo bounded failure policy.
- Deadline bao gồm queue/write backpressure; guard bỏ PING pending khi hết hạn/close/stop. Timeout mark disconnected rồi dùng close/unregister/socket-close hiện có, unblock read/write và clear outbound/heartbeat. EOF/timeout cùng lúc dùng connection CAS và removeIfSame, không cleanup registry thứ hai. Stop hủy/join maintenance worker, restart tạo state/nonce/tasks mới.
- Disconnect WAITING/READY/COUNTDOWN gọi GameRoom.leavePlayer hiện có, xóa slot/selection/ready và cancel countdown. PLAYING/FINISHED không sửa roster, Body/HP/dead/result, không forfeit hoặc GAME_END giả; chỉ cleanup connection. Không reconnect/resume.

Golden PING/PONG fixture `requestId:"r7"` giữ nguyên bytes/header `00 00 00 39` và common codec/model không đổi. Member 2 phải echo requestId của server trong PONG `{}` dù player đứng yên; Member 3 phải giữ contract này, không đổi thành pingId payload hoặc client timestamp.

### ERROR

```json
{"version":1,"type":"ERROR","requestId":"req-123","payload":{"code":"INVALID_PAYLOAD","message":"Invalid message payload"}}
```

code/message bắt buộc strings, không stack trace, exception class, payload echo, đường dẫn filesystem hoặc token bí mật. Server hiện gửi message cố định, không serialize Throwable. Client phải phân nhánh theo code, không theo prose message.

| Code | Ý nghĩa |
|---|---|
| INVALID_PAYLOAD | JSON/envelope/schema/type/direction không hợp lệ |
| UNSUPPORTED_VERSION | Integer version khác 1 |
| UNKNOWN_MESSAGE_TYPE | Tên lạ hoặc MessageType legacy không thuộc MVP |
| INVALID_SESSION | Chưa CONNECT / session không còn open/registered |
| UNAUTHORIZED_PLAYER | Client gửi identity hoặc không thuộc match |
| ROOM_NOT_FOUND | Room bound đã bị xóa |
| ROOM_FULL | Player thứ năm |
| INVALID_ROOM_STATE | Sai binding/phase/không thể ready hoặc input ngoài PLAYING |
| INTERNAL_ERROR | Lỗi xử lý nội bộ; không gửi nguyên nhân nhạy cảm |
| DUPLICATE_INPUT | Sequence duplicate/stale (mã bổ sung rõ ràng) |
| INPUT_QUEUE_FULL | Queue đầy (mã bổ sung rõ ràng) |
| NOT_IMPLEMENTED | Schema đã định nghĩa, handler chưa thuộc Bước 43B |

Nếu không parse được JSON/requestId thì ERROR không có requestId. Nếu requestId malformed không echo ID lỗi. Framing fatal không ERROR. Nếu gửi ERROR thất bại thì đóng session như I/O lifecycle hiện có. Không retry gameplay operation tự động.

## 5. Trusted binding và phạm vi bảo mật

1. Accept tạo ClientSession/sessionId phía server, chưa player/room.
2. CONNECT hợp lệ cấp UUID PlayerId duy nhất cho connection; response giữ requestId.
3. JOIN dùng PlayerId đó; existing handler join và bind room nguyên tử dưới session → room lock.
4. READY/INPUT lấy identity từ session, không JSON. Input chỉ đưa vào InputQueue, không sửa simulation state trên read thread.
5. EOF/I/O/shutdown đánh disconnected, đóng socket, unregister session; không chuyển ID sang connection mới. **Không tự thêm leave/forfeit/reconnect** khi disconnect. Lobby slot có thể còn giữ player sau disconnect theo lifecycle hiện tại: cleanup roster là bước riêng cần luật trước khi implement.

Đây là connection identity, **không authentication**. Không TLS, account login, chứng minh người dùng, reconnect token hoặc rate limiting trong bước này. RequestId không phải credential. Java API bindPlayer nội bộ vẫn giữ tương thích cho test/caller server, nhưng không có đường wire cho client gọi rebind. Không dùng IP/port để xác định PlayerId.

## 6. Codec/API và tương thích common

- `common.protocol.ProtocolMessage`: version/type/requestId/ObjectNode payload; defensive copy.
- `JsonFrameCodec.readNext(InputStream[, maxFrameBytes])`: đọc một envelope, null khi clean EOF; không close stream.
- `JsonFrameCodec.encode(ProtocolMessage[, maxFrameBytes])`: trả toàn bộ framed bytes; validate envelope/limits, bounded encoder.
- `ProtocolV1.playerInput(message, trustedPlayerId)` và `inputPayload(PlayerInput)`: mapping input với DTO hiện có; wire không encode PlayerId.
- `ProtocolException` là application error đã đọc hết frame, mang code/requestId an toàn; IOException khác là fatal transport/framing.
- Server `JsonTcpMessageCodec` implement `TcpMessageCodec`; `DecodedMessage.payload` chứa ProtocolMessage. MessageDispatcher v1 chuyển đến existing JoinRoomHandler/PlayerReadyHandler/PlayerInputHandler và gửi responses trên read worker, ngoài khóa session/room.
- Default GameServer bật v1; codec injection và low-level transport không cấu hình vẫn được giữ cho tests/extension. Mỗi connection có reader/encoder riêng; guards/deadline/EOF/shutdown và serialized writes của Bước 43 vẫn tồn tại.
- Jackson databind **2.21.6**, strict duplicate detection + parser constraints, không bật polymorphic/default typing, không deserialize class do client nêu. Common vẫn không có LibGDX. Dependency JSON được expose `api` vì envelope API dùng ObjectNode.

**Không đổi constructor/field public DTO v0.1**. PlayerState v0.1 có int HP, GameSnapshot v0.1 thiếu matchId/slot/projectiles; chúng không đủ biểu diễn wire GAME_STATE này. `GameStateMessageMapper` dựng ProtocolMessage từ server snapshot, dùng put(float) cho HP, không silently round/cast vào DTO cũ. Member 2/3 phải đọc GAME_STATE theo schema float/slot/projectiles ở trên, không deserialize bằng DTO v0.1 int HP. Không breaking wire change từ Bước 43B; chỉ thêm API server/getter và bắt đầu push đã quy định. MatchResult v0.1 có epoch timestamp khác GAME_END server tick; không sử dụng nhầm. GAME_END đã broadcast từ Bước 45; ranking chưa triển khai.

## 7. Golden test vectors

Reference fixture: `common/test-resources/protocol-v1-vectors.txt`, mỗi dòng `headerHex|exact JSON`. Expected bytes là **4 bytes header đã ghi cố định + UTF-8 bytes của JSON trên đúng dòng đó**, không BOM/newline. Expected decoded result là version/type/requestId/payload object chính xác từ JSON đó. Key order chỉ cố định trong golden encoding fixture, không yêu cầu message nhận cùng order.

Fixture có cả 15 types MVP, kể cả contract-only; GAME_START/GAME_STATE/GAME_END dùng roster hai người (HP phân số trong STATE/END). Bước 44 cập nhật GAME_STATE fixture với HP 99.5/91.25 và một IBM active; header `00 00 02 78` = 632 UTF-8 bytes. Codec tests chứng minh golden **envelope/framing**; TCP tests Bước 44–48 kiểm tra native snapshot/result/start, room isolation và backpressure. GAME_START/GAME_END fixture giữ nguyên vì schema không đổi.

CONNECT exact JSON:

```json
{"version":1,"type":"CONNECT","requestId":"r1","payload":{}}
```

Length 60 (`00 00 00 3c`). Full frame hex:

```text
0000003c7b2276657273696f6e223a312c2274797065223a22434f4e4e454354222c22726571756573744964223a227231222c227061796c6f6164223a7b7d7d
```

UTF-8 vector ERROR `message:"Lỗi"` trong fixture: header `00 00 00 64` (=100 JSON bytes); literal `Lỗi` có UTF-8 `4c e1 bb 97 69`, không tính length theo số Java chars. Player input vector có header `00 00 00 c0` (=192). PING/PONG header `00 00 00 39` (=57).

Negative vectors: `00 00 00 00` / `ff ff ff ff` / `00 10 00 01` → fatal invalid length; `00 00` + EOF → truncated header; `00 00 00 04 7b` + EOF → truncated body; `00 00 00 01 7b` → recoverable INVALID_PAYLOAD, tiếp tục frame sau. `3c 00 00 00` là 1,006,632,960 BE, **không** length 60: fatal oversized.

## 8. Hoàn thiện trong Bước 43B / chưa triển khai

Đã triển khai codec chung, default server v1, CONNECT binding, JOIN/READY/INPUT qua existing handlers, correlated ROOM_STATE/ERROR, bounded TCP framing, duplicate-key rejection, trusted identity, recovery application errors và fatal framing cleanup.

Từ Bước 44 đã triển khai GAME_STATE mapper/publisher và bounded outbound workers; Bước 45 bổ sung GAME_END terminal delivery, Bước 47 bổ sung CREATE_ROOM/ROOM_CREATED create-only, Bước 48 bổ sung GAME_START push trước state; Bước 50 thêm heartbeat PING/PONG giữ schema/framing v1. Chưa triển khai LEAVE handler, countdown broadcast, interpolation, authentication/TLS, reconnect, ranking, database hoặc thay đổi physics/combat/tick. Có schema không đồng nghĩa các handler/push còn lại đã chạy.
