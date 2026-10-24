# Bước 41 — Player death, match ending và kết quả authoritative

Ngày kiểm chứng: 11/10/2026. Chỉ thực hiện Bước 41; không triển khai Bước 42.

## Khảo sát và chính sách đã chốt

Các đường dẫn source bên dưới tính từ thư mục Gradle `final-project/` bên trong repository.

- `core/src/com/smashprofs/game/Actors/Players/Player.java`: HP ban đầu là 100; `checkHealth()` gọi `die()` khi HP <= 0. `die()` đặt dead một lần. Không tìm thấy lives, stock, reset HP, hồi sinh sau khi chết hoặc thời gian chờ respawn trong các đường đi này.
- `respawnPlayers()` trong cùng file thực chất là fall recovery: điều chỉnh vận tốc Y thành 2,6, damping X, giảm gravity và trừ 10 HP. Không phải hồi sinh hay teleport về spawn; rơi khi còn HP vẫn sống.
- `core/src/com/smashprofs/game/Screens/PlayScreen.java` cập nhật player/combat/World rồi HUD và kiểm tra kết thúc.
- `core/src/com/smashprofs/game/Scenes/Hud.java` đặt `worldTimer = 120`, nhưng `testWin()` kiểm tra `timeCount >= 1` hoặc HP <= 0, không trực tiếp kiểm tra `worldTimer == 0`. Vì bộ đếm phụ được reset mỗi lần giảm timer, thời điểm kết thúc thực tế phụ thuộc delta/frame và thường có thêm khoảng một giây sau lần giảm cuối. Không sao chép lỗi timer này sang server.
- `core/src/com/smashprofs/game/Screens/WinScreen.java` chọn HP lớn hơn, dùng `>=` để ưu tiên PlayerOne khi bằng HP; không có outcome draw rõ ràng.
- Đã đối chiếu `core/src/com/smashprofs/game/Helper/CombatManager.java` và các damage/combat/projectile controller hiện có phía server. Bước 41 không thay đổi combat balance hoặc thứ tự resolve hit.

Người dùng xác nhận: **thêm timeout 120 giây, xét HP; bằng HP cao nhất thì hòa**. Chính sách mở rộng 2–4 người:

| Trạng thái sau khi hoàn tất tick | Kết quả |
|---|---|
| Còn 1 người sống | Thắng, `LAST_PLAYER_STANDING` |
| Không còn người sống | Hòa, `ALL_PLAYERS_ELIMINATED` |
| Còn 2–4 người, chưa hết 120 giây | Tiếp tục |
| Còn 2–4 người, đủ 120 giây | Người có HP cao nhất duy nhất thắng; đồng hạng cao nhất hòa, `TIME_LIMIT` |

Timer lấy monotonic server clock (`System.nanoTime` mặc định, injectable manual clock khi test), tính từ `MatchRuntime.start()` thành công. Countdown không nằm trong 120 giây. Không dùng clientTick, giờ máy client hoặc giả định 7.200 tick luôn bằng 120 giây thực. Kết quả được xét ở cuối tick đầu tiên quan sát deadline. HP so sánh dạng float authoritative, không làm tròn thành int. Elimination 0/1 survivor được ưu tiên nếu xảy ra ngay tick hết giờ. Không dùng slot hoặc thứ tự HashMap để phân xử hòa.

## Death và thứ tự kết thúc

Tái sử dụng nguyên `ServerPlayer.applyDamage()` và `dead`: HP clamp về 0, dead không reset, blocking tắt khi chết. Không thêm death state thứ hai. Các gate có sẵn ngăn movement/jump/melee/stomp/ability và projectile mới của người chết; không destroy Body khi chết. Body vẫn có thể chạy physics thụ động trong trận chưa kết thúc để giữ tương tác projectile.

Evaluator chạy **một lần cuối tick thành công**, sau tất cả damage hợp lệ được resolve trong tick và `World.step()`/synchronize, không kết thúc ngay giữa vòng xử lý một hit. Giữ nguyên policy combat slot-order và HP có hiệu lực ngay của các bước trước: người đã bị giết trước lượt không được phát sinh một đòn mới để tạo hòa giả.

