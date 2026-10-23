# Chế độ hợp tác đánh Boss

Cập nhật AI 23/10/2026: boss ưu tiên đuổi người còn ít HP nhất.

## Chạy và chơi

Từ thư mục gốc `smashprofs-integration`:

```powershell
.\scripts\gameplay\Run-Server.ps1
# Terminal thứ hai:
.\scripts\gameplay\Run-Client.ps1
# Terminal thứ ba, dùng bản vừa build:
.\scripts\gameplay\Run-Client.ps1 -SkipBuild
```

Trong màn hình online, người tạo phòng chọn **Hợp tác đánh Boss** trong ô phía trên nút Tạo trận đấu. Gửi mã phòng cho người còn lại; người tham gia chỉ cần nhập mã và bấm Vào phòng. Mỗi người chọn nhân vật, bấm Sẵn sàng, chờ tải và đếm ngược.

Phòng hỗ trợ 2–4 người thật. Boss do server điều khiển, không chiếm slot người chơi. Thời gian mặc định 180 giây, mỗi người 3 mạng; chủ phòng có thể đổi thời gian/số mạng và vật phẩm trong sảnh. Boss luôn có giới hạn thời gian, không dùng lựa chọn sinh tồn vô hạn.

Mọi người khởi đầu và hồi sinh với Laser 6 viên. Hết đạn tự nạp sau 1,5 giây; giữ súng, không tự chuyển sang đấm. Có thể nhặt, đổi hoặc ném vũ khí khác, gồm bốn vũ khí cận chiến mới từ nhánh `dev`: kiếm, búa, song dao và thương. Vũ khí cận chiến dùng số lượt đánh hữu hạn, không tự nạp như súng. Chủ phòng có thể chọn giữ hoặc mất vũ khí khi ném; tùy chọn này độc lập với chế độ Boss. Nhấn H để xem phím hiện tại trong game. Đầu trận được bảo vệ 2 giây; dung nham và hồi sinh tiếp tục dùng luật HP của Fighter.

Phím mặc định WASD: **W nhảy, I ngắm lên, A/D di chuyển, S ngắm xuống/rơi nhanh, J bắn, K đánh mạnh, L né/lướt**. Cấu hình WASD cũ đang dùng Space nhảy/W ngắm lên được chuyển một lần sang W nhảy/I ngắm lên khi mở client mới; các phím tùy chỉnh khác được giữ.

## Boss và các đòn

Boss Demon Slime sử dụng bộ miễn phí của **chierit**, với animation đứng, đi, chém, trúng đòn và chết. Các động tác ra chiêu tận dụng animation chém, kết hợp hiệu ứng rời; không giả định đã có animation phun lửa hoặc đập đất của bản trả phí.

| Đặc điểm | Cách hoạt động |
| --- | --- |
| HP | 1.000 / 1.250 / 1.500 cho đội 2 / 3 / 4 người, chốt lúc bắt đầu trận. |
| Truy đuổi | Chọn người đang sống có HP hiện tại thấp nhất, không xét số mạng còn lại. HP bằng nhau thì giữ mục tiêu đang đuổi; lần chọn đầu xét khoảng cách rồi ID để nhất quán. Khi người khác ít HP hơn, boss đổi mục tiêu trong nhịp di chuyển/chuẩn bị chiêu tiếp theo. |
| Di chuyển | Đi 0,70 m/s ở pha 1, 0,95 m/s ở pha 2; dừng cách mục tiêu 0,65 m. Không vượt mép sàn an toàn hoặc đi xuyên qua mục tiêu. Chạm mép thì đứng lại thay vì phát animation đi tại chỗ. |
| Pha 2 | Bắt đầu khi HP còn tối đa 50%; boss tăng nhịp hành động và phóng ba đạn lửa thay vì một. |
| Quét/chém gần | Vùng đỏ phía trước báo đòn. Khi đòn có hiệu lực, gây 22 HP; một người chỉ bị tính một hit trong lần ra đòn. |
| Đạn lửa | Chốt người bị nhắm khi báo chiêu, lấy vị trí người đó lúc phóng, gây 16 HP khi trúng; có thể né. Nếu người bị nhắm đã chết/đang hồi sinh thì bỏ lượt bắn đó. Không bẻ chiêu sang đồng đội khác giữa lúc báo đòn; đạn đã phóng không tự đổi hướng. |
| Gọi nổ | Đánh dấu vị trí người chơi bằng vùng cảnh báo. Sau 1 giây phát nổ, gây 24 HP; rời vùng hoặc nhảy khỏi vùng đánh để tránh. |
| Nhận sát thương | Tay không, các loại súng, bốn vũ khí cận chiến, vũ khí ném và kỹ năng của cả năm nhân vật đều có thể đánh boss. Boss không bị khóa trong hitstun bởi từng viên đạn. |
| Địa hình | Boss đi trên bệ chính đã kiểm tra từ map hiện có; các chiêu tầm xa gây áp lực lên người chơi ở các bệ khác. |

