# Bước 51 — Authoritative MatchResult → RankingGateway

## 1. Kết luận khảo sát và phạm vi

`server/ranking` hiện chỉ có `package-info.java`, chưa có RankingGateway/RankingService/RatingEngine/RankingStore implementation hoặc contract tiếp nhận kết quả đã thống nhất. Không có duplicate matchId protection/persistence/ACK từ Member 3. Kiến trúc hiện tại phù hợp boundary in-process trong cùng module server; không tạo service/REST/TCP inter-service hay hệ thống ranking thứ hai.

**RankingService/persistence integration thật: BLOCKED** vì chưa có implementation/API của Member 3. Bước này hoàn thiện boundary/publication phía Member 1, kiểm tra bằng recording/failing gateway chỉ trong test. Production mặc định **NOT_CONFIGURED**, không dùng fake/no-op rồi báo đã lưu.

Model thực tế là `com.smashprofs.server.game.AuthoritativeMatchResult`, gồm matchId, roomId, finishedAtTick, winnerId nullable, finishReason và immutable participants (PlayerId, slotIndex, characterId, finalHp float, eliminated). `MatchRuntime.tick()` gọi `MatchEndEvaluator.evaluate()` trên snapshot sau World.step thành công rồi publish volatile result; `getMatchResult()` trả chính instance đó, null khi chưa kết thúc và còn đọc được sau close. UUID matchId từ MatchSetup đã ổn định cho cả trận.

GameRoom.getPhase/updateLifecycle refresh PLAYING → FINISHED từ result volatile. FixedTickScheduler unregister finished runtime rồi gọi completion ngoài runtime/registration lock. RoomManager callback trước đây chỉ update lifecycle. GAME_END hiện dùng chính AuthoritativeMatchResult qua GameEndDelivery; không tính lại winner. `common.model.MatchResult` v0.1 có finishedAtEpochMs và không đủ fields authoritative; không chuyển sang DTO này hoặc tự tạo epoch/score/rating.

## 2. File

Đường dẫn source/test tính từ Gradle root `final-project/final-project`.

Tạo:

- `server/src/com/smashprofs/server/game/RankingGateway.java`
- `server/src/com/smashprofs/server/game/MatchResultPublisher.java`
- `server/test/com/smashprofs/server/game/MatchResultPublisherTest.java`
- `server/test/com/smashprofs/server/game/RankingGatewayTcpIntegrationTest.java`
- `docs/STEP-51-RANKING-GATEWAY-JOURNAL.md` (từ Git root)

Sửa:

- `server/src/com/smashprofs/server/network/GameServer.java`: optional real gateway injection, lifecycle-owned publisher, status observation, bounded shutdown/fresh restart.
- `server/src/com/smashprofs/server/room/RoomManager.java`: non-blocking completion observer và historical-result fallback khi remove/shutdown thắng notification.
- `server/test/com/smashprofs/server/game/TcpHeartbeatIntegrationTest.java`: thêm barrier CONNECT có sẵn trong fixture để tránh tiến manual clock trước activation sau ACK.

Không sửa MatchRuntime.tick/evaluator/result model, GameRoom/RoomPhase, FixedTickScheduler, StateBroadcaster, GameEndDelivery/mapper, transport/heartbeat production, common/Protocol v1/golden vectors, core/desktop/TMX hoặc build config. Không Bước 52.

## 3. API và ownership

```java
public interface RankingGateway {
    void submitMatchResult(AuthoritativeMatchResult result) throws Exception;
}
```

Member 1 sở hữu boundary trong `server/game`; Member 3 sẽ implement/adapter sang service thật, xử lý rating/store/dedup/persistence. Interface chỉ nhận immutable result, không Body/World, runtime, room, ClientSession hoặc socket. Implementation phải có bounded I/O và hợp tác với interrupt. Trả bình thường chỉ có nghĩa operation của gateway đã trả về; không tự chứng minh durable DB/exactly-once.

Injection đã nối thực tế vào lifecycle, không chỉ tạo interface chưa dùng:

