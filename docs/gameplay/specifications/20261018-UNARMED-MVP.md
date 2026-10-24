# ĐẶC TẢ GAMEPLAY: ĐIỀU KHIỂN, DI CHUYỂN VÀ COMBAT CƠ BẢN



> **Nguyên tắc sử dụng tài liệu:** Các phần mô tả cơ chế Brawlhalla được ghi rõ là *cơ chế tham khảo*. Những thông số chưa được đo trong game được xem là giá trị cần kiểm thử, không phải thông số chính thức. Nếu tài liệu đề xuất đơn giản hóa cho prototype, phần đó sẽ được đánh dấu **Quyết định triển khai** để không nhầm với cơ chế gốc.

## Phạm vi và những gì chủ động chưa triển khai

Tài liệu này chỉ đặc tả lớp gameplay nền tảng: nhập phím, di chuyển, nhảy, tấn công không vũ khí (*Unarmed*), né, Gravity Cancel, hitbox/hurtbox, sát thương, hitstun, knockback, combo và công cụ kiểm thử.

**Tạm thời loại khỏi phạm vi:**

- Toàn bộ danh sách vũ khí và combo theo từng vũ khí.
- Nhặt, đổi, rơi và ném vũ khí; vật phẩm/gadget.
- Signature riêng theo Legend + vũ khí.
- Chế độ vũ khí, Active Input theo từng vũ khí, Greatsword, Chakram và các cơ chế độc quyền khác.
- Bảng cân bằng damage/recovery theo từng vũ khí của Patch 11.00.

Trong Brawlhalla, khi không cầm vũ khí, nhân vật vẫn có bộ đòn Unarmed dùng chung. Bản prototype nên bắt đầu từ bộ đòn này. Không tạo `weaponId`, `weaponMode` hay `legendId` chỉ để phục vụ các hệ thống đã bị loại khỏi phạm vi.

---

## 1. Nguyên tắc gameplay

### 1.1. Tách Input, Attack và Combo

Hệ thống nên chia thành ba lớp độc lập:

1. **Input Resolver:** đọc phím hướng và hành động; xác định ý định tấn công là Neutral, Side hay Down.
2. **Attack System:** chọn đòn phù hợp theo trạng thái Grounded/Airborne, hướng và loại tấn công; quản lý Startup, Active và Recovery.
3. **Combo/Hit Reaction:** xác định đòn có trúng hay không, áp sát thương, hitstun, knockback và tính xem đối thủ có kịp né giữa hai đòn hay không.

Một chuỗi input được bấm liền nhau **không tự động là true combo**. Kết quả phụ thuộc vào thời điểm ra đòn, hitbox, khoảng cách, hướng văng, hitstun và trạng thái né của đối thủ.

### 1.2. True Combo, String và Dodge Read

- **True combo:** đòn tiếp theo trúng trước khi đối thủ có thể thực hiện Dodge. Khi kiểm tra bằng Show Stun, chuỗi cần có **0 Dodge Frames** trong đúng điều kiện thử.
- **String:** người tấn công nối nhiều đòn nhưng giữa các đòn có cửa sổ né. Người phòng thủ có thể thoát nếu chọn thời điểm/hướng phù hợp.
- **Dodge read:** người tấn công dự đoán hướng né và chọn đòn hoặc vị trí đón đầu. Đây là phán đoán của người chơi, không phải combo được đảm bảo.
- **Kill confirm:** chuỗi ngắn có khả năng kết liễu khi damage/vị trí đối thủ đạt điều kiện; không phải mọi combo đều là kill confirm.

### 1.3. Damage và Knockback

Brawlhalla dùng **damage meter**, không dùng thanh HP truyền thống trong chế độ platform fighter tiêu chuẩn. Damage càng cao thì đối thủ thường bị hất xa hơn và dễ bị loại khỏi sàn hơn. Đòn đánh nên có các tham số riêng cho damage, lực văng, góc văng và hitstun.

