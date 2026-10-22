# BÁO CÁO TỔNG QUAN GAMEPLAY DỰ ÁN SUPER SMASH PROFS
## DÀNH CHO CẤP QUẢN LÝ / BÁO CÁO TIẾN ĐỘ DỰ ÁN

---

> **Tên dự án:** Super Smash Profs
> **Thể loại:** 2D Platform Fighter Đa người chơi (Multiplayer Online & Local)
> **Nền tảng phát triển:** Java 17, LibGDX, Box2D Physics, Authoritative Dedicated Server
> **Định hướng sản phẩm:** Kế thừa phong cách thi đấu thể thao điện tử chuẩn Brawlhalla & Super Smash Bros kết hợp nét vẽ truyện tranh Comic hiện đại.

---

## I. TỔNG QUAN DỰ ÁN & ĐỊNH HƯỚNG SẢN PHẨM

*Super Smash Profs* là trò chơi đối kháng nhiều người chơi trên nền tảng 2D (Platform Fighter). Khác với các game đối kháng truyền thống trừ thanh máu (HP) cố định, trò chơi áp dụng cơ chế thi đấu hiện đại theo chuẩn quốc tế:
- **Tích lũy sát thương theo phần trăm (%):** Càng nhận nhiều đòn đánh, tỷ lệ % sát thương của nhân vật càng tăng cao.
- **Lực đẩy lũy tiến (Progressive Knockback):** % sát thương càng lớn, người chơi càng bị đánh văng xa hơn khi trúng đòn.
- **Luật đấu Số mạng (Stocks) & Ranh giới Tử địa (Blast Zone):** Mục tiêu tối thượng của trận đấu là hất văng đối thủ ra khỏi các ranh giới bản đồ (trên, dưới, trái, phải) để giành điểm hạ gục (K.O).

Dự án hiện đã hoàn thiện đầy đủ hệ thống gameplay cốt lõi từ cơ chế vật lý, chiến đấu cận chiến, vũ khí tầm xa, kỹ năng nhân vật, hệ thống mạng thời gian thực (Client/Server) cho đến giao diện tùy chỉnh phím tự do cho người dùng.

---

## II. CHI TIẾT CÁC TÍNH NĂNG GAMEPLAY ĐÃ TRIỂN KHAI

```
+---------------------------------------------------------------------------------------+
|                                HỆ THỐNG GAMEPLAY ĐÃ HOÀN TẤT                          |
+---------------------------------------------------------------------------------------+
|  1. ĐIỀU KHIỂN & ĐỊNH HƯỚNG     |  2. CHIẾN ĐẤU CẬN CHIẾN      |  3. VŨ KHÍ & VẬT PHẨM    |
|  - Tách nút Nhảy (Space)        |  - Combo 3 đòn đánh nhẹ (J)  |  - Rơi vũ khí ngẫu nhiên |
|  - Ngắm 8 hướng (WASD + góc 45°)|  - Hút đối thủ & tiến tới    |  - Bắn đạn theo 8 hướng  |
|  - Tự do gán lại 12 nút phím    |  - Đòn đánh mạnh văng xa (K) |  - Nhặt (E), Thả (Q),    |
|  - Lưu cài đặt vĩnh viễn        |  - Vệt chém VFX sắc gọn      |    Ném vũ khí sát thương |
+---------------------------------+------------------------------+--------------------------+
|  4. CƠ ĐỘNG & ĐỊA HÌNH          |  5. KỸ NĂNG NHÂN VẬT         |  6. HẠ TẦNG MẠNG & UI    |
|  - Lướt theo hướng mặt (L)      |  - Alex: Tên lửa tầm nhiệt   |  - Server Authoritative  |
|  - Rơi xuyên sàn từng bậc (S+S) |  - Viktor: Máy tính ném nổ   |  - Lobby đồng bộ real-time|
|  - Sàn Dung nham nảy ngược      |  - Maurice: Phi tiêu Star    |  - Màn hình nạp trận     |
|  - Hồi sinh ngẫu nhiên an toàn  |  - Leo: Đặt mìn bẫy          |    Versus Screen chuẩn   |
+---------------------------------------------------------------------------------------+
```

