# BẢNG MÔ TẢ CHI TIẾT CÁC LỖI & PHƯƠNG ÁN KHẮC PHỤC (FIX1.MD)

Tài liệu này ghi nhận đầy đủ hiện tượng, phân tích nguyên nhân kỹ thuật trong mã nguồn và đề xuất giải pháp xử lý chính xác cho 6 vấn đề gameplay vừa phát hiện.

---

## 1. Lỗi Camera Giật Khi Nhảy Quá Cao (Vượt Chiều Cao Map)
* **Hiện tượng:**
  * Khi nhân vật nhảy lên rất cao (vượt qua mép trên cùng của bản đồ `mapHeight`), toàn bộ khung hình bản đồ bị khựng/giật giật cục một nhịp rất khó chịu.
* **Nguyên nhân kỹ thuật trong code:**
  * Nằm trong hàm `updateCamera()` của file `final-project/core/src/com/smashprofs/game/Screens/OnlinePlayScreen.java` (dòng 163–169):
    ```java
    float width = Math.max(5.5f, Math.max(maxX - minX + 1.5f, (maxY - minY + 1) * aspect));
    float height = width / aspect;
    float y = height < mapHeight 
        ? Math.max(height/2 - padding, Math.min(mapHeight - height/2 + padding, (minY+maxY)/2 + .1f)) 
        : mapHeight / 2;
    ```
  * **Giải thích:** Khi nhân vật nhảy vọt lên cao (`maxY` tăng vọt), kích thước `height` được mở rộng theo. Khi `height >= mapHeight`, toán tử điều kiện bậc ba `height < mapHeight ? ... : mapHeight/2` đột ngột lật trạng thái, ép tọa độ `camera.position.y` nhảy tức thì về tâm bản đồ (`mapHeight / 2`) thay vì bám mượt mà theo nhân vật. Bước nhảy đột ngột này tạo ra hiện tượng giật hình (visual stutter).
* **Giải pháp khắc phục:**
  * Dùng thuật toán làm mượt nội suy (Linear Interpolation - `lerp`) cho camera: `camera.position.y += (targetY - camera.position.y) * 0.1f`.
  * Khóa biên trần mềm (Soft ceiling clamp) thay vì gán cứng nhảy cóc về `mapHeight / 2`.

---

## 2. Lỗi Nhảy Sát Tường Bị Bật Ngược Ra Thay Vì Bám Tường Nhảy Lên
* **Hiện tượng:**
  * Người chơi nhảy sát vào vách tường đá thẳng đứng với ý định bám tường trượt xuống rồi nhảy bật lên (`Wall Slide & Wall Jump`).
  * Thực tế nhân vật vừa chạm tường là bị dội văng ngược ra ngoài ngay lập tức, không bám được vào vách tường để leo lên.
* **Nguyên nhân kỹ thuật trong code:**
  * Nằm trong `final-project/server/src/com/smashprofs/server/game/physics/PlayerJumpController.java`:
    1. **Điều kiện kích hoạt bám tường quá ngặt nghèo (dòng 108):**
       ```java
       state.wallSliding = wallMovement && contacts.wallDirection(playerId)!=0 && !state.grounded && body.getLinearVelocity().y < 0 && state.knockbackTicks==0;
       ```
       Hệ thống chỉ cho phép bám tường khi `velocityY < 0` (đang rơi xuống). Khi người chơi nhảy lao vào tường (`velocityY > 0`), trạng thái bám tường **chưa được bật**, thế là vật lý va chạm Box2D tự động đẩy nảy nhân vật bật ra xa tường ngay trước khi kịp rơi xuống.
    2. **Lực đẩy ngang khi nhảy bật tường quá mạnh (dòng 81):**
       ```java
       wallBody.applyLinearImpulse(wall * 1.4f - wallBody.getLinearVelocity().x, JUMP_FORCE - .8f * wallBody.getLinearVelocity().y, ...);
       ```
       Hệ số `wall * 1.4f` thổi bay nhân vật theo phương ngang (ra xa tường) quá nhiều thay vì cho phép nhảy chếch hướng lên trên theo mặt phẳng tường.
