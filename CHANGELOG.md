# Changelog

## [Unreleased]

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

[Unreleased]: https://github.com/wspulse/client-kt/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/wspulse/client-kt/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/wspulse/client-kt/releases/tag/v0.1.0
