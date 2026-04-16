# Component Test Coverage -- client-kt

> **Contract:** all scenarios defined in
> [`.github/doc/contracts/client/test-scenarios.md`](https://github.com/wspulse/.github/blob/main/doc/contracts/client/test-scenarios.md)

Component tests use `MockTransport` and `MockDialer` to exercise the
client's internal coroutine machinery (readLoop, writeLoop,
reconnectLoop) without any network I/O. No external dependencies
(testserver, Go toolchain) are required.

**Run:** `./gradlew test` (or `make test`)

## Scenario Matrix

| #   | Scenario                                                          | Test Name                                                              |
| --- | ----------------------------------------------------------------- | ---------------------------------------------------------------------- |
| 1   | Connect, send, receive echo, close clean                          | `connects, sends a frame, receives echo, and closes cleanly`           |
| 2   | Transport drop, onTransportDrop + onDisconnect (no reconnect)     | `transport drop fires onTransportDrop and onDisconnect without reconnect` |
| 3   | Auto-reconnect: transport drop, reconnects within maxRetries      | `reconnects after transport drop and resumes message flow`             |
| 4   | Max retries exhausted, `onDisconnect(RetriesExhaustedException)`  | `fires RetriesExhaustedException after max retries exhausted`          |
| 5   | `close()` during reconnect, loop stops, `onDisconnect(null)`      | `close during reconnect fires onDisconnect null`                       |
| 6   | `send()` on closed client, `ConnectionClosedException`            | `send after close throws ConnectionClosedException`                    |
| 7   | Concurrent sends: no data race or interleaving                    | `concurrent sends from multiple coroutines do not race`                |
| 8   | Concurrent `close()` + transport drop, onDisconnect exactly once  | `close racing with transport drop fires onDisconnect exactly once`     |

## Additional Tests

| Test Name                                                          | What It Covers                                    |
| ------------------------------------------------------------------ | ------------------------------------------------- |
| `round-trips all Frame fields (event, payload)`                    | Full Frame field fidelity through codec            |
| `handles server rejection gracefully`                              | Dial failure propagates exception                  |
| `sends multiple frames and receives them in order`                 | Message ordering preservation                      |
| `onDisconnect fires exactly once on close`                         | User-initiated close, single callback              |
| `close is idempotent`                                              | Multiple `close()` calls, single callback          |
| `transport error with exception fires onTransportDrop with that error` | Error propagation through transport drop       |
| `onTransportRestore does not fire on initial connect`              | Initial connect does not trigger restore callback  |
| `close from onTransportDrop suppresses onTransportRestore`         | Close during reconnect suppresses restore          |
| `throwing onTransportDrop does not prevent onDisconnect from firing` | onTransportDrop callback exception safety (no reconnect path) |
| `throwing onTransportDrop in reconnect loop does not abort reconnect` | onTransportDrop callback exception safety (reconnect loop path) |
| `clean close fires onTransportDrop null before onDisconnect null`    | Clean close delivers null to both callbacks in order           |

**Total: 19 component tests** (8 scenarios + 11 additional).
