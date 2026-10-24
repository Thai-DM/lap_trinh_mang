# ĐẶC TẢ TÍNH NĂNG TOÀN DIỆN CHO NETWORKED SUPER SMASH PROFS
*(Tài liệu chuẩn hóa danh mục tính năng chính thức & các đề xuất mở rộng đột phá)*

---

## 📌 PHẦN I: TÍNH NĂNG CHÍNH THỨC ĐÃ CHỐT (CORE FEATURES)

### 1. Tùy biến cấu hình phòng đấu (Host Lobby Settings)
Chủ phòng (Host) có bảng điều khiển trực quan để thiết lập luật thi đấu trước khi bấm bắt đầu:
* **Số người chơi tối đa (Max Players):**
  * Tùy chọn: `2 người`, `3 người`, hoặc `4 người` cùng chiến đấu trong một phòng.
* **Thời gian trận đấu tùy chỉnh (Match Time Limit):**
  * Tùy chọn: `60s`, `90s`, `120s`, `180s`, `300s`, hoặc **`Vô hạn (Infinite / Last Man Standing)`**.
  * Ở chế độ *Vô hạn*, không có đồng hồ đếm ngược, trận chiến kéo dài đến khi chỉ còn duy nhất 1 người sống sót!
* **Số mạng của mỗi người chơi (Stock / Lives System):**
  * Tùy chọn: `1 mạng` (Sudden Death), `2 mạng`, `3 mạng` (chuẩn esports), `5 mạng`.
* **Tần suất rơi vật phẩm (Item Drop Rate):**
  * Tùy chọn: **`Tắt (None)`** | **`Thấp (30s/lần)`** | **`Vừa (15s/lần)`** | **`Cao (8s/lần)`**.

---

### 2. Cơ chế Số mạng, Hồi sinh & Chuyển sang Khán giả (Stock, Respawn & Spectator)
* **Hồi sinh (Respawn with Invulnerability):**
  * Mỗi khi máu tụt về 0 hoặc rơi xuống nham thạch, người chơi mất 1 mạng (`lives -= 1`).
  * Nếu còn mạng: Sau 1.5 giây, nhân vật hồi sinh lại tại bệ đá trung tâm với 100% HP.
  * Có **2 giây bất tử tạm thời (Invulnerability frames)**: Nhân vật nhấp nháy phát sáng, miễn nhiễm toàn bộ sát thương để chống bị đối thủ camp bệ hồi sinh.
* **Chết hẳn & Chuyển thành Khán giả (Elimination & Spectator Mode):**
  * Khi hết sạch mạng (`lives == 0`): Người chơi chính thức bị loại khỏi trận (`ELIMINATED`).
  * **Hành vi mạng:** Server ngắt quyền nhận phím điều khiển của người này.
  * **Hành vi Client:** Màn hình tự động chuyển sang chế độ Khán giả (Spectator), camera tự động lia bám theo các người chơi còn sống, góc màn hình hiện thông báo đỏ mờ: `BẠN ĐÃ HẾT MẠNG - ĐANG XEM TRẬN ĐẤU (SPECTATING)`.

---

### 3. Kho Vũ khí & Lựu đạn rơi ngẫu nhiên (Weapons & Grenades)
Vật phẩm xuất hiện trên các bệ đá dựa theo tần suất đã cài đặt ở sảnh:
* **Các loại Súng (Ranged Firearms):**
  * **Shotgun:** Bắn chùm 3 viên đạn tỏa hình quạt, cực mạnh ở cự ly gần.
  * **Laser / Sniper Rifle:** Bắn tia đạn siêu tốc xuyên thấu toàn màn hình.
  * **Bazooka / Rocket Launcher:** Bắn tên lửa bay tốc độ vừa phải, chạm mục tiêu phát nổ diện rộng hất văng cực mạnh.
* **Lựu đạn / Bom hẹn giờ (Throwable Explosives):**
  * Người chơi nhặt được lựu đạn cầm trên tay. Bấm phím tấn công để ném theo quỹ đạo parabol vật lý.
  * Nổ sau 2 giây hoặc khi va chạm đối thủ, tạo sóng xung kích hất tung.
* **Vật phẩm hồi phục & phòng thủ:**
  * **Bình Máu (Health Pack):** Hồi phục ngay +30 HP.
  * **Khiên Năng Lượng (Energy Shield):** Chặn hoàn toàn sát thương của 1 đòn đánh kế tiếp.

---

### 4. Thời gian hồi chiêu cho Chiêu Cuối (Ultimate Ability F Cooldown)
* Chiêu `F` (Tuyệt chiêu riêng của từng nhân vật) được quản lý thời gian hồi chiêu chặt chẽ:
  * **Server Authoritative:** Server đếm cooldown theo số tick (`600 ticks = 10 giây`), loại bỏ hoàn toàn khả năng hack spam chiêu từ Client.
  * **Giao diện HUD:** Icon chiêu `F` ở góc màn hình bị làm mờ xám kèm đồng hồ đếm ngược `[F: 8s]`. Khi sẵn sàng, icon phát sáng kèm hiệu ứng âm thanh và chữ `[F: READY!]`.

