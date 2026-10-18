# Platform Fighter — giai đoạn 2

> Lịch sử triển khai. Bản hiện hành: [Unarmed MVP](13-UNARMED-MVP.md), dùng HP thống nhất, không có vũ khí/vật phẩm.

Chế độ online tạo từ giao diện mặc định dùng **fighter-v1**: bắt đầu ở **0%**, sát thương chiến đấu tích lũy làm tăng knockback, mất mạng khi vượt blast zone. Theo luật dung nham người dùng đã chọn, mỗi mạng còn có **100 HP**: chạm dung nham bật lên ngay tại mặt lửa và **trừ 10 HP**, tối đa một lần mỗi giây. HP về 0 mất một mạng và hồi sinh với 100 HP/0%. Chế độ `gameplay-v1` HP và offline/mock cũ được giữ để tương thích; không có hai engine cùng điều khiển một trận.

## P0 — vật lý, damage %, stock và K.O

- `MatchRules.fighter` được khóa từ lúc tạo phòng. Capability `fighter-v1` phải được CONNECT trước khi vào phòng; client gameplay cũ bị từ chối khi JOIN phòng fighter. Cấu hình stocks 1/2/3/5, thời gian và tần suất vật phẩm vẫn dùng sảnh hiện tại.
- `MatchRuntime` chọn đúng một nhánh mô phỏng: `FighterEngine` thay walking/jump/stomp/melee/lava cũ. Một World Box2D, một owner, một step 1/60 giây mỗi tick; packet mạng chỉ enqueue input.
- Theo phản hồi chơi thử, tốc độ ngang cơ sở hiện là **1,6 m/s** (giảm thêm khoảng 43% từ 2,8), vẫn nhân theo `STAT_SPEED`; gia tốc đất 12, trên không 8 m/s². Nhả phím hãm vận tốc ngang mỗi tick còn 0,55 trên đất và 0,80 trên không, dừng hẳn dưới 0,02 m/s. Không hãm đà bật tường khi khóa điều khiển hay knockback trong hitstun. Dash và dodge dùng **2,6 m/s**, giữ nguyên thời lượng/cooldown. Các thông số di chuyển và tốc độ dash dùng chung giữa server và prediction client.
- Nhảy 4,2; một lần nhảy trên không; nhả W sớm cắt vận tốc lên còn 1,8. S giữ khi đang rơi cho tốc độ -9,6, so với rơi thường -6.
- Wall touch khôi phục tài nguyên nhảy trên không khi còn lượt tường; tối đa ba cú bật tường trước khi tiếp đất. Wall slide giới hạn -0,55; bật ngang 0,6, dọc 4,2. Không áp clamp đi bộ/bám tường trong hitstun.
- Damage % là số tích lũy độc lập cho đòn đánh/knockback, không chết khi đạt 100%. HP dùng cho dung nham: hết HP cũng gây mất mạng như K.O. HUD fighter hiển thị cả `% · HP`, thanh nhỏ theo HP, nên nhìn thấy được sức chịu dung nham còn lại.
- Công thức: `(B + D*S/10) * 200/(STAT_DEFENSE+100)`, dùng D sau hit. Chỉ vận tốc đưa vào Box2D được chặn tối đa 50 m/s để giữ bước vật lý ổn định; không áp trần gameplay 100/200 cho damage %. Hitstun `ceil(min(0.15 + D*S/600, 0.75)*60)` tick.
- Blast zones theo tâm body: **x<1; x>11,5; y<-1,5; y>9 khi còn hitstun**. Dung nham được xử lý trước K.O: khi rơi đến mặt y=0,16 m, body được đưa về y=0,215 m và bật lên 2,8 m/s ngay trong tick đó. Không rơi xuống vùng trống dưới map; biên dưới sâu chỉ còn dự phòng cho vị trí không hợp lệ. K.O chỉ trừ một mạng; attribution vẫn cần hit hợp lệ trong 300 tick gần nhất.
- Cú bật dung nham hủy đòn/dodge/fast fall đang diễn ra, có 8 tick phục hồi, khôi phục nhảy trên không và recovery. Trừ 10 HP tối đa mỗi 60 tick; đang bất tử vẫn bật lên nhưng không nhận damage. Đứng trên sàn đá gốc không chạm dung nham. Đòn kết liễu bởi dung nham đi qua lifecycle stock/hồi sinh hiện có, không chỉ tăng % vô hạn.
- Hồi sinh sau 90 tick ở bệ trung tâm trên cao, damage 0%, tay không. Bệ giữ body tối đa 180 tick; di chuyển/thao tác sẽ rời bệ sớm. Khi rời bệ bắt đầu đúng 120 tick bảo vệ; khi còn trên bệ cũng không nhận hit. Giữ nhấp nháy vàng 12 Hz.
- Hết giờ xét nhiều stocks hơn, rồi damage % thấp hơn; bằng cả hai thì hòa. Người bị loại vẫn xem trận, chỉ server công bố kết quả.