```java
new GameServer("127.0.0.1", port, maxConnections, memberThreeGateway);
GameServer.withRankingGateway(schedulerFactory, monotonicMatchClock, memberThreeGateway);
```

Các constructor cũ giữ nguyên: không có gateway thì ghi NOT_CONFIGURED. `GameServer.getResultPublisher()` cho quan sát status, không phải ACK của RankingService. Service implementation/gateway được caller/Member 3 sở hữu; publisher/executor do từng GameServer lifecycle sở hữu. Restart tạo publisher mới, không reuse map/tasks cũ; gateway được cấu hình có thể là cùng service instance, durable dedup là trách nhiệm của service đó.

## 4. Trigger và asynchronous handoff

```text
production tick hoàn tất → AuthoritativeMatchResult
→ scheduler unregister → RoomManager completion (FINISHED)
→ MatchResultPublisher.offer(result)
→ bounded worker → RankingGateway.submitMatchResult(same result)
```

Không polling thread đọc RoomPhase, không contact-listener callback, không DB/gateway trong tick/StateBroadcaster. Completion lấy getter volatile của result, offer nhanh ngoài room/runtime/registration lock; worker chỉ giữ immutable result/status, không giữ native runtime/World. GAME_END publisher/hook/writer vẫn nguyên trạng và không chờ ranking.

Remove/shutdown có thể thắng scheduler unregister notification sau tick cuối. RoomManager.closeRuntime đợi/unregister, dispose runtime theo lifecycle cũ rồi offer historical result nếu thực sự đã có. GameServer.stop dừng scheduler, chụp result hoàn tất còn trong registry, shutdown rooms **trước** bounded ranking drain; unfinished runtime không được ép finish. Các fallback dùng cùng matchId gate, không tạo result/publication thứ hai, không giữ World chỉ để đợi ranking.

## 5. Boundedness và publish-once

Một ThreadPoolExecutor một worker/lifecycle, ArrayBlockingQueue, AbortPolicy (**không CallerRuns**). Worker tạo lazy khi có gateway và result đầu; không thread mỗi match. Default 128 queued results + tối đa 1 in-flight. Không configured gateway thì không tạo executor/thread.

Map status/tombstone theo matchId có limit 4096/lifecycle; không giữ completed result/room/runtime/World trong map. Publisher constructor cho cấu hình queue capacity, tracked capacity và shutdown wait; GameServer dùng default. Đồng bộ admission dedup và execute dưới lock ngắn, task gateway chạy ngoài lock. Hai room kết thúc gần nhau có matchId/result/participants riêng; FIFO theo thứ tự accepted offer, không hứa thứ tự thời gian toàn cục giữa room, không dùng HashMap order làm rating policy.

Không xóa tombstone sau failure/rejection để callback lặp không retry/enqueue tiếp. Queue đầy → QUEUE_FULL, đã tracked theo matchId và warning. Tracking đầy → TRACKING_FULL trả về, counter `getTrackingRejections()` và warning khi lần đầu đầy; **không** enqueue hoặc eviction rồi vô tình publish lại. Trạng thái per-ID ngoài tracking capacity không được lưu thêm (getStatus trả null), cần xem return/counter; completion vẫn chạy/game vẫn finish nhưng ranking cho match đó chưa được chuyển. Giới hạn này không phải room/game capacity hoặc DB quota; cần Member 3 durable dedup để tiến tới chiến lược lịch sử tốt hơn.

Đây là **at-most-one enqueue attempt trong lifecycle** đối với matchId được theo dõi, không exactly-once durable processing. Queue/submit failure không tự retry, không durable outbox, crash/restart có thể mất pending và reset dedup. Caller muốn retry xuyên lifecycle phải qua contract/dedup của Member 3, không tự gọi lại rồi coi là an toàn.

## 6. Status và failure policy

