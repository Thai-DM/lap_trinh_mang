# 52F — Real desktop multiplayer smoke

Thực hiện ngày 12/10/2026, giờ Asia/Saigon. Dùng hai cửa sổ libGDX thật trong mỗi phòng, production NetworkClient và GameServer thật trên localhost. Không dùng unit/headless tests làm bằng chứng GUI.

**Kết quả:** đã quan sát movement, jump, stomp, melee damage từ cả hai client, block state, projectile, HP float, winner/draw và F9 khi PLAYING. Không phát hiện lỗi client cần sửa trong các tình huống đã thực hiện. Chất lượng FPS/latency, kiểm tra leak dài hạn và một số kiểm tra sâu vẫn UNTESTED; không tuyên bố toàn bộ checklist PASS. Dừng trước 52G.

## Khởi động và điều khiển

JDK 17.0.12: `E:\2026-2\lap-trinh-mang\baitap`. CWD: `final-project`. Khởi động bằng đúng các task đã có:

```powershell
$env:JAVA_HOME='E:\2026-2\lap-trinh-mang\baitap'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :tests:runLocalServer -Pport=54555 --console=plain
.\gradlew.bat :desktop:run --args="--server-host=127.0.0.1 --server-port=54555 --room=demo52f --create-room" --console=plain
.\gradlew.bat :desktop:run --args="--server-host=127.0.0.1 --server-port=54555 --room=demo52f --join-room" --console=plain
```

Chạy server/A/B ở các process riêng; chỉ mở B sau ảnh lobby xác nhận A joined. Server PID **60064** giữ nguyên và lắng nghe port 54555 xuyên suốt tất cả lượt, chỉ dừng sau khi hoàn tất. Các lượt sau thay room để không tái sử dụng trận đã kết thúc. Lượt DRAW được để chạy trong lúc thực hiện lượt combat ở phòng riêng.

Dùng Windows PostMessage gửi key-down/up tới đúng HWND theo PID client; đây là đường input của cửa sổ GLFW/libGDX, không gọi trực tiếp ClientMatchSession. Thu ảnh client area của riêng game; bring-to-front khi capture. Không sửa gameplay để điều khiển test, không thay clock/timeout server.

## Các lượt thực tế

| Phòng | Client A/B | Kết quả quan sát |
|---|---|---|
| demo52f | 67472 / 65088; Viktor / Leo | Gameplay; IBM/mine; TIME_LIMIT, Viktor thắng, HP 90.0 / 89.9 ở cả hai cửa sổ |
| demo52f-draw | 63564 / 68212; Viktor / Leo | Đứng yên; TIME_LIMIT, DRAW, HP 100.0 / 100.0 ở cả hai |
| demo52f-disconnect | 60444 / 49516; Viktor / Maurice | A melee làm B 90.0 → 80.0; trận sau đó kết thúc LAST_PLAYER_STANDING, Maurice thắng, A 0.0 / B 80.0 |
| demo52f-f9 | 44824 / 59388; Viktor / Alex | B Space làm A 100.0 → 90.0; sau đó trận kết thúc LAST_PLAYER_STANDING, Alex thắng |
| demo52f-f9only | 37064 / 62164; Alex / Luca | Đứng yên sau start rồi F9 A ngay trong PLAYING; A Disconnected, B vẫn Connected/PLAYING |

Hai lượt có tên disconnect/f9 đầu tiên kết thúc do gameplay trước thao tác F9; **không tính chúng là bằng chứng disconnect**. Chỉ lượt f9only được tính cho kiểm tra đó. Kết quả eliminated được hiển thị từ GAME_END, không quy kết là forfeit. Không xác định riêng nguyên nhân tất cả damage/elimination của server trong smoke này.

## Checklist và bằng chứng

Ảnh/log nằm tại `final-project/tests/build/52f-smoke/`, được giữ lại, không stage. Các tên ảnh chỉ mô tả thao tác; những ảnh chụp sau END không được dùng để kết luận gameplay PASS.