## P1 — combat và hiển thị

`FighterMoves` chứa frame data cho **NLight, SLight, DLight, NAir, SAir, DAir, NSig, SSig, DSig, Recovery, Ground Pound**. Hitbox chỉ truy vấn trong active frames, theo vị trí và hướng nhìn server. Hurtbox gồm sensor capsule gắn vào body; broadphase Box2D được kiểm lại bằng giao capsule/hình chữ nhật để loại false positive ở góc.

Mỗi `Attack` có ID server tăng dần và tập victim đã trúng riêng. Một victim chỉ bị ghi nhận một lần trong một đòn, kể cả shield hấp thụ; các viên từ cùng một lần bắn chia sẻ tập hit. Heavy có thể giữ tích lực tối đa 45 tick; bị đánh trúng hủy đòn. Recovery dùng một lần trên không, phục hồi khi tiếp đất. Heavy trúng gây hit-stop đúng hai bước cho hai body; knockback lớn hơn 6 tạo tumble. Tất cả tiếp tục dùng sprite/punch animation và âm thanh có sẵn của năm nhân vật.

Shift/Ctrl thực hiện spot hoặc directional dodge theo hướng giữ W/A/S/D (hoặc mũi tên lên/xuống). Startup hai tick, i-frames từ tuổi đòn 2 đến 13, recovery đến hết tuổi 21. Cooldown 60 tick trên đất, 150 tick trên không; không nhận dodge trong hitstun. C lướt đất 12 tick, cooldown 24, không cấp i-frames.

Camera bao các đấu thủ đang active, nội suy theo delta, có trần zoom và biên trên liên tục. Nền trời được vẽ cả vùng ngoài map để tránh viền đen lúc hồi phục ngoài sân. HUD có avatar, stock sáng/xám, damage % theo các mốc 40/80/120/160, ping nhỏ, tên vũ khí/đạn và túi Tab. Hiển thị bệ hồi sinh, vệt fast fall, dodge, tumble, hit flash và burst tại biên K.O. Kết quả giữ cảnh đấu thêm 1,5 giây với animation chậm rồi chuyển podium.

Camera **đi theo nhân vật**, có nội suy và zoom theo khoảng cách các đấu thủ. Biên dưới khung nhìn không thấp hơn -0,45 m, kể cả khi zoom, nên không kéo xuống vùng đáy trống. Đệm dọc giữ bệ hồi sinh dưới HUD; reset khung khi hồi sinh. Thẻ 4 người có độ rộng riêng để tên, mạng và nhãn bệ hồi sinh không tràn ra ngoài.

## P2 — sàn phụ, vật phẩm, tải và đấu lại

- Các rectangle phụ rộng ít nhất 0,3 m, dày tối đa 0,4 m, mặt trên cao hơn 0,65 m là one-way platform. Bệ chính thấp vẫn solid. PreSolve bỏ contact khi đi lên/từ dưới hoặc trong 15 tick Down+Jump; cùng policy được áp dụng trước khi ghi ground contacts. Sàn phụ có vạch cyan mảnh.
- Giữ địa hình và dung nham gốc, đã bỏ sàn đất bổ sung của lần thử trước. `FighterArena` dùng chung mặt dung nham và lực bật cho server/prediction. Client chỉ dự đoán vị trí/cú bật; damage và stocks vẫn do server quyết định.
- Vật phẩm theo chu kỳ rơi từ phía trên xuống mặt bệ hợp lệ. E nhặt gần nhất trong 0,35 m; G thả giữ đạn. Q ném theo hướng giữ để gây damage/knockback/hitstun, rồi có thể nhặt lại vũ khí và đạn còn lại khi nó chạm sàn. Slot cho vũ khí đang bay được dự phòng trong giới hạn 16 vật phẩm; projectile vẫn tối đa 64. Bình hồi phục hồi 30 HP (tối đa 100) và giảm 30 điểm phần trăm.
- Loading chuyển map và toàn bộ sprite đã nạp sang PlayScreen qua `OnlineArenaAssets`; không nạp rồi bỏ đi trước khi xác nhận sẵn sàng. Server chờ LOAD_READY của cả roster rồi đếm ngược ba giây; không nhận điều khiển trước GAME_START. Gửi lại LOAD_READY không kéo dài countdown.
- Kết quả có nút **Đấu lại / Enter**. Server đếm phiếu từ các kết nối còn trong phòng; đủ ít nhất hai người và tất cả đồng ý thì tạo match ID/runtime/world mới. Damage, stocks, vị trí đầu, đòn/cooldown, vật phẩm, input history, snapshot và kill feed không đi từ trận cũ sang trận mới. Giữ nhân vật/luật/kết nối. Người đã rời phòng được bỏ khỏi roster trận mới.

