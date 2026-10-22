# NHIỆM VỤ NGƯỜI THỨ 3 — Ranking & Protocol Backend
> **Project:** Networked Super Smash Profs (Java 17 + libGDX + Gradle 7.3.3)
> **Yêu cầu môi trường:** JDK 17, Gradle 7.3.3. Không nâng cấp Gradle, không thay đổi core gameplay/physics/combat.
> **Quy tắc:** Viết code kèm test JUnit 4 tương ứng cho từng Task. Sau khi xong mỗi Task, bắt buộc chạy lệnh test kiểm thử qua Gradle.

---

## PHẦN 1: CÁC HẠNG MỤC ĐÃ CÓ (KHÔNG LẠI LÀM)
- Protocol contract cơ bản (MessageType v1, `ProtocolMessage`, DTOs), framing TCP, serializer/parser JSON.
- Heartbeat PING/PONG, timeout policy, `MatchResultPublisher` và `RankingGateway` interface.
- `FileMatchHistory` (lưu JSON lịch sử trận) và `LoadGenerator` (load test).

---

## PHẦN 2: CÁC HẠNG MỤC CẦN THỰC HIỆN (GIAO CHO ANTIGRAVITY)

### [x] Task T03 — Xây dựng `RatingEngine`
- **Mục tiêu:** Tính toán rating mới từ `AuthoritativeMatchResult` cho 2–4 người chơi.
- **Yêu cầu:** 
  - Tạo `RatingPolicy.java`, `PlayerRecord.java` và `RatingEngine.java` trong package `server/src/com/smashprofs/server/ranking/`.
  - Áp dụng công thức Elo đối đầu (hòa không đổi rating chỉ tăng số trận, chống giá trị NaN).
- **Test:** Viết `RatingEngineTest.java` (test 2/3/4 người, trường hợp hòa, không NaN).
- **Lệnh kiểm thử:** `.\gradlew.bat :server:test --tests "*RatingEngineTest"`

### [ ] Task T04 — Xây dựng `RankingStore` (SQLite + Lưu trữ)
- **Mục tiêu:** Lưu trữ hồ sơ người chơi và chống trùng `matchId` trong một giao dịch cơ sở dữ liệu.
- **Yêu cầu:**
  - Thêm dependency `sqlite-jdbc` vào `server/build.gradle`.
  - Tạo `RankingStore.java` (interface), `InMemoryRankingStore.java` và `SqliteRankingStore.java` trong package `server/src/com/smashprofs/server/ranking/`.
  - Bảng dữ liệu gồm: `players` (profile_id PK, stats...) và `processed_matches` (match_id PK). INSERT trùng `matchId` phải trả về trạng thái `DUPLICATE`.
- **Test:** Viết `RankingStoreTest.java`.
- **Lệnh kiểm thử:** `.\gradlew.bat :server:test --tests "*RankingStoreTest"`

### [x] Task T05 — Xây dựng `RankingService` (Idempotent + Đồng thời)
- **Mục tiêu:** Hiện thực hóa `RankingGateway` để xử lý kết quả trận đấu an toàn, chống cộng điểm 2 lần khi trùng `matchId`.
- **Yêu cầu:**
  - Tạo `RankingService.java` (implements `RankingGateway`).
  - Đảm bảo tính an toàn đồng thời (thread-safe, xử lý tuần tự qua lock) để nhiều trận kết thúc cùng lúc không bị lệch dữ liệu.
  - Xử lý ngoại lệ DB an toàn, không làm crash GameLoop.
- **Test:** Viết `RankingServiceTest.java` và `RankingServiceConcurrencyTest.java` (kiểm tra nộp trùng `matchId` và nộp đồng thời bằng `ExecutorService`).
- **Lệnh kiểm thử:** `.\gradlew.bat :server:test --tests "*RankingServiceTest"`

### [x] Task T07 & T08 — Bổ sung Protocol Leaderboard & `profileId`
- **Mục tiêu:** Mở rộng giao thức mạng để hỗ trợ lấy bảng xếp hạng và định danh người chơi.
- **Yêu cầu:**
  - Cập nhật `MessageType.java` thêm: `GET_LEADERBOARD`, `RANKING_SNAPSHOT`, `RANKING_UPDATED`.
  - Tạo `RankingProtocol.java` (xử lý payload/parser cho message leaderboard).
  - Hỗ trợ nhận tùy chọn `profileId` và `displayName` trong message `CONNECT`.
- **Test:** Viết `RankingProtocolTest.java`.
- **Lệnh kiểm thử:** `.\gradlew.bat :common:test --tests "*RankingProtocolTest"`

### [x] Task T09 & T10 — Handler `GET_LEADERBOARD` & `LeaderboardBroadcaster`
- **Mục tiêu:** Cho phép client yêu cầu bảng xếp hạng và server tự động đẩy (push) cập nhật realtime khi có trận đấu kết thúc.
- **Yêu cầu:**
  - Xử lý message `GET_LEADERBOARD` trong `ProtocolV1Dispatcher.java` để trả về `RANKING_SNAPSHOT`.
  - Tạo `LeaderboardBroadcaster.java` (implements `RankingListener`) để gửi tin nhắn `RANKING_UPDATED` tới các client đã đăng ký một cách non-blocking.
- **Test:** Viết `LeaderboardRequestTest.java` và `LeaderboardBroadcasterTest.java`.
- **Lệnh kiểm thử:** `.\gradlew.bat :server:test --tests "*Leaderboard*Test"`

### [ ] Task T11 — Kiểm thử tích hợp End-to-End Ranking qua TCP
- **Mục tiêu:** Kiểm tra toàn bộ luồng từ Client gửi kết nối, chơi game, kết thúc trận, cập nhật DB đến nhận dữ liệu Leaderboard qua TCP thực tế.
- **Yêu cầu:**
  - Tạo `RankingEndToEndTcpTest.java` mô phỏng luồng client kết nối và nhận kết quả xếp hạng.
- **Lệnh kiểm thử:** `.\gradlew.bat :server:test --tests "*RankingEndToEndTcpTest"`