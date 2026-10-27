# KẾ HOẠCH: HIỂN THỊ TÊN NGƯỜI CHƠI (DISPLAY NAME) TRÊN MÀN HÌNH TỔNG KẾT / BẢNG XẾP HẠNG CUỐI TRẬN

> **Tài liệu dành cho Antigravity / người thực thi.** Đọc hết mục 0 và mục 1 trước khi sửa bất kỳ file nào.
> Phạm vi: `GAME_END` (Server) → `WireMatchResult` (Client) → `OnlineResultScreen` (thẻ kết quả trên bục podium).
> Kế hoạch được lập hoàn toàn từ mã nguồn trong `lap_trinh_mang_dev.zip` và ảnh chụp màn hình kết quả (MAURICE / ALEX).
> Tài liệu này **nối tiếp** `docs/displaynameplan.md` (tên trên đầu nhân vật trong trận). Các thành phần đã làm xong ở đó (`DisplayName`, `GameRoom.displayNames`, `MatchSetup.Participant.displayName`, `GameStartRoster.Participant.displayName`) được **tái sử dụng**, không làm lại.

---

## 0. LƯU Ý BẮT BUỘC TRƯỚC KHI THỰC THI

### 0.1. Stack & quy ước
- **Java 17+ / libGDX / Box2D / Gradle**; client–server qua TCP, JSON `Protocol v1`.
- Module Gradle: `common`, `server`, `core`, `desktop`, `tests` (+ `web/` dùng TeaVM, dùng chung `core`/`common`).
- Mã mới trong `common` và `core` **chỉ dùng API Java cơ bản** (không `java.text.Normalizer`, regex nặng, reflection) để TeaVM build được.
- Mọi đường dẫn tính từ thư mục gốc repo `lap_trinh_mang_dev/`. Lệnh Gradle chạy từ `final-project/`.

### 0.2. Quy tắc sửa file
- **CRLF**: toàn bộ `.java` và `docs/*.md` dùng `\r\n`. **Phải giữ nguyên CRLF**, kể cả file mới tạo.
  - Kiểm tra sau khi sửa: `git diff --stat` chỉ được hiện vài dòng đổi mỗi file, **không** phải toàn file.
  - Kiểm tra kiểu xuống dòng: `file <đường-dẫn>` phải có chữ `with CRLF line terminators`.
- **Phong cách code**: dự án viết dày (nhiều lệnh một dòng, ít khoảng trắng quanh toán tử, `if(...) { ... }`). Giữ phong cách của vùng đang sửa.
- **KHÔNG sửa**: `**/bin/**`, `**/build/**`, `.gradle/`, mọi file trong `assets/`.
- **KHÔNG đụng vật lý Box2D**: không sửa file nào trong `server/src/**/physics/**`, `FighterPhysics.java`, `LocalFighterPrediction.java`, `PlayerPhysicsFactory.java`. Display Name chỉ là dữ liệu + UI.
- **KHÔNG đổi cấu trúc ELO / Database**: `SqliteRankingStore`, `InMemoryRankingStore`, `RatingEngine`, `PlayerRecord` giữ nguyên. Chỉ được sửa `RankingService.resolveProfiles` ở mức "chuyển tiếp thêm 1 tham số" (xem Bước 1.4).
- Tạo nhánh: `git checkout -b feature/display-name-in-scoreboard`.

### 0.3. Yêu cầu nghiệp vụ (tóm tắt)
1. Ở mỗi thẻ kết quả (dưới tên nhân vật `MAURICE`, `ALEX`...), dòng phụ đề hiện tại là `"Player " + (slot+1)` (+ `" (Bạn)"`) phải đổi thành **Display Name** của người chơi đó.
2. Vẫn giữ hậu tố `" (Bạn)"` nếu đó là người chơi cục bộ của client.
3. Tên tuân thủ `DisplayName.sanitize` (**tối đa 12 ký tự**), không tràn khung thẻ.
4. Gói tin `GAME_END` phải **mang `displayName` của từng người chơi** từ Server về mọi Client.
5. Tương thích ngược ở phía client: server cũ (không có `displayName`) → fallback sang `characterId` (chuỗi cũ "Player N" bị loại bỏ vì không có ý nghĩa với người chơi).

---

## 1. PHÂN TÍCH LUỒNG DỮ LIỆU KẾT QUẢ TRẬN ĐẤU (MATCH END DATA FLOW)

### 1.1. Class / Packet đại diện cho kết quả trận đấu