---

### 5. Hất văng tăng theo sát thương (Knockback % Scaling)
* Chuẩn phong cách đối kháng vật lý đỉnh cao:
  * Khi nhân vật còn đầy máu (100 HP): Bị trúng đòn chỉ lùi lại một khoảng ngắn.
  * Khi nhân vật còn ít máu (dưới 40 HP): Càng mất nhiều máu, lực đẩy lùi (Knockback Impulse) của cú đấm, đạn súng và lựu đạn càng nhân lên gấp bội, khiến nhân vật bị **thổi bay vút lên không trung** và rất dễ rớt thẳng xuống vực nham thạch!

---

### 6. Bám tường & Nhảy bật tường (Wall Slide & Wall Jump) *(MỚI THÊM VÀO)*
* **Cơ chế hoạt động:**
  * Khi bị đánh văng ra khỏi sàn đấu và đang rơi xuống vực, nếu nhân vật áp sát vào mép tường đá thẳng đứng của sàn đấu:
    * Nhân vật sẽ tự động **áp người bám vào vách tường và trượt chậm dần xuống** (Wall Slide).
    * Nhấn phím `W` ngay lúc đó để **nhảy bật ngược trở lại sàn đấu** (Wall Jump).
  * **Điểm nhấn gameplay:** Cho phép người chơi thực hiện các pha "lội ngược dòng" ngoạn mục từ cõi chết trở về, tăng tính kỹ năng cá nhân của game lên rất nhiều!

---

### 7. Bảng Thông báo Chiến công (Kill Feed & Combat Log)
* Góc trên bên phải màn hình hiển thị tức thời dòng chữ thông báo các sự kiện kịch tính:
  * `[Player 1] đã bắn hạ [Player 2] bằng Bazooka! 💥`
  * `[Player 3] trượt chân rơi xuống Nham thạch! 🔥`
  * `[Player 2] ĐÃ HẾT MẠNG! (Chuyển sang Khán giả) 👁️`
  * Thông báo tự động cuộn lên và mờ dần sau 4 giây.

---

### 8. Bảng Đo Độ Trễ & Báo Mạng Thời Gian Thực (In-Game Ping & RTT HUD)
* Góc màn hình hiển thị trực tiếp chất lượng kết nối của từng người chơi theo thời gian thực:
  * `P1 (Host): 4ms 🟢`
  * `P2: 28ms 🟢`
  * `P3: 115ms 🔴 (Lag)`
* Giúp người chơi và giảng viên theo dõi trực quan cơ chế Heartbeat PING/PONG và độ trễ mạng trong suốt trận đấu.

---

### 9. Bảng Tổng kết Chiến tích & Vinh danh MVP (Match MVP & Stats Screen)
* Khi trận đấu kết thúc, màn hình hiển thị bảng vàng tổng kết chi tiết từng chỉ số:
  * 👑 **Nhà Vô Địch (Champion):** Người sống sót cuối cùng.
  * 🎯 **Vua Sát Thương (Damage Dealer):** Người gây nhiều sát thương nhất trận.
  * 🔫 **Thợ Nhặt Đồ (Weapon Hoarder):** Người nhặt được nhiều súng/lựu đạn nhất.
  * ☠️ **Thần Chết (Most K.O.):** Người hạ gục nhiều mạng của đối thủ nhất.

---

## 🚀 PHẦN II: CÁC ĐỀ XUẤT TÍNH NĂNG MỚI TIẾP THEO (MỜI BẠN CHỌN THÊM)

Dưới đây là **6 đề xuất tính năng mới toanh**, tiếp tục bổ trợ cho bộ kỹ năng chiến đấu và độ mượt mà của game:

---

### 💡 Đề xuất 1: Lướt Né Chiêu / Lăn Né Đòn (Dash Evade / Air Dodge)
* **Gameplay:**
  * Nhấn đúp nhanh phím di chuyển `A-A` hoặc `D-D` (hoặc phím `Shift`): Nhân vật lướt vút sang một bên trong chớp mắt.
  * Trong 0.25 giây lướt đi, nhân vật có **Khung hình miễn nhiễm (Invulnerability frame)**: Né hoàn toàn đòn đấm của đối thủ hoặc đạn súng bay qua người!
* **Giá trị:** Tăng chiều sâu đối kháng (mind-game), người chơi có thể lướt né chiêu ulti hoặc lướt luồn ra sau lưng đối thủ để phản công.

---

### 💡 Đề xuất 2: Dậm Đất Tạo Sóng Xung Kích (Super Stomp & Ground Shockwave)
* **Gameplay:**
  * Hiện tại game có phím `S` (Stomp - dậm rơi nhanh xuống đất).
  * **Nâng cấp mới:** Khi dậm từ vị trí trên cao xuống bệ đá, cú tiếp đất tạo ra một **Vòng sóng xung kích (Shockwave)** lan tỏa sang hai bên:
    * Đối thủ đứng gần bệ đá sẽ bị hất tung lên trời hoặc mất thăng bằng trong 0.5 giây.
