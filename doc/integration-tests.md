# Integration Test Coverage — client-kt

Integration tests run against a live `wspulse/server` via the shared
[testserver](../../testserver/). The Go test server is spawned by
`ProcessBuilder` in `@BeforeAll` and killed in `@AfterAll`.

**Run:** `./gradlew integrationTest` (or `make test-integration`)

## Scenario Matrix

| #   | Scenario                                                       | Test Name                                                            | Query Params          |
| --- | -------------------------------------------------------------- | -------------------------------------------------------------------- | --------------------- |
| 1   | Connect → send → echo → close clean                            | `connects, sends a frame, receives echo, and closes cleanly`         | —                     |
| 2   | Server drops → onTransportDrop + onDisconnect (no reconnect)   | `onDisconnect fires exactly once on close`                           | —                     |
| 3   | Auto-reconnect: server drops → reconnects within maxRetries    | `reconnects after kick and resumes echo (scenario 3)`                | `?id=…`               |
| 4   | Max retries exhausted → `onDisconnect(RetriesExhaustedException)` | `fires RetriesExhaustedException after shutdown (scenario 4)`     | `?id=…`               |
| 5   | `close()` during reconnect → loop stops, `onDisconnect(null)`  | `close during reconnect fires onDisconnect null (scenario 5)`        | `?id=…`               |
| 6   | `send()` on closed client → `ConnectionClosedException`        | `send after close throws ConnectionClosedException`                  | —                     |
| 7   | Heartbeat pong timeout → `ConnectionLostException`             | `pong timeout triggers ConnectionLostException (scenario 7)`         | `?ignore_pings=1`     |
| 8   | Concurrent sends: no race                                      | `concurrent sends do not race`                                       | —                     |
| 9   | Concurrent close + transport drop → onDisconnect exactly once  | `close is idempotent`                                                | —                     |

## Additional Integration Tests

| Test Name                                                  | What It Covers                                     |
| ---------------------------------------------------------- | -------------------------------------------------- |
| `round-trips all Frame fields (id, event, payload)`        | Full Frame field fidelity through the wire          |
| `handles server rejection gracefully`                      | Server returns HTTP 401 via `?reject=1`             |
| `sends multiple frames and receives them in order`         | Message ordering preservation                      |
| `connects to a specific room via query param`              | Room routing via `?room=…`                          |
| `detects server-initiated kick via control API`            | `POST /kick?id=…` → `onDisconnect(non-null)`       |

**Total: 14 integration tests.**

## Testserver Control API

| Method | Endpoint          | Purpose                                     |
| ------ | ----------------- | ------------------------------------------- |
| GET    | `/health`         | Liveness probe                              |
| POST   | `/kick?id=<id>`   | Server-initiated disconnect                 |
| POST   | `/shutdown`       | Stop WS listener (all dials fail)           |
| POST   | `/restart`        | Rebind WS listener on same port             |

WebSocket query parameters: `?reject=1`, `?room=<id>`, `?id=<id>`, `?ignore_pings=1`.
