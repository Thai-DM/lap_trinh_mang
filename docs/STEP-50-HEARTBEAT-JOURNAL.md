# Bước 50 — Heartbeat, connection timeout và disconnect cleanup

## 1. Khảo sát và quyết định

Protocol v1 đã định nghĩa PING/PONG payload `{}` và cùng requestId, không phải pingId payload. Vì vậy giữ nguyên schema, common DTO/codec/MessageType và golden vectors (PING/PONG `requestId:"r7"`, header `00 00 00 39`). Dispatcher trước bước này trả NOT_IMPLEMENTED cho PING/PONG/LEAVE; giờ chỉ LEAVE vẫn contract-only.

Trước Bước 50 chưa có application heartbeat timestamp/maintenance scheduler. Idle socket timeout chỉ kiểm tra lifecycle, partial-frame deadline vẫn 5 giây và không phải heartbeat. ClientSession đã có trusted PlayerId/roomId/connected, ConnectionManager là registry duy nhất. Connection close có CAS, socket-close unblock I/O, clear outbound, cancel reader/writer và removeIfSame. Lobby chưa gọi leave khi EOF; GameRoom.leavePlayer đã có xóa slot/selection/ready, cancelCountdown và từ chối leave khi PLAYING/FINISHED. Tái sử dụng các API này, không thêm luật chết/forfeit.

## 2. File tạo/sửa

Các đường dẫn server dưới đây tính từ Gradle root `final-project/final-project`.

Tạo:

- `server/src/com/smashprofs/server/network/HeartbeatConfiguration.java`
- `server/src/com/smashprofs/server/network/HeartbeatDelivery.java`
- `server/src/com/smashprofs/server/network/HeartbeatManager.java`
- `server/test/com/smashprofs/server/network/HeartbeatManagerTest.java`
- `server/test/com/smashprofs/server/game/TcpHeartbeatIntegrationTest.java`
- `docs/STEP-50-HEARTBEAT-JOURNAL.md` (từ Git root)

Sửa:

- `server/src/com/smashprofs/server/network/GameServer.java`: cấu hình/injection và fresh maintenance lifecycle cùng transport.
- `server/src/com/smashprofs/server/network/TcpServerTransport.java`: start/stop controller; callback EOF/timeout dùng leave lobby hiện có.
- `server/src/com/smashprofs/server/network/TcpClientConnection.java`: một heartbeat pending slot riêng, dùng writer/sendLock hiện có, clear khi close.
- `server/src/com/smashprofs/server/network/ClientSession.java`: state heartbeat riêng session và offer delegation; clear state khi disconnected/close.
- `server/src/com/smashprofs/server/network/SessionChannel.java`: non-blocking offerHeartbeat, default từ chối thay vì send đồng bộ.
- `server/src/com/smashprofs/server/network/MessageDispatcher.java`: gắn heartbeat vào wire boundary trước accept.
- `server/src/com/smashprofs/server/network/ProtocolV1Dispatcher.java`: activation sau ACK, validate PONG, trả PONG cho client PING.
- `server/test/com/smashprofs/server/game/TcpTestClient.java`: tự echo PONG khi đọc PING trong regression; cho phép tắt autoPong để kiểm tra timeout.
- `server/test/com/smashprofs/server/game/GameStartTcpTest.java`: đợi cleanup lobby hữu hạn, roster không còn người disconnect; helper lookup slot đúng khi slot có khoảng trống.
- `server/test/com/smashprofs/server/network/TcpProtocolV1Test.java`: PING đã implement, unsolicited PONG không còn NOT_IMPLEMENTED.
- `server/test/com/smashprofs/server/network/TcpSnapshotBackpressureTest.java`: thêm ba regression heartbeat control/backpressure/terminal ordering.
- `docs/PROTOCOL_V1.md`, `docs/NETWORK-PROTOCOL.md`, `docs/PROTOCOL-CHANGELOG.md` (từ Git root).

Không sửa common source/golden vectors/build config, core, desktop, RoomManager/GameRoom, MatchRuntime.tick, FixedTickScheduler, StateBroadcaster, combat/physics hoặc TMX. Không registry connection thứ hai.

## 3. Contract và thời gian

```json
{"version":1,"type":"PING","requestId":"<session UUID nonce>-<sequence>","payload":{}}
{"version":1,"type":"PONG","requestId":"<same requestId>","payload":{}}
```