## Netcode

Giữ framing/envelope v1, schema HP legacy và các constructor input/rules cũ. Fighter thêm `rules.fighter`, `gameplay.fighter` và nhóm input tùy chọn `fighter` gồm held directions/jump/charge và edge heavy/dodge/throw/dash. Không nhận damage, stocks, vị trí nhặt hay attack ID từ client. Input malformed/giả danh/stale vẫn bị từ chối; không replay edge đã tiêu.

Input fighter bắt buộc có `matchId` trùng runtime đang chơi. Gói còn trên đường truyền của trận trước được bỏ trước khi chạm sequence watermark của trận mới; kiểm thử gửi lại sequence 999999 của match cũ rồi xác nhận input mới vẫn hoạt động. Client dùng capability đã negotiate cho input đầu ngay cả khi chưa nhận snapshot đầu; kết thúc trận xóa cả lịch sử prediction để winner không tiếp tục đi từ input còn dư.

Fighter state có damage %, action/attack/frame, hitstun, dodge/cooldown, bệ hồi sinh, tài nguyên nhảy và sequence/clientTick đã xử lý. Client lưu tối đa 128 input enqueue thành công; ACK xóa lịch sử đã xác nhận. `LocalFighterPrediction` dùng World riêng ở render thread, cùng `FighterMovement` và TMX, replay tối đa 15 tick cho di chuyển/nhảy; gộp input cùng clientTick để 120 FPS không làm chạy nhanh gấp đôi. Đòn đánh, hitstun, dodge và K.O vẫn lấy authority; prediction không gây hit/trừ mạng. Đối thủ tiếp tục nội suy buffer; mốc đời riêng tránh dùng lại lịch sử chết khi hồi sinh.

Cluster control plane, assignment và worker claim mang cờ fighter; CLI cluster có `--fighter`, còn `--gameplay` giữ HP. UI tạo/vào phòng thông thường tự negotiate fighter.

## Kiểm chứng và chạy

Các suite chính: `FighterEngineTest`, `FighterClientTest`, `FighterTcpIntegrationTest`, `GameplayClusterIntegrationTest`, cùng toàn bộ hồi quy HP/legacy. TCP thử 2/3/4 người, đồng nhất damage/stock/result và rematch; trường hợp 3 người có một người rời sau trận vẫn đấu lại với hai người còn kết nối. Prediction được đối chiếu tọa độ với World authoritative thật.

Bản hiện tại: [**726 kiểm tra PASS**](runs/20261017-094051-292-final-gameplay-regression/tests.log), gồm nhận đủ 10 lần sát thương dung nham để HP về 0, trừ đúng một mạng, hồi sinh 100 HP/0% và đi tiếp được; kiểm tra cooldown/bất tử và bình hồi HP. [Tái hiện lỗi cũ](runs/20261017-094003-489-lava-health-respawn-repro/tests.log): mong đợi HP 90 nhưng thực tế vẫn 100. [Client build thành công](runs/20261017-lava-health-build/build-final.log); native [2 người 1280×720](runs/20261017-lava-health-build/two-players-final.log) và [4 người 960×540](runs/20261017-lava-health-build/four-players-small-final.log) PASS, K.O bằng HP dung nham rồi hồi sinh/rời bệ/đấu lại qua TCP/UI thật. [Ảnh HP 90 sau chạm lửa](runs/20261017-lava-health-build/four-players-small-final/lava-bounce.png). Cần khởi động lại cả server và client. Bản [đặc tả đầu vào](specifications/20261017-PLATFORM-FIGHTER.md) được lưu nguyên văn.