**Quyết định triển khai:** nếu game của nhóm dùng HP thay vì damage meter, đó là lựa chọn thiết kế riêng. Không nên coi công thức HP đó là mô phỏng chính xác Brawlhalla.

---

## 2. Điều khiển và chuẩn hóa Input

Các preset dưới đây chỉ dùng để minh họa ánh xạ phím. Người chơi có thể thay đổi control trong game gốc; game của nhóm nên cho phép cấu hình lại phím nếu có đủ thời gian.

| Hành động | Preset B | Preset A | Trách nhiệm trong prototype |
|---|---|---|---|
| Nhảy | `W` | `↑` | Nhảy mặt đất / nhảy trên không khi còn lượt |
| Di chuyển ngang | `A` / `D` | `←` / `→` | Đi/chạy và điều khiển ngang trên không |
| Rơi nhanh / xuyên platform mềm | `S` | `↓` | Fast-fall khi đang rơi; drop-through nếu có platform mềm |
| Quick Attack | `J` | `C` | Tấn công nhẹ; phân giải thành Neutral/Side/Down |
| Heavy Attack | `K` | `X` | Tấn công mạnh; trên không phân giải thành Recovery/Ground Pound |
| Dodge / Dash | `L` | `Z` | Dodge theo trạng thái; Dash là hành động di chuyển riêng cần phân giải rõ |
| Grab / Throw | Không gán trong MVP | Không gán trong MVP | **Không triển khai** ở giai đoạn chưa có vũ khí/vật phẩm |
| Taunt | `1`–`8` | `1`–`8` | Tùy chọn, không tác động combat |

> `W`/`↑` có thể được dùng như Jump và/hoặc hướng ngắm tùy cài đặt. Trong code nên tách **nút nhảy** khỏi **ý định hướng lên** thành hai biến logic (`jumpPressed`, `aimUpHeld`) để tránh việc bấm hướng lên ngoài ý muốn tiêu hao lượt nhảy. Ánh xạ vật lý của hai biến có thể dùng chung phím nếu thiết kế cho phép.

### 2.1. Mẫu dữ liệu input theo tick

Mỗi tick gameplay, đọc đồng thời:

```text
horizontal: -1 | 0 | 1
vertical:   -1 | 0 | 1
jumpPressed
lightAttackPressed
heavyAttackPressed
dodgePressed
aimUpHeld
fastFallHeld
```

Không xử lý phím thành chuỗi tuần tự kiểu “chỉ có một phím được bấm mỗi lần”. Người chơi phải có thể nhấn nhiều input cùng lúc, ví dụ nhảy + đánh hoặc hướng + đánh.

### 2.2. Quy tắc phân giải hướng đánh

- Hướng xuống + Attack: đòn **Down**.
- Hướng trái/phải + Attack: đòn **Side**.
- Không có hướng ngang/xuống, hoặc ý định hướng lên theo quy tắc điều khiển: đòn **Neutral**.
- Hướng mặt nhân vật (`facingDirection`) phải được lưu riêng với hướng của đòn đánh (`attackDirection`). Không buộc nhân vật quay mặt theo mọi hướng đòn.
- Nếu triển khai tùy chọn tương đương `Prioritize Neutral over Side`, hãy kiểm thử riêng trường hợp giữ hướng lên + hướng ngang + Attack. Không để thứ tự đọc phím ngẫu nhiên quyết định loại đòn.

**Giới hạn:** chính sách ưu tiên Neutral/Side cần được làm rõ cùng thiết kế control thực tế của game nhóm. Không giả định một quy tắc phân giải hướng chưa được triển khai và kiểm thử là đã giống hoàn toàn Brawlhalla.

---

## 3. Ma trận đòn đánh Unarmed

### 3.1. Đòn nhẹ trên mặt đất