* **Giải pháp khắc phục:**
  * Bổ sung vùng đệm tiếp xúc tường (Wall Latch Window): Cho phép bám tường ngay cả khi nhân vật đang ở đỉnh điểm cú nhảy (`velocityY >= -0.1f`).
  * Khi người chơi giữ phím hướng về phía tường (ví dụ giữ `D` khi tường ở bên phải), duy trì trạng thái áp sát tường để triệt tiêu lực nảy ngang của Box2D.
  * Giảm lực hất ngang từ `1.4f` xuống `0.6f`, tăng lực nhảy thẳng đứng `JUMP_FORCE` để nhân vật nhảy vọt lên cao dọc theo bờ tường.

---

## 3. Chuyển Đổi Cơ Chế Nhặt Tự Động Sang Phím Nhặt & Vứt Vũ Khí
* **Hiện tượng & Nhu cầu:**
  * Hiện tại trong `ItemSystem.java` (dòng 50), hễ người chơi chạy ngang qua chạm vào bán kính `0.25m` của cây súng là game **tự động nhặt ngay lập tức**, đồng thời đè mất vũ khí cũ đang cầm.
  * Người chơi muốn chủ động: Phải bấm phím chỉ định mới nhặt, và có phím để vứt bỏ vũ khí đang cầm nếu không muốn dùng.
* **Nguyên nhân kỹ thuật trong code:**
  * `ItemSystem.java` quét khoảng cách `Math.hypot(...) <= 0.25` và gọi trực tiếp `collect(id, drop.type)` mỗi tick mà không kiểm tra cờ input phím từ người chơi.
  * Trong giao thức `PlayerInput.java` chưa có cờ `pickupPressed` hoặc `dropPressed`.
* **Giải pháp khắc phục:**
  1. **Thêm phím bấm:**
     * Phím **`E`**: Nhặt vũ khí khi đứng gần vật phẩm.
     * Phím **`G`** (hoặc giữ `E` 0.5s): Vứt bỏ vũ khí hiện tại (trở về đánh tay không).
  2. **Cập nhật giao thức mạng:**
     * Bổ sung cờ `interactPressed` vào `PlayerInput.java` và `ProtocolV1.java`.
  3. **Cập nhật Server:**
     * Trong `ItemSystem.java`: Chỉ gọi `collect()` khi khoảng cách `<= 0.35m` **VÀ** người chơi có gửi cờ `interactPressed`.
     * Khi người chơi bấm vứt đồ: Cây súng được nhả ra rơi lại xuống sàn với số đạn còn lại, trạng thái nhân vật chuyển về `weapon = "NONE"`.

---

## 4. Bậc Thấp Nhất Của Bản Đồ Bị Nhận Nhầm Là Dung Nham
* **Hiện tượng:**
  * Người chơi đang đứng vững vàng trên bậc thềm đá thấp nhất của bản đồ nhưng liên tục bị giật nảy lên và bị trừ 10 HP như thể vừa rơi xuống dung nham.
* **Nguyên nhân kỹ thuật trong code:**
  * Nằm trong hàm `applyLavaContacts()` của `final-project/server/src/com/smashprofs/server/game/GameplayMatch.java` (dòng 100–103):
    ```java
    for(var lava:lavaZones) {
        float left = lava.xPixels()/100, right = (lava.xPixels()+lava.widthPixels())/100, top = (lava.yPixels()+lava.heightPixels())/100;
        if(position.xMeters()+radius >= left && position.xMeters()-radius <= right && position.yMeters()-radius <= top + .002f) { 
            touching = true; 
            break; 
        }
    }
    ```
  * **Giải thích:**
    1. Vùng dung nham `lavaZones` trong file TMX được đặt quá sát với mép đáy của bậc đá thấp nhất.
    2. Điều kiện kiểm tra `position.yMeters() - radius <= top + .002f` không hề kiểm tra xem nhân vật có đang đứng trên mặt đất kiên cố (`grounded`) hay không!
    3. Khi nhân vật tiếp đất trên bậc đá thấp nhất, trọng lực Box2D làm nhân vật lún nhẹ vài milimet tiếp xúc, đáy vòng tròn chân nhân vật lọt vào ngưỡng `top + .002f`, dẫn đến hệ thống phán quyết nhầm là chạm dung nham!
