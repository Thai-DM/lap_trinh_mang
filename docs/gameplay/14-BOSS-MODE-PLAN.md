# Kế hoạch thêm chế độ đánh boss

Ngày lập: 22/10/2026. Đây là kế hoạch ban đầu; chế độ Boss đã được triển khai với ba chiêu theo trao đổi tiếp theo. Xem [hướng dẫn và trạng thái thực tế](15-BOSS-MODE.md). Các checklist bên dưới được giữ để tham khảo thiết kế ban đầu.

## 1. Mục tiêu và luật chơi bản đầu

Xây dựng một chế độ **PvE hợp tác**: 2–4 người chơi trong một phòng cùng đánh một boss do server điều khiển. Giữ lại điều khiển, nhân vật, vũ khí, vật phẩm, di chuyển, dung nham và cơ chế mạng sống của chế độ Fighter. Boss là thực thể của trận đấu, **không chiếm một slot người chơi**. Vì vậy phòng vẫn nhận tối đa bốn người thật.

Luật đề xuất cho bản đầu:

| Nội dung | Quy tắc |
| --- | --- |
| Bắt đầu | Chủ phòng chọn chế độ Boss lúc tạo phòng; tối thiểu 2 người sẵn sàng và tải xong dữ liệu. |
| Thời gian | 180 giây; có thể đổi trong sảnh nếu muốn dùng lại bộ luật hiện có. |
| Mạng sống | Mỗi người 3 mạng; mất mạng và hồi sinh theo cơ chế hiện tại. Người hết mạng chỉ xem trận. |
| Máu boss | Đề xuất 1.000 HP với 2 người, thêm 250 HP cho mỗi người từ người thứ ba; cần chỉnh sau khi chơi thử. |
| Sát thương | Người chơi gây sát thương cho boss; đòn của người chơi không gây sát thương đồng đội. Boss gây sát thương lên người chơi theo luật HP hiện có. |
| Thắng | Boss còn 0 HP: cả đội thắng, kể cả thành viên đã hết mạng nếu còn trong trận. |
| Thua | Cả đội hết mạng, hoặc hết giờ khi boss còn HP. Nếu tất cả rời trận, server kết thúc theo luật bỏ cuộc. |
| Đấu lại | Dùng lại quy trình bỏ phiếu đấu lại, nhưng tạo boss/trận/mã trận mới và đặt lại toàn bộ trạng thái. |

Chỉ làm **một boss, một bản đồ, hai pha, hai kiểu tấn công** cho bản đầu. Đề xuất đòn 1 quét ngang và đòn 2 đập đất; cả hai có dấu hiệu báo trước, thời gian ra đòn và hồi chiêu. Khi HP boss còn tối đa 50%, vào pha 2: tăng nhịp ra đòn và thay đổi mẫu tấn công. Giữ mức độ khó và số liệu đòn trong cấu hình chung để cân bằng sau khi chơi thử.

## 2. Những điểm trong code cần thay đổi

