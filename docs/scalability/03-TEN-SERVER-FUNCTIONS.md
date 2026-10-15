# Đối chiếu 10 chức năng server

DONE trong bảng chỉ áp dụng phạm vi prototype local được mô tả. Không mục nào xác nhận 100M CCU.

| # | Chức năng | Trạng thái | Source và kiểm chứng | Giới hạn |
|---|---|---|---|---|
| 1 | Authentication | PARTIAL | ControlPlane `/auth/guest`, opaque 256-bit token, expiry, identity server cấp; ControlPlaneTest và secured TCP integration | Guest identity, chưa có account/password/IdP, TLS/revocation chủ động |
| 2 | Connection/TCP | DONE (local) | Transport/JsonFrameCodec có sẵn; counters, write watchdog, bounded latest snapshot/control; framing, transport, backpressure tests | Hai blocking I/O tasks/connection; chưa NIO, chưa chứng minh số socket lớn |
| 3 | Distributed sessions | PARTIAL | Một source of truth ở CP; session→room→worker, ticket index, single claim, expiry; replay/cross-room/process tests | In-memory một CP, chưa reconnect/resume, slot reservation giữ tới cleanup |
| 4 | Matchmaking | DONE (local) | Queue FIFO theo region/size 2–4, timeout 30s, cancel, dedupe; ControlPlaneTest | Không ELO/ping selection; logical region, không WAN deployment |
| 5 | Room sharding | DONE (local) | UUID RoomId, atomic select+reserve, fixed owner, worker claim và bounded local registry; concurrent allocation + 3-process TCP test | Registry HA chưa triển khai; room chết không migrate |
| 6 | Authoritative simulation | DONE (local) | Tái sử dụng MatchRuntime/Box2D/60 Hz; movement, combat, projectile, HP/result; existing 2/4-client TCP integration | Một simulation thread/worker; gameplay stress đa dạng cần soak dài |
| 7 | Adaptive sync | DONE cho full snapshot; PROPOSED cho delta | BASELINE 20 Hz; ADAPTIVE 5–20 Hz, queue/send/tick pressure và hysteresis; policy + snapshot/legacy parser tests | Delta chưa gửi; client gap metric 20 Hz không phải packet loss khi giảm snapshot rate |
| 8 | Fault tolerance/recovery | PARTIAL | Lease worker 5s, fail-stop trước hết lease, lost owner ABORTED, disconnect cleanup, drain, bounded shutdown; process fault test | Không HA CP/checkpoint, không resume; force kết thúc sau grace là abort, không result giả |
| 9 | Persistence/ranking | PARTIAL | Existing async RankingGateway/Publisher; FileMatchHistory atomic create by MatchId, conflict detection/history, unit tests và TCP→result→file→cleanup→room mới | Không DB/ELO Member 3; bounded publisher không retry lỗi; file/directory crash durability chưa đủ production |
| 10 | Monitoring/allocation/scaling | PARTIAL | Worker metrics, RR/WLA, configurable weights/limits/cooldown; autoscaler + local JVM provisioner; unit/process tests + benchmark | GC collection time không phải pause histogram; chưa RSS/native accounting hoàn chỉnh, multi-host/Agones chưa tích hợp |

## Những phần còn phụ thuộc

**BLOCKED về xác minh:** môi trường nhiều máy/region, DB/ranking thực của Member 3 và Kubernetes/Agones chưa có trong repository/phiên kiểm thử. Không chặn việc chạy prototype local.

**PROPOSED:** HA replicated registry, consensus fencing, durable result outbox, authenticated account service, checkpoint/replay Box2D, negotiated delta snapshots, selector/Netty data plane. Không đánh dấu các phần này là đã code.

Source hoạt động, test phù hợp, lệnh chạy và output đối chiếu nằm ở [README](README.md), [benchmark](08-LOAD-TEST-RESULTS.md), thư mục `results/`, và `verification.json`. Các module offline không bị viết lại; public v1 schema giữ nguyên.
