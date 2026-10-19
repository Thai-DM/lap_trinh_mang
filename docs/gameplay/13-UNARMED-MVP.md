# Combat — điều khiển, di chuyển và trang bị

Đặc tả: [bản người dùng gửi](specifications/20261018-UNARMED-MVP.md). Tài liệu này thay phần điều khiển/combat của [giai đoạn 2](12-PLATFORM-FIGHTER.md). Giữ nhân vật, sprite/map, camera bám có chặn đáy, phòng 2–4 người và rematch.

## Quyết định triển khai

- **HP thống nhất:** đòn đánh và dung nham đều trừ HP. Hết HP hoặc vượt biên mất một mạng; hồi sinh 100 HP sau 90 tick (1,5 giây). Hết giờ xét stocks rồi HP cao hơn, bằng nhau hòa.
- HP thấp tăng lực văng theo mô hình nội bộ. Không tuyên bố đây là công thức Brawlhalla. HUD/podium chỉ hiện HP; trường `damagePercent` cũ trên wire giữ 0, không dùng làm mô hình thứ hai.
- Dung nham bật lên ngay và trừ 10 HP, tối đa một lần/60 tick. Bảo vệ sau hồi sinh chặn damage nhưng vẫn bật lên. Không có sàn đất bổ sung, không lọt xuống đáy trống.
- **Giữ các tính năng vũ khí/vật phẩm sẵn có** cùng bộ đòn tay không mới. Có Shotgun/Laser/Bazooka/Grenade, bình hồi 30 HP và khiên chặn một đòn. E nhặt/đổi súng, G thả, Q ném, Tab xem túi. Đổi/thả/ném giữ số đạn còn lại; hết đạn trở về tay không. Server tiếp tục sở hữu sát thương, đạn và việc nhặt đồ.
- Phòng mới mặc định rơi đồ **15 giây/lần**; chủ phòng chọn Tắt/30/15/8 giây trong cài đặt trước khi sẵn sàng. Vật phẩm xuất hiện trên cao rồi rơi xuống bệ, chỉ nhặt khi bấm E trong khoảng 0,35 m. Bộ đòn tay không bổ sung cho game hiện có; các hệ vũ khí/Signature mới theo Legend vẫn ngoài phạm vi.
- Các thông số frame/force/recovery là **balance prototype để playtest**, không phải bảng chính thức của game gốc.

## Chạy và điều khiển

Dừng server/client cũ. Từ `smashprofs-integration`, chạy ở hai terminal:

```powershell
.\scripts\gameplay\Run-Server.ps1
```

```powershell
.\scripts\gameplay\Run-Client.ps1 -SkipBuild
```

Khi lấy source sang máy khác, chạy `Verify-Gameplay.ps1` để biên dịch server và bỏ `-SkipBuild` khi mở client lần đầu; cần JDK17 và dependency cache.

`Run-Server.ps1` tự biên dịch common/server trước mỗi lần mở, in `SERVER_BUILD` kèm commit và thời gian. Nếu cổng đang có server cũ, script dừng với hướng dẫn Ctrl+C tại terminal cũ; mở lại client không cập nhật code của server đang chạy. Có thể dùng `Run-Server.ps1 -BuildOnly` để chỉ biên dịch. Sau cập nhật phải dừng server cũ, chạy lại server và tạo phòng mới. Bản rơi đồ mặc định Medium bắt đầu ở giây 15; có thể chọn High để thử ở giây 8.

Đã tìm và thay tiến trình local cũ mở 09:02 trước bản sửa trang bị. [Kiểm tra trực tiếp TCP54555](runs/20261018-server-restart/live-drop.log) dùng hai client/phòng riêng, không inject đồ hay điều khiển tick: server mới mặc định Medium và gửi Shotgun tự rơi ở **tick 900 (15 giây)** tới cả hai client.