| Tầng | Class / Packet | File | Ghi chú |
|---|---|---|---|
| Wire | Message `GAME_END` (S → C, push, không `requestId`) | `docs/PROTOCOL_V1.md` mục "GAME_END" | Luôn đi sau `GAME_STATE` cuối cùng (`phase=FINISHED`) trong cùng một gói `GameEndDelivery` |
| Server – model | `AuthoritativeMatchResult` + `ParticipantResult` | `final-project/server/src/com/smashprofs/server/game/AuthoritativeMatchResult.java` | Kết quả bất biến, do `MatchEndEvaluator` tạo |
| Server – mapper | `GameEndMessageMapper.map(...)` | `final-project/server/src/com/smashprofs/server/network/GameEndMessageMapper.java` | Chuyển model → JSON payload |
| Client – model | `WireMatchResult` + `WireMatchResult.Participant` | `final-project/core/src/com/smashprofs/game/network/client/WireMatchResult.java` | Parser **nghiêm ngặt** |
| Client – phiên | `ClientMatchSession.acceptEnd(...)` | `final-project/core/src/com/smashprofs/game/network/client/ClientMatchSession.java` (~dòng 133) | Parse → `validateRoster(roster)` → đối chiếu snapshot cuối |
| Client – UI | `OnlineResultScreen` | `final-project/core/src/com/smashprofs/game/Screens/OnlineResultScreen.java` | Vẽ podium + thẻ kết quả |

> Lưu ý: `common/.../model/MatchResult.java` (`matchId, roomId, winnerId, participantIds, finishedAtEpochMs`) là DTO **legacy v0.1**, **không** được dùng trên đường GAME_END v1 (`MATCH_RESULT` bị codec từ chối). **Không sửa** file này.

### 1.2. Dòng chảy dữ liệu hiện tại (tên bị MẤT ở đâu)

```
GameRoom.displayNames[playerId]  (đã sanitize ≤ 12, có từ displaynameplan)
   └─ GameRoom.prepareMatch(): MatchSetup.Participant(playerId, slot, characterId, displayName)   ✔ có tên
        ├─ GAME_START.players[] = {playerId, slotIndex, characterId, displayName}                ✔ có tên
        │      └─ Client: GameStartRoster.Participant(..., displayName)                           ✔ có tên (nhưng chỉ dùng trong trận)
        │
        └─ MatchRuntime (giữ `setup`)  ── tick cuối ──►  MatchEndEvaluator.evaluate(snapshot, expired)
               │   snapshot.players() = AuthoritativeGameSnapshot.PlayerSnapshot (KHÔNG có tên)
               ✗ (1) ParticipantResult(playerId, slotIndex, characterId, finalHp, eliminated)   → mất tên
                 └─ GameEndMessageMapper: participants[i] = {playerId, slotIndex, characterId, finalHp, eliminated}
                      ✗ (2) payload không có displayName (5 field/participant)
                        └─ Client: WireMatchResult.parse → bắt buộc p.size() == 5
                             ✗ (3) Participant(playerId, slotIndex, characterId, finalHp, eliminated)
                               └─ OnlineResultScreen (dòng ~281):
                                    subtitle = "Player " + (slotIndex + 1) + (isLocal ? " (Bạn)" : "")   ← chuỗi cứng
```

### 1.3. Các field hiện có của dữ liệu người chơi trong kết quả trận

**`GAME_END` payload (cấp gốc – 6 field, hoặc 7 nếu có `gameplay`):**
- `matchId`, `roomId`, `finishedAtTick`, `winnerId` (nullable), `finishReason`, `participants[]`, và (tùy chọn) `gameplay`.

**`participants[i]` hiện tại – đúng 5 field:**

| Field | Kiểu | Nguồn |
|---|---|---|
| `playerId` | string | `PlayerSnapshot.playerId()` |
| `slotIndex` | int 0..3 | `PlayerSnapshot.slotIndex()` |
| `characterId` | string | `PlayerSnapshot.characterId()` |
| `finalHp` | number 0..100 | `PlayerSnapshot.hp()` |
| `eliminated` | boolean | `dead` hoặc `lives()==0` |

**Thống kê chi tiết (SCORE / KOS / FALLS / DMG DONE)** nằm trong `gameplay.players[]` (`GameplaySnapshot`): `kills`, `deaths`, `damageDealt`, `lives`. Điểm ELO trên thẻ do client tự tính (`±16`) chứ không có trong gói tin. Hai nhóm này **không cần sửa**.

### 1.4. ĐIỂM THIẾU FIELD `displayName` (kết luận)

- Thiếu ở **3 điểm** trên đường GAME_END:
  1. `AuthoritativeMatchResult.ParticipantResult` – không có component `displayName`.
  2. `GameEndMessageMapper` – không `put("displayName", ...)`.
  3. `WireMatchResult.parse` / `WireMatchResult.Participant` – ép `p.size()==5`, record không có `displayName`.
- **Nguyên nhân gốc**: `MatchEndEvaluator` chỉ nhận `AuthoritativeGameSnapshot` (không có tên). Nhưng `MatchRuntime` vẫn giữ `setup` (có `Participant.displayName` ở mọi người chơi), nên **điểm gắn tên đúng** là `MatchRuntime`, không phải `MatchEndEvaluator` (evaluator giữ nguyên là hàm thuần).
- Màn hình kết quả hiện chỉ hiển thị được `characterId` (MAURICE/ALEX) – đúng như ảnh chụp.

