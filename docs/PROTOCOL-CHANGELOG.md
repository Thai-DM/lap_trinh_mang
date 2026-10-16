# Protocol Changelog

All protocol changes require team agreement and corresponding updates to `NETWORK-PROTOCOL.md`.

## TCP Wire v1 — Step 50 heartbeat (2026-10-11)

- Activate the existing PING/PONG schema: payload `{}`, echo requestId; do not introduce pingId/timestamp fields, new enum values, framing/version or common DTO changes. Existing golden vectors remain byte-identical.
- After CONNECT_ACK, server uses per-session UUID-nonce/sequence correlation, one outstanding probe, monotonic interval 5s and timeout 15s from enqueue (configurable). Stale/duplicate/cross-session/late PONG does not extend deadline; invalid payload uses existing error codes.
- Dedicated bounded-lifecycle maintenance scheduler only offers a separate bounded control slot to the existing writer. No simulation socket I/O or change to final STATE → END adjacency.
- Timeout/EOF reuse CAS close and exact-instance unregister. Lobby/countdown cleanup calls existing leave/cancel behavior; playing/finished gameplay and results are untouched. No reconnect/forfeit.

## TCP Wire v1 — Step 48 GAME_START (2026-10-11)

- Activate existing GAME_START schema `{matchId,roomId,players:[{playerId,slotIndex,characterId}]}` from immutable authoritative MatchSetup; no added spawn/tick fields or codec/golden/version change.
- Existing after-cycle publisher enqueues start once per runtime lifecycle for trusted registered room/roster sessions before first GAME_STATE; no new lifecycle/scheduler thread.
- Bounded per-connection start slot and existing worker prioritize start over snapshots/terminal pair, preserving final STATE → END adjacency and non-blocking simulation.
- Add real TCP 2–4-player, multi-room/cancel/failure/ordering tests; update E2E to consume GAME_START. No countdown broadcast/gameplay changes.

## TCP Wire v1 — Step 47 CREATE_ROOM (2026-10-11)

- Activate CREATE_ROOM/ROOM_CREATED with unchanged payload `{roomId}`, envelope, codec and golden vectors.
- Retain client-proposed validated room IDs, explicit JOIN, fixed maximum four players and existing JOIN get-or-create behavior. No server-generated UUID room IDs, owner/host or configurable capacity.
- Atomic create-only registry API rejects duplicate IDs with INVALID_ROOM_STATE; trusted CONNECT session required; internal creation failures return sanitized INTERNAL_ERROR.
- No total room quota/expiration or rollback after committed creation with response I/O failure; cleanup uses existing remove/shutdown lifecycle.
- E2E tests now create their main room through TCP. GAME_START remains a separate gap, not implemented in this step.

## TCP Wire v1 — Step 45 GAME_END delivery (2026-10-11)

- Activate existing GAME_END schema, directly mapped from immutable AuthoritativeMatchResult; no wire/DTO/version/codec change.
- Enqueue once per session in the terminal match lifecycle; no client acknowledgment/retry/exactly-once processing claim.
- Existing writer sends ordered final GAME_STATE/GAME_END bundle, replacing obsolete pending snapshots without coalescing the result. Bounded terminal queue and existing disconnect/shutdown cleanup.
- Room-scoped registered trusted roster recipients; inbound GAME_END cannot finish a match. No gameplay/ranking changes.
- See PROTOCOL_V1.md and STEP-45-GAME-END-JOURNAL.md for guarantees and slow-client limitations.

## TCP Wire v1 — Step 44 GAME_STATE publication (2026-10-11)

- Activate GAME_STATE push already specified in Step 43B; no envelope/field/version/framing change.
- HP remains floating point. Explicit server snapshot mapper bypasses legacy integer-HP DTOs; no public common model API changes.
- Clarify world meters/m/s, deterministic slot/projectile ordering, authoritative remaining time, 20 Hz default over 60 Hz ticks and terminal-once policy.
- Add bounded per-connection snapshot latest-wins outbound path, keeping reliable control messages separate. No GAME_END push or write deadline/heartbeat.
- Update GAME_STATE golden vector (632 payload bytes, HP 99.5/91.25 and IBM projectile), keeping other vectors unchanged.

## TCP Wire v1 — Step 43B (2026-10-11)

Project owner selected the first wire contract; [PROTOCOL_V1.md](PROTOCOL_V1.md) is authoritative for all members. This is wire version integer 1, not a claim that v0.1 previously had a wire format.

- JSON UTF-8 without BOM, 4-byte signed int32 BE payload length, 1–1,048,576 bytes.
- Envelope version/type/payload mandatory; correlated requests require string requestId up to 64 Unicode code points. Duplicate keys prohibited.
- Append CONNECT/CONNECT_ACK/CREATE_ROOM/ROOM_CREATED/ROOM_STATE/LEAVE_ROOM/GAME_START to MessageType without changing legacy names. MATCH_RESULT/RANKING_UPDATED remain Java values but are not TCP MVP types.
- Define all 15 MVP schemas/directions, correlated ROOM_STATE/ERROR, session-owned UUID identity and input without client-supplied playerId.
- Add common JsonFrameCodec/ProtocolMessage/ProtocolException/ProtocolV1, Jackson 2.21.6, golden envelope vectors and codec tests; add server adapter, routing and real TCP integration tests.
- Keep all original model DTO APIs intact. Wire GAME_STATE uses floating HP/slot/projectiles instead of silently changing or rounding legacy PlayerState/GameSnapshot. GAME_END uses authoritative finishedAtTick, not fabricated epoch time.
- Only CONNECT/JOIN/READY/INPUT are handled; create/leave/ping/pong return explicit NOT_IMPLEMENTED. No snapshot/end broadcast or gameplay changes.

## v0.1 - Initial shared contract

Added shared models:

- `PlayerId`
- `PlayerInput`
- `PlayerState`
- `GameSnapshot`
- `MatchResult`
- `RankingEntry`

Added message types:

- `JOIN_ROOM`
- `PLAYER_READY`
- `PLAYER_INPUT`
- `GAME_STATE`
- `PING`
- `PONG`
- `GAME_END`
- `MATCH_RESULT`
- `RANKING_UPDATED`
- `ERROR`

No serialization, framing, transport, networking, server behavior, ranking behavior, heartbeat behavior, reconnect behavior, or synchronization behavior is defined in v0.1.