| Kiểm tra | Trạng thái | Bằng chứng / giới hạn |
|---|---|---|
| A/B cùng room, Ready, GAME_START | PASS | a-lobby, b-lobby, a-start, b-start; roster và PLAYING đúng |
| Local/remote identity | PASS | YOU đổi đúng slot giữa hai cửa sổ; cùng short PlayerId/character; local B không phải slot 0 |
| A/D của A | PASS | a-right, a-left; b-sees-a-right, b-sees-a-left thấy Viktor đổi vị trí |
| A/D của B | PASS | b-left, b-right; a-sees-b-left, a-sees-b-right thấy Leo đổi vị trí |
| W của A/B | PASS | a-jump, b-jump; nhân vật ở trên không trong PLAYING |
| S của A/B | PASS | a-stomp, b-stomp và combat-a-land/combat-b-land; sau W nhân vật hạ xuống; không kiểm chứng riêng stomp damage trúng đối phương |
| V melee A gây damage | PASS | combat-close: Maurice 90.0; combat-melee-hit-a/b: 80.0, cả hai cửa sổ nhận HP mới |
| Space melee B gây damage | PASS | f9-b-close: Viktor 100.0; f9-b-melee/f9-a-hit: 90.0, hai góc nhìn đồng thuận |
| Left Ctrl A/B | PASS | a-block/b-block: đúng character tint cyan và HUD BLOCK; sau nhả phím trạng thái bỏ BLOCK |
| Block chặn damage khi bị đánh | UNTESTED | Lượt thử attack vào block đã chụp sau END nên không được tính |
| F ability Viktor / Leo | PASS | a-ability/b-sees-ibm cùng thấy IBM; a-sees-mine/b-projectiles thấy mine của Leo |
| Projectile biến mất | PASS | a-projectiles-expired/b-projectiles-expired: IBM và mine không còn trong PLAYING |
| HP float | PASS | Cả hai cùng hiển thị 90.0 / 89.9; damage melee hiển thị đồng bộ, không ép integer |
| Animation cơ bản / facing | PASS có giới hạn | Ảnh đứng, chạy, trên không và BLOCK khác nhau; không có punch/stomp animation riêng trong contract hiện tại |
| Đồng bộ sau nhả input | PASS có giới hạn | Ảnh đứng yên hai cửa sổ cùng vị trí hiển thị, roster/HP/projectile; ảnh được lấy nối tiếp, không cùng timestamp |
| Sai lệch position so với wire snapshot | UNTESTED đo định lượng | Không capture raw GAME_STATE cùng timestamp để đo sai số; không kết luận độ lệch bằng số |
| GAME_END winner | PASS | a-result/b-result cùng Viktor slot 0; các lượt combat còn xác nhận winner slot 1 Maurice/Alex đúng identity |
| GAME_END DRAW | PASS | draw-a-result/draw-b-result cùng DRAW, TIME_LIMIT, participants HP 100.0 |
| Result giữ ổn định khi bấm gameplay | PASS ở UI | a-result-after-input vẫn cùng kết quả; không thấy transition lặp trong các ảnh sau end |
| Không gửi PLAYER_INPUT sau END; duplicate END injection | UNTESTED trong GUI/wire | Không thu packet trace/tiêm END lặp trong smoke. Regression TCP/model 52E đã kiểm tra, không thay thế bằng chứng GUI |
| F9 mất kết nối trong PLAYING | PASS | f9only-a-playing còn Connected/118.25 s; f9only-a-disconnected/after hiện đỏ Disconnected, giữ hình/timer cuối 116.75 s |
| B sống sau F9 A; không forfeit | PASS | f9only-b-after Connected, PLAYING/113.11 s, cả hai HP 100.0; không ResultScreen/forfeit, process B còn sống |
| Snapshot reception | PASS tại thời điểm lấy ảnh | HUD thường quanh 20 snapshots/s; idle DRAW vẫn kết thúc đúng; B tiếp tục nhận sau F9 A |
| FPS / latency / input responsiveness định lượng | UNTESTED | Không bật FPS meter hoặc thu timestamp input→snapshot→frame; không lấy snapshots/s làm FPS/latency |
| Continuous stutter / starvation / teleport | UNTESTED đầy đủ | Ảnh tĩnh không đủ đánh giá liên tục; HUD skipped broadcasts ban đầu 0–1, B sau F9 có 4; không gọi đó là packet loss hoặc tự kết luận nguyên nhân |
| Exit / chạy lại | PASS | Thoát bằng Esc, các desktop:run hoàn tất exit 0; các lượt tiếp theo khởi động và joined được |
| Process/thread cleanup | PASS ở mức process | Sau Esc không còn 10 PID desktop của các lượt; sau cleanup không còn server PID/port 54555 listening |
| Heap/memory leak dài hạn | UNTESTED | Có sample WorkingSet/thread count trước close nhưng không có baseline/soak/heap comparison để kết luận leak |
| Exception của client | PASS trong lượt thực hiện | Các client *.err.log trống, không thấy Java/render exception; không coi lỗi công cụ capture là lỗi game |