### 1.5. Các phát hiện quan trọng (đã kiểm chứng trong mã)

1. **Parser client rất nghiêm ngặt**: `WireMatchResult.parse` yêu cầu `p.isObject() && p.size() == 5`. Thêm field ở server mà không sửa parser → client **ném `IllegalArgumentException`, không hiện màn kết quả**.
2. **Client ĐÃ có sẵn tên**: `GameStartRoster` (nhận từ GAME_START) đã chứa `displayName` cho mọi người chơi, và `ClientMatchSession` giữ `roster`. Đây là lớp phòng vệ thứ hai (xem Bước 2.3), nhưng yêu cầu nghiệp vụ là gói kết quả phải mang tên nên vẫn làm đầy đủ ở server.
3. **Hàng chục test gọi `new ParticipantResult(id, slot, "Alex", hp, dead)` (5 tham số)** (xem `RankingServiceTest`, `RatingEngineTest`, `GuestProfileTest`, `FileMatchHistoryTest`, `TcpSnapshotBackpressureTest`, `OnlinePresentationTest`...). Bắt buộc **giữ constructor 5 tham số** (cùng cách `MatchSetup.Participant` đã làm).
4. **`MatchEndEvaluator.evaluate(snapshot, expired)` (2 tham số) được test gọi trực tiếp** (`BossModeTest`, `BossRankingIntegrationTest`). Bắt buộc **giữ overload 2 tham số**.
5. **`RankingService.resolveProfiles` (dòng ~192) dựng lại `ParticipantResult`** để đổi `playerId` sang profile xếp hạng. Nếu quên chuyển tiếp `displayName`, tên sẽ bị reset về `characterId` trong dữ liệu lịch sử/ranking (không ảnh hưởng ELO, nhưng làm mất dữ liệu).
6. **`LoadGenerator` (cluster) kiểm tra GAME_END theo từng `path(...)`, không kiểm tra `size()`** → thêm field không làm hỏng; **không cần sửa**.
7. **`FileMatchHistory` lưu `AuthoritativeMatchResult` thành JSON** và so byte để phát hiện kết quả trùng. Thêm field `displayName` chỉ làm JSON dài hơn, hoàn toàn xác định (cùng kết quả → cùng bytes). **Không cần sửa**, nhưng `FileMatchHistoryTest` nên chạy lại.
8. **Module `web/` không có parser GAME_END** (đã grep: không có `GAME_END`/`WireMatchResult`/`characterId`), nên **không phải sửa** `web/`.
9. **Font**: `OnlineUi.small(text, color)` dùng `ui/online/small.fnt` (623 glyph, gồm tiếng Việt `ế, ệ, ư, ơ, đ`) → hiển thị được tên tiếng Việt. Không dùng `new BitmapFont()` mặc định.
10. **Độ rộng thẻ**: `pillarW` = 280 / 270 / 245 / 230 (1 / 2 / 3 / 4 người); `cardW = pillarW - 14`, padding 8+8, icon 28 + 6 → còn ~166 px chữ ở trường hợp 4 người. Tên 12 ký tự + `" (Bạn)"` có thể sát giới hạn → thêm `setEllipsis(true)` + width cố định làm chốt an toàn.
11. **Chế độ Boss** cũng đi qua cùng thẻ này (`result.isBoss()`: rank text "ĐỒNG ĐỘI n"). Việc thay phụ đề áp dụng cho cả PvP lẫn Boss.
12. **Dòng `"▲ BẠN"` phía trên nhân vật** (≈ dòng 594) là chỉ báo người chơi cục bộ, **giữ nguyên**, không thuộc phạm vi.

### 1.6. Quyết định thiết kế

| # | Quyết định | Lý do |
|---|---|---|
| D1 | Thêm `displayName` vào **`GAME_END.participants[i]`** (5 → 6 field) | Đúng yêu cầu "gói kết quả truyền tải displayName"; scoreboard không phụ thuộc trạng thái roster |
| D2 | Gắn tên ở **`MatchRuntime`** (lấy từ `setup.participants()`), qua **overload** `MatchEndEvaluator.evaluate(snapshot, expired, names)` | Evaluator vẫn thuần túy; không đọc session/room (đúng comment đầu class) |
| D3 | `ParticipantResult` thêm component thứ 6 `displayName` + **giữ constructor 5 tham số** | Không phá hàng chục test |
| D4 | Compact constructor của `ParticipantResult` và `WireMatchResult.Participant` gọi `DisplayName.sanitize(displayName, characterId)` | Một nguồn quy tắc duy nhất (tối đa 12 ký tự), fallback = `characterId` |
| D5 | Parser client chấp nhận **5 hoặc 6 field** | Client mới chạy được với server cũ |
| D6 | **Server mới ↔ client cũ KHÔNG tương thích** (client cũ từ chối payload 6 field) | Phải deploy server + client cùng lúc; ghi vào `PROTOCOL-CHANGELOG.md` + `PROTOCOL_V1.md` |
| D7 | `validateRoster` **không** so sánh `displayName` | Tránh từ chối oan khi server cũ/mới trộn; tên chỉ để hiển thị |
| D8 | UI: dòng 1 vẫn là tên nhân vật in hoa (`MAURICE`), dòng 2 là `displayName` (+ `" (Bạn)"`) | Khớp bố cục ảnh hiện tại; thay đúng chuỗi cứng, không đổi layout |