| Tên | Input logic | Ý nghĩa |
|---|---|---|
| `NLight` | Grounded + Quick Attack + Neutral | Đòn nhẹ trung lập |
| `SLight` | Grounded + Quick Attack + Left/Right | Đòn nhẹ ngang |
| `DLight` | Grounded + Quick Attack + Down | Đòn nhẹ xuống |

### 3.2. Đòn mạnh trên mặt đất khi Unarmed

| Tên | Input logic | Ý nghĩa |
|---|---|---|
| `NHeavy` | Grounded + Heavy Attack + Neutral | Đòn mạnh trung lập Unarmed |
| `SHeavy` | Grounded + Heavy Attack + Left/Right | Đòn mạnh ngang Unarmed |
| `DHeavy` | Grounded + Heavy Attack + Down | Đòn mạnh xuống Unarmed |

Trong Brawlhalla, các đòn Heavy trên mặt đất khi cầm vũ khí được gọi là Signature theo Legend + vũ khí. Hệ thống đó đã bị loại khỏi phạm vi tài liệu này. Với Unarmed, dùng tên `NHeavy`, `SHeavy`, `DHeavy`, **không gọi các đòn này là NSig/SSig/DSig**.

### 3.3. Đòn nhẹ trên không

| Tên | Input logic | Ý nghĩa |
|---|---|---|
| `NAir` | Airborne + Quick Attack + Neutral | Đòn nhẹ trung lập trên không |
| `SAir` | Airborne + Quick Attack + Left/Right | Đòn nhẹ ngang trên không |
| `DAir` | Airborne + Quick Attack + Down | Đòn nhẹ xuống trên không |

### 3.4. Đòn mạnh trên không

| Tên | Input logic | Ý nghĩa |
|---|---|---|
| `Recovery` (`Rec`) | Airborne + Heavy Attack + Neutral/Up | Đòn mạnh hướng lên, thường giúp nhân vật lấy lại độ cao |
| `Ground Pound` (`GP`) | Airborne + Heavy Attack + Down | Lao xuống để tấn công |

Không định nghĩa một `Side Heavy` trên không riêng biệt. Khi đang trên không, Heavy trung lập/hướng lên là `Recovery`; Heavy hướng xuống là `Ground Pound`. Hướng ngang có thể điều khiển hướng mặt hoặc vận tốc nhưng không tạo loại aerial Heavy thứ ba.

---

## 4. Từ điển thuật ngữ combat

| Thuật ngữ | Định nghĩa dùng trong tài liệu |
|---|---|
| `NLight` / `SLight` / `DLight` | Đòn nhẹ trung lập / ngang / xuống trên mặt đất |
| `NHeavy` / `SHeavy` / `DHeavy` | Đòn mạnh Unarmed trung lập / ngang / xuống trên mặt đất |
| `NAir` / `SAir` / `DAir` | Đòn nhẹ trung lập / ngang / xuống trên không |
| `Rec` | Recovery, Heavy trên không hướng trung lập/lên |
| `GP` | Ground Pound, Heavy trên không hướng xuống |
| `GC` | Gravity Cancel: dùng Dodge giữa không trung để thực hiện đòn đánh kiểu Grounded khi còn cửa sổ hợp lệ |
| `CD` | Chase Dodge: lao đuổi theo sau một đòn trúng đủ điều kiện |
| `Hitstun` | Khoảng thời gian sau khi trúng đòn mà nhân vật chưa thể tự do hành động |
| `Recovery` (trạng thái) | Thời gian khóa hành động sau startup/active của một đòn; khác với đòn đánh tên `Rec` |
| `Dodge Frames` | Số frame đối thủ có thể né giữa các đòn trong phép thử Show Stun |
| `DI` | Directional Influence: cơ chế điều chỉnh hướng văng trong điều kiện nhất định; để ngoài phạm vi MVP cho đến khi có kiểm chứng độc lập |
| `X-pivot` | Kỹ thuật đổi hướng quán tính kết hợp đòn đánh trên không; tính năng nâng cao, không bắt buộc cho MVP |