Nonce UUID riêng connection, sequence tăng không tái sử dụng, ID tối đa 56 ký tự (envelope limit 64); khi hết sequence range đóng connection thay vì wrap/tái dùng. Không lấy ID/IP/port/client time làm liveness. Session khác có nonce khác, nên PONG A không xác nhận probe B dù sequence bằng nhau. Đây là correlation/liveness MVP, không authentication/TLS hoặc mật mã chứng thực client.

Heartbeat active **sau CONNECT_ACK gửi thành công**, chưa CONNECT thì không có application PING/timeout mới. CONNECT lặp không reset deadline. Server vẫn trả PONG cho client PING `{}` sau CONNECT, echo requestId, nhưng request đó không acknowledge probe server.

HeartbeatConfiguration.DEFAULT: interval 5s, timeout 15s. Duration.toNanos kiểm tra overflow; interval > 0, timeout > interval. Có constructor GameServer nhận cấu hình và seam nhận clock/executor riêng heartbeat. Default đo System.nanoTime; không dùng currentTimeMillis hoặc match/client tick. Clock heartbeat độc lập clock trận ở integration tests để không tự timeout toàn bộ connection khi test tiến trận thêm 120s.

Một probe outstanding/session. Probe đầu sau interval kể từ activation hoặc valid PONG gần nhất. Deadline bắt đầu từ **enqueue**, không từ actual socket write. Không resend và không kéo dài deadline khi đang chờ. Mặc định: activation t0, probe sớm nhất t0+5s, timeout tProbe+15s; quét mỗi interval nên actual close có thể trễ thêm một interval cộng scheduling delay. Không hứa socket delivery/exact wall-clock deadline.

PONG chỉ hợp lệ khi session open/registered đúng instance, heartbeat active, requestId khớp outstanding và server elapsed < timeout. Sau đó clear outstanding và cập nhật lastValidNanos. Duplicate/stale/wrong/cross-session/unsolicited/late/null-ID PONG không refresh; trả ERROR INVALID_PAYLOAD khi còn gửi được. Field playerId/sessionId bị UNAUTHORIZED_PLAYER; pingId/timestamp/field thừa INVALID_PAYLOAD. Các error code/message hiện có, không stack trace hoặc error schema mới. PLAYER_INPUT/read activity/CONNECT/PING không thay cho successful PONG; người đứng yên vẫn sống nếu echo PONG.

## 4. Concurrency, outbound và cleanup

Một ScheduledExecutorService network maintenance cho lifecycle, chỉ một periodic task, không thread/task mỗi client. State nằm trong ClientSession, guarded bằng session monitor; manager chỉ scan snapshot ConnectionManager. Offer/close ngoài khóa session, không socket write/encode/sendLock wait hoặc room/JNI trong manager.

Writer TCP cũ nhận HeartbeatDelivery immutable vào một pending slot riêng. Slot không latest-wins; đầy thì fail/close riêng connection, không drop âm thầm hoặc queue vô hạn. START ưu tiên, terminal final STATE + END giữ cùng sendLock, heartbeat không xen terminal pair; snapshot vẫn cap 1/latest-wins, terminal cap 4. Guard bỏ heartbeat chưa ghi khi timeout/close/stop/ack invalidates outstanding. In-flight frame không bị cắt/chèn bytes; socket close unblock writer. Không thêm writer/snapshot worker hay thay rate simulation 60Hz / broadcast 20Hz.

Timeout mark disconnected dưới session lock trước khi close: PONG/read routing không cứu lại session đã hết hạn. Dùng ClientSession.close → TcpClientConnection.close (CAS) → socket close → cancel tasks/clear outbound → ConnectionManager.removeIfSame → existing callback. EOF/timeout race chỉ connection CAS winner chạy unregister/callback; stale close không xóa session mới cùng ID. Global closeAll vẫn theo behavior cũ: clear registry trước close; server shutdown sau đó đóng tất cả rooms.

Transport stop hủy/đợi heartbeat tối đa 5s trước đóng transport resources, không tự chờ maintenance thread nếu stop gọi từ chính thread đó. Mọi guard bị invalid ngay khi controller closed. Restart tạo transport/registries/controller/executor và ClientSession/nonce mới, không kế thừa outstanding hoặc old scheduled task. Legacy/raw transport hoặc dispatcher không có wire boundary không được tự ép protocol heartbeat.

