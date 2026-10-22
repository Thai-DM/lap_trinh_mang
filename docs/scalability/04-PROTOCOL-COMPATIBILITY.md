# Tương thích Protocol v1

Header vẫn int32 Big Endian (4 byte), payload JSON UTF-8 v1. Không sửa common DTO hoặc codec. Full GAME_STATE, GAME_START và GAME_END có cùng fields, ordering và immutable authoritative data như trước. Length/frame limits, malformed UTF-8/JSON/duplicate fields, partial reads và timeout vẫn đi qua codec/transport hiện tại.

## Hai listener được chọn rõ ràng

**Standalone:** constructor GameServer cũ + NetworkClient cũ gửi `CONNECT {}`; server cấp identity như trước. Unknown fields và client-supplied PlayerId/SessionId vẫn bị từ chối. Đây là đường tương thích client hiện tại.

**Cluster opt-in:** client lấy assignment qua HTTP, kết nối trực tiếp tới worker và gửi:

```json
{"version":1,"type":"CONNECT","requestId":"example","payload":{"capability":"cluster-v1","assignment":"OPAQUE_SINGLE_USE_TICKET"}}
```

Capability chỉ được chấp nhận khi listener được tạo bằng `GameServer.clusterWorker`. `CONNECT_ACK` vẫn chỉ chứa `playerId`; không đưa token vào snapshot. Worker kiểm tra ticket với CP: owner/incarnation, expiry, session identity và trạng thái room. Sau CONNECT, JOIN/READY/INPUT/PING/PONG vẫn là v1. Worker chỉ cho JOIN vào room trong assignment; CREATE_ROOM phải qua control plane.

Không tự fallback từ worker bảo mật sang anonymous CONNECT. Ticket đã claim không thể mở TCP thứ hai. CONNECT lặp trên chính connection chỉ được chấp nhận với cùng ticket/capability và credential còn hạn. Reconnect trên connection mới chưa hỗ trợ: cần đợi room cleanup hoặc dùng assignment mới sau room terminal.

Client desktop: factory `NetworkClient.forCluster` và `ClusterLobbyConnector`; LobbyClient/ClientMatchSession/WireGameSnapshot được tái sử dụng. Các cờ CLI cũ và offline/mock vẫn giữ nguyên. Chế độ cluster được chọn bằng `--control-url`, không trộn với cờ server trực tiếp.

## Compatibility tests

- TcpProtocolV1Test, JsonFrameCodecTest: v1 payload, identity và framing.
- NetworkClientProtocolV1Test, LobbyClientTcpIntegrationTest: client cũ trên server cũ.
- ClusterLobbyIntegrationTest: client thật qua assignment, READY, START, INPUT và full snapshot parser.
- FourClientTcpMatchEndToEndTest, GameEndTcpDeliveryTest: isolation, final state và GAME_END.
- StateBroadcasterTest/TcpSnapshotBackpressureTest: rate, bounded replacement và terminal events.
- ClusterProcessIntegrationTest: worker cũ không bỏ qua ticket, replay và cross-room bị từ chối.

Adaptive chỉ giảm số full snapshots; không gửi field bị lược bỏ hoặc delta giả dạng v1. Interpolator tiếp tục dùng tick/timestamp. Chỉ số snapshot gap mặc định 20 Hz của client cần diễn giải theo rate mới, không xem là số packet TCP bị mất.