Khi ghi `A → B`, đòn `A` phải trúng trước rồi mới thực hiện `B`. Dấu `/` nghĩa là các phương án thay thế, không có nghĩa phải thực hiện tất cả đòn.

---

## 5. Di chuyển, nhảy và phục hồi trên không

### 5.1. Jump và Air Actions

Theo wiki Brawlhalla, nhân vật có thể thực hiện **2 Air Jumps** trước khi phải chạm sàn hoặc tường; Ground Jump và Wall Jump không tính vào con số này. Recovery cũng giúp tăng độ cao khi đang trên không.

Một chuỗi bay có tổng cộng tối đa 3 hành động nhảy/phục hồi trên không trước khi chạm sàn hoặc tường, với các tổ hợp tham khảo như:

- 2 Air Jumps + 1 Recovery.
- 1 Air Jump + 2 Recovery.
- 0 Air Jumps + 3 Recovery.

Recovery thứ hai và các Recovery tiếp theo trước khi chạm sàn/tường là **Exhausted Recovery**: độ bay lên thấp hơn và tiêu thụ tài nguyên nhảy theo luật của game. Khi triển khai, hãy quản lý bằng counters/trạng thái chứ không chỉ dùng một cờ `hasJumped`.

**Dữ liệu đề xuất:**

```text
groundJumpAvailable: boolean
airJumpsLeft: integer (0..2)
recoveryCountSinceGroundOrWall: integer
aerialActionsUsed: integer
```

Giá trị và quy tắc hồi tài nguyên nên được kiểm thử bằng tình huống chạm sàn, chạm tường, bị đánh trúng và rơi khỏi mép sàn.

### 5.2. Dash, Backdash, Sprint và Dash Jump

- **Dash/Backdash:** chuyển động tăng tốc nhanh theo hướng tiến hoặc lùi. Cần tách hành động Dash với Dodge có i-frames; không gán cho Dash các frame bất tử của Dodge.
- **Sprint:** trạng thái di chuyển nhanh sau khi đạt điều kiện tốc độ/đầu vào phù hợp.
- **Dash Jump:** nhảy trong lúc đang Dash để giữ nhiều vận tốc ngang hơn một cú nhảy đứng thông thường.
- **Fast-fall:** khi nhân vật đang rơi, giữ hướng xuống có thể tăng tốc độ rơi.
- **Drop-through:** nếu có platform mềm, input xuống cần phân biệt giữa rơi xuyên platform và chọn đòn Down.

Không gắn một tỷ lệ cố định như “độ cao giảm đúng một nửa” vào vật lý nếu chưa đo trong game. Dùng tham số có thể điều chỉnh và cân bằng bằng playtest.

### 5.3. Wall Slip

Brawlhalla có cơ chế Wall Slip để hạn chế việc bám tường kéo dài. Wiki ghi nhận cơ chế này được kích hoạt sau 9 aerial actions mà không chạm đất, đánh trúng hoặc bị đánh trúng; các mốc cảnh báo trước đó là 5 và 7 hành động.

Đây là cơ chế nâng cao, có thể đưa vào sau khi nền tảng di chuyển hoạt động ổn định. Đặt các mốc thành cấu hình, không rải số 5/7/9 trực tiếp ở nhiều nơi trong code.

---

## 6. Dodge, Chase Dodge và Gravity Cancel

### 6.1. Phân biệt các hành động

- **Ground Spot Dodge:** né tại chỗ khi đang trên mặt đất; có i-frames.
- **Aerial Spot Dodge:** né tại chỗ trên không; có i-frames.
- **Directional Air Dodge:** né theo một trong tám hướng trên không; có i-frames.
- **Dash/Backdash:** di chuyển nhanh trên mặt đất; không nên coi là Dodge có i-frames.
- **Chase Dodge:** lao nhanh theo hướng đuổi sau một đòn trúng đủ điều kiện; có bộ đếm và cửa sổ dùng riêng.

