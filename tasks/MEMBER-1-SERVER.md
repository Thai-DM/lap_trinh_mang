# Member 1 - Server Runtime

## Ownership

- `server/network`
- `server/room`
- `server/game`

## Expected deliverables

- GameServer and connection handling
- session lifecycle
- room lifecycle and membership
- validated input queue
- fixed-tick authoritative match runtime
- authoritative game/combat state
- `MatchResult` creation

## Should not own

- client rendering or UI
- client snapshot interpolation
- unilateral changes to `common`
- ranking/rating algorithms
- database implementation
- protocol serialization without coordination with Member 3

## Independent testing

Use `FakeClient` / `FakeSession` test doubles to exercise connections, sessions, rooms, input ordering, match ticks, and result creation without depending on the real desktop client.