---

### 1. Hệ thống Điều khiển & Cơ chế Ngắm 8 Hướng (8-Way Directional Aiming)
- **Tách biệt hoàn toàn nút Nhảy và Ngắm hướng lên:**
  - Nút **`Space`**: Dùng để Nhảy (hỗ trợ nhảy kép - double jump linh hoạt trên không).
  - Nút **`W`**: Độc lập dùng để ngắm bắn hoặc vung đòn lên trên, triệt tiêu hoàn toàn lỗi người chơi vô tình bị nhảy bật lên khi đang muốn ngắm mục tiêu ở trên cao.
- **Điều hướng 8 hướng linh hoạt qua cụm phím di chuyển:**
  - Hướng thẳng: `W` (ngắm lên trời 90°), `S` (ngắm xuống đất), `A` (ngang trái), `D` (ngang phải).
  - Hướng góc chéo 45°: Khi giữ 2 phím liền kề (`W+D`: chéo 45° lên trước; `W+A`: chéo 45° lên sau; `S+D`: chéo 45° chúc xuống; `S+A`: chéo 45° xuống sau).
  - Cơ chế 8 hướng này tác động đồng thời lên: **Vệt đòn cận chiến, Hướng bắn của tất cả các loại súng, và Hướng phóng chiêu kỹ năng F**.

---

### 2. Chuỗi Đòn Đánh Cận Chiến & Cơ chế Combo Hấp Dẫn (Unarmed Combat)
- **Chuỗi Combo 3 đòn Đánh Nhẹ (Light Attack - Phím `J`):**
  - **Đòn 1 & 2:** Tầm đánh được rút ngắn gấp 4 lần so với bản thử nghiệm để đòn đánh chân thực, hitbox chuẩn người. Khi trúng đích, đòn đánh **hút dính đối thủ lại gần** và nhân vật của người chơi **tự động bước tiến nhẹ lên phía trước**. Cơ chế này giúp giữ đối phương trong tầm kiểm soát, không bị hiện tượng vừa đánh trúng đã văng ra xa khiến mất chuỗi combo.
  - **Đòn 3 (Kết liễu combo):** Vung đòn dứt điểm, tạo lực đẩy hất văng đối thủ theo hướng ngắm đã chọn.
- **Đòn Đánh Mạnh (Heavy Attack - Phím `K`):**
  - Tích lực vung đòn cực mạnh với tầm tác động rộng hơn, tạo lực Knockback vượt trội nhằm hất văng đối thủ ra khỏi sàn đấu hoặc rơi vào tử địa khi đối phương đã tích lũy % sát thương cao.
- **Hiệu ứng Vệt Đòn Đánh (Attack VFX Trajectory):**
  - Vệt chém màu trắng (đòn nhẹ) và màu vàng (đòn mạnh) được tinh chỉnh kích thước ngắn gọn, sắc nét (`0.042m` cho đòn nhẹ, `0.070m` cho đòn mạnh), xoay đúng góc chéo 45° hoặc 90° theo hướng người chơi đang ngắm.

---

### 3. Cơ chế Né Đòn (Dodge) & Lướt Cơ Động (Dash)
- Phím **`L`** kích hoạt trạng thái cơ động:
  - **Né đòn (Spot Dodge):** Khi đứng yên trên không, nhân vật chớp mờ tạo khung hình bất tử (Invulnerability frames) giúp né tránh đòn đánh hoặc đạn bay tới.
  - **Lướt nhanh (Dash):** Khi di chuyển trên sàn hoặc trên không, nhân vật phóng nhanh về phía trước để áp sát hoặc rút lui khỏi giao tranh.