| Vùng | Code hiện tại | Việc cần làm |
| --- | --- | --- |
| Chọn chế độ và phòng | `MatchRules`, `GameRoom`, `ProtocolV1Dispatcher` | Thêm lựa chọn Boss tường minh ở lúc tạo phòng; khóa chế độ sau khi tạo. Giữ các phòng cũ đọc đúng bộ luật cũ. Sảnh Boss dùng lại ready/load-ready/countdown. |
| Giao thức | `GameplayProtocol`, `GameplaySnapshot`, `GAME_END` | Thêm dữ liệu Boss có giới hạn: ID, vị trí, hướng, HP/maxHP, pha, trạng thái tấn công và tick còn lại. Thêm kết quả hợp tác `TEAM_WIN`/`TEAM_LOSE` tường minh; `winner=null` một mình không đủ vì hiện còn có nghĩa hòa. Client chưa hỗ trợ Boss phải bị từ chối vào phòng Boss. |
| Mô phỏng | `MatchRuntime`, `GameplayMatch`, `FighterEngine`, `MatchEndEvaluator` | Tạo `BossEngine` hoặc thành phần tương đương, chạy trên cùng owner thread và tick 60 Hz. Boss tự chọn mục tiêu, di chuyển, báo trước/đánh/hồi chiêu và nhận sát thương. Điều kiện kết thúc Boss tách khỏi cách xét người cuối cùng còn sống của PvP. |
| Va chạm và sát thương | `FighterPhysics`, combat controllers, `ProjectileManager`, `ItemSystem` | Tạo hitbox/hurtbox boss; nối các nguồn sát thương hiện có vào boss (tay không, súng, vật ném, kỹ năng). Đòn boss áp dụng HP, khiên, bất tử và hồi sinh đúng thứ tự; chặn friendly fire. |
| Bản đồ và tài nguyên | `assets/*.tmx`, `OnlineArenaAssets` | Chọn một đấu trường đủ rộng, đặt điểm spawn boss và người chơi, kiểm tra vùng dung nham/sàn. Bản đầu có thể vẽ boss dạng hình/sprite tạm với vùng báo đòn dễ nhìn. |
| Client và giao diện | `OnlineRoomScreen`, `OnlineLoadingScreen`, `OnlinePlayScreen`, `OnlineHud`, `OnlineResultScreen` | Chọn Boss trong sảnh, tải asset, vẽ boss từ snapshot, thanh HP/pha, dấu báo vùng nguy hiểm, thông báo thắng/thua và nút đấu lại. Camera bao được boss và đội nhưng vẫn giữ ranh giới dưới để không lộ khoảng trống dưới map. |

Thiết kế dữ liệu nên phân biệt rõ `PlayerId` của người thật và `BossId`/thực thể boss. **Không tạo client giả hoặc PlayerSlot giả** cho boss: hiện `GameplaySnapshot` giới hạn danh sách người chơi theo roster 2–4 và nhiều chỗ giả định người chơi có kết nối, lựa chọn nhân vật, mạng sống. Dùng một nhánh mode rõ ràng, giữ tương thích với cờ `fighter` của protocol cũ khi chuyển dần sang lựa chọn chế độ tường minh.

## 3. Thứ tự thực hiện

### Giai đoạn 1 — Chốt hợp đồng dữ liệu và phòng

- [ ] Viết test cho tạo/vào phòng Boss, từ chối client thiếu capability, không cho đổi chế độ sau khi tạo, ready/load-ready và đấu lại.
- [ ] Mở rộng `MatchRules` và codec theo cách vẫn đọc được phòng Fighter/legacy cũ; thêm chế độ vào thông tin phòng và `MatchSetup`.
- [ ] Định nghĩa `BossSnapshot` có giới hạn và kết quả `TEAM_WIN`/`TEAM_LOSE`. Chặn dữ liệu boss không hợp lệ (HP âm, tọa độ không hữu hạn, pha sai, đối tượng vượt giới hạn).

**Xong khi:** hai client thấy cùng chế độ trong sảnh; client cũ không vào nhầm phòng Boss; protocol cũ vẫn qua kiểm tra hồi quy.

### Giai đoạn 2 — Boss đứng yên nhưng đánh được

- [ ] Tạo boss trong `MatchRuntime` cùng tick 60 Hz; điểm spawn và HP chỉ do server quyết định.
- [ ] Cho tay không, súng và kỹ năng gây sát thương lên boss qua một hàm kiểm tra hit duy nhất. Mỗi hit chỉ trừ HP một lần; đạn/đòn không trúng không trừ HP.
- [ ] Đồng bộ boss trong snapshot mặc định 20 Hz. Khi HP về 0, tạo final snapshot rồi `GAME_END` với `TEAM_WIN`; khi hết giờ/cả đội hết mạng, trả `TEAM_LOSE`.

**Xong khi:** hai client cùng nhìn thấy HP boss và kết quả giống nhau; không có kết thúc PvP nhầm khi chỉ còn một người còn mạng.

### Giai đoạn 3 — AI và đòn đánh

