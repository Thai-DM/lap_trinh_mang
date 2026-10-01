# Work Division

## Shared rule

Member 3 maintains `common`, but no contract change may be made without team agreement because both client and server depend on it. An agreed contract change must update `NETWORK-PROTOCOL.md` and `PROTOCOL-CHANGELOG.md`.

## Member 1 - Server Runtime

Owns mainly:

- `server/network`
- `server/room`
- `server/game`

Responsibilities:

- GameServer / connection handling
- sessions
- room management
- input queue
- authoritative match runtime
- fixed tick
- authoritative combat/state
- `MatchResult` creation

## Member 2 - Client Networking & Synchronization

Owns mainly:

- client-related changes inside `core`
- future `core` input/network/sync packages

Responsibilities:

- decouple `Gdx.input` later
- NetworkClient
- send `PlayerInput`
- receive `GameSnapshot`
- client player collection
- snapshot buffering/interpolation
- leaderboard UI

## Member 3 - Protocol / Ranking / Reliability

Owns mainly:

- `common`
- `server/ranking`
- `server/reliability`
- `server/metrics`
- network tests

Responsibilities:

- protocol DTOs and message contracts
- future serialization
- heartbeat/reconnect
- RankingService/rating
- duplicate `matchId` protection
- real-time leaderboard broadcast
- metrics/load tests

## Git workflow

- `main` = stable
- `dev` = integration
- `feature/server-runtime` = Member 1
- `feature/client-sync` = Member 2
- `feature/protocol-ranking` = Member 3

Rules:

- Feature branches are created from `dev`.
- Pull requests go back to `dev`.
- Do not push directly to `main`.
- Protocol/common changes must update `NETWORK-PROTOCOL.md` and `PROTOCOL-CHANGELOG.md`.
- Before a pull request, build and test the owned module.
- Keep commits small and focused.

## Independent testing

- Member 1 tests server work with a `FakeClient` / `FakeSession` test double.
- Member 2 tests client work against a `MockServer` test double.
- Member 3 tests contracts, reliability, and ranking with a `HeadlessClient` test double and unit tests.

The named test doubles are future test fixtures, not implementations provided by the shared setup.