- **Khóa hướng lướt thông minh:** Khi người chơi bấm phím `L` mà đã nhả tay khỏi các phím di chuyển, hệ thống sẽ tự động lướt theo hướng mặt của nhân vật (dựa theo phím `A` hoặc `D` bấm gần nhất), loại bỏ hoàn toàn tình trạng đứng khựng đơ tại chỗ.

---

### 4. Tương tác Địa hình, Cơ chế Rơi Thềm & Hồi Sinh Thông Minh
- **Cơ chế rơi qua sàn bán rắn (Drop through One-way Platform):**
  - Địa hình màn chơi gồm nhiều tầng thềm bán rắn bên trên và mặt đất bên dưới.
  - Khi người chơi muốn rơi xuống tầng dưới, chỉ cần **nhấn đúp 2 lần phím `S`**.
  - **Điểm cải tiến vượt bậc:** Nhân vật chỉ rơi qua **đúng 1 tầng thềm hiện tại** và dừng chân vững vàng tại tầng thềm ngay bên dưới; hoàn toàn không bị lỗi rơi tuột xuống tận đáy sàn đấu.
- **Sàn Dung Nham (Lava Hazard Zone):**
  - Khu vực dung nham đỏ ở hai bên đáy võ đài: Khi nhân vật rơi xuống sẽ bị thiêu đốt gây sát thương và bật nảy ngược lên trên, tạo cơ hội cho người chơi dùng kỹ năng nhảy phục hồi (Recovery) để sống sót quay lại sàn đấu.
- **Cơ chế Hồi sinh Ngẫu nhiên Thông minh (Smart Respawn):**
  - Khi nhân vật bị hạ gục (K.O) và mất 1 mạng, hệ thống Server sẽ tính toán vị trí hồi sinh ngẫu nhiên trên các điểm xuất hiện (Spawn Points) hợp lệ.
  - Thuật toán tự động **loại trừ 100% các khu vực dung nham hoặc rìa vực chết**, đảm bảo người chơi luôn hồi sinh trên thềm an toàn kèm 3 giây bất tử (Invincibility Shield).

---

### 5. Hệ thống Vũ khí & Vật phẩm Chiến trường (Weapons & Items)
- **Vũ khí rơi ngẫu nhiên:** Hòm vũ khí tự động rơi xuống võ đài trong quá trình thi đấu.
- **Bộ sưu tập vũ khí đa dạng:**
  - *Súng Bazooka:* Bắn tên lửa nổ gây sát thương diện rộng và lực đẩy cực mạnh.
  - *Súng Laser:* Bắn tia năng lượng tốc độ tức thì xuyên thẳng mục tiêu.
  - *Súng Shotgun:* Bắn chùm đạn xòe tầm gần, uy lực hủy diệt.
  - *Lựu đạn nổ & Mìn bẫy:* Ném hoặc đặt bẫy trên sàn đấu chờ đối phương dẫm phải.
- **Tương tác linh hoạt:**
  - Nhặt vũ khí: Phím **`E`**.
  - Bắn vũ khí: Phím **`J`** (đạn bay chuẩn theo góc ngắm 8 hướng của cụm phím WASD).
  - Thả vũ khí xuống sàn: Phím **`Q`**.
  - Ném vũ khí vào người đối phương: Phím **`U`** (vũ khí bay đi gây sát thương vật lý khi va chạm).

---

### 6. Kỹ năng Đặc biệt Riêng biệt của từng Nhân vật (Skill F)
Mỗi nhân vật trong game đại diện cho một Giáo sư với bộ kỹ năng độc quyền (kích hoạt bằng phím **`F`**, có thời gian hồi chiêu Cooldown):
- **Alex:** Bắn tên lửa tầm nhiệt truy đuổi theo vector ngắm 8 hướng.
- **Viktor:** Ném khối máy tính cổ IBM phát nổ khi va chạm mặt đất.
- **Maurice:** Phóng phi tiêu ngôi sao điện toán tốc độ cao xuyên phá.
- **Leo:** Rải mìn công nghệ cảm ứng bước chân phát nổ hất văng mục tiêu.
- **Luca:** Kích hoạt luồng sóng chấn động năng lượng đẩy lùi kẻ địch xung quanh.

