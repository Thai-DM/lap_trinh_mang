# Architecture

## Scope of this setup

The original Super Smash Profs gameplay remains in the existing `core` and `desktop` modules. This setup adds only a dependency-free shared contract and an empty server package skeleton. It does not implement networking, rooms, authoritative gameplay, ranking, reliability, or synchronization.

## Authoritative server principle

The future server is the sole owner of official match state. A client reports player intent; it does not decide official position, health, damage, death, winner, or match results. The server validates input, advances the match on a fixed tick, and publishes snapshots. Clients render the latest synchronized server state.

Future gameplay flow:

```text
input
  -> server
  -> authoritative state
  -> snapshot
  -> client render
```

Future ranking flow:

```text
GAME_END
  -> MatchResult
  -> RankingService
  -> RANKING_UPDATED
```

## Module dependencies

```text
common <- core <- desktop
common <- server

tests -> core
```

- `common` is pure Java 17. It contains shared immutable data and protocol types and has no LibGDX dependency.
- `core` remains the existing cross-platform game/client module. Future client input, networking adapters, and synchronization code will live here without moving the existing source tree.
- `desktop` remains the LWJGL launcher and desktop dependency assembly. It depends on `core` and must not be used by the server.
- `server` is reserved for future server-side components. It depends only on `common` in this setup.
- `tests` remains the existing baseline test module.

The server must never depend on `desktop`. Shared contracts must not import LibGDX types.

## Future server package responsibilities

- `com.smashprofs.server.network`: connections, sessions, transport adapters, inbound/outbound messages.
- `com.smashprofs.server.room`: room membership, readiness, character selection, and room lifecycle.
- `com.smashprofs.server.game`: fixed-tick authoritative match runtime, input queue, state, combat, and `MatchResult` creation.
- `com.smashprofs.server.ranking`: ranking service, match-result processing, and leaderboard publication.
- `com.smashprofs.server.reliability`: heartbeat, disconnect detection, reconnect policy, and session recovery.
- `com.smashprofs.server.metrics`: latency, message-rate, load, and experiment metrics.

These packages are ownership boundaries only in protocol version 0.1; they contain no implementation yet.