| Hành động | Preset B mặc định | Preset A |
|---|---|---|
| Di chuyển | A/D | Trái/Phải |
| Nhảy | W | Lên |
| Xuống / fast fall | S | Xuống |
| Quick Attack | J | C |
| Heavy Attack | K | X |
| Dodge / Dash | L | Z |
| Ngắm lên, không nhảy | I | I |
| Đổi/lưu preset | F6 | F6 |
| Hướng dẫn | H | H |
| Hitbox/hurtbox, Show Stun | F2 | F2 |
| Nhặt / đổi vũ khí | E | E |
| Thả / ném vũ khí | G / Q | G / Q |
| Túi đồ | Tab | Tab |

**Đang cầm súng thì chỉ bắn.** J/K (preset B) hoặc C/X (preset A) dùng vũ khí, chỉ tạo projectile; không tạo hitbox, sát thương hay animation đánh tay, không chạy Heavy/Recovery/Ground Pound tay không. Bắn ngay theo hướng đang nhập, cooldown 24 tick (0,4 giây). Bấm trong cooldown không đánh tay thay thế. Viên cuối vẫn chỉ là một phát bắn; từ lần nhập tiếp theo sau khi hết đạn, G thả hoặc Q ném súng mới dùng bộ đòn tay không. Nhặt/thả/ném thực hiện khi nhân vật đang tự do, bị khóa trong hitstun/dodge/đòn đang chạy. Q ưu tiên hơn nhặt và đánh nếu bấm cùng frame. Không thay công thức HP, dung nham hay hồi sinh.

Đọc đồng thời mọi phím; trái+phải cho axis=0. Jump và aim-up tách riêng. Hướng đòn chọn **Down > aim-up Neutral > Side > Neutral**; hướng mặt và hướng đòn lưu riêng. Trên đất, ngang+Dodge là Dash/Backdash không bất tử; đứng yên+Dodge là Ground Spot. Trên không, không hướng là Air Spot, có hướng là Directional Air Dodge. Dash Jump giữ đà burst. Sprint sau 18 tick chạy liên tục: tốc cơ sở 1,6 m/s theo stats, sprint x1,12; dash/dodge 2,6 m/s.

**Điều chỉnh lướt 19/10:** Dash/Backdash trên đất kéo dài 6 tick (0,1 giây), giảm từ 12 tick; đoạn tăng tốc đi khoảng 0,26 m thay vì 0,52 m. Cooldown vẫn 24 tick. Một lần nhấn nút né/lướt chỉ gửi một hành động; giữ nút rồi bấm/đổi hướng không tự kích hoạt lượt thứ hai. Client và server dùng chung thời lượng; prediction kiểm tra cả trước, trong và sau khi nhận ACK để không thêm một nhịp lướt. Air Dodge và Chase Dodge giữ luật riêng.

Né khi đứng yên trên đất cũng chỉ trượt 6 tick rồi dừng; không tiếp tục trượt suốt startup/miễn sát thương/recovery như trước. Vẫn giữ nguyên thời gian bảo vệ và cooldown của né.

## Attack và tài nguyên

`UnarmedInputResolver` phân giải hướng; `FighterMoves` định nghĩa đòn; `FighterEngine` quản lý phase và phản ứng. Bộ đòn gồm **NLight/SLight/DLight, NHeavy/SHeavy/DHeavy, NAir/SAir/DAir, Recovery/Ground Pound**. Không có aerial Side Heavy thứ ba. NSig/SSig/DSig chỉ còn alias đọc dữ liệu cũ, engine không chọn các tên đó.

Move có startup, active frames, hit/miss recovery riêng, damage/force/góc/base stun, hitbox và min/max charge. Prototype dùng fixed recovery, variable recovery=0. NHeavy charge 4–30, SHeavy 6–40, DHeavy 5–36 tick; hụt có recovery dài hơn trúng. Hitbox chỉ query trong active frames, một victim/attack ID. Capsule hurtbox hạ thấp trong active frame DLight/DHeavy. Heavy hitstop 2 bước; hitstun khóa attack/dodge và giữ launch velocity. Server quyết định mọi HP/stocks/hit.