---

## 2. DANH SÁCH FILE & COMPONENT CẦN TÁC ĐỘNG

### 2.1. File ở SERVER (sửa)

| # | Đường dẫn | Việc cần làm |
|---|---|---|
| S1 | `final-project/server/src/com/smashprofs/server/game/AuthoritativeMatchResult.java` | `ParticipantResult` thêm component `displayName` + constructor 5 tham số + sanitize trong compact constructor |
| S2 | `final-project/server/src/com/smashprofs/server/game/MatchEndEvaluator.java` | Thêm overload `evaluate(snapshot, expired, Map<PlayerId,String> names)`; overload cũ gọi overload mới với `Map.of()` |
| S3 | `final-project/server/src/com/smashprofs/server/game/MatchRuntime.java` | Thêm helper `displayNames()` từ `setup.participants()`; truyền vào **2 chỗ** gọi `MatchEndEvaluator.evaluate` (~dòng 410 và ~458) |
| S4 | `final-project/server/src/com/smashprofs/server/network/GameEndMessageMapper.java` | `.put("displayName", player.displayName())` cho mỗi participant |
| S5 | `final-project/server/src/com/smashprofs/server/ranking/RankingService.java` | `resolveProfiles` (~dòng 192): chuyển tiếp `p.displayName()` vào `ParticipantResult` mới (chỉ thêm 1 tham số, không đổi logic ELO) |

### 2.2. File ở `core` (Client – sửa)

| # | Đường dẫn | Việc cần làm |
|---|---|---|
| C1 | `final-project/core/src/com/smashprofs/game/network/client/WireMatchResult.java` | `Participant` thêm `displayName` + constructor 5 tham số; `parse` nhận 5 hoặc 6 field |
| C2 | `final-project/core/src/com/smashprofs/game/Screens/OnlineResultScreen.java` | Thay `"Player " + (slot+1)` (~dòng 281) bằng `displayName` (+ `" (Bạn)"`), thêm ellipsis + width |

### 2.3. File chỉ ĐỌC (không sửa logic)

| Đường dẫn | Lý do |
|---|---|
| `common/src/com/smashprofs/common/model/DisplayName.java` | Dùng `DisplayName.sanitize(raw, fallback)`; `MAX_LENGTH = 12` |
| `common/src/com/smashprofs/common/model/MatchResult.java` | DTO legacy v0.1, **không** nằm trên đường GAME_END v1 |
| `server/src/com/smashprofs/server/game/MatchSetup.java` | `Participant.displayName` đã có (đã sanitize) – nguồn tên cho Bước 1.3 |
| `server/src/com/smashprofs/server/room/GameRoom.java` | `displayNames` + `setDisplayName` đã có (dòng ~38, ~232, ~505) |
| `server/src/com/smashprofs/server/network/StateBroadcaster.java`, `GameEndDelivery.java` | Chỉ chuyển nguyên message đã map; không sửa |
| `server/src/com/smashprofs/server/cluster/LoadGenerator.java` | Kiểm tra GAME_END theo `path()`, không phụ thuộc số field |
| `server/src/com/smashprofs/server/cluster/FileMatchHistory.java` | Lưu JSON result; field mới tự được ghi thêm |
| `core/.../network/client/ClientMatchSession.java` | `acceptEnd` gọi `WireMatchResult.parse` + `validateRoster`; không cần sửa |
| `core/.../network/client/GameStartRoster.java` | Đã có `displayName`; dùng cho fallback (Bước 2.3) |
| `core/.../ui/OnlineUi.java` | `small(text, color)` dùng `small.fnt` (có tiếng Việt) |
| `web/**` | Không có parser GAME_END → không ảnh hưởng |

### 2.4. Test & tài liệu cần cập nhật

| Đường dẫn | Việc cần làm |
|---|---|
| `server/test/com/smashprofs/server/network/GameEndMessageMapperTest.java` | Thêm test `displayName`; assert `participants[i].size()==6` |
| `server/test/com/smashprofs/server/game/MatchRuntimeEndingTest.java` | Thêm test: kết quả cuối trận mang đúng tên từ `MatchSetup` |
| `server/test/com/smashprofs/server/game/TwoClientTcpMatchEndToEndTest.java`, `FourClientTcpMatchEndToEndTest.java`, `GameEndTcpDeliveryTest.java` | Thêm assert `displayName` ở GAME_END (xem mục 4.1) |
| `tests/src/com/smashprofs/game/network/client/OnlinePresentationTest.java` | Cập nhật test parse (5 hoặc 6 field); thêm test tên rỗng/dài/sai kiểu |
| `docs/PROTOCOL_V1.md` (mục "GAME_END") | Thêm `displayName:string` vào schema Participant |
| `docs/PROTOCOL-CHANGELOG.md` | Ghi mục mới (xem Bước 3) |

