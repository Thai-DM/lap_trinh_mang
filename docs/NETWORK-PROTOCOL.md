# Network Protocol

## Status

Protocol version `0.1` defines shared data shapes and message names only. No serialization format, message envelope, framing, socket transport, or delivery guarantee has been selected or implemented.

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