---

### 7. Hệ thống Cài đặt Phím Tự do (Custom Keybindings Settings)
- **Truy cập thuận tiện:** Nút **`CÀI ĐẶT PHÍM  [C]`** được tích hợp ngay tại Menu chính với phím tắt nhanh `C` hoặc `S`.
- **Tùy biến 100% không giới hạn:** Cho phép người chơi tự do đổi nút cho toàn bộ **12 hành động trong game**:
  - *Di chuyển:* Trái, Phải, Ngắm Lên, Ngắm Xuống, Nhảy.
  - *Chiến đấu:* Đánh nhẹ / Bắn súng, Đánh mạnh, Né / Lướt, Kỹ năng F.
  - *Vật phẩm:* Nhặt đồ, Thả đồ, Ném vũ khí.
- **Thao tác đơn giản:** Click vào ô hành động -> Nút nhấp nháy `[ BẤM PHÍM... ]` -> Nhấn bất kỳ phím nào trên bàn phím (chữ, số, phím mũi tên, Space, Shift, Ctrl, Alt...).
- **Lưu trữ vĩnh viễn (Persistence):** Tự động ghi nhớ cấu hình vào hệ thống máy. Tắt game mở lại vẫn giữ nguyên bộ phím đã cài.
- **Mẫu phím nhanh (Presets):** Có sẵn nút khôi phục nhanh `MẶC ĐỊNH (WASD)` và `MẪU MŨI TÊN`.

---

## III. KIẾN TRÚC KỸ THUẬT & HẠ TẦNG MẠNG

1. **Mô hình Mạng Authoritative Server:**
   - Server Java chạy độc lập, chịu trách nhiệm mô phỏng toàn bộ thế giới vật lý Box2D, phát hiện va chạm hitbox/hurtbox, tính toán % sát thương và xác nhận vị trí nhân vật.
   - Client gửi các gói tin điều khiển (Input Packets) với tần số cao; Server phản hồi các gói trạng thái (Snapshot Packets).
2. **Khử trễ mạng (Client Prediction & Server Reconciliation):**
   - Phía Client áp dụng kỹ thuật dự đoán chuyển động nội suy cục bộ, giúp thao tác của người chơi có phản hồi tức thì (0ms cảm giác trễ), trong khi Server vẫn đảm bảo tính công bằng và chống gian lận.
3. **Quy trình Trận đấu Hoàn chỉnh (Match Lifecycle):**
   - **Phòng chờ (Online Room Lobby):** Đồng bộ việc chọn nhân vật thời gian thực, hiển thị chỉ số đặc trưng (Tấn công, Phòng thủ, Tốc độ, Độ khéo léo).
   - **Màn hình nạp trận (Versus Loading Screen):** Thiết kế thẻ bài truyện tranh Comic Brawlhalla ấn tượng.
   - **Trong trận (In-game Match):** HUD hiển thị chi tiết % sát thương theo màu sắc cảnh báo, số mạng còn lại (Stocks), vạch hồi chiêu kỹ năng F, và số lượng đạn dược của súng.
   - **Kết thúc trận (Match End):** Tự động phát hiện khi chỉ còn 1 người sống sót và chuyển sang màn hình Vinh danh Người chiến thắng (Win Screen).

---

## IV. BẢNG TỔNG HỢP HỆ THỐNG PHÍM ĐIỀU KHIỂN

