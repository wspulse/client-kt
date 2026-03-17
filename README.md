# wspulse/client-kt

[![CI](https://github.com/wspulse/client-kt/actions/workflows/ci.yml/badge.svg)](https://github.com/wspulse/client-kt/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/wspulse/client-kt.svg)](https://jitpack.io/#wspulse/client-kt)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A Kotlin WebSocket client with optional automatic reconnection, designed for use with [wspulse/server](https://github.com/wspulse/server).

Works on **JVM 17+** and **Android API 26+** via [Ktor CIO](https://ktor.io/docs/client-engines.html#cio).

**Status:** v0 — API is being stabilized. Artifact: `com.github.wspulse:client-kt` ([JitPack](https://jitpack.io/#wspulse/client-kt)).

---

## Design Goals

- Thin client: connect, send, receive, auto-reconnect
- Matches server-side `Frame` wire format via JSON text frames
- Exponential backoff with configurable retries (equal jitter)
- Transport drop vs. permanent disconnect callbacks
- Coroutine-native with non-blocking `send()`

---

## Install

Add the JitPack repository and the dependency:

### Gradle (Kotlin DSL)

```kotlin
// build.gradle.kts
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.wspulse:client-kt:v0.1.0")
}
```

### Gradle (Groovy)

```groovy
// build.gradle
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.wspulse:client-kt:v0.1.0'
}
```

### Maven

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.wspulse</groupId>
    <artifactId>client-kt</artifactId>
    <version>v0.1.0</version>
</dependency>
```

---

## Quick Start

```kotlin
import com.wspulse.client.Frame
import com.wspulse.client.WspulseClient

val client = WspulseClient.connect("ws://localhost:8080/ws?room=r1&token=xyz") {
    onMessage = { frame ->
        println("[${frame.event}] ${frame.payload}")
    }
    autoReconnect = AutoReconnectConfig(
        maxRetries = 5,
        baseDelay = 1.seconds,
        maxDelay = 30.seconds,
    )
}

client.send(Frame(event = "msg", payload = mapOf("text" to "hello")))

// Suspend until permanently disconnected.
client.done.await()
```

### Android (ViewModel)

```kotlin
class ChatViewModel : ViewModel() {
    private var client: Client? = null

    fun connect(url: String) {
        viewModelScope.launch {
            client = WspulseClient.connect(url) {
                onMessage = { frame ->
                    // Update UI state
                }
                autoReconnect = AutoReconnectConfig(maxRetries = 10)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { client?.close() }
    }
}
```

> **Note:** The library is lifecycle-agnostic — it does not reference `Dispatchers.Main`. Call `client.close()` in `onCleared()` or `onDestroy()` to release resources.

---

## Frame Format

The default `JsonCodec` encodes frames as JSON text frames:

```json
{
  "id": "msg-001",
  "event": "chat.message",
  "payload": { "text": "hello" }
}
```

The `event` field is the routing key on the server side. Set `frame.event` to match the handler registered with `r.On("chat.message", ...)` on the server. The `payload` field carries arbitrary data — the library does not inspect it.

```kotlin
// Send a typed frame — server routes by "event"
client.send(Frame(
    event = "chat.message",
    payload = mapOf("text" to "hello world"),
))

// Receive typed frames
onMessage = { frame ->
    when (frame.event) {
        "chat.message" -> handleMessage(frame)
        "chat.ack"     -> handleAck(frame)
    }
}
```

To use a custom wire format, implement the `Codec` interface:

```kotlin
object ProtobufCodec : Codec {
    override val frameType = FrameType.BINARY
    override fun encode(frame: Frame): ByteArray = /* serialize */
    override fun decode(data: ByteArray): Frame = /* deserialize */
}

val client = WspulseClient.connect(url) {
    codec = ProtobufCodec
}
```

---

## Public API Surface

| Symbol          | Description                                          |
| --------------- | ---------------------------------------------------- |
| `Client`        | Interface: `send()`, `close()`, `done`               |
| `WspulseClient` | Implementation with `companion object { connect() }` |
| `Frame`         | Data class: `id?`, `event?`, `payload?`              |
| `Codec`         | Interface: `encode()`, `decode()`, `frameType`       |
| `JsonCodec`     | Default codec — JSON text frames                     |
| `ClientConfig`  | Builder DSL for client configuration                 |

### Client Options

| Option            | Type                          | Default           |
| ----------------- | ----------------------------- | ----------------- |
| `onMessage`       | `(Frame) -> Unit`             | no-op             |
| `onDisconnect`    | `(WspulseException?) -> Unit` | no-op             |
| `onReconnect`     | `(attempt: Int) -> Unit`      | no-op             |
| `onTransportDrop` | `(Exception) -> Unit`         | no-op             |
| `autoReconnect`   | `AutoReconnectConfig?`        | `null` (disabled) |
| `heartbeat`       | `HeartbeatConfig`             | 20s / 60s         |
| `writeWait`       | `Duration`                    | 10s               |
| `maxMessageSize`  | `Long`                        | 1 MiB (1 048 576) |
| `dialHeaders`     | `Map<String, String>`         | `emptyMap()`      |
| `codec`           | `Codec`                       | `JsonCodec`       |

### Error Types

| Exception                   | Thrown by / Passed to     |
| --------------------------- | ------------------------- |
| `ConnectionClosedException` | `send()` after `close()`  |
| `SendBufferFullException`   | `send()` when buffer full |
| `RetriesExhaustedException` | `onDisconnect`            |
| `ConnectionLostException`   | `onDisconnect`            |

---

## Features

- **Auto-reconnect** — exponential backoff with configurable max retries, base delay, and max delay. Equal jitter formula: delay ∈ `[half, full]` where full = min(base × 2^attempt, max).
- **Transport drop callback** — `onTransportDrop` fires on every transport death, even when auto-reconnect follows. Useful for metrics and logging.
- **Permanent disconnect callback** — `onDisconnect` fires exactly once when the client is truly done (`close()` called, retries exhausted, or connection lost without auto-reconnect).
- **Heartbeat** — Client-side Ping/Pong keeps the connection alive and detects silently-dead servers.
- **Max message size** — Inbound messages exceeding `maxMessageSize` are rejected with close code 1009.
- **Backpressure** — bounded 256-frame send buffer; throws `SendBufferFullException` when full.
- **Non-blocking send** — `send()` is a regular function (not `suspend`), safe to call from any coroutine or thread.
- **`done` Deferred** — completes when the client reaches CLOSED state. Await it to suspend until permanently disconnected.
- **Idempotent close** — `close()` is safe to call multiple times from concurrent coroutines.

---

## Development

```bash
make fmt        # auto-format source files (ktlint)
make check      # lint, test (pre-commit gate)
make test       # ./gradlew test
make clean      # remove build artifacts
```

---

## Related Modules

| Module                                                    | Description                            |
| --------------------------------------------------------- | -------------------------------------- |
| [wspulse/core](https://github.com/wspulse/core)           | Shared types, codecs, and event router |
| [wspulse/server](https://github.com/wspulse/server)       | WebSocket server                       |
| [wspulse/client-go](https://github.com/wspulse/client-go) | Go client (reference implementation)   |
| [wspulse/client-ts](https://github.com/wspulse/client-ts) | TypeScript client                      |