Đòn cận chiến và đạn lửa có thời gian báo trước 0,8 giây ở pha 1, 0,6 giây ở pha 2. Vùng gọi nổ giữ báo trước 1 giây. Khiên hấp thụ một đòn; né và bảo vệ sau hồi sinh được kiểm tra trên server. Người chơi không gây sát thương lên đồng đội.

Boss hết HP thì **cả đội thắng**, kể cả thành viên đã hết mạng. Cả đội hết mạng/mất kết nối thì thua; hết giờ mà boss còn HP cũng thua. Người đang chờ hồi sinh vẫn còn trong đội. Nếu boss chết trong cùng tick đội mất mạng cuối hoặc hết giờ, chiến thắng hạ boss được ưu tiên.

Màn kết quả hiển thị kết quả chung, đóng góp sát thương của từng người và nút đấu lại. Đấu lại tạo mã trận, boss, HP, đạn và các vùng đánh mới. Không tái sử dụng dữ liệu của lượt trước.

## Server và giao thức

- `BossEngine` chạy cùng simulation owner 60 Hz; AI, chọn mục tiêu, sát thương và timer không chạy trên client. Boss là thực thể riêng, không tạo kết nối hoặc slot giả.
- Client mới thương lượng capability `boss-v1`. Tạo phòng dùng `CREATE_ROOM` có `gameplay:true,boss:true`; client Fighter cũ không được vào nhầm phòng Boss.
- `MatchRules.boss` và `GameplaySnapshot.boss` là phần mở rộng có điều kiện. Phòng cũ giữ schema cũ. Boss snapshot gồm vị trí, hướng, HP/maxHP, pha, hành động, đòn, tick bắt đầu, attack ID, hit serial và tối đa 24 hazard.
- Dùng lại snapshot mặc định 20 Hz, chia sẻ mã hóa, backpressure và hàng đợi kết thúc hiện có. Trạng thái đòn/vùng báo được gửi trong snapshot; không tạo reliable event cho từng viên đạn/hit.
- `GAME_END.finishReason` dùng `BOSS_DEFEATED`, `TEAM_ELIMINATED`, `BOSS_TIMEOUT`. Boss không có một người thắng duy nhất; client đọc lý do kết thúc để hiển thị kết quả đội, không suy ra hòa từ `winnerId:null`.
- Cả standalone và đường tạo/join phòng qua control plane/worker cluster đều truyền thông tin chế độ Boss. UI online hiện kết nối standalone; cluster dùng launcher `--control-url=... --create-room --boss` hoặc join mã phòng đã được cấp.
- Đã sửa giới hạn `GameStateDelivery` theo gameplay để trận 180/300 giây phát được snapshot; phòng legacy giữ giới hạn cũ 120 giây.

## Tài nguyên và ghi công

Asset gốc và giấy phép: [assets/boss/README.md](../../final-project/assets/boss/README.md). PNG nhân vật giữ nguyên, các animation được ghép và điều chỉnh thời điểm phát trong game. Màn kết quả Boss có dòng ghi công chierit theo CC BY 4.0. Hiệu ứng dùng Kenney Particle Pack và More Explosions (CC0).