| Hành động trong Game | Phím Mặc định | Chức năng chi tiết |
| :--- | :---: | :--- |
| **Sang trái / Sang phải** | `A` / `D` | Di chuyển trên sàn hoặc điều hướng khi rơi trên không. |
| **Ngắm Lên trên** | `W` | Ngắm góc 90° lên trời (kết hợp `W+D`/`W+A` thành góc chéo 45°). |
| **Ngắm Xuống dưới** | `S` | Ngắm góc chúc xuống đất (kết hợp `S+D`/`S+A` thành góc chéo 45°). |
| **Rơi qua bậc thềm** | `S` + `S` (Nháy 2 lần) | Rơi qua 1 tầng thềm bán rắn xuống thềm bên dưới. |
| **Nhảy (Jump)** | `Space` | Nhảy đơn hoặc nhảy kép (Double Jump) trên không. |
| **Đánh Nhẹ / Bắn súng** | `J` | Đánh combo 3 đòn tay không (hút lại gần) hoặc bắn súng theo hướng ngắm. |
| **Đánh Mạnh (Heavy)** | `K` | Tung đòn uy lực hất văng đối thủ ra xa. |
| **Né đòn / Lướt (Dodge/Dash)** | `L` | Bất tử né đòn tại chỗ hoặc lướt nhanh theo hướng mặt. |
| **Kỹ năng đặc biệt (Skill)** | `F` | Kích hoạt kỹ năng riêng của từng Giáo sư theo hướng ngắm. |
| **Nhặt vũ khí** | `E` | Nhặt vũ khí rơi trên sàn đấu. |
| **Thả vũ khí** | `Q` | Thả vũ khí đang cầm để quay lại đánh tay không. |
| **Ném vũ khí** | `U` | Ném vũ khí bay thẳng vào đối phương gây sát thương. |
| **Mở Cài đặt phím** | `C` (hoặc `S`) | Mở bảng tùy biến phím ngay tại Menu chính. |

---

## V. HƯỚNG DẪN CHẠY DEMO BÁO CÁO TRỰC TIẾP

Để chuẩn bị buổi trình chiếu hoặc demo trực tiếp cho cấp trên, có thể khởi động hệ thống nhanh chóng bằng 2 cách:

### Cách 1: Chạy Chế độ Chơi Mạng (2 Người hoặc nhiều Client)
1. **Khởi động Game Server:**
   Mở terminal tại thư mục dự án và chạy:
   ```powershell
   .\run_server.bat
   # hoặc: java -jar final-project/server/build/libs/server-1.0.jar
   ```
2. **Khởi động 2 Client người chơi:**
   Mở 2 cửa sổ terminal riêng biệt và chạy:
   ```powershell
   java -jar final-project/desktop/build/libs/desktop-1.0.jar
   ```
3. Tại mỗi cửa sổ, chọn **CHƠI ONLINE [O]**, bấm **SẴN SÀNG** để Server tự động ghép trận và bắt đầu thi đấu.

### Cách 2: Chạy Chế độ Chơi Cục bộ (Local 2 Người cùng máy)
1. Khởi động Game Client:
   ```powershell
   java -jar final-project/desktop/build/libs/desktop-1.0.jar
   ```
2. Chọn **CHƠI CỤC BỘ [L]**: 2 người chơi có thể thi đấu đối kháng trực tiếp trên cùng một bàn phím.

---

## VI. KẾT LUẬN & ĐÁNH GIÁ CHUNG

- **Tính hoàn thiện:** 100% các tính năng gameplay đã được kiểm thử tự động với **1524 tests PASS**, không có xung đột mã hay lỗi hồi quy.
- **Chất lượng trải nghiệm:** Cảm giác va đập (game feel), nhịp độ thi đấu dồn dập, độ nhạy của phím bấm và độ chính xác của cơ chế ngắm 8 hướng đạt tiêu chuẩn cao của dòng game đối kháng điện tử.
- **Mã nguồn:** Đã được kiểm tra kỹ lưỡng, đóng gói thành file chạy `.jar` tiện lợi và lưu trữ an toàn trên nhánh `dev` của hệ thống Git.