---

## 3. HƯỚNG DẪN SỬA ĐỔI TỪNG BƯỚC (STEP-BY-STEP FOR ANTIGRAVITY)

> Chạy build sau **mỗi bước**: `cd final-project && ./gradlew :common:test :server:compileJava :core:compileJava` (Windows: `gradlew.bat`). Chỉ sang bước kế tiếp khi biên dịch xanh.

### BƯỚC 0 – Chuẩn bị
- `git checkout -b feature/display-name-in-scoreboard`
- Chạy test hiện có để lấy baseline: `./gradlew :server:test :tests:test` (ghi lại test nào đỏ sẵn, nếu có).

---

### BƯỚC 1 – Server Payload (thêm `displayName` vào kết quả trận)

**1.1. `AuthoritativeMatchResult.java`** – sửa `ParticipantResult`:

```java
/** eliminated là bản sao dead authoritative tại tick cuối, không phải nguồn death state mới. displayName chỉ để hiển thị. */
public record ParticipantResult(PlayerId playerId, int slotIndex, String characterId,
                                float finalHp, boolean eliminated, String displayName) {
    public ParticipantResult(PlayerId playerId, int slotIndex, String characterId, float finalHp, boolean eliminated) {
        this(playerId, slotIndex, characterId, finalHp, eliminated, null);
    }
    public ParticipantResult { displayName = com.smashprofs.common.model.DisplayName.sanitize(displayName, characterId); }
}
```
- Constructor 5 tham số **bắt buộc giữ** (nhiều test dùng).
- `sanitize(null, characterId)` trả về `characterId` → fallback đúng yêu cầu.

**1.2. `MatchEndEvaluator.java`** – thêm overload, giữ hàm cũ:

```java
static AuthoritativeMatchResult evaluate(AuthoritativeGameSnapshot snapshot, boolean timeLimitReached) {
    return evaluate(snapshot, timeLimitReached, java.util.Map.of());
}

static AuthoritativeMatchResult evaluate(AuthoritativeGameSnapshot snapshot, boolean timeLimitReached,
                                         java.util.Map<PlayerId, String> displayNames) {
    // ... giữ nguyên toàn bộ thân hàm hiện tại ...
    var participants = snapshot.players().stream().map(player ->
            new AuthoritativeMatchResult.ParticipantResult(player.playerId(), player.slotIndex(),
                    player.characterId(), player.hp(),
                    snapshot.gameplay()==null?player.dead():snapshot.gameplay().player(player.playerId().value()).lives()==0,
                    displayNames.get(player.playerId()))).toList();
    // ... phần return giữ nguyên ...
}
```
- **Không** để evaluator đọc session/room. Chỉ nhận `Map` đã chuẩn bị sẵn.
- `Map.get` trả `null` nếu thiếu → `ParticipantResult` tự fallback `characterId`.

**1.3. `MatchRuntime.java`** – truyền tên vào cả 2 chỗ gọi evaluator:

- Thêm helper (gần các getter `getMatchId()` / `getParticipants()`):
```java
private java.util.Map<com.smashprofs.common.model.PlayerId, String> displayNames() {
    var names = new java.util.HashMap<com.smashprofs.common.model.PlayerId, String>();
    for (var participant : setup.participants()) { names.put(participant.playerId(), participant.displayName()); }
    return names;
}
```
- Dòng ~410: `MatchEndEvaluator.evaluate(completedSnapshot, expired)` → `MatchEndEvaluator.evaluate(completedSnapshot, expired, displayNames())`.
- Dòng ~458: `MatchEndEvaluator.evaluate(snapshot, ... )` → thêm tham số thứ 3 `displayNames()`.
- Chỉ gọi `displayNames()` **khi trận kết thúc** (đặt trong nhánh đã tính ra result, hoặc chấp nhận gọi mỗi tick cũng được vì Map nhỏ ≤ 4 phần tử; ưu tiên không tạo rác mỗi tick: tính một lần trong constructor/field `private final Map<...> resultNames` nếu thuận tiện).
- ⚠️ `MatchRuntime` nằm cạnh vật lý nhưng thay đổi này **không** động vào Box2D/`FighterPhysics`.

**1.4. `RankingService.java`** (~dòng 192) – chuyển tiếp tên:

```java
var participants=result.participants().stream().map(p->new AuthoritativeMatchResult.ParticipantResult(
        new PlayerId(store.rankingProfile(p.playerId().value())),p.slotIndex(),p.characterId(),p.finalHp(),p.eliminated(),p.displayName())).toList();
```
- Chỉ thêm đối số cuối `p.displayName()`. **Không** đổi logic ELO/DB.