Wiki hiện liệt kê các thời lượng Dodge tham khảo như sau. Đây là số liệu để đối chiếu, còn khi triển khai cần đặt thành cấu hình và kiểm thử lại theo phiên bản:

| Loại | Thời lượng bất tử tham khảo |
|---|---:|
| Ground Spot Dodge | 16 frames |
| Aerial Spot Dodge | 20 frames |
| Directional Air Dodge | 12 frames |
| Chase Dodge | 17 frames |

Wiki cũng liệt kê cooldown tham khảo 60 frames cho Dodge mặt đất và 163 frames cho Dodge trên không, với điều kiện rút ngắn/reset liên quan đến việc chạm đất. Không coi các con số này là hằng số không thể thay đổi trong game nhóm.

### 6.2. Gravity Cancel (GC)

**Cách chuẩn để bắt đầu:** khi đang trên không, thực hiện **Aerial Spot Dodge không kèm hướng**, sau đó nhập một đòn Grounded trong cửa sổ cho phép. Theo wiki, đòn được nhập trong 18 frames sau Aerial Spot Dodge có thể trở thành Gravity-Cancelled attack.

Một biến thể nâng cao dùng Directional Air Dodge: đòn phải được nhập trong 3 frames đầu và phải khớp hướng né theo luật của Brawlhalla. Không nhầm directional-dodge GC với cách chuẩn dùng spot dodge.

Với bộ Unarmed của prototype, GC cần cho phép các đòn Grounded sau, nếu còn cửa sổ hợp lệ:

- `NLight`, `SLight`, `DLight`.
- `NHeavy`, `SHeavy`, `DHeavy`.

Nếu GC hụt, nhân vật vẫn ở trên không và đã tiêu hao Dodge theo luật tương ứng. Cần kiểm thử cả trường hợp trúng, hụt, nhập đòn quá muộn và giữ hướng không hợp lệ.

### 6.3. Chase Dodge (CD)

Sau khi một đòn đánh trúng đủ điều kiện, game có thể cho phép Chase Dodge theo hướng đuổi. Đưa cơ chế này vào sau khi đánh trúng/hitstun hoạt động đúng.

**Yêu cầu triển khai:**

- Có điều kiện cấp quyền `chaseDodgeEligible`.
- Lưu số lần/khung thời gian dùng liên tiếp nếu áp dụng.
- Không kích hoạt chỉ vì người chơi bấm Dodge tùy ý khi chưa đủ điều kiện.
- Phân biệt i-frames của Chase Dodge với vận tốc Dash thông thường.

Không triển khai quy tắc số lần Chase Dodge chỉ từ một câu mô tả khái quát; cần kiểm chứng trong game/nguồn cập nhật và viết thành test cụ thể.

---

## 7. Combo Unarmed để làm bộ kiểm thử ban đầu

**Quan trọng:** combo bên dưới là danh sách khởi đầu dựa trên guide cộng đồng Unarmed, không phải tuyên bố rằng mọi chuỗi luôn true ở mọi damage, khoảng cách hoặc phiên bản. Phải xác minh từng chuỗi bằng Show Stun trước khi chuyển thành test bắt buộc.

### 7.1. Chuỗi được cộng đồng ghi nhận là true combo để kiểm thử trước

| Chuỗi | Điều kiện cần ghi khi test | Trạng thái trong tài liệu |
|---|---|---|
| `DLight → NAir` | Ground hit, khoảng cách, damage hiện tại | Cần test trong bản game mục tiêu |
| `DLight → SAir` | Khoảng cách/hướng đối thủ văng | Cần test trong bản game mục tiêu |
| `DLight → DAir` | Vị trí ngang và độ cao sau DLight | Cần test trong bản game mục tiêu |
| `DLight → Rec` | Timing nhảy/Recovery và khoảng cách | Cần test trong bản game mục tiêu |
| `DLight → GP` | Vị trí/độ cao để GP trúng | Cần test trong bản game mục tiêu |
| `DLight → GC DLight → NAir/SAir/DAir/Rec/GP` | Cần đủ cửa sổ GC và đúng vị trí sau mỗi đòn | Nâng cao; kiểm thử riêng từng nhánh |