## 5. Disconnect theo RoomPhase

| Phase | Hành vi |
|---|---|
| WAITING / READY | Callback gọi leavePlayer: giải phóng slot, selection/ready; không đổi slot người khác; cập nhật lobby phase. |
| COUNTDOWN | leavePlayer/cancelCountdown hiện có; nếu không còn điều kiện thì không có runtime/START giả. |
| PLAYING | leavePlayer từ chối; chỉ dọn connection. Roster, HP/dead, Body, world và result không thay bởi heartbeat. Held input vẫn expire theo timeout input đã có. |
| FINISHED | Connection cleanup; roster/result cuối vẫn giữ, không END lần hai. Native cleanup vẫn removeRoom/server stop. |

Không reconnect/resume, authentication, automatic forfeit, matchmaking, ranking hoặc database. Disconnect đã được commit trước start thì cleanup lobby; nếu runtime đã start/phase PLAYING trong cuộc đua lifecycle, giữ chính sách PLAYING thay vì rollback runtime hay tự đặt player dead. Callback chỉ sửa lobby dưới khóa GameRoom, không giữ room lock khi gửi TCP và không thao tác JNI.

## 6. Kiểm thử và kết quả

Unit clock deterministic: 21 test HeartbeatManagerTest, gồm activation, interval, envelope, valid/duplicate/stale/cross-session PONG, late deadline, CONNECT lặp, one outstanding, configuration overflow, nanoTime signed wrap, offer failure, state clear/shutdown và EOF/timeout race. Fake channel bảo đảm manager không gọi send đồng bộ.

TCP loopback/dynamic port/codec thật: 11 test TcpHeartbeatIntegrationTest, dùng manual maintenance + production scheduler Runnable và clock riêng, không chờ 15s thực. CONNECT gating, exact timeout EOF/unregister, PONG spoof/duplicate, lobby/slot cleanup, countdown cancel, 2/4-player lobbies, hai room 4+2 PLAYING không HP/dead/result/World disposal thay đổi bởi timeout, remaining clients tiếp tục STATE, FINISHED result/END không lặp, EOF race và restart clean. Có một test scheduler heartbeat thật, await PING bằng socket deadline rồi shutdown/join worker, không Thread.sleep.

Thêm ba test vào backpressure suite: pending heartbeat không chặn peer khác/không split terminal pair, overflow chỉ đóng peer quá tải, invalid guard không socket-write. Tái sử dụng real socket small-buffer/latch fixture đã có. Các E2E dùng helper autoPong để không coi maintenance PING là gameplay push lỗi.

Regression lần đầu có một failure do helper GameStartTcpTest dùng slotIndex làm JSON array index sau lobby disconnect (slots còn 1,2). Production không compact slot là đúng; sửa helper lookup theo slot và giữ assertions đầy đủ. Đây là failure test-assumption phát sinh khi activation cleanup lobby, không phải lỗi combat/map/runtime. Sau sửa chạy lại toàn bộ targeted scope PASS.

| Suite | PASS | FAIL / ERROR / SKIP |
|---|---:|---|
| HeartbeatManagerTest | 21 | 0 / 0 / 0 |
| TcpHeartbeatIntegrationTest | 11 | 0 / 0 / 0 |
| TcpSnapshotBackpressureTest | 16 | 0 / 0 / 0 |
| TcpProtocolV1Test | 27 | 0 / 0 / 0 |
| TcpIoLifecycleTest | 21 | 0 / 0 / 0 |
| TcpServerTransportTest | 28 | 0 / 0 / 0 |
| TcpSocketWriteTest | 9 | 0 / 0 / 0 |
| GameServerTest | 6 | 0 / 0 / 0 |
| GameServerSchedulerIntegrationTest | 28 | 0 / 0 / 0 |
| ClientSessionTest | 13 | 0 / 0 / 0 |
| ConnectionManagerTest | 14 | 0 / 0 / 0 |
| FourClientTcpMatchEndToEndTest | 4 | 0 / 0 / 0 |
| TwoClientTcpMatchEndToEndTest | 8 | 0 / 0 / 0 |
| GameStartTcpTest | 9 | 0 / 0 / 0 |
| GameStateTcpBroadcastTest | 15 | 0 / 0 / 0 |
| GameEndTcpDeliveryTest | 14 | 0 / 0 / 0 |
| **Server targeted** | **244** | **0 / 0 / 0** |
| common JsonFrameCodecTest | 27 | 0 / 0 / 0 |
| **Tổng** | **271** | **0 / 0 / 0** |

