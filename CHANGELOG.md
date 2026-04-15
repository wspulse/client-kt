# Changelog

## [Unreleased]

### Changed (BREAKING)

- **Heartbeat API flattened**: removed `HeartbeatConfig` data class and `heartbeat` config property. Replaced by top-level `pingInterval` (was `heartbeat.pingPeriod`) and `writeTimeout` (was `writeWait`). Pong deadline is now implicit in `writeTimeout` (was separate `heartbeat.pongWait`).
- Renamed `writeWait` config property to `writeTimeout` for naming consistency across SDKs.
- Validation error messages updated accordingly.

### Removed

- `HeartbeatConfig` data class.
- `heartbeat.pongWait` config property (pong deadline now uses `writeTimeout`).

---

## [0.5.0] - 2026-04-04

### Added

- `dispatcher` parameter on internal `connectInternal()` — injects a `CoroutineDispatcher` for the client's coroutine scope, enabling virtual-time testing with `UnconfinedTestDispatcher`.
- `random` parameter on internal `backoff()` — injects a `Random` instance, enabling deterministic jitter in tests.
- Component tests: all 5 test classes now inject `UnconfinedTestDispatcher(testScheduler)` so heartbeat timers use virtual time, and reconnect/pong-timeout scenarios advance virtual time with `testScheduler.advanceTimeBy()`.
- `waitUntil` helpers gain a synchronous fast-path (`if (condition()) return`) that avoids releasing the test coroutine to `workRunner` when the condition is already satisfied.
- `connect()` auto-converts `http://` to `ws://` and `https://` to `wss://` (case-insensitive per RFC 3986). Unsupported or missing schemes throw `IllegalArgumentException`.
- `sendBufferSize` config option — configurable outbound channel capacity [1, 4096], default 256

### Changed

- **BREAKING**: `onTransportDrop` callback signature changed from `(Exception) -> Unit` to `(Exception?) -> Unit`. The callback may fire with `null` when the active transport closes cleanly due to a user-initiated `close()`, and still fires exactly once per transport lifecycle. In reconnect scenarios, each transport drop produces one invocation; calling `close()` while already reconnecting does not produce an additional `null` invocation.
- Internal: replaced Ktor `Frame` and `CloseReason` types in `Transport` interface with library-owned `TransportFrame` (sealed class) and `TransportCloseReason`. Ktor types are now fully encapsulated inside `RealTransport`. No public API changes.
- Migrated integration tests to deterministic component tests using mock transport (no network I/O, no testserver dependency). 20 component tests replace 16 integration tests.
- Internal: extracted `Transport` and `Dialer` interfaces from `DefaultWebSocketSession` usage. Internal visibility only -- no public API changes.
- CI: removed `test-integration` job; component tests run in `lint-test`

### Removed

- **BREAKING**: `Frame.id` field removed — transport layer does not use it. Applications needing message IDs should use payload.

---

## [0.4.0] - 2026-03-24

### Added

- `onTransportRestore` callback option, fired after a successful reconnect

### Removed

- `onReconnect` callback option (replaced by `onTransportRestore`) (**breaking**)

---

## [0.3.0] - 2026-03-22

### Changed

- **BREAKING**: negative `maxRetries` now throws instead of being treated as
  unlimited. Use `0` for unlimited retries.
- Validation error messages use fully-qualified field names (`heartbeat.pongWait`,
  `autoReconnect.baseDelay`) to match the config validation contract.

### Added

- Missing config validation rules: `maxMessageSize`, `writeWait`, `maxRetries`
  upper bounds; negative `maxRetries` rejection.
- `CodecTest`: malformed JSON decode test now asserts `JSONException`.
- `ErrorsTest`: expanded sentinel error description coverage.

---

## [0.2.1] - 2026-03-21

### Fixed

- `connect()` now throws immediately on initial dial failure regardless of
  `autoReconnect` configuration. No callbacks fire and no `Client` is returned.
  Auto-reconnect only activates after a successful initial connection drops.

### Changed

- KDoc for `connect()` reworded: return type describes best-effort connection
  state rather than guaranteeing CONNECTED.
- Test callback flags changed from plain `var` to `AtomicBoolean` for
  thread-safety.

---

## [0.2.0] - 2026-03-17

### Changed

- Upgraded Kotlin from 2.1.10 to 2.3.0
- Upgraded Ktor from 3.1.1 to 3.4.1
- Upgraded kotlinx-coroutines from 1.10.1 to 1.10.2
- Upgraded ktlint Gradle plugin from 12.1.2 to 14.2.0

---

## [0.1.0] - 2026-03-16

### Added

- Project scaffold: Gradle (Kotlin DSL), version catalog, Makefile, CI/CD workflows
- `Frame` data class (`id`, `event`, `payload` — all optional)
- `Codec` interface with `JsonCodec` default (org.json)
- `ClientConfig` builder DSL with `resolveOptions()`-style defaults
- `backoff()` function with equal jitter (matches `client-go` formula)
- Error types: `ConnectionClosedException`, `SendBufferFullException`, `RetriesExhaustedException`, `ConnectionLostException`
- `WspulseClient.connect()` suspend factory returning a `Client`
- `Client` interface: `send()`, `close()`, `done`
- Auto-reconnect with exponential backoff, configurable `maxRetries`, `baseDelay`, `maxDelay`
- Heartbeat: client-side Ping/Pong with `pingPeriod` and `pongWait` (Ktor CIO)
- `writeWait`: write deadline per WebSocket send
- `maxMessageSize`: inbound message size enforcement (close code 1009)
- `dialHeaders`: custom HTTP headers for WebSocket upgrade
- Bounded 256-frame send buffer with non-blocking `send()`
- 22 tests: 4 backoff, 9 codec, 9 integration (against live Go testserver)
- CI workflow: JDK 17 + 21 matrix, `./gradlew check`
- README with quick-start, Android ViewModel example, API reference

[Unreleased]: https://github.com/wspulse/client-kt/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/wspulse/client-kt/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/wspulse/client-kt/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/wspulse/client-kt/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/wspulse/client-kt/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/wspulse/client-kt/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/wspulse/client-kt/releases/tag/v0.1.0
