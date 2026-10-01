# Protocol Changelog

All protocol changes require team agreement and corresponding updates to `NETWORK-PROTOCOL.md`.

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