**1.5. `GameEndMessageMapper.java`** – thêm field vào payload:

```java
.forEach(player -> participants.addObject().put("playerId", player.playerId().value())
        .put("slotIndex", player.slotIndex()).put("characterId", player.characterId())
        .put("displayName", player.displayName())
        .put("finalHp", player.finalHp()).put("eliminated", player.eliminated()));
```

**Kiểm tra Bước 1**
- `./gradlew :server:compileJava :server:test --tests "*GameEndMessageMapperTest" --tests "*RankingServiceTest" --tests "*MatchRuntimeEndingTest"`
- Test cũ chỉ đỏ ở chỗ assert đúng 5 field (nếu có) → sửa theo mục 4.1.

---

### BƯỚC 2 – Client Network & Parser

**2.1. `WireMatchResult.java`** – record + parser:

```java
public record Participant(String playerId, int slotIndex, String characterId, float finalHp, boolean eliminated, String displayName) {
    public Participant(String playerId, int slotIndex, String characterId, float finalHp, boolean eliminated) {
        this(playerId, slotIndex, characterId, finalHp, eliminated, null);
    }
    public Participant { displayName = com.smashprofs.common.model.DisplayName.sanitize(displayName, characterId); }
}
```
- `sanitize(null, characterId)` → fallback `characterId` (trường hợp server cũ). Nếu `characterId` null thì sanitize trả null – nhưng `WireMatchResult` compact constructor đã `require(characterId != null ...)` ngay sau, nên vẫn bị bắt đúng chỗ.

Trong `parse(...)`:
```java
for (var p : array) {
    require(p.isObject() && (p.size() == 5 || p.size() == 6), "Invalid result participant fields");
    var hp = p.get("finalHp"); var eliminated = p.get("eliminated");
    require(hp != null && hp.isNumber() && Double.isFinite(hp.doubleValue()) && Float.isFinite(hp.floatValue()), "Invalid finalHp");
    require(eliminated != null && eliminated.isBoolean(), "Invalid eliminated");
    String name = null;
    if (p.size() == 6) { var node = p.get("displayName"); require(node != null && node.isTextual(), "Invalid displayName"); name = node.textValue(); }
    participants.add(new Participant(text(p, "playerId"), slot(p), text(p, "characterId"), hp.floatValue(), eliminated.booleanValue(), name));
}
```
- Nếu `size()==6` mà thiếu key `displayName` (ví dụ thừa một field lạ) → `require` ném lỗi (giữ tinh thần parser nghiêm ngặt, test `p.put("extra", true)` vẫn đỏ đúng).
- Chuỗi rỗng/chỉ khoảng trắng → `sanitize` trả fallback `characterId`.
- Tên dài >12 ký tự → bị cắt lại ở client (phòng server lỗi).
- `validateRoster`: **không** thêm so sánh `displayName` (Quyết định D7).

**2.2. `ClientMatchSession.acceptEnd`** – **không cần sửa**. Lưu ý: `result.equals(incoming)` (so sánh GAME_END trùng) tự động bao gồm `displayName` vì là component của record.

**2.3. (Tùy chọn – lớp phòng vệ thứ hai) Fallback từ roster**
- Mục đích: nếu server cũ không gửi `displayName`, màn kết quả vẫn dùng được tên đã nhận ở GAME_START.
- Trong `OnlineResultScreen`, viết helper dùng chung:
```java
private String displayNameOf(WireMatchResult.Participant p) {
    // 1) Tên từ GAME_END (đã sanitize). 2) Nếu chỉ là fallback characterId, thử roster của GAME_START. 3) characterId.
    String name = p.displayName();
    if (!name.equals(p.characterId())) { return name; }
    var lobby = game.getLobbyClient();
    var session = lobby == null ? null : lobby.getMatchSession();
    if (session != null) {
        for (var member : session.getRoster().players()) {
            if (member.playerId().value().equals(p.playerId())) { return member.displayName(); }
        }
    }
    return name;
}
```
- Chỉ làm bước này nếu `getMatchSession()` / `getRoster()` truy cập được (đã thấy `lobby.getMatchSession().getRoster()` được dùng ở ~dòng 514). Nếu truy cập không an toàn (null sau khi rời trận) thì **bỏ bước 2.3**, vì Bước 2.1 đã đủ.

**Kiểm tra Bước 2**
- `./gradlew :core:compileJava :tests:test --tests "*OnlinePresentationTest" --tests "*NetworkClientProtocolV1Test"`

---

### BƯỚC 3 – UI Render Scoreboard (thẻ kết quả)

**File:** `core/src/com/smashprofs/game/Screens/OnlineResultScreen.java`, trong vòng lặp tạo thẻ (~dòng 270–285).

Hiện tại:
```java
String subtitle = "Player " + (p.slotIndex() + 1) + (isLocal ? " (Bạn)" : "");
nameCol.add(ui.small(subtitle, isLocal ? OnlineUi.BRAWL_CYAN : Color.valueOf("7EA2C7")));
```