35 test mới (21 unit + 11 TCP + 3 backpressure). Không chạy toàn bộ 1.250+ tests. Scope lifecycle rộng hơn Bước 49 vì bật timer mặc định và cleanup EOF lobby; do đó chạy thêm server/scheduler/session/registry/transport/write regressions, không chỉ test heartbeat. Common golden codec suite đã chạy PASS dù common source không sửa; lần regression cuối common:test UP-TO-DATE từ cùng task trước, không tính là một lượt thực thi mới.

Lệnh tại Gradle root, JDK17:

```powershell
.\gradlew.bat :common:test --tests '*JsonFrameCodecTest' :server:test --tests '*HeartbeatManagerTest' --tests '*TcpHeartbeatIntegrationTest' --tests '*TcpProtocolV1Test' --tests '*TcpIoLifecycleTest' --tests '*TcpServerTransportTest' --tests '*TcpSocketWriteTest' --tests '*GameServerTest' --tests '*GameServerSchedulerIntegrationTest' --tests '*ClientSessionTest' --tests '*ConnectionManagerTest' --tests '*FourClientTcpMatchEndToEndTest' --tests '*TwoClientTcpMatchEndToEndTest' --tests '*GameStartTcpTest' --tests '*GameStateTcpBroadcastTest' --tests '*GameEndTcpDeliveryTest' --tests '*TcpSnapshotBackpressureTest' --no-daemon
.\gradlew.bat :server:build :common:build -x :server:test -x :common:test --no-daemon
```

Build server + common **PASS**, wrapper trả exit code 0. Gradle deprecated-feature warning hiện hữu không phải compile/test failure. Sáu tracked build artifacts (common/server JAR, compilation cache và manifest) đã phục hồi đúng bytes trước task, xác minh SHA-256 để giữ thay đổi của người dùng; reports/classes mới giữ lại. Chỉ xóa backup tạm riêng Bước 50 sau restore; không xóa source/report hoặc dữ liệu người dùng. JAR phục hồi không phải artifact deployment của lần build này; cần build lại trước demo/deploy. TMX giữ SHA-256 `366044B95702FA12EB4310ED227DA808489A5AA1AAE9E648B6D365F17458E708`.

## 7. Giới hạn và giao Member 2/3

- Heartbeat là session liveness, không authentication hoặc chống malicious client tự echo PONG.
- Chưa CONNECT thì chưa heartbeat: không thêm handshake/idle-auth timeout. Không global room quota/expiration.
- Deadline từ enqueue bao gồm backpressure: peer không đọc có thể bị heartbeat đóng dù network path còn tồn tại; không bảo đảm PING đã flush trước deadline. Probe timeout chỉ cho connected session; không thêm write-deadline subsystem chung.
- Maintenance quét theo interval, elapsed monotonic có scheduling lag; không real-time guarantee hay load/soak/WAN test.
- Lobby callback có thể đợi khóa metadata room hiện có; không socket I/O dưới khóa, không game-command executor mới. Không hứa toàn bộ hệ thống lock-free.
- Room PLAYING vẫn giữ disconnected player trong authoritative roster/World; không forfeit/reconnect hoặc xác định winner từ connection count. FINISHED native resources giữ đến cleanup tường minh.
- Member 2 phải nhận PING trong mọi UI phase sau CONNECT và echo **cùng requestId, payload `{}`**, kể cả không gửi input; tách heartbeat khỏi GAME_STATE/render queue. Không gửi playerId/timestamp/pingId.
- Member 3 phải giữ schema/golden/framing v1, dùng requestId correlation thay pingId payload; reconnect/reliability/ranking ngoài Bước 50 chưa implement.

Nhật ký đồ án: tích hợp một maintenance lifecycle độc lập simulation với probe correlation từng session, bounded control delivery qua writer cũ và timeout cleanup race-safe; verify bằng unit clock và TCP 2–4 người/multi-room. Protocol không đổi bytes, physics/combat không đổi, disconnect không tạo luật thắng/thua mới. Dừng Bước 50, không triển khai Bước 51.