`AirActionBudget` dùng chung cho authority/prediction: **2 Air Jumps, tối đa 3 air actions**. Hỗ trợ 2 jumps+1 Rec, 1 jump+2 Rec, hoặc 3 Rec. Rec đầu 4,8 m/s; Rec kiệt sức 3,2 m/s và tiêu thêm lượt nhảy. Ground Jump không tiêu ngân sách này. Sàn/tường hợp lệ reset tài nguyên. Wall fatigue cảnh báo 5/7 và Wall Slip ở 9 actions; bỏ chặn cứng ba wall jumps. Tiếp đất hoặc hit combat trúng/nhận trúng reset fatigue. Giá trị tập trung trong `UnarmedRules`.

## Dodge, GC, Chase và buffer

| Hành động | Startup | I-frames prototype | Recovery | Cooldown |
|---|---:|---:|---:|---:|
| Ground Spot | 2 | 16 | 4 | 60 |
| Air Spot | 2 | 20 | 4 | 163 |
| Directional Air | 2 | 12 | 4 | 163 |
| Chase | 2 | 17 | 4 | quyền riêng sau hit |

Tick là 1/60 giây. Landing rút cooldown air xuống tối đa 60 tick; một normal air dodge trước khi chạm đất.

**GC:** Air Spot Dodge không hướng rồi nhập một trong sáu đòn ground trong 18 tick. Frame 18 hợp lệ, 19 không. Đòn khóa vị trí đến hết recovery; hụt xong vẫn rơi, dodge đã tiêu và cooldown còn nguyên. Directional GC 3-frame là biến thể nâng cao chưa bật; directional dodge không vô tình vào đường spot-GC.

**Chase:** chỉ hit thật mới cấp hai lượt trong 30 tick; hướng đuổi đối thủ còn sống hoặc theo chiều dọc. Whiff không cấp quyền. Cooldown normal dodge không chặn quyền Chase riêng; có thể nối lượt thứ hai hoặc hủy Chase bằng attack. Đây là policy prototype có test.

Buffer mặc định 5 tick, một pending action/người, chỉ nhận ở cuối attack recovery. Hết hạn thì xóa, dùng một lần. Không giữ attack trong hitstun/dodge/respawn; bị đánh, dung nham, chết/hồi sinh xóa pending. Chỉnh server bằng `-Dsmashprofs.unarmed.inputBufferFrames=0..12`, áp dụng từ match mới.

## Show Stun và tool combo

F2 hiện capsule cyan, active hitbox đỏ và panel rõ nền với state/phase/frame/stun/Dodge Frames. Nested snapshot detail có GC window, quyền Chase, số recoveries/actions, pending buffer và hướng đòn.

Dodge Frames đếm **frame được phép nhập Dodge**, không đợi startup né kết thúc. Một frame trống cũng phải >0. Hit đầu: -1/UNVERIFIED. Có cửa sổ: STRING; không cửa sổ: ZERO_WINDOW của đúng fixture. Nếu Dodge cooldown/hết lượt: DODGE_UNAVAILABLE, không gọi là true combo.

```powershell
.\scripts\gameplay\Run-Unarmed-Training.ps1
```

Tool ghi `combos.json`/`training.log` vào run mới. **17 chuỗi × 4 mức HP (100/75/50/25) × 2 chế độ bot = 136 fixtures**. Ghi khoảng cách/độ cao đầu, từng hit thật, tick, HP, stun, dodge frames và đủ chuỗi đúng thứ tự hay không. Bot né chỉ bắt đầu sau starter trúng, thử ngay frame đầu được phép. Follow-up chạy qua tick/Box2D thật, không teleport để ép trúng; nhảy thử giữ nút một tick rồi nhả.

Vì chọn HP, dùng dải HP thay các mốc % của hướng dẫn damage-meter. **Danh mục vẫn UNVERIFIED**. `observedZeroWindowInThisFixture` chỉ đúng khi đủ mọi đòn theo thứ tự và không có cửa sổ nhập Dodge; không chứng nhận chuỗi luôn true. Cần playtest thêm khoảng cách/độ cao và đo lại sau đổi balance.

## Netcode, kiểm chứng và giới hạn