* **Giá trị:** Biến phím `S` thành một đòn tấn công bất ngờ từ trên không (Drop Attack) cực kỳ uy lực!

---

### 💡 Đề xuất 3: Hiệu ứng Kết Liễu Rung Màn Hình (Impact Frame & Ring-Out)
* **Gameplay:**
  * Khi ai đó tung cú đấm dứt điểm hoặc bắn đạn tiễn đối thủ mất mạng cuối cùng:
    * Màn hình khựng lại trong 0.1 giây (Hit-stop / Impact Freeze) tạo cảm giác lực đánh cực mạnh.
    * Camera hơi rung nhẹ (Screen Shake) và có tia chớp phát sáng theo hướng nhân vật bị thổi bay ra khỏi mép màn hình kèm chữ `RING OUT!`.
* **Giá trị:** Tạo cảm giác "đã tay", nhìn trận đấu hoành tráng như một tựa game thương mại thực thụ.

---

### 💡 Đề xuất 4: Lựu Đạn Lỗ Đen Hút Đối Thủ (Graviton / Black Hole Grenade)
* **Gameplay:**
  * Một loại lựu đạn đặc biệt hiếm: Khi ném ra bệ đá, nó tạo ra một **Vòng xoáy lỗ đen vũ trụ** trong 2.5 giây.
  * Vòng xoáy tạo lực hút vật lý (Box2D Gravitational Force) kéo tất cả người chơi và đạn bay ở xung quanh vào tâm vòng xoáy!
* **Giá trị:** Dùng để gom cụm 2-3 đối thủ lại một chỗ rồi lao vào xả đòn đấm diện rộng hoặc ném tiếp quả bom thứ hai để nổ tung cả đám!

---

### 💡 Đề xuất 5: Tự Đổi Màu Áo Khi Trùng Nhân Vật (Color Palette Swap)
* **Gameplay:**
  * Khi 2 người chơi cùng chọn chung một nhân vật (ví dụ: Cả 2 cùng chọn Viktor):
    * Người chơi 1 giữ màu áo gốc (áo trắng/xanh).
    * Người chơi 2 tự động được phủ màu áo khác (ví dụ: áo đỏ rực hoặc vàng cam).
* **Giá trị:** Người chơi và khán giả theo dõi không bao giờ bị hoa mắt nhầm lẫn giữa nhân vật của mình và nhân vật của đối phương.

---

### 💡 Đề xuất 6: Phòng Tập Một Mình Với Bot Gỗ (Practice Mode with Dummy Bot)
* **Gameplay:**
  * Một chế độ chơi tập luyện offline: Chỉ có 1 mình bạn và một con Bot đứng yên trên sàn đá.
  * Cho phép bạn thử các loại súng (Shotgun, Bazooka), tập ném lựu đạn theo quỹ đạo, và luyện tập kỹ năng **Bám tường & Nhảy bật tường** vừa thêm vào!
* **Giá trị:** Rất tiện lợi cho người chơi mới làm quen phím bấm và thử nghiệm tính năng trước khi bước vào phòng đấu mạng online thật.

---

## 📊 BẢNG THEO DÕI TIẾN TRÌNH TÍNH NĂNG

| STT | Tên tính năng | Phân loại | Trạng thái |
| :---: | :--- | :--- | :---: |
| 1 | Host Lobby Settings (Max player, Time, Lives, Drops) | Sảnh đấu | ✅ Đã chốt chính thức |
| 2 | Stock & Spectator (Số mạng, Hồi sinh, Khán giả) | Trận đấu | ✅ Đã chốt chính thức |
| 3 | Vũ khí & Lựu đạn rơi ngẫu nhiên (Weapons & Grenades) | Vật phẩm | ✅ Đã chốt chính thức |
| 4 | Ultimate Cooldown (Bộ đếm hồi chiêu F) | Cơ chế | ✅ Đã chốt chính thức |
| 5 | Knockback Scaling (Hất văng tăng theo sát thương) | Vật lý | ✅ Đã chốt chính thức |
| 6 | Bám tường & Nhảy bật tường (Wall Jump) | Kỹ năng | ✅ Đã chốt chính thức |
| 7 | Kill Feed (Bảng thông báo chiến công) | Giao diện | ✅ Đã chốt chính thức |
| 8 | Real-time Ping HUD (Bảng đo RTT từng người) | Mạng | ✅ Đã chốt chính thức |
| 9 | Match MVP & Stats (Bảng tổng kết chiến tích) | Kết quả | ✅ Đã chốt chính thức |
| 10 | 6 Đề xuất mới: Dash né đòn, Sóng dậm đất, Rung màn hình, Lỗ đen, Đổi màu áo, Phòng tập | Đề xuất | ⏳ Đang đợi bạn chọn thêm |