- [ ] Máy trạng thái nhỏ: `IDLE → TELEGRAPH → ACTIVE → RECOVERY`, sau đó chọn đòn tiếp theo. Chọn mục tiêu còn hoạt động; khi mục tiêu chết/rời trận thì chọn lại.
- [ ] Thêm quét ngang và đập đất. Dấu báo trước cần xuất hiện đủ lâu để người chơi né; hitbox chỉ gây sát thương trong `ACTIVE`, mỗi mục tiêu tối đa một hit trong một lần ra đòn.
- [ ] Chuyển sang pha 2 ở ngưỡng 50% HP; đổi nhịp hoặc chuỗi đòn. Mọi quyết định AI và va chạm chạy trên server; client chỉ vẽ và phát hiệu ứng.
- [ ] Giới hạn số hitbox/đạn boss cùng lúc, không tạo thread/timer/socket riêng cho boss. Trạng thái đòn nằm trong snapshot; chỉ phát sự kiện tin cậy cho chuyển pha và kết thúc để tránh đầy journal.

**Xong khi:** né đúng thời điểm tránh được sát thương, khiên và bất tử sau hồi sinh có hiệu lực, hai client nhìn cùng một pha và cùng một đòn.

### Giai đoạn 4 — Giao diện và trải nghiệm chơi

- [ ] Thêm mục **Đánh boss** vào tạo phòng, hiển thị luật ngắn ngay trong sảnh.
- [ ] Vẽ boss, thanh HP gọn ở mép trên, vùng báo đòn dễ nhìn, trạng thái pha và thông báo đồng đội bị loại; tránh che sân đấu.
- [ ] Camera theo nhóm có boss nhưng giữ giới hạn bản đồ; thử ở 1280×720 và 960×540. Kết quả dùng chữ **Cả đội thắng / Cả đội thua** thay vì bục xếp hạng PvP.
- [ ] Nạp boss asset trong màn loading, kiểm tra âm thanh/animation; giữ placeholder nếu tài nguyên chưa xong để kiểm tra gameplay trước.

**Xong khi:** người mới có thể tạo phòng Boss, vào trận, hiểu dấu báo đòn, xem HP boss và đấu lại mà không cần lệnh dòng lệnh riêng.

### Giai đoạn 5 — Kiểm thử và cân bằng

- [ ] Unit test: HP boss, mỗi đòn một hit, chuyển pha đúng một lần, lựa chọn mục tiêu, timeout, cả đội hết mạng, hồi sinh, ngắt kết nối, reset sau rematch.
- [ ] Integration test: 2 và 4 client qua TCP thật; snapshot boss nhất quán; kết quả chỉ phát một lần; client chậm không giữ simulation tick; phòng Fighter/legacy vẫn hoạt động.
- [ ] Chơi thử các trường hợp: cả đội dùng súng, không ai đánh, một người mất kết nối, người chơi chết đúng lúc boss chết, kết thúc đúng tick hết giờ, boss đứng sát mép/dung nham, mạng có độ trễ.
- [ ] Sau khi chơi thử, chỉnh HP, thời gian báo đòn, sát thương và nhịp pha theo dữ liệu thực tế; chưa thêm boss thứ hai hoặc cơ chế xếp hạng trước khi bản đầu ổn định.

## 4. Ưu tiên làm ngay

Làm giai đoạn 1 rồi giai đoạn 2 trước. Đây là một lát cắt chạy được từ sảnh → đánh boss đứng yên → kết quả; sau đó mới thêm AI và giao diện hoàn chỉnh. Nếu làm AI/asset trước khi sửa luật kết thúc và protocol, boss sẽ khó kiểm thử qua nhiều client.

Các mốc kiểm tra code phù hợp: test của `common` và `server`, `scripts/gameplay/Verify-Gameplay.ps1`, sau đó chạy server với 2 client bằng `scripts/gameplay/Run-Server.ps1` và `Run-Client.ps1` từ **thư mục gốc repository**. Ghi lại một trận thắng, một trận thua và một lần đấu lại để đối chiếu giữa hai client.