Nguồn cộng đồng có danh sách các chuỗi trên trong phần Unarmed, nhưng đây không thay thế kiểm thử trực tiếp theo phiên bản. Nếu Show Stun hiện dodge frames tại bất kỳ mắt xích nào, chuyển chuỗi đó sang nhóm String hoặc đánh dấu điều kiện cụ thể thay vì giữ nhãn true combo.

### 7.2. Chuỗi luyện tập / follow-up, không mặc định là true combo

- `NLight → NAir` hoặc `NLight → SAir`.
- `SLight → NLight`.
- `NAir → SAir` hoặc `NAir → Rec`.
- `SAir → NAir`.
- `DAir → GC DLight → follow-up`.
- `DAir → GP`.

Những chuỗi này thích hợp làm kịch bản luyện tập để quan sát hitstun, knockback, recovery và khả năng né của đối thủ. Chỉ gọi là true combo sau khi test cụ thể.

### 7.3. Kill Confirm

Các follow-up như `DLight → Rec`, `DLight → GP` hoặc GC follow-up có thể trở thành kill confirm tùy damage và vị trí. Không hard-code ngưỡng “cam/đỏ là chắc chắn kết liễu”; kết quả phụ thuộc vào vị trí trên sân, góc/lực văng và vùng KO.

---

## 8. Đặc tả kỹ thuật cho Prototype

### 8.1. State Machine

Các trạng thái nền tảng nên có:

```text
Idle
Run
Dash
Backdash
JumpStartup
AirborneRising
AirborneFalling
FastFall
AttackStartup
AttackActive
AttackRecovery
GroundSpotDodge
AirSpotDodge
AirDirectionalDodge
ChaseDodge
GravityCancel
Hitstun
WallSlide
WallSlip
LandingRecovery
Dead
Respawn
```

Không nhất thiết phải tách mọi trạng thái thành class riêng. Có thể dùng enum + dữ liệu trạng thái, miễn chuyển trạng thái rõ ràng và tránh cho cùng một frame xử lý hai action xung đột.

### 8.2. Attack Definition (không có dữ liệu vũ khí)

```text
moveId
attackGroup          // unarmed_ground_light, unarmed_ground_heavy,
                     // unarmed_air_light, unarmed_air_heavy
requiredState        // Grounded | Airborne
attackDirection      // Neutral | Side | Down
startupFrames
activeFrames[]
fixedRecoveryOnHit
variableRecoveryOnHit
fixedRecoveryOnMiss
variableRecoveryOnMiss
damage
fixedForce
variableForce
knockbackAngle
baseStunFrames
hitboxShapes[]
chargeable
minChargeFrames
maxChargeFrames
```

`minChargeFrames` và `maxChargeFrames` chỉ có tác dụng với đòn được xác định là chargeable. Giá trị khởi đầu phải được đo/cân bằng; không dùng chung một số frame cho mọi đòn nếu animation và cơ chế không giống nhau.

### 8.3. Hitbox, Hurtbox và Combat Output

- Hitbox chỉ hoạt động trong những frame được xác định ở `activeFrames[]`.
- Hurtbox thuộc nhân vật và cập nhật theo animation/frame.
- Khi hitbox va chạm hurtbox, xác định trúng đòn một lần theo quy tắc multi-hit của move; không gây damage mỗi tick chỉ vì hai vùng còn chồng nhau.
- Áp damage, knockback, hitstun và reaction state từ dữ liệu đòn đánh.
- Phân biệt hit và miss recovery; đòn hụt thường cần có rủi ro rõ để việc đánh bừa không an toàn tuyệt đối.
- Có hitstop nếu thiết kế cần cảm giác va chạm rõ; thông số phải cấu hình và kiểm thử.

### 8.4. Knockback: dùng mô hình có tham số, không khẳng định công thức gốc

