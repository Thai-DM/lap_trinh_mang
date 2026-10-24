# TÀI LIỆU ĐẶC TẢ KIẾN TRÚC & GAMEPLAY PLATFORM FIGHTER (BRAWLHALLA STYLE)
## DỰ ÁN: SUPER SMASH PROFS — NÂNG CẤP GAMEPLAY TOÀN DIỆN (GIAI ĐOẠN 2)

> **Mục tiêu tối thượng:** Chuyển đổi toàn diện cơ chế gameplay trong trận của *Super Smash Profs* từ đối kháng trừ HP truyền thống sang chuẩn **2D Platform Fighter (tương tự Brawlhalla / Super Smash Bros)**: di chuyển linh hoạt trên không/mặt đất, hệ thống Hitbox/Hurtbox chuẩn frame, sát thương tích lũy theo %, lực đẩy Knockback lũy tiến, cơ chế Số mạng (Stocks), Blast Zone và K.O văng khỏi bản đồ.  
> **Nguyên tắc bảo tồn:** Giữ nguyên nhân vật (Alex, Luca, Maurice, Leo, Viktor), sprite/animation, âm thanh, phong cách đồ họa comic và kiến trúc Client/Server Box2D LibGDX hiện hữu.

---

## MỤC LỤC CHI TIẾT
1. [Khảo sát Hiện trạng & Khoảng cách Kỹ thuật (Gap Analysis)](#1-khảo-sát-hiện-trạng--khoảng-cách-kỹ-thuật)
2. [Hệ thống Vật lý & Di chuyển Mở rộng (Movement & Physics)](#2-hệ-thống-vật-lý--di-chuyển-mở-rộng)
3. [Cơ chế Chiến đấu: Hitbox, Hurtbox & Frame Data (Combat Engine)](#3-cơ-chế-chiến-đấu-hitbox-hurtbox--frame-data)
4. [Hệ thống Sát thương Tích lũy (%) & Công thức Knockback](#4-hệ-thống-sát-thương-tích-lũy--công-thức-knockback)
5. [Cơ chế Dodge (Né đòn), Dash & Phục hồi (Recovery)](#5-cơ-chế-dodge-né-đòn-dash--phục-hồi-recovery)
6. [Hệ thống Đấu trường: Blast Zone, Stock, K.O & Respawn](#6-hệ-thống-đấu-trường-blast-zone-stock-ko--respawn)
7. [Hệ thống Camera Thích ứng Thông minh (Dynamic Adaptive Camera)](#7-hệ-thống-camera-thích-ứng-thông-minh)
8. [Giao diện HUD Trận đấu Chuẩn Comic Platform Fighter](#8-giao-diện-hud-trận-đấu-chuẩn-comic-platform-fighter)
9. [Vũ khí, Vật phẩm & Tương tác Sàn đấu](#9-vũ-khí-vật-phẩm--tương-tác-sàn-đấu)
10. [Kiến trúc Mạng & Đồng bộ Client/Server (Authoritative Netcode)](#10-kiến-trúc-mạng--đồng-bộ-clientserver)
11. [Vòng đời Trận đấu Toàn diện (Match Lifecycle State Machine)](#11-vòng-đời-trận-đấu-toàn-diện)
12. [Lộ trình Triển khai Chi tiết (Phased Implementation Plan: P0, P1, P2)](#12-lộ-trình-triển-khai-chi-tiết)
13. [Tiêu chí Nghiệm thu & Bộ Kịch bản Kiểm thử (Acceptance Criteria & Test Matrix)](#13-tiêu-chí-nghiệm-thu--bộ-kịch-bản-kiểm-thử)

---

## 1. KHẢO SÁT HIỆN TRẠNG & KHOẢNG CÁCH KỸ THUẬT

Qua phân tích trực tiếp mã nguồn trong `Player.java`, `PlayScreen.java`, `CombatManager.java`, `CameraManager.java`, `PlayerState.java`, các điểm khác biệt cốt lõi cần giải quyết:

| Hạng mục | Cơ chế hiện tại trong mã nguồn | Mục tiêu chuẩn Brawlhalla Platform Fighter |
| :--- | :--- | :--- |
| **Cơ chế Thắng/Thua** | Trừ máu `HP = 100` về `0`. Chạm đáy vực bị đẩy ngược lên và trừ 10 HP. | **Stocks (Số mạng, mặc định 3 stocks)**. Thua khi rơi/bị hất văng ra khỏi **Blast Zone** (biên tử địa). |
| **Sát thương (Damage)** | HP giảm dần. Đòn đánh không thay đổi lực đẩy dù đối thủ sắp chết. | **Damage tích lũy (%) bắt đầu từ 0%**. Sát thương càng cao, đối thủ càng nhẹ và bị hất văng càng xa. |
| **Đòn đánh & Va chạm** | Dùng khoảng cách thô `distanceBetweenPlayers < attackReach` (khoảng 0.2m) trong `CombatManager.java`. | **Hệ thống Hitbox/Hurtbox theo Frame**: Startup, Active, Recovery frames; xác định vùng đánh đa hướng (N/S/D). |
| **Giới hạn bản đồ** | `limitPlayersToEdge()` ép cố định vị trí $x \in [3.0, 8.5]$ làm nhân vật không thể văng khỏi sàn. | Loại bỏ rào cản nhân tạo; xây dựng **4 Blast Zones** (Trái, Phải, Trên, Dưới) ngoài tầm camera. |
| **Hệ thống Nhảy & Rơi** | 1 lần nhảy thêm (`jumpCount <= 1`), rơi tự do với gravity cố định, không thể rơi xuyên sàn platform. | **Jump trên đất, Double Jump trên không, Fast Fall** (nhấn Down trên không), rơi xuyên **One-Way Platforms**. |
| **Phòng thủ & Né** | Giữ nút phím gán biến `isBlocking = true` giảm nhẹ sát thương. | **Dodge (Né đòn 8 hướng hoặc tại chỗ)** cấp **Invulnerability frames (i-frames)** ngắn kèm **Cooldown** chống spam. |
| **Camera** | Trục Y cố định `110 / PPM`, chỉ lia trục X theo trung điểm 2 người, không zoom. | **Dynamic Framing Camera**: Zoom in/out mượt mà theo khoảng cách người chơi, ôm trọn sàn đấu và nhân vật. |
| **HUD** | Hiển thị chữ `HP %` đếm ngược từ 100% xuống 0%. | **HUD Comic**: Avatar, biểu tượng Stock mạng, Damage % đổi màu từ Trắng $\to$ Vàng $\to$ Cam $\to$ Đỏ $\to$ Đỏ sẫm. |

---

## 2. HỆ THỐNG VẬT LÝ & DI CHUYỂN MỞ RỘNG (MOVEMENT & PHYSICS)

### 2.1. Quản lý Trọng lực & Vận tốc trên Box2D
- **Fixed Timestep:** Vật lý Box2D chạy độc lập với tốc độ khung hình (khuyến nghị $1/60$s = $0.01667$s), triệt tiêu hoàn toàn sự phụ thuộc FPS.
- **Vận tốc & Gia tốc Ground vs Air:**
  - `groundAcceleration = 35.0f`: Tăng tốc nhanh trên mặt đất, phản hồi rẽ hướng tức thời khi đổi phím A/D.
  - `groundFriction = 0.82f`: Giảm tốc nhanh khi nhả phím, không bị trượt như trên băng.
  - `airAcceleration = 18.0f`: Cho phép điều hướng trên không (*aerial drift*) nhưng độ bám thấp hơn mặt đất.
  - `maxHorizontalVelocity = 3.8f` (điều chỉnh theo chỉ số `STAT_SPEED` của từng nhân vật).

### 2.2. Cơ chế Nhảy Đa tầng (Jumping Mechanics)
1. **Grounded Jump:** Lực đẩy $F_y = 4.2f$, bật khỏi mặt đất.
2. **Double Jump (Mid-Air Jump):** Cho phép nhảy thêm 1 lần khi đang ở trên không.
   - Khi thực hiện nhảy trên không: Reset vận tốc rơi hiện tại về $0$ trước khi áp dụng lực nhảy mới, đảm bảo độ nảy ổn định dù đang rơi nhanh.
   - Khôi phục Double Jump ngay khi nhân vật tiếp đất hoặc bám thành tường (Wall Touch).
3. **Short Hop & Full Hop:** Căn cứ thời gian nhả nút Jump (nhả nhanh $\to$ nhảy thấp, giữ nguyên $\to$ nhảy tối đa).

### 2.3. Rơi nhanh (Fast Fall)
- Khi nhân vật đang ở trạng thái rơi tự do (`linearVelocity.y < 0`), nếu người chơi nhấn giữ phím **Down (S / Mũi tên xuống)**:
  - Tăng vận tốc rơi tối đa lên gấp $1.6 \times$ và đặt $v_y = -\text{FastFallSpeed}$.
  - Hiệu ứng hình ảnh: Xuất hiện vệt gió trắng/bóng mờ kéo dài dưới chân nhân vật.
  - Cho phép người chơi chủ động tiếp đất nhanh để né đòn trên không hoặc tiếp tục chuỗi tấn công.

### 2.4. Đi xuyên & Rơi xuyên Sàn mỏng (Drop-Through One-Way Platforms)
- Đấu trường có các bệ đá phụ (One-Way Platform):
  - Người chơi nhảy từ dưới lên: Đi xuyên qua mặt sàn.
  - Người chơi tiếp đất từ trên xuống: Đứng vững trên sàn.
  - **Cơ chế Drop-Through:** Khi đang đứng trên platform mỏng, nhấn tổ hợp **Down + Jump**:
    - Tạm thời vô hiệu hóa va chạm (bằng Box2D ContactFilter hoặc đổi category/mask bits) trong $0.25$s để nhân vật rơi mượt mà xuyên xuống dưới.

---

## 3. CƠ CHẾ CHIẾN ĐẤU: HITBOX, HURTBOX & FRAME DATA (COMBAT ENGINE)

### 3.1. Phân định Hurtbox & Hitbox
- **Hurtbox:** Vùng cơ thể nhân vật có thể bị đối thủ đánh trúng (dạng Box2D Sensor Capsule bọc quanh sprite, bám theo tọa độ và chiều lật `facingRight`).
- **Hitbox:** Vùng tấn công gây sát thương do đòn đánh tạo ra, chỉ tồn tại trong các khung hình hiệu lực (Active Frames).

```
        [Startup Frames]         [Active Frames]         [Recovery Frames]
       (Chuẩn bị ra đòn)       (Hitbox gây sát thương)     (Hồi phục / Sơ hở)
[Phím ấn] -------------> |====== HITBOX ACTIVE ======| -------------------> [Điều khiển lại]
```

### 3.2. Bộ Đòn đánh Nhẹ (Light Attacks)
Đòn đánh cơ động, startup cực ngắn ($2-5$ frames), lực đẩy thấp, thích hợp kéo máu và nối combo:
- **Neutral Light (NLight):** Đứng yên bấm Đánh. Đấm/chém nhanh tại chỗ $1-2$ nhịp.
- **Side Light (SLight):** Vừa đi tới vừa bấm Đánh. Lướt nhẹ về phía trước tung đòn hất đối thủ theo góc nghiêng thấp.
- **Down Light (DLight):** Ngồi/Cúi thấp bấm Đánh. Gạt chân hoặc quét tầm thấp, hất nhẹ đối thủ lên trên làm tiền đề cho đòn trên không.
- **Neutral Air (NAir):** Đang trên không bấm Đánh không giữ hướng. Tung đòn xoay người quanh cơ thể.
- **Side Air (SAir):** Đang trên không bấm Đánh kèm hướng Trái/Phải. Lao đòn ngang hất đối thủ văng ra xa theo phương ngang.
- **Down Air (DAir):** Đang trên không bấm Đánh kèm hướng Xuống. Đạp/quét xuống dưới, tạo hiệu ứng đẩy đối thủ xuống vực (*spike/meteor smash* nếu ở mép sàn).

### 3.3. Bộ Đòn đánh Mạnh / Tuyệt kỹ (Heavy / Signature Attacks)
Đòn kết liễu chủ lực, có thời gian tích lực (Charge), startup dài hơn ($8-16$ frames), recovery lớn nếu đánh hụt, lực đẩy cực mạnh:
- **Neutral Heavy (NSig):** Tung tuyệt kỹ hướng lên trên hoặc góc chéo cao.
- **Side Heavy (SSig):** Lao mạnh về phía trước với đòn đánh uy lực theo phương ngang.
- **Down Heavy (DSig):** Đòn phủ diện rộng 2 bên mặt đất hoặc tạo vùng cấm địa.
- **Recovery (Up Air Heavy):** Đòn cứu sinh trên không! Hất nhân vật vọt lên cao đồng thời tạo hitbox bảo vệ phía trên; dùng để bay ngược về sàn đấu.
- **Ground Pound (Down Air Heavy):** Lao thẳng từ trên không xuống mặt đất với tốc độ cao, giáng đòn chí mạng vào bất kỳ ai đứng bên dưới.

### 3.4. Cơ chế Chống Trúng Đòn Đa Lần (Hit Registration & Anti-MultiHit)
- Mỗi lần kích hoạt đòn đánh, khởi tạo một `AttackInstance` với mã định danh duy nhất (`attackId`).
- `AttackInstance` chứa một danh sách `Set<Long> hitEntities`.
- Khi Hitbox chạm Hurtbox của đối thủ:
  - Kiểm tra nếu `hitEntities.contains(victimId)` $\to$ **BỎ QUA**.
  - Nếu chưa $\to$ Gây damage, tính knockback, áp dụng hitstun, và thêm `victimId` vào danh sách.
  - Ngăn chặn hoàn toàn lỗi đòn đánh gây 60 lần sát thương mỗi giây khi chạm nhau!

---

## 4. HỆ THỐNG SÁT THƯƠNG TÍCH LŨY (%) & CÔNG THỨC KNOCKBACK

### 4.1. Cơ chế Sát thương % (Accumulated Damage)
- Người chơi bắt đầu trận đấu với **$0\%$ Damage**.
- Mỗi lần trúng đòn: `damagePercent += attack.baseDamage`.
- Chỉ số % không có giới hạn trên (thường dao động từ $0\%$ đến $200\%+$).
- Trạng thái % càng cao, màu sắc hiển thị càng chuyển đậm và khả năng chống cự lực đẩy càng giảm.

### 4.2. Công thức Lực đẩy Động học (Damage-to-Knockback Formula)
Lực đẩy tổng hợp áp dụng lên trọng tâm Box2D của nạn nhân được tính theo công thức:

$$\vec{F}_{\text{knockback}} = \left( B + \frac{D \cdot S}{10} \right) \cdot \frac{200}{w + 100} \cdot \vec{U}$$

Trong đó:
- $D$: Phần trăm sát thương tích lũy của nạn nhân tại thời điểm bị đánh trúng ($\%$).
- $B$: Base Knockback (Lực đẩy nền cố định của chiêu thức).
- $S$: Knockback Scaling (Hệ số tăng tiến lực đẩy của chiêu thức).
- $w$: Chỉ số cân nặng / phòng thủ của nhân vật (lấy từ hằng số `STAT_DEFENSE`).
- $\vec{U}$: Vector đơn vị định hướng lực văng của chiêu thức (ví dụ: SAir hướng góc $30^\circ$, DAir hướng góc $-80^\circ$, NSig hướng góc $75^\circ$).

### 4.3. Hitstun & Phản hồi Trực quan
- **Hitstun Duration:** Thời gian nạn nhân bị choáng, không thể điều khiển hoặc né đòn:
  $$t_{\text{hitstun}} = \min\left(0.15 + \frac{D \cdot S}{600}, 0.75\right) \quad (\text{giây})$$
- **Hit-Stop (Micro Freeze):** Đóng băng 2 khung hình ($0.033$s) ngay khoảnh khắc va chạm đòn mạnh để tạo cảm giác lực đòn chắc nịch.
- **Tumble State (Văng xoay):** Khi knockback vượt ngưỡng an toàn ($> 6.0$), nhân vật chuyển sang trạng thái xoay lộn trên không trước khi phục hồi thăng bằng.

---

## 5. CƠ CHẾ DODGE (NÉ ĐÒN), DASH & PHỤC HỒI (RECOVERY)

### 5.1. Cơ chế Né đòn (Dodge)
- Kích hoạt bằng phím chuyên biệt: **Shift** (hoặc L-Ctrl / Controller Trigger).
- **Phân loại Dodge:**
  1. **Spot Dodge (Né tại chỗ):** Bấm Dodge khi không giữ phím hướng. Nhân vật mờ đi tại chỗ.
  2. **Directional Dodge (Né có hướng):** Bấm Dodge kèm 1 trong 8 hướng (Ngang, Lên, Xuống, Chéo). Nhân vật lướt nhanh $0.25$s theo hướng đó.
- **Khung hình Bất tử (Invulnerability Frames - i-frames):**
  - Khởi động: $2$ frames.
  - Bất tử hoàn toàn: $12$ frames ($0.2$ giây). Hurtbox tắt va chạm đòn đánh.
  - Recovery: $8$ frames (có thể bị đánh nếu đối thủ đoán trước hướng lướt).
- **Cooldown Chống Spam:**
  - Né trên mặt đất: Cooldown $1.0$ giây.
  - Né trên không (Air Dodge): Cooldown $2.5$ giây. Tránh việc người chơi lơ lửng ngoài vực vĩnh viễn.

### 5.2. Kỹ thuật Phục hồi khi Rơi khỏi Sàn (Off-Stage Recovery)
Một người chơi bị đánh văng ra ngoài không gian sàn đấu có các tài nguyên để trở về:
1. **1 cú Double Jump trên không.**
2. **1 cú Directional Air Dodge hướng về phía sàn.**
3. **1 cú chiêu thức Recovery (Up Air Heavy) để bốc bổng lên cao.**
4. **Bám tường (Wall Slide & Wall Jump):** Khi chạm thành thẳng đứng của sàn đấu, nhân vật trượt chậm lại và có thể đạp tường bật ngược lên tối đa 3 lần.

---

## 6. HỆ THỐNG ĐẤU TRƯỜNG: BLAST ZONE, STOCK, K.O & RESPAWN

### 6.1. Bốn Biên Tử Địa (The 4 Blast Zones)
Thay thế hoàn toàn hàm `limitPlayersToEdge()`. Đấu trường có kích thước thế giới mở rộng và được bao bọc bởi 4 vùng cảm ứng:
- **Left Blast Zone:** $x < X_{\min}$ (ví dụ: $x < 1.0$m)
- **Right Blast Zone:** $x > X_{\max}$ (ví dụ: $x > 11.5$m)
- **Bottom Blast Zone:** $y < Y_{\min}$ (ví dụ: $y < -1.5$m)
- **Top Blast Zone:** $y > Y_{\max}$ (ví dụ: $y > 9.0$m — đối thủ chỉ bị văng chết biên trên khi đang trong trạng thái Hitstun lực đẩy cực mạnh).

```
                        [ TOP BLAST ZONE ]
   +---------------------------------------------------------+
   |                                                         |
[L]|                     Camera Viewport                     |[R]
[E]|                +-----------------------+                |[I]
[F]|                |    [P2]               |                |[G]
[T]|                |          =====        |                |[H]
   |                |  ===================  |                |[T]
[B]|                |         [MAIN STAGE]  |                |[B]
[L]|                +-----------------------+                |[L]
[A]|                                                         |[A]
[S]|                                                         |[S]
[T]|                                                         |[T]
   +---------------------------------------------------------+
                       [ BOTTOM BLAST ZONE ]
```

### 6.2. Quy trình Xử lý K.O & Trừ Mạng (Stock Depletion)
1. **Xác nhận K.O một lần duy nhất:** Ngay khi tâm nhân vật vượt ra ngoài Blast Zone:
   - Khóa cờ `isKO = true` để không kích hoạt trừ mạng nhiều lần trong cùng một tích tắc.
   - Trừ mạng: `stocks -= 1`.
   - Ghi nhận người ra đòn kết liễu cuối cùng (`lastAttackerId`) để tính điểm hạ gục.
   - Phát âm thanh K.O vang dội và hiệu ứng nổ sao chớp sáng trên biên màn hình tương ứng.
2. **Kiểm tra Điều kiện Kết thúc Trận đấu:**
   - Nếu `stocks == 0`: Người chơi bị loại hoàn toàn (`ELIMINATED`). Nếu chỉ còn 1 người chơi/đội có stock $\to$ Kết thúc trận đấu, kích hoạt `MATCH_END` chuyển sang Result Screen.
   - Nếu `stocks > 0`: Khởi động chu kỳ hồi sinh (Respawn).

### 6.3. Bệ Hồi sinh & Thời gian Bất tử (Respawn Platform & Invulnerability)
- Sau khi bị K.O $1.5$ giây, người chơi tái xuất hiện trên một **Bệ hồi sinh bay lơ lửng (Respawn Platform)** ở vị trí trung tâm trên cao sàn đấu.
- `damagePercent` được reset về $0\%$.
- Nhân vật đứng an toàn trên bệ tối đa $3.0$ giây hoặc tự động rơi xuống khi nhấn bất kỳ phím di chuyển/tấn công nào.
- Sau khi rời bệ, nhận **2.0 giây Bất tử (Invulnerability frames)**: Sprite phát sáng nhấp nháy, miễn nhiễm sát thương và lực đẩy để chống bị đối thủ camp điểm xuất hiện.

---

## 7. HỆ THỐNG CAMERA THÍCH ỨNG THÔNG MINH (DYNAMIC ADAPTIVE CAMERA)

Thay thế logic cố định `middleVector = new Vector3((p1.x + p2.x)/2, 110/PPM, 0)` trong `CameraManager.java`:

### 7.1. Thuật toán Bao bọc Mục tiêu (Bounding Target Centering)
- Tính toán hộp bao chứa tất cả các đấu thủ còn sống:
  $$X_{\text{center}} = \frac{\min(x_i) + \max(x_i)}{2}, \quad Y_{\text{center}} = \frac{\min(y_i) + \max(y_i)}{2}$$
- Khoảng cách bao:
  $$\Delta X = \max(x_i) - \min(x_i) + \text{Padding}_X$$
  $$\Delta Y = \max(y_i) - \min(y_i) + \text{Padding}_Y$$

### 7.2. Dynamic Zoom mượt mà
- Hệ số Zoom mục tiêu căn cứ khoảng cách giữa các người chơi:
  $$\text{TargetZoom} = \max\left(\text{MinZoom}, \min\left(\text{MaxZoom}, \max\left(\frac{\Delta X}{\text{ViewportWidth}}, \frac{\Delta Y}{\text{ViewportHeight}}\right)\right)\right)$$
- Dùng phép nội suy mượt mà (Linear Interpolation - Lerp) để tránh giật lag:
  $$\text{CameraPos} = \text{CameraPos} + (\text{TargetPos} - \text{CameraPos}) \cdot 5.0f \cdot \Delta t$$
  $$\text{CameraZoom} = \text{CameraZoom} + (\text{TargetZoom} - \text{CameraZoom}) \cdot 3.0f \cdot \Delta t$$
- Giới hạn camera luôn nằm trong Camera Bounds của bản đồ, không bao giờ để lộ viền đen bên ngoài.

---

## 8. GIAO DIỆN HUD TRẬN ĐẤU CHUẨN COMIC PLATFORM FIGHTER

Thiết kế lại `Hud.java` đồng bộ phong cách comic Brawlhalla:

```
[ P1 AVATAR ]  ALEX                    TIME                    LUCA  [ P2 AVATAR ]
[ ♥ ♥ ♥ ]     124%                     01:45                    45%       [ ♥ ♥ ]
             (ĐỎ CAM)                                         (VÀNG)
```

### 8.1. Bố cục Hai Góc Màn hình
- **Góc Trên Bên Trái (Player 1):**
  - Khung Avatar chân dung nhân vật phong cách truyện tranh.
  - Tên người chơi và Tên nhân vật.
  - **Dãy Biểu tượng Stock (Mạng còn lại):** 3 icon đầu nhân vật mini hoặc trái tim vàng. Khi mất 1 mạng, icon chuyển xám tối.
  - **Chỉ số Sát thương Damage % Lớn:** Số to, viền đen sắc nét.
- **Góc Trên Bên Phải (Player 2):**
  - Cấu trúc đối xứng tương tự cho đối thủ.
- **Chính Giữa:**
  - Đồng hồ đếm ngược trận đấu lớn, dễ nhìn.
  - Ping mạng hiển thị nhỏ gọn ở góc dưới.

### 8.2. Bảng Phổ Màu Sát thương Tích lũy (Damage Color Gradient)
Chỉ số % tự động đổi màu theo mức độ nguy hiểm:
- $0\% - 40\%$: **Trắng sáng / Xanh lơ** (An toàn, lực văng rất nhỏ).
- $41\% - 80\%$: **Vàng rực** (Cảnh báo, bắt đầu dễ bị nối combo).
- $81\% - 120\%$: **Cam đậm** (Nguy hiểm, các đòn Heavy có thể hất sát mép sàn).
- $121\% - 160\%$: **Đỏ tươi** (Vùng tử địa, trúng đòn Smash/Heavy là nguy cơ K.O cao).
- $161\%+$: **Đỏ sẫm nhấp nháy viền đen** (Bất kỳ đòn đánh nào chạm trúng cũng bay với vận tốc cực lớn).

---

## 9. VŨ KHÍ, VẬT PHẨM & TƯƠNG TÁC SÀN ĐẤU

### 9.1. Trạng thái Tay không vs Cầm vũ khí
- Mặc định khi bắt đầu trận hoặc vừa hồi sinh: Nhân vật chiến đấu **Tay không (Unarmed)** với bộ đòn đấm/đá cơ bản.
- **Hộp Vũ khí Rơi (Weapon Drop):** Rơi ngẫu nhiên từ trên trời xuống các bệ đá theo chu kỳ cấu hình.
- Nhặt vũ khí: Nhấn phím Đánh hoặc phím tương tác khi đứng gần hộp.
- Khi cầm vũ khí:
  - Tầm đánh xa hơn, hitbox lớn hơn, damage và knockback tăng cường theo từng loại vũ khí đặc trưng của giáo sư.
- Ném vũ khí (Throw): Có thể ném vũ khí theo hướng điều khiển để làm choáng đối thủ từ xa hoặc ngắt chiêu thức của địch.

---

## 10. KIẾN TRÚC MẠNG & ĐỒNG BỘ CLIENT/SERVER (AUTHORITATIVE NETCODE)

Tận dụng và mở rộng hệ thống mạng Client-Server hiện hữu (`common/model/PlayerState`, `PlayerInput`, `LobbyClient`, `GameSession`):

### 10.1. Mở rộng Model Trạng thái Mạng (`PlayerState.java`)
Bổ sung các trường dữ liệu cần thiết cho cơ chế Platform Fighter mà không phá vỡ tính tương thích:
```java
public record PlayerState(
        PlayerId playerId,
        String characterId,
        float x,
        float y,
        float velocityX,
        float velocityY,
        int damagePercent,      // Thay thế logic hp cũ bằng % sát thương tích lũy
        int stocksRemaining,    // Số mạng còn lại (0..3)
        boolean grounded,
        boolean dodging,        // Trạng thái né đòn (i-frames)
        boolean invulnerable,   // Bất tử khi vừa hồi sinh
        int currentAttackId,    // ID đòn đánh đang thực hiện để đồng bộ animation
        int attackFrame,        // Frame của đòn đánh
        boolean dead,
        boolean facingRight
) {}
```

### 10.2. Mở rộng Gói tin Điều khiển Client (`PlayerInput.java`)
```java
public record PlayerInput(
        PlayerId playerId,
        long sequence,
        long clientTick,
        float moveAxis,         // Trục ngang (-1.0 đến 1.0)
        boolean jumpPressed,    // Bấm nhảy
        boolean downHeld,       // Giữ xuống (Fast fall / Drop through platform)
        boolean lightAttack,    // Đòn đánh nhẹ
        boolean heavyAttack,    // Đòn đánh mạnh / tuyệt kỹ
        boolean dodgePressed,   // Phím né đòn
        boolean throwPressed    // Phím ném vũ khí
) {}
```

### 10.3. Nguyên tắc Thẩm quyền Server (Server Authority)
- **Server:** Chịu trách nhiệm kiểm tra va chạm Hitbox/Hurtbox, tính toán công thức Knockback, phát hiện nhân vật rơi vào Blast Zone, trừ Stock, đếm thời gian hồi sinh và tuyên bố kết quả thắng cuộc.
- **Client:** Thu thập input tức thời, gửi lên Server, chạy Client-side Prediction cho di chuyển của chính mình, nội suy (Interpolation) mượt mà chuyển động của đối thủ từ Snapshot của Server.

---

## 11. VÒNG ĐỜI TRẬN ĐẤU TOÀN DIỆN (MATCH LIFECYCLE STATE MACHINE)

```
[ WAITING / LOADING ]
         │ (Tất cả client nạp xong tài nguyên bản đồ)
         ▼
  [ COUNTDOWN 3-2-1 ]  ──> Khóa toàn bộ input di chuyển/tấn công
         │ (Đồng hồ về 0, vang âm FIGHT!)
         ▼
     [ FIGHTING ]      ──> Mở khóa toàn bộ cơ chế chiến đấu & di chuyển
         │
         ├──> [ PLAYER KO ] ──> Trừ 1 stock, hiệu ứng nổ biên màn hình
         │         │
         │         ├──> Còn stock: [ RESPAWN ] (Bệ bay + 2s bất tử) ──> Tiếp tục FIGHTING
         │         └──> Hết stock: [ ELIMINATED ] (Chuyển sang Spectator)
         ▼ (Chỉ còn 1 người/đội sống sót hoặc hết giờ đấu)
   [ MATCH OVER ]      ──> Khóa điều khiển, chậm thời gian 1.5s
         │
         ▼
  [ VICTORY SCREEN ]   ──> Chuyển sang màn hình bệ vinh danh Podiums
```

---

## 12. LỘ TRÌNH TRIỂN KHAI CHI TIẾT (PHASED IMPLEMENTATION PLAN)

Kế hoạch được phân bổ khoa học thành 3 giai đoạn độc lập, có thể kiểm chứng từng bước:

### 🟢 Giai đoạn P0 — Cốt lõi Platform Fighter (Core Physics, Blast Zones & Stocks)
- [ ] Xóa bỏ rào chắn nhân tạo `limitPlayersToEdge()` trong `Player.java`.
- [ ] Thiết lập 4 ranh giới **Blast Zones** quanh bản đồ đấu trường.
- [ ] Chuyển đổi thuộc tính `HP` thành `damagePercent` ($0\% \to \infty$) và thêm biến `stocks` (mặc định 3).
- [ ] Triển khai công thức lực đẩy động học Knockback theo phần trăm sát thương tích lũy.
- [ ] Xử lý logic K.O đúng 1 lần khi rơi khỏi Blast Zone, trừ stock và hồi sinh tại vị trí an toàn.
- [ ] Nâng cấp bộ nhảy: Ground Jump, Double Jump trên không và Fast Fall khi nhấn giữ Down.
- [ ] Cập nhật unit test và kiểm tra luồng vật lý trên cả local lẫn mạng.

### 🟡 Giai đoạn P1 — Combat Hoàn chỉnh & Trải nghiệm Trực quan (Attacks, Dodge & Camera)
- [ ] Xây dựng hệ thống Hitbox/Hurtbox dựa trên Frame (Startup, Active, Recovery) thay thế phép đo khoảng cách thô trong `CombatManager.java`.
- [ ] Triển khai phân loại đòn đánh: Light Attacks (N, Side, Down) và Heavy Attacks.
- [ ] Triển khai cơ chế Dodge (Né đòn 8 hướng hoặc tại chỗ) có i-frames và Cooldown.
- [ ] Viết lại `CameraManager.java` theo thuật toán Dynamic Bounding Box: bám theo 2 người chơi cả trục X/Y và tự động Zoom in/out mượt mà.
- [ ] Thiết kế lại `Hud.java`: Avatar, hiển thị Stocks, bộ đếm Damage % đổi màu gradient theo phong cách comic.

### 🔵 Giai đoạn P2 — Hoàn thiện Chi tiết & Tính năng Mở rộng (Items, Polish & Rematch)
- [ ] Sàn đấu phụ (One-way Platforms) cho phép nhảy xuyên từ dưới lên và nhấn Down + Jump để rơi xuyên xuống.
- [ ] Cơ chế nhặt và ném vũ khí cơ bản trên sàn đấu.
- [ ] Hiệu ứng âm thanh va chạm, hit-stop micro freeze, vệt gió khi rơi nhanh và nổ biên khi K.O.
- [ ] Vòng lặp Rematch hoàn chỉnh: Reset sạch sẽ Damage %, Stocks, vị trí spawn và vật phẩm khi chơi lại trận mới.

---

## 13. TIÊU CHÍ NGHIỆM THU & BỘ KỊCH BẢN KIỂM THỬ (ACCEPTANCE CRITERIA)

| Mã test | Kịch bản kiểm thử | Hành vi kỳ vọng đạt chuẩn |
| :--- | :--- | :--- |
| **TC-01** | Di chuyển, Nhảy & Double Jump | Nhân vật nhảy nhạy bén từ mặt đất, có thể nhảy thêm 1 lần trên không và rơi nhanh khi giữ phím Down. |
| **TC-02** | Rơi xuyên sàn phụ | Đứng trên one-way platform nhấn Down + Jump rơi mượt mà xuống dưới, không bị kẹt hay giật hình. |
| **TC-03** | Va chạm Hitbox/Hurtbox | Đòn đánh chỉ trúng khi Hitbox chồng Hurtbox trong khung hình Active; đánh trúng 1 mục tiêu duy nhất 1 lần trong 1 chu kỳ ra đòn. |
| **TC-04** | Tỷ lệ Damage-to-Knockback | Ở $0\%$ damage: đối thủ chỉ giật nhẹ; ở $120\%$ damage: cùng đòn đánh đó hất đối thủ văng xa gấp $3-4$ lần. |
| **TC-05** | Dodge & Cooldown | Kích hoạt Dodge nhân vật lướt né đòn và không nhận sát thương; trong thời gian cooldown không thể kích hoạt né tiếp. |
| **TC-06** | Blast Zone & K.O | Bị đánh văng qua biên trái, phải, trên hoặc rơi xuống vực $\to$ Kích hoạt K.O, trừ chính xác 1 stock. |
| **TC-07** | Hồi sinh & Bất tử | Sau khi K.O, hồi sinh tại bệ trung tâm với $0\%$ damage và có 2 giây nhấp nháy bất tử chống bị đánh trúng. |
| **TC-08** | Điều kiện Hết Stock | Khi stock về 0: người chơi bị loại, trận đấu công bố người thắng cuộc và chuyển sang Result Screen. |
| **TC-09** | Dynamic Camera | Khi 2 người chơi đứng cạnh nhau, camera zoom cận cảnh; khi 1 người bị hất bay ra xa, camera zoom out mượt mà giữ cả hai trong khung nhìn. |
| **TC-10** | Đồng bộ Mạng (1v1) | Cả 2 máy Client đều hiển thị đồng nhất: cùng số stock, cùng % damage, cùng vị trí và cùng khoảnh khắc K.O. |

---
*Tài liệu được thiết kế nhằm chuẩn bị sẵn sàng cho công tác hiện thực hóa gameplay. Mọi chi tiết kỹ thuật được ánh xạ trực tiếp vào kiến trúc Box2D và LibGDX của Super Smash Profs.*