## Kiểm tra

```powershell
.\scripts\gameplay\Verify-Gameplay.ps1
```

`BossModeTest` kiểm tra roster riêng, súng khởi đầu/tự nạp, chống friendly fire, bốn vũ khí và vật ném, năm kỹ năng nhân vật, các chiêu boss, chuyển pha, giới hạn hazard, codec, HP, timeout và các trường hợp kết thúc cùng tick.

`BossTargetingTest` bổ sung 10 kiểm tra: ưu tiên HP thay vì khoảng cách/số mạng,
đổi mục tiêu khi HP thay đổi, giữ mục tiêu khi hòa HP, bỏ người chết/hồi sinh/hết
mạng, mép sàn, khoảng dừng, khóa mục tiêu đạn lửa khi báo chiêu và tốc độ pha 2.
31 kiểm tra tập trung Boss/targeting/ranking đã qua sau cập nhật AI.
Toàn bộ **840 kiểm tra hồi quy qua**. Evidence:
[Boss targeting](runs/20261023-165656-832-boss-low-hp-targeting/verification.json),
[hồi quy](runs/20261023-165811-268-final-gameplay-regression/verification.json),
[kết quả JUnit](runs/20261023-165811-268-final-gameplay-regression/tests.log).

`BossTcpIntegrationTest` chạy 2 và 4 client qua TCP thật: kiểm tra capability, loading gate, sát thương boss, kết quả chung và reset khi đấu lại. `GameplayClusterIntegrationTest` kiểm tra thêm phòng Boss qua control plane và worker.

`BossDesktopSmoke` là helper chạy màn hình libGDX thật trong cửa sổ ẩn, với TCP thật và fixture mô phỏng có kiểm soát; chụp sảnh, boss, cảnh báo chém, đạn lửa, gọi nổ, pha 2 và màn kết quả. Helper không thay thế việc chơi tay để cân bằng độ khó hoặc nghe âm thanh.

Chạy lại kiểm tra đồ họa sau khi có desktop JAR:

```powershell
.\scripts\gameplay\Verify-Boss-Desktop.ps1
```

Kết quả đã ghi nhận: **798 kiểm tra hồi quy đạt**, sau đó **17 kiểm tra tập trung cho Boss đạt** sau chỉnh giao diện/hiệu ứng. Kiểm tra đồ họa native đạt cho 2 người ở 1280×720 và 4 người ở 960×540, với 16 ảnh chụp.

Sau khi gộp cập nhật `dev` (`638edb7`), **813 kiểm tra hồi quy đạt**. Bộ test bổ sung kiểm tra bốn vũ khí cận chiến đánh Boss đúng một lần mỗi đòn, không làm choáng/phá khiên đồng đội, và kiểm tra codec giữ độc lập hai tùy chọn Boss/giữ vũ khí khi ném. Các test vũ khí mới đo choáng tại tick va chạm và đặt lại mục tiêu giữa các đòn để không nhầm knockback với lỗi đăng ký hit.

- [Hồi quy bản gộp: 813 kiểm tra](runs/20261022-150432-790-final-gameplay-regression/verification.json).
- [Build client/server của bản gộp](runs/20261022-boss-dev-merged-build/verification.json).

- [Hồi quy 798 kiểm tra](runs/20261022-142939-937-final-gameplay-regression/verification.json).
- [Manifest đồ họa và cấu hình chạy](runs/20261022-boss-native/verification.json).
- [Boss và thanh HP](runs/20261022-boss-native/two-players/boss.png).
- [Cảnh báo pha 2 ở màn hình nhỏ](runs/20261022-boss-native/four-players-small/eruption-warning-phase2.png).
- [Cả đội chiến thắng](runs/20261022-boss-native/two-players/team-win.png).
- [Cả đội thất bại](runs/20261022-boss-native/four-players-small/team-loss.png).