| Status | Ý nghĩa |
|---|---|
| QUEUED / IN_FLIGHT | Đang chờ / đã gọi gateway; chưa xác nhận thành công. |
| SUBMITTED | Gateway operation trả bình thường; không tự xác nhận DB persisted. |
| NOT_CONFIGURED | Chưa có Member 3 gateway, chưa handoff. |
| FAILED | Gateway throw; giữ result/phase, không retry. |
| QUEUE_FULL / TRACKING_FULL | Refused rõ ràng vì limit, không CallerRuns/drop giả success. |
| CLOSED | Result mới bị từ chối sau publisher close. |
| CANCELLED_SHUTDOWN | Chưa chạy trước khi deadline drain kết thúc. |
| SHUTDOWN_UNCONFIRMED | In-flight khi shutdown hết hạn; không thể kết luận service có/không persist. |

Default shutdown drain tối đa 1 giây; sau đó cancel queued và interrupt worker. Không tự đợi worker nếu close gọi từ worker đó. Service không hợp tác interrupt có thể còn chạy sau bounded close: worker daemon tránh giữ JVM sống vô hạn, nhưng **không thể cưỡng bức dừng Java task hoặc rollback DB side effect**. Status giữ SHUTDOWN_UNCONFIRMED kể cả gateway trở về sau deadline; không biến thành fake success. Member 3 phải timeout I/O/cooperate interrupt và cung cấp idempotency/ACK đúng nghĩa.

Gateway Exception được bắt trên worker, warning chỉ matchId, không exception payload/stack trace gửi client. Không sửa winner/draw, HP, FINISHED hoặc terminal snapshot; không rollback result, không phát GAME_END lại. Runtime/getMatchResult vẫn cùng instance sau failure và native close.

## 7. Test thực tế

`MatchResultPublisherTest` (12 test) kiểm tra exact immutable identity cho 2/3/4 participants, winner/draw giữ nguyên, repeat/concurrent same+different matchId, gateway failure/no retry, NOT_CONFIGURED, bounded queue + rejection, bounded tracking, graceful drain/cancel/unconfirmed shutdown, validation và native World.step counter không tăng bởi handoff. Test cleanup riêng tái hiện result đã có nhưng notification chưa chạy: scheduler chưa start, tick native một lần trong fixture, removeRoom phải handoff historical result sau dispose; không fake result/evaluator.

`RankingGatewayTcpIntegrationTest` (6 test) dùng TcpTestClient/official codec/socket thật, manual clock/executor chạy production scheduler/World:

- Trận 2/3/4 người kết thúc cùng cycle: nhận đúng ba matchId độc lập và **cùng result instance** production; TIME_LIMIT winner với HP float, draw null và LAST_PLAYER_STANDING. Fixtures HP được khai báo trong test để tạo điều kiện nhanh, không tạo result/END giả.
- Gateway chậm rồi throw: GAME_END/final STATE vẫn tới đủ roster đúng thứ tự, phòng B vẫn tick/publish, remove A dispose native trước khi gateway xong; historical fallback không gọi lại gateway.
- WAITING/COUNTDOWN/PLAYING, disconnect và unfinished shutdown không có result/publication.
- Default thiếu service thể hiện NOT_CONFIGURED nhưng GAME_END vẫn hoạt động.
- Restart publisher mới, matchId mới và không stale publication state.
- Shutdown hai completed results (một in-flight/một queued): World dispose, queued cancel, in-flight unconfirmed, cooperative worker terminate, sockets EOF.

Recording/failing gateways chỉ test scope, **không test DB persistence/rating/real Member 3 adapter**. Không Thread.sleep. Await latch/queue/future/socket có timeout; status polling bằng bounded monotonic deadline/yield. Thực hiện targeted scope rộng hơn hai class mới vì sửa RoomManager completion và server stop/restart.

Một lần regression có timeout trong test heartbeat từ Bước 50: đọc ACK không bảo đảm server đã chạy activation sau send; test tiến manual clock sớm gây PING chưa đến interval. Bổ sung CONNECT barrier có sẵn vào fixture trước khi tiến clock, không đổi heartbeat production/policy. Sau đó targeted regression đã PASS; lượt xác nhận cuối dùng toàn bộ assertions mới được compile lại.

## 8. Targeted test/build

Tại Gradle root, JAVA_HOME JDK17:

