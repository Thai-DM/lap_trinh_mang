# Member 2 - Client Networking & Synchronization

## Ownership

- client-related changes inside `core`
- future `core` input/network/sync packages

## Expected deliverables

- later separation of `Gdx.input` from gameplay commands
- NetworkClient
- sending `PlayerInput`
- receiving `GameSnapshot`
- collection-based client player representation
- snapshot buffering and interpolation
- leaderboard UI

## Should not own

- authoritative server combat or match results
- server room/session management
- ranking/rating algorithms
- database implementation
- unilateral changes to `common`
- server heartbeat/reconnect policy

## Independent testing

Use a `MockServer` test double that can accept client messages and emit deterministic snapshots, errors, match results, and ranking updates without running the authoritative server.