Không dùng công thức tự suy ra trong bản cũ như thể đó là công thức đầy đủ của Brawlhalla. Để prototype, có thể dùng mô hình nội bộ đơn giản:

```text
knockbackMagnitude = fixedForce + variableForce * damageFactor
velocity = directionFromAngle(knockbackAngle) * knockbackMagnitude
```

`damageFactor` là hàm cân bằng riêng của game nhóm, không tuyên bố tương đương chính xác với công thức game gốc. Sau đó điều chỉnh bằng test ở nhiều mức damage để tạo được cảm giác tăng knockback hợp lý.

### 8.5. Input Buffer

Buffer cho phép input được nhấn sớm trong cuối thời gian recovery và thực hiện ngay khi nhân vật có thể hành động. Bản cũ đề xuất 4–6 frames; nên xem đây là **giá trị bắt đầu để thử nghiệm cho prototype**, không phải thông số xác thực chính thức của Brawlhalla.

Đề xuất:

- `inputBufferFrames` cấu hình được.
- Xóa input đã hết hạn.
- Không lưu lệnh tấn công khi nhân vật đang ở trạng thái không cho phép hành động nếu game không chủ đích hỗ trợ buffer ở trạng thái đó.
- Viết test để xác nhận input không lặp ngoài ý muốn sau hitstun, landing hoặc respawn.

### 8.6. Damage Meter / Ringout

Nếu chọn mô phỏng Brawlhalla, dùng damage meter và knockback thay vì HP là hướng tham khảo phù hợp. Tách việc tích lũy damage khỏi điều kiện thắng:

- Damage tăng theo đòn đã trúng.
- Knockback được tính sau khi xác nhận hit.
- Khi nhân vật vượt vùng KO của màn đấu, trừ một stock/lượt.
- Khi hết stock, trận đấu kết thúc theo luật game.

Nếu prototype dùng HP, thay phần này bằng hệ thống HP rõ ràng và không trộn hai mô hình damage meter + HP một cách thiếu nhất quán.

---

## 9. Quy trình kiểm thử và tiêu chí chấp nhận

### 9.1. Bộ test tối thiểu

1. Đọc đa phím: trái/phải + attack; hướng xuống + attack; jump + attack; hướng + dodge.
2. Đòn Unarmed mặt đất: NLight, SLight, DLight, NHeavy, SHeavy, DHeavy.
3. Đòn Unarmed trên không: NAir, SAir, DAir, Recovery, Ground Pound.
4. Đòn bị hụt: recovery, hitbox không gây damage khi không chạm hurtbox, không lặp damage theo frame.
5. Hit reaction: damage/knockback/hitstun áp dụng đúng một lần cho mỗi hit hợp lệ.
6. Dodge: spot dodge, directional air dodge, cooldown và i-frames theo cấu hình.
7. GC: spot dodge trên không → đòn Grounded; test timing hợp lệ, quá hạn và dodge có hướng.
8. Nhảy/Recovery: cạn lượt, exhausted recovery, chạm sàn/tường và reset tài nguyên.
9. Combo: bật Show Stun; ghi nhận Dodge Frames ở mỗi mắt xích.
10. Ringout: kiểm tra các góc văng và vùng KO ở mép trái/phải/trên/dưới tùy thiết kế sân.

### 9.2. Cách kiểm tra combo

Trong Training Mode, với mỗi chuỗi:

- Ghi rõ move, damage mục tiêu, khoảng cách, độ cao và trạng thái của hai nhân vật.
- Bật Show Stun và kiểm tra Dodge Frames sau từng đòn nối.
- Test lại tại các mức damage đại diện (ví dụ 0, 50, 100, 150), nhưng không giả định các mức đó luôn đại diện hoàn toàn cho mọi điều kiện.
- Kiểm tra cả đối thủ không né và đối thủ cố né ở frame đầu tiên được phép.
- Nếu có Dodge Frames lớn hơn 0, nhãn true combo chỉ được giữ khi tài liệu xác định rõ điều kiện khác tạo ra 0 frame; nếu không thì đổi thành String/Follow-up.
- Test lại sau khi thay đổi startup, recovery, hitstun, force, hitbox hoặc movement.