**Tách bắn và đánh tay:** [manifest](runs/20261018-projectile-only/verification.json). 769 kiểm tra hồi quy PASS; TCP với 2/3/4 client xác nhận Laser chỉ trừ 24 HP, bớt một viên và snapshot không có attack tay không. Có kiểm tra cooldown, viên cuối, bắn hụt qua vị trí thuộc tầm tay, hướng bắn ở frame đầu và bắn trên không bằng Heavy không tạo Recovery. Native 4 người/960×540 xác nhận HP 80→56 sau một phát Laser, không có animation/hitbox đấm; giữ được nhặt/thả/ném/túi đồ, dung nham và hồi sinh.

**Bản khôi phục trang bị theo yêu cầu giữ tính năng cũ:** [manifest](runs/20261018-equipment-restored/verification.json), [túi đồ 4 người/960×540](runs/20261018-equipment-restored/four-players-small/weapon-inventory.png). Đã qua 764 kiểm tra hồi quy, 27 kiểm tra cuối gồm rơi đồ theo tick thật; native 2/4 người xác nhận E nhặt, active frame bắn một lần, Q ném, nhặt lại giữ đạn, G thả và túi Tab giữ trạng thái qua các frame HUD. Các log/manifest Unarmed bên dưới ghi lịch sử trước khi khôi phục vũ khí. Cần dừng và mở lại server/client; phòng mới mặc định Medium, phòng đã chọn Tắt phải đổi cài đặt.

Capability **unarmed-v1** ngăn client fighter cũ vào trận có schema/đòn mới; cần mở lại cả server/client. Giữ framing v1, matchId/sequence, queue hữu hạn, ACK/history và constructor HP legacy. `fighter.detail` được kiểm tra chặt; airJumps=0..2. Client không khai damage/stocks/hitbox.

Prediction chỉ replay movement/jump/dash; dừng trước combat transition chưa biết, dùng authority cho attack/GC/dodge/stun. Tick 60 Hz tránh chạy gấp đôi ở 120 FPS. Đã đối chiếu tọa độ native cho sprint, dash chốt hướng, dash-jump và air jumps.

Xem [manifest hiện tại](runs/20261018-unarmed-build/verification.json), [kết quả combo](runs/20261018-unarmed-build/combos.json) và log/ảnh cùng thư mục. Hồi quy gồm HP/legacy/TCP/cluster; native LWJGL gồm 2 người/1280×720 và 4 người/960×540. Không chạy load benchmark trên máy hạn chế RAM; native tắt audio, không thay buổi chơi tay đầy đủ.

Kết quả: [754 kiểm tra hồi quy PASS](runs/20261018-084628-416-final-gameplay-regression/tests.log), [66 kiểm tra combat/input/TCP cuối PASS](runs/20261018-085310-007-unarmed-final-actions/tests.log), [client build thành công](runs/20261018-unarmed-build/build-final.log). Native cuối: [2 người](runs/20261018-unarmed-build/two-players-final.log), [4 người màn nhỏ](runs/20261018-unarmed-build/four-players-small-final.log). Đã xem trực tiếp [Gravity Cancel + Show Stun](runs/20261018-unarmed-build/four-players-small-final/gravity-cancel.png).

Wiki trực tiếp trả 403; đã đối chiếu nội dung được lập chỉ mục của [Terminology](https://brawlhalla.wiki.gg/wiki/Terminology) và [Hints](https://brawlhalla.wiki.gg/wiki/Hints) về Chase/GC. [Patch notes chính thức 2.24](https://cms.brawlhalla.com/news/patch-2-24/) mô tả quyền Chase sau hit kể cả normal dodge đang cooldown. Các nguồn hỗ trợ ý nghĩa/điều kiện, không chứng thực balance prototype là số liệu hiện hành chính thức.

Hệ vũ khí/gadget/Signature mới theo Legend, DI/X-pivot, directional GC nâng cao, taunt và UI remap từng phím nằm ngoài MVP. Vũ khí/vật phẩm cũ được giữ nguyên. Có đổi/lưu hai preset; không thêm tài khoản/skin/cosmetic hay backend bên ngoài.
