# wspulse/client-kt

[![CI](https://github.com/wspulse/client-kt/actions/workflows/ci.yml/badge.svg)](https://github.com/wspulse/client-kt/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/wspulse/client-kt.svg)](https://jitpack.io/#wspulse/client-kt)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-17%2B-orange.svg)](https://openjdk.org)
[![Android](https://img.shields.io/badge/Android-API%2026%2B-green.svg?logo=android)](https://developer.android.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A Kotlin WebSocket client with optional automatic reconnection, designed for use with [wspulse/server](https://github.com/wspulse/server).

Works on **JVM 17+** and **Android API 26+** via [Ktor CIO](https://ktor.io/docs/client-engines.html#cio).

**Status:** v0 — API is being stabilized. Artifact: `com.github.wspulse:client-kt` ([JitPack](https://jitpack.io/#wspulse/client-kt)).

---

## Design Goals

- Thin client: connect, send, receive, auto-reconnect
- Matches server-side `Message` wire format via JSON text frames
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
    implementation("com.github.wspulse:client-kt:v0.2.0")
}
```

### Gradle (Groovy)

```groovy
// build.gradle
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.wspulse:client-kt:v0.2.0'
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
    <version>v0.2.0</version>
</dependency>
```

---

## Quick Start

```kotlin
import com.wspulse.client.Message
import com.wspulse.client.WspulseClient

val client = WspulseClient.connect("ws://localhost:8080/ws?room=r1&token=xyz") {
    onMessage = { msg ->
        println("[${msg.event}] ${msg.payload}")
    }
    autoReconnect = AutoReconnectConfig(
        maxRetries = 5,
        baseDelay = 1.seconds,
        maxDelay = 30.seconds,
    )
}

client.send(Message(event = "msg", payload = mapOf("text" to "hello")))

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
                onMessage = { msg ->
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

## Message Format

The default `JsonCodec` encodes messages as JSON text frames:

```json
{
  "event": "chat.message",
  "payload": { "text": "hello" }
}
```

The `event` field is the routing key on the server side. Set `Message.event` to match the handler registered with `r.On("chat.message", ...)` on the server. The `payload` field carries arbitrary data — the library does not inspect it.

```kotlin
// Send a typed message — server routes by "event"
client.send(Message(
    event = "chat.message",
    payload = mapOf("text" to "hello world"),
))

// Receive typed messages
onMessage = { msg ->
    when (msg.event) {
        "chat.message" -> handleMessage(msg)
        "chat.ack"     -> handleAck(msg)
    }
}
```

To use a custom wire format, implement the `Codec` interface:

```kotlin
object ProtobufCodec : Codec {
    override val wireType = WireType.BINARY
    override fun encode(message: Message): ByteArray = /* serialize */
    override fun decode(data: ByteArray): Message = /* deserialize */
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
| `Message`       | Data class: `event?`, `payload?`                     |
| `Codec`         | Interface: `encode()`, `decode()`, `wireType`        |
| `JsonCodec`     | Default codec — JSON text frames                     |
| `ClientConfig`  | Builder DSL for client configuration                 |

### Client Options

| Option            | Type                          | Default           |
| ----------------- | ----------------------------- | ----------------- |
| `onMessage`       | `(Message) -> Unit`           | no-op             |
| `onDisconnect`    | `(WspulseException?) -> Unit` | no-op             |
| `onTransportRestore` | `() -> Unit`               | no-op             |
| `onTransportDrop` | `(Exception?) -> Unit`        | no-op             |
| `autoReconnect`   | `AutoReconnectConfig?`        | `null` (disabled) |
| `writeWait`       | `Duration`                    | 10s               |
| `maxMessageSize`  | `Long`                        | 1 MiB (1 048 576) |
| `dialHeaders`     | `Map<String, String>`         | `emptyMap()`      |
| `codec`           | `Codec`                       | `JsonCodec`       |

### Logging

The client logs internal diagnostics via [SLF4J](https://www.slf4j.org/). Add an SLF4J binding to your project to see log output.

**Example with slf4j-simple (Gradle):**

```kotlin
dependencies {
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
}
```

**Disable logging** by using the `slf4j-nop` binding:

```kotlin
dependencies {
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")
}
```

### Error Types

| Exception                   | Thrown by / Passed to     |
| --------------------------- | ------------------------- |
| `ConnectionClosedException` | `send()` after `close()`  |
| `SendBufferFullException`   | `send()` when buffer full |
| `ServerClosedException`     | `onTransportDrop` when server sends a close frame (carries `code` and `reason`) |
| `RetriesExhaustedException` | `onDisconnect`            |
| `ConnectionLostException`   | `onDisconnect`            |

---

## Features

- **Auto-reconnect** — exponential backoff with configurable max retries, base delay, and max delay. Equal jitter formula: delay ∈ `[half, full]` where full = min(base × 2^attempt, max).
- **Transport drop callback** — `onTransportDrop` fires on every transport death, even when auto-reconnect follows. Useful for metrics and logging.
- **Permanent disconnect callback** — `onDisconnect` fires exactly once when the client is truly done (`close()` called, retries exhausted, or connection lost without auto-reconnect).
- **Max message size** — Inbound messages exceeding `maxMessageSize` are rejected with close code 1009.
- **Backpressure** — bounded 256-message send buffer; throws `SendBufferFullException` when full.
- **Non-blocking send** — `send()` is a regular function (not `suspend`), safe to call from any coroutine or thread.
- **`done` Deferred** — completes when the client reaches CLOSED state. Await it to suspend until permanently disconnected.
- **Idempotent close** — `close()` is safe to call multiple times from concurrent coroutines.

## Development

```bash
make fmt        # auto-format source files (ktlint)
make check      # lint, test (pre-commit gate)
make test       # ./gradlew test
make clean      # remove build artifacts
```

---

## Related Modules

| Module                                                    | Description                          |
| --------------------------------------------------------- | ------------------------------------ |
| [wspulse/server](https://github.com/wspulse/server)       | WebSocket server                     |
| [wspulse/client-go](https://github.com/wspulse/client-go) | Go client (reference implementation) |
| [wspulse/client-ts](https://github.com/wspulse/client-ts) | TypeScript client                    |