Sau tinh chỉnh tốc độ: [30 kiểm tra physics/prediction/TCP PASS](runs/20261017-090929-161-fighter-movement-control-final/tests.log), gồm giới hạn chạy, quãng dừng trên đất dưới 12 cm, hãm/đổi hướng trên không, tốc độ dash/dodge và đối chiếu vị trí client/server. [Client build thành công](runs/20261017-movement-control-build/build.log); [native smoke 2 người chơi PASS](runs/20261017-movement-control-build/native.log). Cần khởi động lại cả server và client để dùng cùng thông số mới.

Lịch sử bản thử sàn liền/camera cố định (đã bỏ theo xác nhận lại của người dùng): [721 kiểm tra PASS](runs/20261017-092241-747-final-gameplay-regression/tests.log), log và ảnh giữ trong `runs/20261017-fixed-floor-build`. Bản hiện tại dùng dung nham bật lên và camera bám nhân vật như mô tả phía trên.

Client JAR build thành công với Gradle offline, heap 256 MiB ([log](runs/20261017-fighter-build/build-packaged-final.log)). Native smoke dùng JAR đó ở 2 người/1280×720, 4 người/1280×720 và 4 người/960×540, mỗi JVM 128 MiB. Có **27 ảnh cuối**: versus, HUD, túi, dodge, K.O, bệ hồi sinh, rời bệ, podium và rematch. Đã xem trực tiếp [bệ hồi sinh ở màn nhỏ](runs/20261017-fighter-native/final-small-screen/respawn-platform.png), [podium](runs/20261017-fighter-native/final-two-players/result.png) và [trận mới 4 người](runs/20261017-fighter-native/final-four-players/rematch.png). Helper điều khiển fixture/clock trên simulation owner, input và nút rematch đi qua TCP/UI thật; đây không phải buổi chơi tay/kiểm thử audio.

Log native và manifest nằm trong `runs/20261017-fighter-native`. Lượt thử fixture đầu đặt người đang dodge quá sát biên nên không K.O; giữ nguyên log timeout và thread dump. Lượt cuối đặt fixture vượt hẳn biên và có cleanup cả khi helper lỗi; cả ba cấu hình cuối PASS.

| Tiêu chí | Kiểm chứng |
|---|---|
| TC-01 nhảy/nhảy đôi/fast fall | Box2D thật, short hop và số lần nhảy có giới hạn |
| TC-02 xuyên sàn | Đi lên xuyên, rơi xuống tiếp đất, S+W bỏ contact 15 tick |
| TC-03 frame hitbox | Startup không hit, active hit, mỗi victim một lần; test capsule góc |
| TC-04 knockback | Công thức 0→120% tăng bốn lần với profile chuẩn; đo vận tốc body thực |
| TC-05 dodge | Startup, 12 tick miễn hit, recovery, cooldown đất/không và hitstun gate |
| TC-06 K.O | Trái/phải/dưới, trên có hitstun; không trừ lặp |
| TC-07 hồi sinh | 90 tick, bệ treo, rời bệ rồi bảo vệ 120 tick, damage reset |
| TC-08 hết stock | Result authoritative, spectator, native world cleanup |
| TC-09 camera | Bám nhân vật, nội suy ở 30/60/120 FPS, resize giữ tỷ lệ, không kéo xuống đáy trống |
| TC-10 mạng | TCP 2/3/4 client, cluster thực, rematch không dùng state cũ |

Từ repo root:

```powershell
.\scripts\gameplay\Verify-Gameplay.ps1
.\scripts\gameplay\Run-Server.ps1
# Terminal thứ hai và thứ ba:
.\scripts\gameplay\Run-Client.ps1
```

Phím: **A/D** đi, **W** nhảy (giữ cao/nhả thấp), **S** fast fall, **S+W** xuyên sàn phụ, **V/Space** light, **F/J** heavy (giữ tích lực), **S+F** ground pound trên không, **F** recovery trên không, **Shift/Ctrl** dodge, **C** dash đất, **E/G/Q** nhặt/thả/ném, **Tab/H** túi/hướng dẫn. Chạm mặt dung nham bật lên và trừ 10 HP; hết HP mất một mạng, chờ 1,5 giây rồi hồi sinh.

Không chạy lại load benchmark do giới hạn RAM đã ghi nhận. Kiểm thử đồ họa và wiring âm thanh không thay cho chơi tay kéo dài hoặc nghe âm thanh thực.
