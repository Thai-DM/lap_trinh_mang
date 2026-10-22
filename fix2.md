# DANH SÁCH LỖI & VẤN ĐỀ CẦN SỬA (GIAI ĐOẠN 2) — SUPER SMASH PROFS

---

## ✅ 1. Không dùng được chiêu phím F (Kỹ năng đặc trưng / Bắn đạn của từng Giáo sư) — [ĐÃ KHẮC PHỤC TRIỆT ĐỂ & VERIFIED 100%]

- **Hiện tượng thực tế:** 
  - Trong trận đấu online (chế độ Platform Fighter mới), người chơi nhấn phím `F` (hoặc `P`) nhưng nhân vật không kích hoạt được chiêu thức / kỹ năng đặc trưng riêng biệt vốn có:
    - *Alex:* Không bắn ra Tên lửa tầm nhiệt (`HomingMissile`).
    - *Maurice:* Không phóng chùm Phi tiêu 4 hướng (`ThrowingStar`).
    - *Leo:* Không đặt được Mìn mặt đất (`Landmine`).
    - *Luca:* Không tạo được Vòng phi tiêu bảo vệ quanh người (`CircleStar / Orbit`).
    - *Viktor:* Không thả được Khối máy tính rơi tự do (`IBM`).

- **Nguyên nhân kỹ thuật tận gốc:**
  1. **Ở Client ([`OnlinePlayScreen.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/core/src/com/smashprofs/game/Screens/OnlinePlayScreen.java)):**
     - Điều kiện `!fighter && Keys.F` đã triệt tiêu sự kiện gửi `abilityPressed` khi chạy chế độ võ sĩ (`isFighter() == true`), đồng thời phím `F` bị gán đè vào đòn đánh cận chiến nặng (`heavyPressed`).
  2. **Ở Máy chủ ([`MatchRuntime.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/server/src/com/smashprofs/server/game/MatchRuntime.java)):**
     - Vòng lặp `tickOwned` khi phát hiện `setup.rules().fighter() == true` đã rẽ nhánh sang hàm `tickFighter(...)` riêng biệt.
     - Trong `tickFighter(...)`, logic cập nhật đạn (`projectileManager.updateProjectiles()`), hết hạn đạn (`expireGameplayProjectiles`), kiểm tra bấm phím kỹ năng (`input.abilityPressed()`), spawn đạn (`spawnProjectile` / `items.orbit`), và xử lý va chạm sát thương đạn (`resolveHits()`, `collectHitsAfterStep()`, `cleanupDestroyed()`) **hoàn toàn chưa được gọi**! Do đó máy chủ trước đây bỏ qua toàn bộ phím F trong chế độ đấu võ sĩ.
  3. **Ở Giao diện HUD ([`OnlineHud.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/core/src/com/smashprofs/game/Scenes/OnlineHud.java)):**
     - Ô hiển thị kỹ năng `ability` bị ghi đè thành `Shift · NÉ` thay vì hiển thị thời gian hồi chiêu của phím `F` (`F · SẴN SÀNG`).

- **Các thay đổi đã thực hiện:**
  1. [`OnlinePlayScreen.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/core/src/com/smashprofs/game/Screens/OnlinePlayScreen.java):
     - Luôn gửi `abilityPressed = true` khi nhấn `F` (hoặc `P` cho P2).
     - Chuyển phím đòn đánh nặng (Heavy / Signature) sang phím `J` / `K` để phím `F` hoàn toàn độc lập cho kỹ năng đặc trưng.
  2. [`MatchRuntime.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/server/src/com/smashprofs/server/game/MatchRuntime.java):
     - Tích hợp đầy đủ vào `tickFighter`:
       - `projectileManager.updateProjectiles()`
       - `projectileManager.expireGameplayProjectiles(tick)`
       - Xử lý `input.abilityPressed()` -> gọi `projectileManager.spawnProjectile(id, tick)` (với Alex, Maurice, Leo, Viktor) hoặc `gameplay.items.orbit(id, tick)` (với Luca).
       - Khấu trừ cooldown bằng `gameplay.ultimateUsed(id)`.
       - Xử lý va chạm qua `projectileManager.resolveHits()` và gây sát thương thông qua `gameplay.damage(...)`.
       - Thu thập contact và dọn dẹp sau Box2D step (`collectHitsAfterStep()`, `cleanupDestroyed()`).
  3. [`FighterEngine.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/server/src/com/smashprofs/server/game/FighterEngine.java):
     - Bỏ `input.abilityPressed()` khỏi các đòn cận chiến.
     - Cho phép bấm `F` để rời bệ hồi sinh (`f.platformUntil = 0`).
  4. [`OnlineHud.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/core/src/com/smashprofs/game/Scenes/OnlineHud.java):
     - Hiển thị đúng trạng thái chiêu `F · SẴN SÀNG` hoặc số giây hồi chiêu trên thanh trang bị.
     - Cập nhật menu trợ giúp phím tắt (`[H] Phím`): `F: kỹ năng đặc trưng`, `J / K: đòn đánh mạnh`.
  5. [`FighterEngineTest.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/server/test/com/smashprofs/server/game/FighterEngineTest.java):
     - Thêm test tự động kiểm chứng toàn bộ 5 nhân vật kích hoạt chiêu F trong chế độ võ sĩ:
       - `alexCanFireHomingMissileInFighterModeAndDamagesTarget`: Alex phóng Rocket tầm nhiệt bay trúng đối thủ và gây sát thương % knockback.
       - `lucaCanActivateOrbitStarsInFighterMode`: Luca kích hoạt 2 sao bay quanh người và bắt đầu tính cooldown.
       - `allCharacterAbilitiesSpawnInFighterMode`: Maurice bắn 4 sao, Leo đặt mìn, Viktor thả IBM thành công 100%.

- **Kết quả kiểm thử thực tế:**
  - `.\gradlew.bat :server:test --tests "com.smashprofs.server.game.FighterEngineTest"`: **BUILD SUCCESSFUL**, toàn bộ 30 test cases PASS.
  - Đã biên dịch và đóng gói mới nhất:
    - `:desktop:dist`: `final-project/desktop/build/libs/desktop-1.0.jar`
    - `:server:jar`: `final-project/server/build/libs/server-1.0.jar`

---

## ⚠️ 2. Vấn đề trải nghiệm Combo võ thuật chưa được như ý & Kế hoạch tinh chỉnh toàn diện

- **Hiện tượng thực tế khi trải nghiệm:**
  - Cảm giác bấm combo bị "sượng", "khựng", bấm phím nhanh cảm giác không ăn chiêu tiếp theo.
  - Các chuỗi combo cơ bản (như `DLight -> NAir`, `DLight -> SAir`, `SLight -> NLight`) rất khó hoặc không thể nối dính (đối thủ rơi xuống đất hoặc kịp né trước khi đòn thứ 2 kịp vung ra).
  - Khả năng không chiến (aerial combat) bị hụt hơi: Sau khi hất tung đối thủ, nhảy theo được 1 nhịp là cạn tài nguyên nhảy, dễ rơi xuống vực/dung nham.
  - Cảm giác đánh chưa thực sự "dính tay" và "mượt mà" như các tựa game Platform Fighter chuẩn (Brawlhalla, Smash Bros).

- **Nguyên nhân kỹ thuật tận gốc:**
  1. **Lỗi nuốt lệnh do hoàn toàn thiếu Input Buffer (Buffer Window) — [Trọng yếu]:**
     - Trong [`FighterEngine.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/server/src/com/smashprofs/server/game/FighterEngine.java), điều kiện nhận đòn mới là `f.attack == null`. Khi người chơi đang trong hoạt ảnh của đòn trước (đặc biệt là giai đoạn hồi chiêu `recovery`), nếu bấm phím đòn tiếp theo sớm dù chỉ 1 tick (1/60s), hệ thống vứt bỏ input đó ngay lập tức. Người chơi buộc phải chờ đúng tích tắc frame cuối cùng mới được bấm, tạo cảm giác bấm phím bị trơ hoặc phải spam nút liên tục.
  2. **Lệch pha giữa Hitstun nạn nhân và Recovery người tấn công (Triệt tiêu True Combo):**
     - Đòn khởi đầu combo kinh điển `DLIGHT` (quét chân hất tung) có `recovery` lên tới 13 frames.
     - Trong khi đó, ở mức sát thương thấp (0% - 30%), công thức tính `hitstun = ceil((0.15 + damage * scaling / 600) * 60)` chỉ cho nạn nhân bị choáng khoảng **9 frames**.
     - Hệ quả: Nạn nhân hồi phục hành động trước cả khi người ra đòn hoàn thành cú quét chân! Người chơi dù bấm chuẩn đến đâu cũng không thể kịp nhảy lên đánh tiếp trước khi đối thủ né đòn.
  3. **Thiếu tài nguyên nhảy trên không (`Air Jumps` = 1 thay vì 2):**
     - Hiện tại code chỉ cấp 1 lần nhảy kép trên không (`airJumps = 1`). Sau khi nhảy theo đối thủ vừa bị hất tung, người chơi lập tức hết lượt nhảy, không thể điều chỉnh độ cao để tung đòn `NAir` / `SAir` hay cứu sinh nếu đối thủ bị văng xa.
  4. **Thiếu Dash Jump (Vận tốc ngang bị tụt dốc khi nhảy từ Dash):**
     - Khi bấm `C` (Dash), vận tốc đạt 2.6 m/s. Nhưng nếu bấm `W` để nhảy trong lúc Dash, hệ thống lập tức giới hạn vận tốc ngang về mức chạy bộ thường (`1.6 m/s`), khiến nhân vật không thể phóng vút theo quán tính để đón đầu đối thủ bị hất văng.
  5. **Chưa có cơ chế Gravity Cancel (GC - Hủy trọng lực ra đòn trên không):**
     - Thiếu tính năng cho phép `Spot Dodge` trên không rồi tung chiêu mặt đất lơ lửng, làm mất đi 50% độ đa dạng của chuỗi combo trên không.
  6. **Chưa có cơ chế Chase Dodge (CD - Lướt bám đuổi khi đánh trúng):**
     - Đánh trúng đối thủ xong không có cơ chế hủy frame hồi phục để lập tức lao theo mục tiêu nối đòn.

- **Kế hoạch & Kết quả nâng cấp (Tích hợp từ commit `d1bbe2c` & Kiểm chứng 100%):**
  - [x] **2.1. Thêm Input Buffer (Bộ đệm nhận lệnh sớm 5 frames):**
    - Đã triển khai bộ đệm lệnh `Pending` trong 5 ticks cuối của giai đoạn Recovery (`bufferFrames = 5`). Đòn sau tự động tung ra ngay frame hợp lệ đầu tiên, loại bỏ hiện tượng nuốt lệnh khi combo nhanh.
  - [x] **2.2. Tinh chỉnh Hitstun & Recovery cho các đòn khởi đầu:**
    - Triển khai thời lượng `baseStun` và cơ chế On-hit Recovery vs On-miss Recovery trong `FighterMoves.java`. Đòn trúng mục tiêu giảm recovery đáng kể giúp nối True Combo mượt mà.
  - [x] **2.3. Nâng cấp 2 Air Jumps (Nhảy 2 lần trên không) qua `AirActionBudget`:**
    - Cấp 2 lần nhảy trên không (`AirActionBudget.java`), reset khi chạm đất hoặc bám tường. Recovery trên không dùng chung ngân sách và chuyển sang Exhausted Recovery khi cạn kiệt.
  - [x] **2.4. Triển khai Gravity Cancel (GC):**
    - Nhấn né tại chỗ trên không (`Air Spot Dodge`) rồi nhập đòn đánh mặt đất trong cửa sổ 18 ticks (`GC_WINDOW`) để thực hiện đòn mặt đất lơ lửng giữa không trung.
  - [x] **2.5. Triển khai Chase Dodge (CD):**
    - Khi đòn đánh trúng mục tiêu, nhận được charge Chase Dodge để lập tức lướt áp sát kẻ địch theo hướng di chuyển với tốc độ cao, ngắt frame hồi chiêu và nối tiếp combo.
  - [x] **2.6. Hệ thống điều khiển Presets A & B, Hitbox / Stun Debug:**
    - Nhấn `F6` để chuyển đổi giữa Preset B (`W/A/S/D`, `J`: nhẹ, `K`: mạnh, `L`: né) và Preset A (Mũi tên, `C`: nhẹ, `X`: mạnh, `Z`: né).
    - Phím `F` luôn giữ nguyên cho kỹ năng đặc trưng của nhân vật ở cả 2 preset.
    - Nhấn `F2` để bật/tắt HUD gỡ lỗi thời gian choáng (Hitstun / Stun Info) và khung va chạm.

---

## ✅ 3. Sửa lỗi Lướt đất (Ground Dash): Bấm `L + A` hoặc `L + D` không lướt được — [ĐÃ KHẮC PHỤC TRIỆT ĐỂ & VERIFIED 100%]

- **Hiện tượng thực tế:** 
  - Khi nhân vật đứng dưới đất, người chơi bấm `L + A` hoặc `L + D` (hoặc `Z + ← / →`) nhưng nhân vật không lướt né được, cảm giác bị trơ hoặc chỉ đứng im làm động tác né tại chỗ (Spot Dodge).
- **Nguyên nhân kỹ thuật:**
  1. Trong `UnarmedControls.java`, phím `L` chỉ gửi `dodgePressed` duy nhất ở frame vừa nhấn xuống (`isKeyJustPressed`), còn trường `dashPressed` luôn bị gán cứng là `false`. Nếu người chơi nhấn phím `L` trước `A/D` chỉ 1-2 frame, hệ thống hiểu là `x = 0`, lập tức kích hoạt `GROUND_SPOT` dodge (đứng im tại chỗ) và đưa vào cooldown 60 ticks (1 giây), triệt tiêu hoàn toàn khả năng lướt.
  2. Ngược lại, nếu giữ `L` rồi mới nhấn hướng, `dodgePressed` đã hết hiệu lực từ frame trước.
- **Giải pháp xử lý:**
  - Trong [`UnarmedControls.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/core/src/com/smashprofs/game/network/client/UnarmedControls.java): Nhận diện `dashIntent = (dodgePressed && movingHoriz) || (dodgeHeld && (leftPressed || rightPressed))`. Đảm bảo cả 3 cách bấm:
    - Bấm `L` trong khi đang giữ `A` hoặc `D`.
    - Giữ `L` rồi nhấn `A` hoặc `D`.
    - Bấm đồng thời cả `L` và `A` hoặc `D`.
    Đều lập tức nhận diện ý định lướt đất, truyền cờ `dashPressed = true` và `dodgeEdge = true`, hướng ngang `horizontal` được kích hoạt ngay lập tức.
  - Trong [`FighterEngine.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/server/src/com/smashprofs/server/game/FighterEngine.java): Khi `grounded && (x != 0 || input.fighter().dashPressed())`, kích hoạt cú lướt `DASH` / `BACKDASH` tức thì với vận tốc `FighterMovement.DASH_SPEED` (2.6 m/s) liên tục trong 12 frames và hồi chiêu ngắn `f.nextDash = tick + 24`.

---

## ✅ 4. Giới hạn số lần nhảy chuẩn 2 lần (1 lần từ đất + 1 lần trên không) — [ĐÃ KHẮC PHỤC TRIỆT ĐỂ & VERIFIED 100%]

- **Hiện tượng thực tế:**
  - Người chơi thấy trong game nhân vật có thể nhảy tới 3 lần (1 lần nhảy từ mặt đất + 2 lần nhảy liên tiếp trên không).
- **Nguyên nhân kỹ thuật:**
  - Hằng số trong [`UnarmedRules.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/common/src/com/smashprofs/common/model/UnarmedRules.java) đang để `AIR_JUMPS = 2` và `AIR_ACTIONS = 3`.
  - Khi ở dưới đất: Cú nhảy đầu tiên là Ground Jump (không tốn ngân sách `air.jump()`).
  - Khi lên không trung: Được cấp thêm 2 lần nhảy `air.jump()`.
  - Tổng số lần nhảy: 1 (đất) + 2 (không) = 3 lần nhảy.
- **Giải pháp xử lý:**
  - Cập nhật [`UnarmedRules.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/common/src/com/smashprofs/common/model/UnarmedRules.java):
    `public static final int AIR_JUMPS = 1, AIR_ACTIONS = 2;`
  - Bây giờ:
    - Nhảy lần 1: Nhảy từ đất (Ground Jump).
    - Nhảy lần 2: Nhảy trên không (Air Jump - tiêu thụ 1 lượt duy nhất).
    - Lần nhảy tiếp theo trên không: Bị từ chối vì hết ngân sách (`air.jumps() == 0`).
    - Tổng số lần nhảy tối đa chính xác là **2 lần**.
  - Cập nhật [`OnlineHud.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/core/src/com/smashprofs/game/Scenes/OnlineHud.java): Hiển thị `Nhảy X/1` (thay vì `/2`).

---

## ✅ 5. Đòn đánh nhẹ (`J`) thành chuỗi Combo 3 phát liên tục (3-Hit Jab String) — [ĐÃ KHẮC PHỤC TRIỆT ĐỂ & VERIFIED 100%]

- **Hiện tượng & Yêu cầu:**
  - Phím đánh nhẹ `J` (hoặc `C`) phải là chuỗi combo 3 phát:
    - *Phát 1 & Phát 2:* Kẻ địch chưa bị văng đi mà hơi dính vào chiêu đánh thường một chút (vacuum / hitstun giữ chặt mục tiêu ngay trước mặt).
    - *Phát 3:* Kẻ địch mới văng nhẹ ra trước (nhẹ hơn đáng kể so với đòn đánh mạnh `K`).
- **Giải pháp xử lý:**
  1. Trong [`FighterMoves.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/common/src/com/smashprofs/common/model/FighterMoves.java):
     - Định nghĩa chuỗi 3 đòn jab:
       - `NLIGHT` (Phát 1): Sát thương 7.0f, base stun 12.
       - `NLIGHT2` (Phát 2): Sát thương 7.0f, base stun 12.
       - `NLIGHT3` (Phát 3 - Finisher): Sát thương 8.0f, góc hất 30 độ, base knockback `0.85f` (nhẹ hơn nhiều so với đòn mạnh `1.6f - 1.7f`).
  2. Trong [`FighterEngine.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/server/src/com/smashprofs/server/game/FighterEngine.java):
     - Thêm bộ đếm `lightComboStep` và `lastLightAttackTick`. Khi người chơi bấm đánh nhẹ Neutral Light liên tiếp trong vòng 32 ticks (khoảng 0.5s):
       - Bấm phát 1 -> ra `NLIGHT` (Step 1).
       - Bấm phát 2 -> ra `NLIGHT2` (Step 2).
       - Bấm phát 3 -> ra `NLIGHT3` (Step 3).
       - Bấm tiếp -> lặp lại chu kỳ mới từ Step 1.
     - Trong hàm `hit(...)`:
       - Khi trúng `NLIGHT` hoặc `NLIGHT2`: Áp dụng cơ chế **Vacuum Lock**: Nạn nhân được hút giữ nhẹ nhàng ở cự ly lý tưởng `0.26m` ngay trước mặt người ra đòn (`pullVx`), không bị văng (`tumble = false`), tạo điều kiện để phát thứ 2 và phát thứ 3 kết nối dính 100%.
       - Khi trúng `NLIGHT3`: Nạn nhân được giải phóng và văng nhẹ về phía trước theo góc 30 độ (`base 0.85f`), hoàn thành chuỗi combo 3 đòn cực kỳ đã tay.

---

## 🎮 BẢNG HƯỚNG DẪN TỔ HỢP PHÍM TEST TOÀN BỘ TÍNH NĂNG GAME

Nhấn `F6` trong trận đấu để chuyển đổi giữa **Preset B** và **Preset A**.

### 1. Bảng phím cơ bản:

| Hành động | Preset B (Mặc định - Khuyên dùng) | Preset A (Cổ điển) | Mô tả chi tiết |
| :--- | :---: | :---: | :--- |
| **Di chuyển Trái / Phải** | `A` / `D` | `←` / `→` | Đi bộ / Chạy / Sprint |
| **Nhảy (Tối đa 2 lần)** | `W` | `↑` | Lần 1: Nhảy từ đất. Lần 2: Nhảy trên không |
| **Cúi / Xuống nền** | `S` | `↓` | Cúi người / Rơi nhanh (`Fast Fall`) / Rơi xuyên sàn một chiều (`Drop Through`) |
| **Lướt đất (Dash / Roll)** | `L + A` hoặc `L + D` | `Z + ←` hoặc `Z + →` | Lướt nhanh né tránh sát mặt đất (bấm `L` khi đang đi hoặc giữ `L` bấm hướng) |
| **Né tại chỗ (Spot Dodge)** | `L` (khi đứng yên) | `Z` (khi đứng yên) | Bất tử trong thoáng chốc tại chỗ |
| **Né trên không (Air Dodge)** | `L` hoặc `L + Hướng` | `Z` hoặc `Z + Hướng` | Né tại chỗ hoặc lướt né theo 8 hướng trên không |
| **Đánh nhẹ (Light Attack)** | `J` | `C` | Đòn đánh thường |
| **Đánh mạnh (Heavy / Sig)** | `K` (hoặc giữ `K` tụ lực) | `X` (hoặc giữ `X` tụ lực) | Đòn đánh mạnh uy lực lớn, hất văng xa |
| **Kỹ năng đặc trưng** | `F` (hoặc `P`) | `F` (hoặc `P`) | Tên lửa Alex / Sao Maurice / Mìn Leo / Sao xoay Luca / IBM Viktor |
| **Nhặt / Dùng vũ khí phụ** | `E` | `E` | Nhặt shotgun / bazooka rơi trên sân |
| **Vứt vũ khí** | `G` | `G` | Ném vũ khí vào người đối thủ |
| **Bật/Tắt HUD Stun Debug** | `F2` | `F2` | Xem hitbox và thời gian choáng thực tế |

---

### 2. Các tổ hợp Combo cần test:

1. **Combo 3 phát đánh nhẹ (3-Hit Jab):**
   - Đứng gần đối thủ, nhấn `J -> J -> J` liên tục (nhịp bấm đều tay).
   - *Kết quả quan sát:* Phát 1 và Phát 2 đấm trúng giữ dính đối thủ ngay trước mặt; Phát 3 đấm văng đối thủ nhẹ ra xa.
2. **Lướt đất (Ground Dash):**
   - Đang đứng hoặc đang chạy, nhấn `L + A` (lướt trái) hoặc `L + D` (lướt phải).
   - *Kết quả quan sát:* Nhân vật lướt vút sang hướng bấm mượt mà, HUD hiển thị `DASH` / `BACKDASH`.
3. **Nhảy 2 lần:**
   - Bấm `W` (nhảy lần 1), lên đỉnh bấm tiếp `W` (nhảy lần 2). Bấm `W` lần thứ 3 sẽ không nhảy nữa. HUD góc dưới hiển thị `Nhảy 0/1`.
4. **Hất tung nối chiêu (DLight -> NAir):**
   - Đứng cạnh đối thủ, bấm `S + J` (quét chân hất tung đối thủ lên trời) -> Lập tức bấm `W + J` (nhảy lên đấm trên không).
5. **Kỹ năng đặc trưng (F):**
   - Bấm `F` để thi triển chiêu riêng biệt của từng nhân vật.

---

## 8. Cập nhật tinh chỉnh theo phản hồi kiểm thử (Phase 3)

### 8.1. Lướt né mặt đất (`A + L` hoặc `D + L`) có i-frames và hồi chiêu
- **Vấn đề trước đó:** Khi đứng trên mặt đất, tổ hợp `A + L` hoặc `D + L` bị quy ước thành cú lướt nhanh thuần túy (`DASH`), không có hiệu ứng tàng hình/né chiêu và không hiển thị thời gian hồi chiêu né.
- **Giải pháp:**
  - Trong [`FighterEngine.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/server/src/com/smashprofs/server/game/FighterEngine.java): Khi bấm `L` (dodge) cùng hướng `A`/`D` trên mặt đất, nhân vật thực hiện **Lướt né chiêu** (`GROUND_SPOT` dạng lướt hướng):
    - Nhận đầy đủ **16 frames bất tử (i-frames)** bảo vệ tuyệt đối trước đòn đánh và đạn.
    - Kích hoạt thời gian hồi chiêu `UnarmedRules.GROUND_COOLDOWN` (60 ticks / 1.0 giây).
    - Vận tốc lướt đạt chuẩn `FighterMovement.DODGE_SPEED` (2.6 m/s).
  - Client hiển thị trạng thái `DODGE` (sprite chuyển màu cyan phát sáng, mờ ảo) và thanh HUD hiển thị đồng hồ đếm ngược `CD Né` rõ ràng.

### 8.2. Hạ thấp độ cao bệ hồi sinh (Respawn Platform Spawn Height)
- **Vấn đề trước đó:** Nhân vật sau khi chết rơi ra ngoài đài đấu được hồi sinh trên bệ lơ lửng ở độ cao quá cao (`spawnY ~ 3.45m` sát nóc bản đồ).
- **Giải pháp:**
  - Trong [`FighterEngine.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/server/src/com/smashprofs/server/game/FighterEngine.java): Điều chỉnh tọa độ hồi sinh xuống `spawnY = 1.35f` (ngay trên đầu mặt sàn trung tâm khoảng 1m).
  - Nhân vật đứng trên bệ an toàn, dễ quan sát và bước xuống tham chiến ngay lập tức.

### 8.3. Nhịp combo 3 đòn đánh thường (`J -> J -> J`) và chống văng sớm
- **Vấn đề trước đó:** Người chơi có thể spam phím `J` liên tục với tần suất quá cao, và các đòn đánh thường ban đầu đã làm kẻ địch văng xa khiến chuỗi combo bị đứt đoạn.
- **Giải pháp:**
  - **Nhịp độ đòn đánh (Attack Pacing):** Thiết lập `f.nextLightAttackTick` để người chơi ra đòn theo nhịp võ thuật chuẩn mực (12 frames giữa các đòn, và 10 frames phục hồi sau khi tung đòn kết liễu thứ 3).
  - **Khóa hút đòn 1 & 2 (`NLIGHT` & `NLIGHT2`):** Trong logic `hit(...)`, đòn 1 và đòn 2 áp dụng lực hút nhẹ `pullVx` và triệt tiêu vận tốc văng (`vy = 0`), giữ chân nạn nhân đứng áp sát ngay trước mặt kẻ tấn công mà chưa bị hất văng đi.
  - **Đòn kết liễu thứ 3 (`NLIGHT3`):** Đòn thứ 3 mới kích hoạt lực đẩy knockback và trạng thái `tumble`, hất kẻ địch văng ra xa vừa phải, kết thúc chuỗi combo mãn nhãn.

### 8.4. Hồi sinh ngẫu nhiên các vị trí an toàn trên sàn đấu (Random Safe Respawn)
- **Vấn đề trước đó:** Vị trí hồi sinh bị gán cứng cố định ở giữa bản đồ (`x = 6.0m`), khiến người chơi hồi sinh dễ đoán và bị đối thủ camp tại chỗ.
- **Giải pháp:**
  - Trong [`GameplayMatch.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/server/src/com/smashprofs/server/game/GameplayMatch.java) và [`FighterEngine.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/server/src/com/smashprofs/server/game/FighterEngine.java):
    - Sử dụng danh sách các điểm ứng viên an toàn trên sàn/bệ đấu (`respawnCandidates`) do `SpawnProvider` tính toán.
    - **Loại trừ 100% dung nham:** Tất cả các vị trí trên hoặc gần vùng dung nham (`lava_hazard`) đều bị thuật toán loại bỏ hoàn toàn.
    - **Chống camper:** Thuật toán tính toán khoảng cách Euclidean tới các đối thủ đang sống, ưu tiên chọn ngẫu nhiên giữa các vị trí an toàn xa kẻ địch nhất.
    - Giữ độ cao bệ hồi sinh ở tầm thấp vừa vặn (`Math.max(1.35f, safe.yMeters() + 0.35f)`).

### 8.5. Nhấp 2 lần phím `S` (Double-tap `S`) để rơi xuyên bậc thềm trên cao xuống sàn dưới
- **Vấn đề trước đó:** Để tụt từ bệ trên cao (one-way platform) xuống sàn dưới, người chơi phải bấm tổ hợp `S + W` (xuống + nhảy), gây ngược tay và khó thao tác trong lúc giao tranh nhanh.
- **Giải pháp:**
  - Trong [`UnarmedControls.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/core/src/com/smashprofs/game/network/client/UnarmedControls.java): Client ghi nhận nhấp kép phím `S` (hoặc phím `DOWN` ở Preset A) trong vòng 350ms và phát tín hiệu `dropPressed`.
  - Trong [`FighterEngine.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/server/src/com/smashprofs/server/game/FighterEngine.java): Server cũng độc lập theo dõi nhịp bấm phím xuống (`f.lastDownPressTick <= 22`). Khi người chơi đang đứng trên bệ một chiều và nhấp 2 lần phím `S`, server kích hoạt `physics.dropThrough(id)`:
    - Vô hiệu hóa va chạm sàn trong 15 frames.
    - Cấp vận tốc rơi xuống `vy = -1.0 m/s`, giúp nhân vật rơi xuống tầng dưới mượt mà.
  - Trong [`LocalFighterPrediction.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/core/src/com/smashprofs/game/network/client/LocalFighterPrediction.java): Bộ dự đoán client đồng bộ mô phỏng rơi xuyên sàn mềm tức thì, không bị giật lag khung hình.
  - Vẫn giữ nguyên tổ hợp `S + W` cho người chơi quen tay cũ.

### 8.6. Bỏ trạng thái đứng yên né dưới đất (Lướt hướng mặc định) & Tinh chỉnh hút dính đòn 1, 2 Combo Đánh nhẹ (Jab)
- **Vấn đề trước đó:**
  1. Khi người chơi đứng yên trên mặt đất và chỉ nhấn phím `L`, nhân vật thực hiện né tại chỗ (spot dodge), không lướt đi đâu cả. Người chơi mong muốn luôn luôn lướt sang trái hoặc phải, kể cả khi chưa kịp bấm phím di chuyển `A`/`D`.
  2. Ở combo đánh nhẹ 3 phát (`J -> J -> J`): Đòn đầu tiên khi đánh trúng đã làm kẻ địch bị đẩy lùi/bật nhẹ ra xa, đòn 2 hút vào, đòn 3 đẩy ra. Điều này khiến combo không giữ được nhịp dính và người chơi cảm giác bị văng sớm.
- **Giải pháp:**
  1. **Lướt hướng mặt đất mặc định khi chỉ nhấn `L`:**
     - Trong [`FighterEngine.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/server/src/com/smashprofs/server/game/FighterEngine.java):
       ```java
       if(grounded) {
           int facing = game.state.getPlayer(id).isFacingRight() ? 1 : -1;
           int direction = x != 0 ? (x < 0 ? -1 : 1) : facing;
           f.dodgeX = direction * FighterMovement.DODGE_SPEED;
           f.dodgeY = 0;
       }
       ```
     - Khi đứng dưới đất và nhấn `L`, hệ thống luôn luôn kích hoạt cú lướt né di chuyển với tốc độ `2.6 m/s`:
       - Nếu đang giữ `A` hoặc `D`: lướt theo hướng phím đang bấm (`x < 0 ? -1 : 1`).
       - Nếu không bấm phím hướng nào: mặc định lướt vút theo hướng mặt nhân vật đang quay về (`isFacingRight() ? 1 : -1`, tức hướng phím `A`/`D` cuối cùng người chơi đã bấm).
       - Hoàn toàn triệt tiêu trạng thái đứng yên né trên mặt đất, nhận đầy đủ 16 i-frames và hồi chiêu né.
  2. **Tiến về phía trước khi tung đòn đánh nhẹ (Attacker Step-Forward):**
     - Khi tung các đòn đánh nhẹ trên mặt đất (`NLIGHT`, `NLIGHT2`, `SLIGHT`): người ra đòn được cấp xung vận tốc tiến về phía trước `0.90 m/s` (khoảng 3.5–4 cm mỗi đòn) để áp sát kẻ địch.
     - Đòn kết liễu thứ 3 (`NLIGHT3`): nhân vật dấn mạnh hơn (`1.15 m/s`) để tung cú đấm quyết định.
  3. **Hút dính chặt mục tiêu ở Đòn 1 và Đòn 2 (Lock-In Suction):**
     - Trong logic `hit(...)`:
       ```java
       boolean isLightHold = attacker != null && (moveName.startsWith("NLIGHT") && !moveName.equals("NLIGHT3") || moveName.equals("SLIGHT"));
       if(isLightHold) {
           float attackerX = game.bodies.getPosition(attacker).xMeters();
           float facing = game.state.getPlayer(attacker).isFacingRight() ? 1f : -1f;
           float targetX = attackerX + facing * 0.15f;
           float currentVictimX = game.bodies.getPosition(victim).xMeters();
           float pullVx = (targetX - currentVictimX) * 6.0f;
           physics.velocity(victim, Math.max(-1.5f, Math.min(1.5f, pullVx)), 0);
           f.tumble = false;
       }
       ```
     - Cự ly neo mục tiêu được điều chỉnh sát ngực (`0.15m`).
     - Triệt tiêu hoàn toàn vận tốc nâng bổng (`vy = 0`), giữ chân nạn nhân đứng áp sát trên sàn và không bị nảy bổng hay trượt lùi ở đòn 1 và đòn 2.
     - Chỉ khi tung trúng Đòn thứ 3 (`NLIGHT3`), lực đẩy knockback và trạng thái `tumble` mới được kích hoạt, hất văng đối thủ ra xa.

### 8.7. Thu hẹp tầm đánh thường (Hitbox ngắn lại ~3–4 lần) & Giảm độ văng đòn đánh thường xuống 3 lần
- **Vấn đề trước đó:**
  1. **Tầm đánh thường quá dài:** Kích thước hộp va chạm (`halfWidth`) của các đòn đánh nhẹ (`NLIGHT`, `NLIGHT2`, `NLIGHT3`) trước đây lên tới `0.32m - 0.40m`, vươn xa tới `0.33m - 0.42m` từ tâm người (vươn hơn 25cm - 33cm ngoài mép cơ thể). Người chơi dù đứng cách xa cả một khoảng lớn vẫn đấm trúng, tạo cảm giác đấm trúng ảo không chân thật.
  2. **Độ văng của đòn thứ 3 vẫn hơi mạnh:** Sau khi trúng đòn 3 của combo nhẹ, kẻ địch vẫn bị đẩy bay với vận tốc `~1.66 m/s - 3.52 m/s`, văng xa làm mất nhịp dồn ép đối thủ.
- **Giải pháp thực hiện trong [`FighterMoves.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/common/src/com/smashprofs/common/model/FighterMoves.java):**
  1. **Thu hẹp tầm đánh thường (Hitbox):**
     - Thu hẹp `halfWidth` của `NLIGHT` từ `0.16f` xuống `0.055f` (giảm gần 3 lần), `NLIGHT2` từ `0.18f` xuống `0.055f`, `NLIGHT3` từ `0.20f` xuống `0.06f`.
     - Điều chỉnh tâm hộp va chạm `x` ôm sát nắm đấm (`0.135m - 0.145m`), tầm với ngoài thân người được rút ngắn lại ~3–4 lần (chỉ còn khoảng `7.5cm - 12cm` phía trước cơ thể).
     - Người chơi phải thực sự áp sát đối thủ (đánh giáp lá cà cận chiến) thì cú đấm mới kết nối, triệt tiêu hoàn toàn hiện tượng đấm trúng từ xa.
  2. **Giảm độ văng khi đánh nhẹ xuống 3 lần:**
     - `base` lực văng của `NLIGHT3` giảm từ `0.85f` xuống `0.28f` (giảm đúng 3 lần).
     - `scaling` tăng theo sát thương giảm từ `0.20f` xuống `0.07f` (giảm gần 3 lần).
     - Vận tốc văng ở 0% máu giảm từ `~1.66 m/s` xuống còn `~0.53 m/s` (giảm 3 lần).
     - Vận tốc văng ở 50% máu giảm từ `~3.52 m/s` xuống còn `~1.20 m/s` (giảm 3 lần).
     - Đòn thứ 3 kết thúc combo nhẹ giờ đây chỉ đẩy lùi đối thủ nhẹ nhàng một bước ngắn (~0.5m), giữ đối thủ ở cự ly vừa vặn để người chơi tiếp tục kiểm soát võ đài.

### 8.8. Loại bỏ toàn bộ phím F1–F12 & Rút ngắn chuẩn xác đường vung đòn màu trắng (đánh nhẹ) và màu vàng (đánh mạnh)
- **Vấn đề phản hồi:**
  1. Người chơi sử dụng laptop (Asus ROG M16) có phím chức năng F1–F12 mặc định gán cho các phím đa phương tiện phần cứng (tăng giảm âm lượng, độ sáng, quạt tản nhiệt), không thể bấm được các phím `F2`, `F6`, `F9`.
  2. Khi ra đòn đánh nhẹ (phím `J`) và đòn đánh mạnh (phím `K`), trên màn hình hiển thị một đường kẻ vệt đòn đánh (đường màu trắng cho đánh nhẹ, đường màu vàng cho đánh mạnh). Hai đường kẻ này kéo dài ngoẵng xuyên suốt nửa võ đài (dài tới 0.44m – 0.60m), trông như vung kiếm laser thay vì đấm bốc cận chiến.
- **Giải pháp xử lý:**
  1. **Loại bỏ 100% việc sử dụng các phím F1–F12:**
     - **Đổi Preset phím:** Chuyển từ `F6` sang phím **`O`** (bấm tiện lợi bằng tay phải). Cập nhật toàn bộ gợi ý giao diện HUD: `O đổi phím`.
     - **Bật/tắt Hitbox & Stun Debug:** Chuyển từ `F2` sang phím **`U`** (hoặc dấu huyền/ngã **`~`**). Cập nhật gợi ý HUD: `U hitbox / Show Stun`.
     - **Ngắt kết nối / Thoát test:** Chuyển từ `F9` sang phím **`BACKSPACE`** trong cả `OnlinePlayScreen` và `PlayScreen`.
  2. **Rút ngắn đường vệt đòn màu trắng và màu vàng ([`OnlinePlayScreen.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/core/src/com/smashprofs/game/Screens/OnlinePlayScreen.java)):**
     - **Nguyên nhân cũ:** Code cũ vẽ từ tâm nhân vật `p.x()` phóng ra xa gấp đôi tầm đánh `move.x() * 2`. Với `SHEAVY`, `move.x() = 0.30m` nên đường màu vàng bị kéo dài tới `0.60m` (gấp 3.5 lần chiều rộng thân nhân vật). Với `SLIGHT`, đường màu trắng dài tới `0.44m`.
     - **Khắc phục chuẩn xác:**
       - Điểm bắt đầu vẽ được neo chuẩn từ ngực/vai trước (`p.x() + dir * 0.065f`), không còn vẽ xuyên từ tim ra ngoài.
       - **Đường màu trắng (đánh nhẹ J - `NLIGHT`, `SLIGHT`):** Kéo dài từ `0.065m` tới `0.14m` (chiều dài vệt đòn chỉ khoảng `0.075m`, ôm sát nắm đấm), độ dày mảnh `.012f`. Vệt đòn ngắn lại gấp 4 lần so với ban đầu.
       - **Đường màu vàng (đánh mạnh K - `SHEAVY`):** Kéo dài từ `0.065m` tới `0.22m` (chiều dài vệt đòn khoảng `0.155m`), màu vàng kim rực rỡ, độ dày dũng mãnh `.020f`. Vệt đòn rõ nét, vươn dài hơn đòn nhẹ nhưng không còn bị quá dài lố lăng qua nửa bản đồ.
  3. **Đồng bộ hộp va chạm (Hitbox) trong [`FighterMoves.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/common/src/com/smashprofs/common/model/FighterMoves.java):**
     - `SLIGHT` (đánh nhẹ khi đang di chuyển ngang): Đồng bộ rút ngắn `x = 0.14f, halfWidth = 0.055f` (tầm với tối đa `0.195m`, thay vì `0.42m` trước đây).
     - `SHEAVY` (đánh mạnh khi đang di chuyển ngang): Tinh chỉnh `x = 0.19f, halfWidth = 0.09f` (tầm với tối đa `0.28m`, thay vì `0.59m` trước đây).
     - `NHEAVY` & `SAIR`: Tinh chỉnh độ vươn tương ứng để khớp hoàn toàn giữa vùng va chạm thực tế và vệt hình ảnh hiển thị.

### 8.9. Sửa lỗi rơi xuyên bậc thềm 3 tầng, đổi phím Nhảy thành Space & Cơ chế ngắm/điều hướng 8 hướng WASD
- **Vấn đề phản hồi:**
  1. **Lỗi rơi xuyên cấu trúc 3 bậc thềm:** Khi nhân vật đang đứng ở bậc cao nhất (bậc 1), ấn 2 lần phím `S` (hoặc `S + Space`) thì bị rơi tuột thẳng xuống tận bậc đáy cùng (bậc 3), thay vì dừng lại ở bậc thứ 2.
  2. **Đổi phím nhảy sang `Space`:** Phím `W` trước đây vừa dùng để nhảy vừa hướng lên gây xung đột khi muốn ngắm bắn/đánh lên trên. Người chơi muốn dùng `Space` để nhảy độc lập, phím `W` chuyên dụng để hướng lên.
  3. **Cơ chế điều hướng 8 hướng bằng cụm WASD:**
     - Các nút `W`, `A`, `S`, `D` dùng để điều hướng đòn đánh thường, hướng bắn súng và chiêu kỹ năng `F`.
     - Khi bấm 2 nút điều hướng liền kề (ví dụ `W + D`, `W + A`, `S + D`, `S + A`): đòn đánh/đạn bắn bay theo góc chéo **45 độ**.
     - Khi bấm phím đơn lẻ (`W`: 90° thẳng đứng lên; `S`: -90° thẳng đứng xuống; `D`: 0° ngang phải; `A`: 180° ngang trái).
     - Khi không bấm phím điều hướng nào: mặc định đánh/bắn theo hướng mặt nhân vật đang quay về.
     - Vệt đường đòn đánh màu trắng (J) và màu vàng (K) phải vẽ nghiêng theo góc 45°/90° và có độ dài ngắn gọn, sắc sảo.

- **Giải pháp & Triển khai thực tế:**
  1. **Sửa lỗi rơi xuyên bậc thềm ([`FighterPhysics.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/server/src/com/smashprofs/server/game/physics/FighterPhysics.java) & [`LocalFighterPrediction.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/core/src/com/smashprofs/game/network/client/LocalFighterPrediction.java)):**
     - **Nguyên nhân:** Trước đây bộ đếm `dropTicks` tắt va chạm với toàn bộ mọi bậc thềm mềm trong 15 tick (0.25 giây). Trong 15 tick rơi tự do, nhân vật rơi được quãng đường ~0.55m, vượt qua chiều cao giữa bậc 1 và bậc 2, khiến nhân vật rơi xuyên qua cả bậc 2 xuống bậc 3.
     - **Khắc phục:** Thêm `Map<PlayerId, CollisionRectangle> droppedPlatform`. Khi người chơi bấm 2 lần `S` để rơi qua bậc thềm, server chỉ tắt va chạm với **chính xác bậc thềm mà người chơi vừa đứng trên**. Khi nhân vật rơi chạm tới bậc tiếp theo (bậc 2), do bậc 2 khác với `droppedPlatform`, hệ thống kích hoạt va chạm solid ngay lập tức giúp nhân vật hạ cánh an toàn ở bậc 2. Muốn xuống tiếp bậc 3, người chơi bấm thêm 2 lần `S` lần nữa.
  2. **Tách phím Nhảy độc lập sang `Space` ([`UnarmedControls.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/core/src/com/smashprofs/game/network/client/UnarmedControls.java)):**
     - Gán `jump = Input.Keys.SPACE`.
     - Phím `W` (Preset B) và `UP` (Preset A) được giải phóng khỏi lệnh nhảy, đóng vai trò độc lập là phím hướng lên (`upHeld`).
     - Người chơi có thể vừa nhảy bằng `Space`, vừa giữ `W` hoặc `W + D` để ngắm đòn đánh hoặc bắn súng chéo 45 độ trên không trung.
  3. **Hệ thống ngắm 8 hướng cho Đòn đánh, Vũ khí và Skill F:**
     - **Đòn đánh cận chiến ([`FighterEngine.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/server/src/com/smashprofs/server/game/FighterEngine.java)):**
       - Tính toán `aimAngle`: `W + D / W + A` -> `45°`; `S + D / S + A` -> `-45°`; `W` -> `85°~90°`; `S` -> `-85°~-90°`; ngang -> `move.angle()`.
       - Hộp va chạm (`physics.targets`) được tịnh tiến theo vector góc `aimAngle`: `offsetX = dir * (x * cos(rad))`, `offsetY = y + x * sin(rad)`.
       - Vector lực hất văng (`knockback`) được phóng theo hướng góc vung đòn: `dir * cos(rad), sin(rad)`.
     - **Bắn vũ khí ([`ItemSystem.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/server/src/com/smashprofs/server/game/ItemSystem.java)):**
       - Đạn Shotgun, Laser, Bazooka, Lựu đạn khi bắn được tính góc chuẩn `baseAngle = atan2(aimY, aimX)`. Khi giữ `W+D`, đạn bazooka/laser bay vút chéo 45 độ lên trên; khi giữ `W`, bắn thẳng đứng lên trời; khi giữ `S+D`, bắn chéo 45 độ xuống dưới.
     - **Skill F ([`ProjectileManager.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/server/src/com/smashprofs/server/game/projectile/ProjectileManager.java) & [`MatchRuntime.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/server/src/com/smashprofs/server/game/MatchRuntime.java)):**
       - Tên lửa Alex, máy tính Viktor (IBM), phi tiêu Maurice (Star), mìn Leo (Mine) xuất kích theo hướng vector ngắm 8 hướng từ cụm WASD.
     - **Vệt đòn đánh ([`OnlinePlayScreen.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/core/src/com/smashprofs/game/Screens/OnlinePlayScreen.java)):**
       - Render vệt đòn chéo 45 độ, thẳng đứng 90 độ hoặc nằm ngang theo đúng trạng thái phím WASD của người chơi.
       - Độ dài vệt đòn thu gọn tinh tế (`0.042m` cho đòn nhẹ, `0.070m` cho đòn mạnh), sắc gọn, rõ ràng, không còn bị dài ngoẵng.

### 8.10. Thêm nút Cài đặt & Hệ thống tùy chỉnh gán phím tự do (Custom Keybindings)
- **Yêu cầu phản hồi:**
  1. Thêm 1 nút **CÀI ĐẶT PHÍM** ở menu chính chọn chế độ chơi (`MainMenuScreen`).
  2. Cho phép người chơi tự do chỉnh nút điều khiển tùy ý theo thói quen cá nhân, không bị bó buộc vào các nút mặc định.
- **Giải pháp & Triển khai thực tế:**
  1. **Thêm nút Cài đặt phím tại Menu chính ([`MainMenuScreen.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/core/src/com/smashprofs/game/Screens/MainMenuScreen.java)):**
     - Đặt thêm nút **`CÀI ĐẶT PHÍM  [C]`** (hoặc ấn nhanh phím `C`/`S`) ở giữa bảng chọn chế độ chơi.
     - Dòng gợi ý: `Tùy chỉnh phím điều khiển (di chuyển, nhảy, đánh, né...)`.
     - Cân đối kích thước bảng `menuCard` và 2 bệ tượng Alex/Leo từ `350px` lên `400px`, giúp 4 nút (Chơi Local, Chơi Online, Cài đặt phím, Thoát game) nằm vừa vặn, chuẩn phong cách Comic Brawlhalla.
  2. **Quản lý cấu hình phím tập trung & Lưu trữ lâu dài ([`KeyBindingConfig.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/core/src/com/smashprofs/game/Helper/KeyBindingConfig.java)):**
     - Cung cấp 12 hành động có thể gán phím:
       - **Di chuyển & Nhảy:** Sang trái, Sang phải, Hướng lên / Ngắm, Hướng xuống / Rơi sàn, Nhảy.
       - **Chiến đấu:** Đánh nhẹ / Bắn súng, Đánh mạnh, Né / Lướt, Kỹ năng F.
       - **Vật phẩm:** Nhặt đồ, Thả đồ, Ném vũ khí.
     - Tự động lưu trữ và tải vào LibGDX `Preferences ("smash-profs-keybindings")`. Khi người chơi chỉnh phím xong và thoát game, cấu hình vẫn được lưu lại cho các lần chơi sau.
  3. **Hộp thoại Cài đặt trực quan (Settings Modal):**
     - Hiển thị bảng cài đặt 2 cột rõ ràng với nhãn hành động và nút phím hiện tại.
     - Khi bấm vào một ô phím: nút chuyển sang trạng thái `[ BẤM PHÍM... ]`. Người chơi chỉ cần ấn phím bất kỳ trên bàn phím (kể cả Space, Ctrl, Shift, Alt, phím số, chữ cái...) để gán ngay lập tức. Ấn `ESC` để hủy gán.
     - Cung cấp 2 mẫu phím nhanh:
       - **`MẶC ĐỊNH (WASD)`**: Khôi phục nhanh về bố cục chuẩn (WASD, Space, J, K, L, F, E, G, Q).
       - **`MẪU MŨI TÊN`**: Khôi phục nhanh về bố cục phím mũi tên (Mũi tên, Space, C, X, Z, P, E, G, Q).
       - **`LƯU & ĐÓNG  [ESC]`**: Lưu và áp dụng ngay lập tức.
  4. **Áp dụng đồng bộ cho toàn bộ trò chơi:**
     - [`UnarmedControls.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/core/src/com/smashprofs/game/network/client/UnarmedControls.java): Lấy phím di chuyển, nhảy, tấn công, né, nhặt/thả/ném trực tiếp từ `KeyBindingConfig`.
     - [`OnlinePlayScreen.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/core/src/com/smashprofs/game/Screens/OnlinePlayScreen.java): Sử dụng phím kỹ năng và 4 hướng ngắm đòn đánh từ cấu hình tùy chỉnh.
     - [`OnlineHud.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/core/src/com/smashprofs/game/Scenes/OnlineHud.java): Thanh giao diện HUD tự động hiển thị đúng tên phím mà người chơi đã cài đặt (ví dụ: `J: đánh`, `F · SẴN SÀNG`, v.v.).
     - [`Player.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/core/src/com/smashprofs/game/Actors/Players/Player.java) & [`PlayScreen.java`](file:///C:/Users/ROG%20M16/Downloads/LTM/lap_trinh_mang/final-project/core/src/com/smashprofs/game/Screens/PlayScreen.java): Chế độ Chơi Local 2 người cũng tự động nhận diện phím tùy chỉnh của Người chơi 1.
