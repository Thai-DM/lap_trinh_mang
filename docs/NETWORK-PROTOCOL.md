# Network Protocol

## Status

TCP Wire Protocol **v1** is defined by [PROTOCOL_V1.md](PROTOCOL_V1.md), the authoritative specification selected by the project owner in Step 43B: JSON UTF-8, int32 big-endian length prefix, maximum 1,048,576 payload bytes. Shared codec is in `common`; server adapter and CONNECT/JOIN/READY/INPUT routing are implemented. Step 47 activates CREATE_ROOM/ROOM_CREATED without changing their schemas: client supplies a validated roomId, duplicate creation is rejected, creator must explicitly JOIN_ROOM. No configurable capacity/owner or room expiration is added.

Step 44 activates room-scoped GAME_STATE push at 20 Hz by default over unchanged 60 Hz simulation. HP stays floating point through a server adapter; the legacy model APIs below remain unchanged. Step 45 activates GAME_END using the existing authoritative result/schema and outbound worker: ordered final GAME_STATE then GAME_END, terminal enqueue-once per session/lifecycle, bounded queue, no application ACK/retry. Step 48 activates GAME_START from the existing immutable MatchSetup before first GAME_STATE, using a bounded start slot and the same writer; its schema/golden vectors do not change. Step 50 activates heartbeat using existing PING/PONG payload `{}` and matching requestId (no new pingId field), interval 5 seconds and timeout 15 seconds from probe enqueue, configurable monotonic timing, one outstanding/pending heartbeat per connection. Disconnect cleans lobby/countdown via existing leave logic; PLAYING/FINISHED roster/HP/result remain unchanged. Leave request/countdown broadcast remain unimplemented. See the v1 specification for lifecycle, units and backpressure policy.

The v0.1 shapes below remain unchanged for Java API compatibility; they are not automatically the v1 wire schemas. In particular, legacy integer HP/GameSnapshot cannot represent the authoritative floating HP/slot/projectiles. V1 explicitly adds enum names CONNECT, CONNECT_ACK, CREATE_ROOM, ROOM_CREATED, ROOM_STATE, LEAVE_ROOM and GAME_START, retaining all legacy values. MATCH_RESULT/RANKING_UPDATED are not enabled on TCP v1 MVP. See the v1 specification for directions, requestId, schemas, limits and identity rules.

Protocol changes require team agreement because both client and server depend on `common`. Any accepted change must update this document and `PROTOCOL-CHANGELOG.md` in the same pull request.

## Shared models v0.1

### `PlayerId`

| Field | Type |
|---|---|
| `value` | `String` |

### `PlayerInput`

| Field | Type |
|---|---|
| `playerId` | `PlayerId` |
| `sequence` | `long` |
| `clientTick` | `long` |
| `moveAxis` | `float` |
| `jumpPressed` | `boolean` |
| `stompPressed` | `boolean` |
| `meleePressed` | `boolean` |
| `blockHeld` | `boolean` |
| `abilityPressed` | `boolean` |

### `PlayerState`

| Field | Type |
|---|---|
| `playerId` | `PlayerId` |
| `characterId` | `String` |
| `x` | `float` |
| `y` | `float` |
| `velocityX` | `float` |
| `velocityY` | `float` |
| `hp` | `int` |
| `grounded` | `boolean` |
| `blocking` | `boolean` |
| `dead` | `boolean` |
| `facingRight` | `boolean` |

### `GameSnapshot`

| Field | Type |
|---|---|
| `roomId` | `String` |
| `serverTick` | `long` |
| `phase` | `String` |
| `remainingTimeMs` | `long` |
| `players` | `List<PlayerState>` |

### `MatchResult`

| Field | Type |
|---|---|
| `matchId` | `String` |
| `roomId` | `String` |
| `winnerId` | `PlayerId` |
| `participantIds` | `List<PlayerId>` |
| `finishedAtEpochMs` | `long` |

### `RankingEntry`

| Field | Type |
|---|---|
| `rank` | `int` |
| `playerId` | `PlayerId` |
| `displayName` | `String` |
| `wins` | `int` |
| `losses` | `int` |
| `totalMatches` | `int` |
| `winRate` | `double` |
| `rating` | `int` |

Collection fields are immutable defensive copies in the Java contract.

## Message types v0.1

| Message type | Initial direction | Intended future purpose |
|---|---|---|
| `JOIN_ROOM` | Client -> Server | Request to join a room. |
| `PLAYER_READY` | Client -> Server | Report readiness for a room. |
| `PLAYER_INPUT` | Client -> Server | Submit a `PlayerInput`. |
| `GAME_STATE` | Server -> Client | Publish a `GameSnapshot`. |
| `PING` | Server -> Client | Future liveness probe. |
| `PONG` | Client -> Server | Future liveness response. |
| `GAME_END` | Server -> Client | Notify that authoritative gameplay ended. |
| `MATCH_RESULT` | Server -> Client | Publish the authoritative `MatchResult`. |
| `RANKING_UPDATED` | Server -> Client | Publish a future ranking/leaderboard update. |
| `ERROR` | Server -> Client | Report a rejected or invalid client operation. |

The initial enum contains exactly these values. Room creation, game start, reconnect, leaderboard queries, and other message types are intentionally deferred until a documented protocol change is approved.