```powershell
.\gradlew.bat :server:test --tests '*MatchResultPublisherTest' --tests '*RankingGatewayTcpIntegrationTest' --tests '*MatchRuntimeEndingTest' --tests '*GameEndTcpDeliveryTest' --tests '*FourClientTcpMatchEndToEndTest' --tests '*TwoClientTcpMatchEndToEndTest' --tests '*GameServerTest' --tests '*GameServerSchedulerIntegrationTest' --tests '*GameRoomLifecycleTest' --tests '*GameRoomPhysicsLifecycleTest' --tests '*RoomManagerTest' --tests '*RoomManagerCreateTest' --tests '*GameStartTcpTest' --tests '*GameStateTcpBroadcastTest' --tests '*TcpHeartbeatIntegrationTest' --tests '*TcpSnapshotBackpressureTest' --no-daemon
.\gradlew.bat :server:build -x :server:test --no-daemon
```

| Suite | Tests |
|---|---:|
| MatchResultPublisherTest | 12 |
| RankingGatewayTcpIntegrationTest | 6 |
| MatchRuntimeEndingTest | 42 |
| GameEndTcpDeliveryTest | 14 |
| FourClientTcpMatchEndToEndTest | 4 |
| TwoClientTcpMatchEndToEndTest | 8 |
| GameServerTest | 6 |
| GameServerSchedulerIntegrationTest | 28 |
| GameRoomLifecycleTest | 21 |
| GameRoomPhysicsLifecycleTest | 2 |
| RoomManagerTest | 11 |
| RoomManagerCreateTest | 6 |
| GameStartTcpTest | 9 |
| GameStateTcpBroadcastTest | 15 |
| TcpHeartbeatIntegrationTest | 11 |
| TcpSnapshotBackpressureTest | 16 |
| **Targeted tổng** | **211** |

Lượt xác nhận cuối: **211 PASS, 0 FAIL/error/skip**, exit code 0, gồm 18 test mới. Build `:server:build -x :server:test --no-daemon`: **PASS**, exit code 0, Java 17. Không chạy toàn bộ suite 1.250+ và không tuyên bố common/RankingService test PASS khi chưa chạy trong Bước 51. Common source/API không sửa. Deprecated-feature warning Gradle 7.3.3 hiện hữu không phải compile failure.

Sáu tracked build artifacts (common/server JAR, compilation cache, manifest) đã được phục hồi đúng bytes trước task và xác minh SHA-256 để không ghi đè thay đổi người dùng; reports/classes mới giữ lại. Chỉ xóa backup tạm riêng Bước 51 sau restore, không xóa source/report hoặc dữ liệu người dùng. JAR phục hồi không phải artifact deployment của lần build này, cần build lại trước deploy. TMX giữ SHA-256 `366044B95702FA12EB4310ED227DA808489A5AA1AAE9E648B6D365F17458E708`.

## 9. Member 3 cần hoàn thiện — BLOCKED

- Implement/adapter in-process nhận AuthoritativeMatchResult đầy đủ, không nhầm common v0.1 epoch DTO; thống nhất nếu cần chuyển module/share contract sau này.
- RankingService/RatingEngine/RankingStore và policy draw cho 2–4 người, không tự lấy slot 0 khi winner null.
- Durable duplicate matchId protection, DB transaction và ACK semantics (kể cả already-processed), timeout/cancellation policy.
- Nếu cần epoch timestamp, account/displayName hoặc global ordering, phải chốt contract bổ sung; Member 1 không tự tạo dữ liệu.
- Durable queue/outbox/retry và RANKING_UPDATED thuộc Member 3, chưa có trong bước này. Bounded in-memory gateway không thay các phần đó.

Nhật ký đồ án: server chuyển chính kết quả production sang một boundary ranking in-process bằng bounded worker, dedup theo matchId và trạng thái handoff rõ ràng. Native/gameplay/TCP result delivery độc lập gateway chậm/lỗi. Kiểm chứng bằng unit và TCP multi-match; actual ranking/persistence còn BLOCKED do chưa có Member 3 service. Dừng sau Bước 51, không Bước 52.