Contact projectile thu được sau step vẫn theo pipeline cũ: damage được resolve ở tick kế tiếp. Không bổ sung một tick gameplay sau FINISHED để giải quyết các contact còn chờ. Test hai projectile đã pending cùng tick xác nhận cả hai damage được resolve trước khi kết luận draw. Các test fatal fall 2–4 người và hoán đổi slot xác nhận simultaneous death không tạo winner tùy thứ tự.

Projectile có sẵn không bị xóa chỉ vì owner chết; Rocket giữ chính sách đã xác nhận ở Bước 38 (bám Body target đã chết, không retarget). Khi toàn trận FINISHED, tất cả simulation dừng; cleanup projectile/native resource thực hiện khi đóng runtime.

## Schema kết quả và snapshot

`AuthoritativeMatchResult` là immutable Java record nội bộ server:

```text
matchId: String               // dùng lại ID của MatchSetup, không sinh UUID mới
roomId: String
finishedAtTick: long
winnerId: PlayerId | null     // null = draw; isDraw() được suy ra, không lưu flag trùng
finishReason: LAST_PLAYER_STANDING | ALL_PLAYERS_ELIMINATED | TIME_LIMIT
participants: List<ParticipantResult>
  playerId: PlayerId
  slotIndex: int
  characterId: String
  finalHp: float
  eliminated: boolean        // bản sao dead ở tick cuối, không phải mutable player state mới
```

Danh sách defensive copy bằng `List.copyOf`; participant records chỉ chứa giá trị, không Body/World/session/ServerPlayer. Roster lấy từ state được xây dựng bằng MatchSetup, không lấy danh sách connection. Kết quả chỉ được tạo một lần và còn đọc được sau `close()`.

`common/src/com/smashprofs/common/model/MatchResult.java` đã tồn tại nhưng chỉ chứa winner/participant IDs và `finishedAtEpochMs`, không có final float HP, slot, character, tick hoặc reason. Vì chưa có nguồn UTC authoritative/adapter chính thức cho event này, không tự bịa epoch timestamp, sửa common hay tạo protocol mới. Kết quả nội bộ có tên khác rõ ràng, tương tự snapshot nội bộ của Bước 40; adapter sang common sẽ cần Member 3 thống nhất.

Final `AuthoritativeGameSnapshot` vẫn dùng `GameSnapshotBuilder` Bước 40; HP/dead/serverTick phản ánh tick cuối. Publish snapshot trước volatile result. Reader thấy result đã publish sẽ thấy final snapshot nếu runtime chưa đóng. Không build snapshot mới sau FINISHED. Sau close, `getLatestSnapshot()` trả null theo contract Bước 40; bản bất biến đã được caller giữ trước đó và historical result vẫn hợp lệ. Không tự thêm phase/winner/timer vào common snapshot.

## Room phase, scheduler và isolation

- Runtime không phụ thuộc ngược vào GameRoom; non-null result là nguồn FINISHED duy nhất của runtime.
- Scheduler unregister runtime kết thúc và gọi completion boundary đúng một lần **ngoài khóa runtime/registration/registry**, không tự dispose World. RoomManager callback capture đúng instance room, không tìm room mới tái sử dụng cùng ID.
- GameRoom chuyển `PLAYING -> FINISHED`; `getPhase()` cũng quan sát volatile completion để không trả PLAYING cho một runtime đã kết thúc nếu callback chưa chạy. Không giữ room lock qua Box2D tick.
- FINISHED không tự quay lại PLAYING. Chặn thay đổi roster/character/ready và startMatch lại; input runtime trả `MATCH_FINISHED`, handler ánh xạ về `ROOM_NOT_PLAYING` có sẵn.
- `tick()` sau FINISHED bị reject trước khi drain input hoặc step; held input và queue pending được clear lúc kết thúc. Runtime/world chưa dispose vẫn phục vụ final snapshot/result.
- Đóng/xóa room hoặc shutdown dùng cleanup có sẵn; CLOSED/DISPOSED không tự tạo winner/result. Disconnect không bị đổi thành HP = 0, không thêm forfeit.
- Mỗi runtime có clock start, state, result và snapshot riêng. Trận A kết thúc/đóng không chặn tick, phase hoặc resource của B. Không thêm thread, executor hoặc static global winner.

## File tạo và sửa

Tạo mới:

- `server/src/com/smashprofs/server/game/AuthoritativeMatchResult.java`
- `server/src/com/smashprofs/server/game/MatchEndEvaluator.java`
- `server/test/com/smashprofs/server/game/MatchRuntimeEndingTest.java`
- `docs/STEP-41-MATCH-ENDING-JOURNAL.md` (tính từ repository root)