* **Giải pháp khắc phục:**
  * Bổ sung điều kiện loại trừ: Nếu nhân vật đang đứng vững trên platform (`state.isGrounded() == true` và tiếp xúc với collider solid của bậc đá) thì **KHÔNG** kích hoạt sát thương dung nham.
  * Hạ thấp ngưỡng `top` của vùng nguy hiểm dung nham xuống thêm `0.08m` (hoặc điều chỉnh lại object layer trong file TMX) để tách biệt hoàn toàn với bề mặt bậc thềm đá.

---

## 5. Lực Bật Nảy Dung Nham Quá Cao (Cần Giảm Lại Cho Vừa Phải)
* **Hiện tượng:**
  * Khi chạm dung nham, nhân vật bị hất văng lên trời quá cao, bay lơ lửng mất kiểm soát cả màn hình.
* **Nguyên nhân kỹ thuật trong code:**
  * Nằm trong hàm `bounceFromLava()` của `final-project/server/src/com/smashprofs/server/game/physics/PlayerJumpController.java` (dòng 165):
    ```java
    body.setLinearVelocity(vx, 2.6f);
    state.gravityImpulse = GRAVITY_IMPULSE * .3f;
    ```
  * **Giải thích:**
    * Vận tốc nhảy thẳng đứng `2.6f` là cực kỳ lớn (lực nhảy thông thường chỉ khoảng `1.2f - 1.5f`).
    * Hơn nữa, dòng code còn làm giảm trọng lực xuống chỉ còn 30% (`GRAVITY_IMPULSE * .3f`), khiến nhân vật vừa bay cực cao vừa rơi xuống rất chậm (hiệu ứng không gian mặt trăng / floaty), làm gián đoạn nhịp độ trận đấu.
* **Giải pháp khắc phục:**
  * Giảm vận tốc bật nảy thẳng đứng từ `2.6f` xuống mức vừa phải: **`1.4f`** (đủ để nhảy thoát khỏi mặt lửa lên bệ đá gần nhất).
  * Giữ nguyên trọng lực bình thường hoặc chỉ giảm nhẹ (`GRAVITY_IMPULSE * 0.7f`) để nhân vật có cảm giác rơi tự nhiên, tiếp đất nhanh chóng và phản xạ điều khiển mượt mà hơn.

---

## 6. Lỗi Khựng 2 Nhịp Khi Hồi Sinh & Nhu Cầu Hồi Sinh Vị Trí Ngẫu Nhiên
* **Hiện tượng & Nhu cầu:**
  1. **Khựng 2 nhịp:** Khi vừa hết 1,5 giây đếm ngược hồi sinh, nhân vật xuất hiện trở lại trên sàn đấu nhưng bị "khựng/đơ" khoảng 2 nhịp (2–3 tick), không phản ứng mượt mà với phím bấm hay rơi tự do ngay.
  2. **Vị trí cố định (dễ bị camp):** Hiện tại người chơi khi chết đi sống lại luôn rớt xuống đúng một điểm cố định duy nhất ở giữa map cho từng slot, khiến đối thủ dễ dàng đứng đón lõng tấn công (spawn-camp). Người chơi muốn được hồi sinh ngẫu nhiên tại các điểm an toàn khác nhau trên map.
  3. **Hiệu ứng bất tử:** Ghi nhận hiệu ứng bất tử nhấp nháy ánh sáng vàng mờ trong 2 giây đầu hiện tại đã hoạt động rất tốt, cần tiếp tục giữ nguyên.