Sửa thành:
```java
String shownName = p.displayName();            // hoặc displayNameOf(p) nếu đã làm Bước 2.3
String subtitle = shownName + (isLocal ? " (Bạn)" : "");
var subtitleLbl = ui.small(subtitle, isLocal ? OnlineUi.BRAWL_CYAN : Color.valueOf("7EA2C7"));
subtitleLbl.setEllipsis(true);
nameCol.add(subtitleLbl).width(cardW - 16 - 28 - 6 - 4).row();   // = cardW - padding - icon - padRight - dự phòng
```
- Dòng 1 (`MAURICE` / `ALEX`) **giữ nguyên** (`p.characterId().toUpperCase(...)`).
- `cardW` đã là biến cục bộ (`pillarW - 14`) được khai báo ở phía trên trong cùng vòng lặp → dùng trực tiếp.
- Nếu `nameCol` đang `defaults().left()`, giữ nguyên căn lề trái.
- Không đổi màu, kích thước thẻ, vị trí `▲ BẠN`, hay công thức SCORE/ELO.
- Không cần thêm import nếu `Color`, `OnlineUi` đã import (đang dùng sẵn trong file).

**Mô phỏng kỳ vọng (theo ảnh chụp):**
- Thẻ hạng 1: `MAURICE` / `<Tên của bạn> (Bạn)` (màu cyan).
- Thẻ hạng 2: `ALEX` / `<Tên đối thủ>` (màu xanh xám).

**Kiểm tra Bước 3**
- `./gradlew :core:compileJava` rồi chạy thử thủ công (mục 4.2).

---

### BƯỚC 4 – Tài liệu giao thức

- `docs/PROTOCOL_V1.md`, mục "GAME_END": sửa dòng Participant thành:
  > Participant: `playerId:string`, `slotIndex:integer0..3`, `characterId:string`, `displayName:string` (≤ 12 ký tự, optional ở parser client để tương thích server cũ), `finalHp:finite number0..100`, `eliminated:boolean`.
- `docs/PROTOCOL-CHANGELOG.md`: thêm mục đầu danh sách:
  > **Unreleased – GAME_END participants mang `displayName`.** Server gửi 6 field/participant; client chấp nhận 5 hoặc 6. Server mới ↔ client cũ **không** tương thích (client cũ từ chối payload 6 field) → deploy đồng bộ. Không đổi ranking/ELO/DB.
- Giữ CRLF cho cả hai file.

---

## 4. KẾ HOẠCH KIỂM THỬ & COMMIT (TEST & COMMIT STRATEGY)

### 4.1. Unit test / Integration test cần bổ sung hoặc sửa

**Server (`final-project/server/test/...`)**
- `network/GameEndMessageMapperTest`
  - Thêm `ParticipantResult(winner, 0, "Alex", 99.5f, false, "Chiến Sĩ 42")` → assert `participants[0].get("displayName").textValue()` bằng giá trị đó.
  - Assert mỗi participant có **đúng 6 field** (`participants.get(0).size()==6`).
  - Assert payload gốc vẫn `size()==6` (không đổi cấp gốc).
  - Test constructor 5 tham số → `displayName == characterId` (fallback).
  - Test tên dài (>12) hoặc nhiều khoảng trắng đi qua `sanitize` (cắt còn ≤ 12).
- `game/MatchRuntimeEndingTest` (hoặc `MatchResultNamesTest` mới)
  - Dựng `MatchSetup` với `new Participant(id, slot, "Alex", "Hùng")` và `new Participant(id2, slot2, "Luca", "Lan")`, chạy tới khi kết thúc, assert `runtime.getMatchResult().participants()` có `displayName` tương ứng đúng theo `playerId` (không lẫn slot).
  - Test boss mode (`BossModeTest`) vẫn xanh (kết quả boss cũng mang tên).
- `ranking/RankingServiceTest`: thêm test `resolveProfiles` giữ nguyên `displayName` (gọi `submitMatchResult` rồi kiểm tra kết quả đã lưu/được chuyển tiếp).
- `cluster/FileMatchHistoryTest`: chạy lại, đảm bảo vẫn xanh (JSON có thêm field).
- `game/GameEndTcpDeliveryTest`, `TwoClientTcpMatchEndToEndTest`, `FourClientTcpMatchEndToEndTest`
  - Dùng `TcpTestClient.connect(profileId, displayName)` với tên **khác nhau** cho 2–4 client.
  - Assert mỗi `participants[i].displayName` khớp tên đã gửi lúc CONNECT (sau sanitize) và khớp tên trong GAME_START.