Sửa source:

- `server/src/com/smashprofs/server/game/MatchRuntime.java`: timer, evaluator, terminal guards, result/snapshot publication.
- `server/src/com/smashprofs/server/game/FixedTickScheduler.java`: completion boundary và unregister khi finished.
- `server/src/com/smashprofs/server/game/InputQueue.java`: chỉ bổ sung `OfferResult.MATCH_FINISHED`; thuật toán queue không đổi.
- `server/src/com/smashprofs/server/network/PlayerInputHandler.java`: ánh xạ kết quả terminal sang routing result có sẵn.
- `server/src/com/smashprofs/server/room/GameRoom.java`: terminal transition/guards, truyền clock hiện có vào runtime.
- `server/src/com/smashprofs/server/room/RoomManager.java`: gắn completion với đúng room instance.
- `server/src/com/smashprofs/server/room/RoomPhase.java`: chỉ cập nhật chú thích FINISHED khác dispose.

Sửa 6 file test cũ trong `server/test/com/smashprofs/server/game/`: `MatchRuntimeAbilityCoverageTest.java`, `MatchRuntimeBoundaryTest.java`, `MatchRuntimeGameplayIntegrationTest.java`, `MatchRuntimeHomingMissileTest.java`, `MatchRuntimeMeleeTest.java`, `MatchRuntimeProjectileTest.java`.

10 test cũ cần tick tiếp sau khi owner/target chết nay dùng 3 participant để còn 2 survivor, thay vì tiếp tục một trận 1v1 đã phải FINISHED. Giữ toàn bộ assertion cũ và không xóa test. Đây là fixture adaptation do lifecycle mới, không phải lỗi baseline có sẵn, cũng không nới lỏng terminal guard.

Không sửa build.gradle, common/core/desktop source, TMX hoặc protocol. Các thay đổi build.gradle có sẵn của người dùng được giữ nguyên.

## Kiểm chứng

Dùng Gradle wrapper 7.3.3, JDK 17 tại `E:\2026-2\lap-trinh-mang\baitap`, JUnit 4.13.1. Không chạy Desktop Launcher/socket hoặc `Thread.sleep()`.

- Baseline trước sửa: **1.081 test PASS**; không có failure baseline.
- `MatchRuntimeEndingTest`: **42 PASS**, bao phủ đủ 29 trường hợp death/ending/result/phase/resource/isolation yêu cầu, cộng timeout boundary/HP tie/damage ordering/float HP/countdown/clientTick và completion lock boundary.
- `existingServerTestsStillPass` được kiểm chứng bằng chạy toàn suite thật, không tạo một test giả chỉ để có tên.
- `.\gradlew.bat :server:test --no-daemon`: **1.123 PASS, 0 failures, 0 errors, 0 skipped; BUILD SUCCESSFUL**.
- `.\gradlew.bat :server:build --no-daemon`: **BUILD SUCCESSFUL** (test task up-to-date sau lần chạy trên).
- Có cảnh báo deprecated Gradle features vốn có; không nâng Gradle trong bước này.
- Report: `server/build/reports/tests/test/index.html`; kết quả đếm từ XML ở `server/build/test-results/test/`.

Để không làm thay đổi các build artifact đã được theo dõi trong working tree, 6 file jar/manifest/compile metadata được khôi phục đúng byte bản có trước khi chạy kiểm chứng. Các class và test report vẫn giữ lại. Nếu cần JAR đóng gói Bước 41, chạy lại `:server:build --rerun-tasks`; JAR theo dõi trong working tree không được coi là release artifact mới của bước này.

## Chủ động để lại cho bước sau / phối hợp Member 3

Chưa có GAME_END/MATCH_RESULT broadcast, serialization/framing, UTC finish timestamp, adapter sang common, ranking, DB, disconnect-forfeit, rematch hoặc tự thu hồi room FINISHED. World được giữ đến explicit close/remove/shutdown, không tạo timer cleanup mới. Sau FINISHED không xử lý thêm pending damage; consumer network phải lấy final snapshot trước dispose nếu muốn giữ nó. Contract/reason/timestamp truyền qua mạng cần team agreement và protocol documentation change, không tự sửa ở Bước 41.
