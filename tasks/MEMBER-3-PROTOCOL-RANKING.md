# Member 3 - Protocol / Ranking / Reliability

## Ownership

- `common`
- `server/ranking`
- `server/reliability`
- `server/metrics`
- network tests

## Expected deliverables

- protocol DTOs and message contracts
- future serialization and framing contract
- heartbeat and reconnect support
- RankingService and rating calculation
- duplicate `matchId` protection
- real-time leaderboard broadcast
- metrics and load tests

## Should not own

- authoritative gameplay implementation
- client rendering or movement UI
- room gameplay rules
- server connection implementation beyond reliability integration
- unreviewed protocol/common changes

## Independent testing

Use a `HeadlessClient` test double plus unit tests for DTOs, serialization, duplicate-result handling, rating updates, heartbeat timeouts, reconnect state, leaderboard publication, and metrics collection.

Although Member 3 maintains `common`, every contract change requires team agreement and matching updates to both protocol documents.