**Client (`final-project/tests/src/...`)**
- `game/network/client/OnlinePresentationTest`
  - Parse GAME_END **6 field** → `Participant.displayName` đúng.
  - Parse GAME_END **5 field** (server cũ) → `displayName == characterId`.
  - Từ chối: `displayName` không phải chuỗi (số/boolean), participant 7 field, participant 6 field nhưng thiếu key `displayName`.
  - Đảm bảo test `p.put("extra", true)` ở payload gốc vẫn bị từ chối.
  - Tên dài > 12 → bị cắt về ≤ 12; tên rỗng/khoảng trắng → fallback.
- `tests/network/DtoRoundTripTest`: rà soát assert schema liên quan (nếu có).
- `server/game/ClientMatchTcpIntegrationTest` (`tests/src/com/smashprofs/server/game/`): `assertEquals(WireMatchResult.parse(end.payload()), result)` phải vẫn xanh; bổ sung assert tên.

**Common**
- `common/test/.../DisplayNameTest`: giữ nguyên, không cần sửa.

### 4.2. Kiểm thử thủ công (End-to-End)

1. Chạy server: `run_server.bat`.
2. Chạy **2 client với thư mục home khác nhau** (hai client cùng máy dùng chung file `Preferences` nên sẽ trùng tên nếu không tách). Gợi ý: dùng `-Duser.home=C:\tmp\p1` và `-Duser.home=C:\tmp\p2` khi chạy `run_client.bat`.
3. Đặt Display Name khác nhau ở Hồ sơ (ví dụ `Hùng Dũng`, `Lan Anh`), tạo phòng, vào trận, để hết giờ hoặc loại đối thủ.
4. Ở màn kết quả, xác nhận:
   - Dưới `MAURICE` / `ALEX` là **tên thật**, không còn `Player 1` / `Player 2`.
   - Thẻ của người chơi cục bộ có hậu tố `(Bạn)`; thẻ đối thủ thì không.
   - Tên 12 ký tự + `(Bạn)` không tràn khung (thử 4 người nếu có thể).
   - Tên tiếng Việt có dấu hiển thị đúng (`ế, ệ, ư, ơ, đ`).
5. Trường hợp biên: đặt tên trống/khoảng trắng → thẻ hiện `characterId` (ví dụ `Alex`), không hiện chuỗi rỗng.
6. Chạy lại một ván Boss (`result.isBoss()`) và xác nhận thẻ "ĐỒNG ĐỘI n" vẫn hiện tên.

### 4.3. Kiểm tra CRLF & phạm vi thay đổi
- `git diff --stat`: mỗi file chỉ đổi vài dòng.
- `git diff --name-only | grep -E "physics|FighterPhysics|LocalFighterPrediction"` → **phải rỗng**.
- `git diff --name-only | grep -E "Sqlite|InMemoryRankingStore|RatingEngine|PlayerRecord"` → **phải rỗng**.
- `file` trên từng `.java`/`.md` đã sửa phải báo `with CRLF line terminators`.

### 4.4. Chia mốc commit (nhỏ gọn)

1. `feat(server): add displayName to authoritative match result participants`
   - S1, S2, S3, S5 + test `MatchRuntimeEndingTest`, `RankingServiceTest`.
2. `feat(network): send displayName in GAME_END participants`
   - S4 + `GameEndMessageMapperTest`, các test TCP E2E (server).
3. `feat(network): parse displayName in client match result`
   - C1 (+ Bước 2.3 nếu làm) + `OnlinePresentationTest`, `ClientMatchTcpIntegrationTest`.
4. `feat(ui): display player names on scoreboard cards`
   - C2.
5. `docs(protocol): document displayName in GAME_END`
   - `PROTOCOL_V1.md`, `PROTOCOL-CHANGELOG.md`, file kế hoạch này.

> Mỗi commit phải biên dịch và test xanh độc lập (commit 1 giữ nguyên constructor 5 tham số nên không làm đỏ test hiện có).

---

## 5. TỔNG HỢP RỦI RO & CÁCH XỬ LÝ

| Rủi ro | Mức | Cách xử lý |
|---|---|---|
| Client mới/server cũ | Thấp | Parser nhận 5 hoặc 6 field, fallback `characterId` (Bước 2.1) |
| Server mới/client cũ | Cao nếu deploy lệch | Deploy đồng bộ; ghi rõ trong `PROTOCOL-CHANGELOG.md` (D6) |
| Test cũ gọi `ParticipantResult` 5 tham số / `evaluate` 2 tham số | Trung bình | Giữ constructor và overload tương thích (D2, D3) |
| Tên bị mất ở `RankingService.resolveProfiles` | Thấp | Chuyển tiếp `p.displayName()` (Bước 1.4) + test |
| Tên dài tràn thẻ 4 người | Thấp | Sanitize ≤ 12 + `setEllipsis(true)` + width cố định (Bước 3) |
| Ảnh hưởng vật lý/ELO | Không | Không sửa file nào trong `physics/**`, ranking store/engine (mục 4.3 có lệnh kiểm tra) |
| Tên trùng nhau giữa 2 người chơi | Thấp | Chấp nhận; phân biệt bằng màu viền + `(Bạn)` + tên nhân vật |