* **Nguyên nhân kỹ thuật trong code:**
  1. **Nguyên nhân khựng 2 nhịp:**
     * Nằm ở sự lệch pha giữa **Server Input Filter** và **Client Interpolation Buffer**:
     * Trên Server (`final-project/server/src/com/smashprofs/server/game/GameplayMatch.java` dòng 49–53): Khi `tick >= p.respawnAt`, server gọi `bodies.respawn(id, x, y)` đặt vận tốc về 0, chuyển trạng thái sang `ACTIVE`. Nhưng trước thời điểm đó, mọi gói phím input từ client đều bị `ProtocolV1Dispatcher.java` loại bỏ (`PLAYER_INACTIVE`).
     * Trên Client (`final-project/core/src/com/smashprofs/game/network/client/StateInterpolator.java` dòng 45–85): Hệ thống hiển thị luôn giữ một lượng trễ bộ đệm nội suy `delayTicks` (khoảng 2–3 tick) để làm mượt chuyển động mạng. Khi gói snapshot hồi sinh đầu tiên về tới client, bộ nội suy vẫn đang phát lại các tick cũ trong bộ đệm trễ (nơi nhân vật vẫn còn đang ở trạng thái chết/đứng yên). Do đó, người chơi nhìn thấy nhân vật bị khựng mất 2 nhịp trước khi bắt đầu nhận chuyển động mượt mà.
  2. **Nguyên nhân vị trí hồi sinh bị cố định:**
     * Nằm trong hàm khởi tạo và hàm cập nhật của `final-project/server/src/com/smashprofs/server/game/GameplayMatch.java`:
     * Dòng 31: Game tính trước một điểm cố định duy nhất cho mỗi người chơi:
       ```java
       for(var p:setup.participants()) { 
           respawnPositions.put(p.playerId(), safeRespawn(geometry, p.slotIndex())); 
       }
       ```
     * Dòng 51: Khi hồi sinh, server chỉ gọi:
       ```java
       var position = respawnPositions.get(id);
       bodies.respawn(id, position.xMeters(), position.yMeters());
       ```
       Vì `position` được lấy cố định từ Map theo `slotIndex`, nhân vật lần nào chết cũng chỉ rơi xuống đúng một tọa độ duy nhất đó.
* **Giải pháp khắc phục:**
  1. **Khắc phục khựng 2 nhịp (Instant Snap & Input Flush):**
     * **Phía Client (`StateInterpolator.java`):** Khi phát hiện trạng thái người chơi chuyển từ `RESPAWNING` sang `ACTIVE` (hoặc `p.dead()` chuyển từ `true` sang `false`), ép cờ `snap = true` và xóa bỏ thời gian trễ nội suy đối với nhân vật đó để lập tức vẽ nhân vật tại tọa độ hồi sinh mới nhất, không chờ trễ 2 nhịp.
     * **Phía Server (`GameplayMatch.java` & `PlayerPhysicsFactory.java`):** Khi vừa kích hoạt `respawn`, cấp ngay một xung lực rơi tự nhiên nhẹ hoặc làm mới hàng đợi input để tiếp nhận tức thì lệnh bấm di chuyển của người chơi.
  2. **Hồi sinh vị trí ngẫu nhiên (Randomized Safe Spawn):**
     * Mở rộng `SpawnProvider.java` và `GameplayMatch.java`: Khai thác danh sách các điểm spawn an toàn trên các bậc địa hình của map (bệ trái, bệ phải, bệ trung tâm, bệ trên cao).
     * Khi xử lý `tick >= p.respawnAt`, thay vì lấy tọa độ cố định, gọi hàm chọn ngẫu nhiên `safeRandomRespawn(geometry)` từ danh sách các bệ platform khả dụng (có thể ưu tiên bệ ở xa đối thủ gần nhất để chống spawn-camp).
  3. **Bảo tồn hiệu ứng:**
     * Giữ nguyên thời gian bất tử `INVULNERABLE_TICKS = 120` (2 giây) và chu kỳ nhấp nháy vàng mờ `animationTime * 12 % 2 == 0` trong `OnlinePlayScreen.java:109`.