### 9.3. Tiêu chí hoàn thành MVP

- Các input trong bảng có thể thực hiện riêng lẻ và kết hợp nhiều phím.
- Attack state có đủ Startup/Active/Recovery.
- Hitbox/hurtbox, damage, knockback và hitstun hoạt động ổn định.
- Nhảy, double jump trên không, Recovery và reset tài nguyên không bị vượt giới hạn.
- Dodge và Gravity Cancel có trạng thái/cooldown riêng, không lẫn với Dash.
- Có công cụ/debug tối thiểu để xem hitbox, hurtbox, frame trạng thái và Dodge Frames hoặc thông tin tương đương.
- Danh sách combo được lưu dạng dữ liệu kiểm thử; chưa xác minh thì không đánh dấu true combo.

---

## 10. Ngoài phạm vi và thứ tự triển khai đề xuất

**Không triển khai trong giai đoạn này:** vũ khí, vũ khí rơi/nhặt/ném, gadget, Signature theo Legend + vũ khí, combo theo từng vũ khí, Greatsword/Chakram, Active Input riêng của vũ khí, bảng balance theo vũ khí và tài khoản/skin/cosmetic.

Thứ tự triển khai đề xuất:

1. Input Resolver và điều khiển di chuyển.
2. Jump/Air Jump/Recovery/Fast-fall/landing.
3. Unarmed Light/Heavy theo Grounded/Airborne và hướng.
4. Hitbox/Hurtbox, damage, hitstun, knockback, ringout.
5. Dodge, cooldown, Gravity Cancel và Chase Dodge.
6. Input Buffer, Show Stun/debug tools và bộ test combo.
7. Tinh chỉnh cảm giác gameplay sau khi tất cả test nền tảng đã ổn định.

---

## 11. Nguồn tham khảo

Các trang dưới đây là tài liệu đối chiếu cơ chế Brawlhalla. Wiki có thể cập nhật theo phiên bản, vì vậy số frame cụ thể cần được kiểm tra lại trước khi hard-code.

1. **Controls – Official Brawlhalla Wiki:** https://brawlhalla.wiki.gg/wiki/Controls  
   Preset phím và tùy chọn điều khiển.
2. **Attacks – Official Brawlhalla Wiki:** https://brawlhalla.wiki.gg/wiki/Attacks  
   Đòn Ground/Aerial, Unarmed, charge, recovery, damage và knockback.
3. **Movement – Official Brawlhalla Wiki:** https://brawlhalla.wiki.gg/wiki/Movement  
   Jump, Recovery, Dodge, cooldown, Gravity Cancel và Wall Slip.
4. **Terminology – Official Brawlhalla Wiki:** https://brawlhalla.wiki.gg/wiki/Terminology  
   Định nghĩa các thuật ngữ combat.
5. **Brawlhalla Unarmed Guide – True Combos (Zexis):** https://www.youtube.com/watch?v=uASAmehOVUo  
   Nguồn hình ảnh cộng đồng để tham khảo combo Unarmed; không thay thế kiểm thử bản hiện hành.
6. **Brawlhalla’s Complete Terminology & Weapon Combos Guide (Steam Community):** https://steamcommunity.com/sharedfiles/filedetails/?id=2949957153  
   Có phần Unarmed và từ điển thuật ngữ; chỉ dùng các phần liên quan tới combat nền tảng trong phạm vi tài liệu này.
7. **Patch 11.00 – Official Brawlhalla:** https://www.brawlhalla.com/news/patch-1100  
   Mốc phiên bản tra cứu. Bản đặc tả này không sao chép bảng balance vũ khí vì hệ thống vũ khí đang nằm ngoài phạm vi triển khai.