## Log, lỗi và build

Các ảnh chính để xem nhanh:

- `a-jump.png`, `a-block.png`, `b-block.png`.
- `a-ability.png`, `b-sees-ibm.png`, `a-sees-mine.png`, `a-projectiles-expired.png`.
- `combat-close.png`, `combat-melee-hit-a.png`, `combat-melee-hit-b.png`.
- `f9-b-close.png`, `f9-b-melee.png`, `f9-a-hit.png`.
- `a-result.png`, `b-result.png`, `draw-a-result.png`, `draw-b-result.png`.
- `f9only-a-playing.png`, `f9only-a-disconnected.png`, `f9only-a-after.png`, `f9only-b-after.png`.

Không thu video. `actions.log` ghi timestamp chuỗi phím lượt đầu; các lượt sau có screenshot timestamps, scripts và stdout/stderr riêng. `process-sample-before-close.json`, `closed-at.txt` ghi cleanup.

Có lỗi script khởi động xuống dòng lúc đầu và capture trước khi cửa sổ sẵn sàng; sửa/retry công cụ trong thư mục build, không sửa source. Một capture B ở lượt f9only có client rectangle kích thước 0 tạm thời; các capture tiếp theo thành công và process/log game không có exception. Không dùng ảnh lỗi đó làm bằng chứng PASS.

Server log có warnings baseline TMX object 35 zero geometry và RankingGateway chưa cấu hình. Server được chủ động Stop-Process sau khi đóng client; vì vậy task JavaExec `runLocalServer` ghi exit -1 / BUILD FAILED khi cleanup. Đây là kết quả dừng process có chủ đích, không phải server tự chết giữa smoke hoặc lỗi compile. Server giữ cùng PID cho mọi lượt test.

**Không sửa source client/server; không có regression test mới cần chạy.** 202 targeted tests của 52E là kết quả trước bước này, không tuyên bố đã chạy lại chúng trong 52F.

Build kiểm tra cuối trên JDK 17:

```powershell
.\gradlew.bat :core:build :desktop:build :tests:build -x :tests:test --console=plain --no-daemon
```

**PASS — BUILD SUCCESSFUL, 12 s**, 8 actionable tasks: 2 executed, 6 up-to-date. Loại tests:test vì không đổi source và không chạy toàn bộ suite. Warning Gradle deprecated còn nguyên.

## Giới hạn trước LAN và Git

Chưa kiểm chứng LAN, FPS/latency, soak/memory leak, độ mượt liên tục, block mitigation hoặc stomp hit. Chưa đo position error đối với raw snapshot. Projectile có thể ở vùng ngoài map do authoritative position; không tự sửa server mechanics/TMX/contract. Không mở rộng sang prediction/reconciliation/reconnect hoặc ranking.

Chỉ thêm báo cáo này và artifacts/scripts/logs trong thư mục build ignored; không đổi source production/tests trong 52F. Branch `integration/member2-check`; HEAD `8c739f98cd9e52028897d82ef42f434ef3b93cd7`, MERGE_HEAD `9dcd8b4735d94ee5bc4d0091e1a267049eed4ad1`. Đối chiếu staged object list + HEAD/MERGE_HEAD trước/sau giống nhau. Không stage/commit/push/merge/reset/clean/restore, giữ các file untracked và thay đổi có sẵn. Không sửa feature/server-runtime/origin/dev. **Dừng tại 52F.**
