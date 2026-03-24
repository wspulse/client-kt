# AGENTS.md — wspulse/client-kt

This file is the entry point for all AI coding agents (GitHub Copilot, Codex,
Cursor, Claude, etc.). Full working rules are in
`.github/copilot-instructions.md` — read it completely before
making any changes.

---

## Quick Reference

**Maven coordinates**: `com.github.wspulse:client-kt` (via [JitPack](https://jitpack.io/#wspulse/client-kt)) | **Package**: `com.wspulse.client`

**Key files**:

- `WspulseClient.kt` — `Client` interface, `WspulseClient.connect()` factory,
  `readLoop` / `writeLoop` / `reconnectLoop` / `pingLoop` coroutines
- `ClientConfig.kt` — Builder DSL for configuration
- `Codec.kt` — `Codec` interface, `FrameType` enum, `JsonCodec` default
- `Frame.kt` — `data class Frame(id, event, payload)`
- `Errors.kt` — `sealed class WspulseException` hierarchy
- `Backoff.kt` — `backoff(attempt, base, max)` with equal jitter

**Pre-commit gate**: `make check` (lint → test)

---

## Non-negotiable Rules

1. **Read before write** — read the target file before any edit.
2. **Coroutine safety** — `send()` and `close()` must be safe for concurrent
   use from any coroutine or thread.
3. **Coroutine lifecycle** — every launched coroutine must have an explicit exit
   condition. `close()` must guarantee all child coroutines have completed.
4. **No breaking changes without version bump.**
5. **No hardcoded secrets.**
6. **Minimal changes** — one concern per edit; no drive-by refactors.

---

## Session Protocol

> `doc/local/` is git-ignored. Never commit files under it.

- **Start of session**: read `doc/local/ai-learning.md` in full (create with header if missing) and check `doc/local/plan/` for any in-progress plan.
- **Feature work**: save plan to `doc/local/plan/<feature-name>.md` before starting.
- **End of session**: append at least one entry to `doc/local/ai-learning.md` — **mandatory even if no mistakes were made**. An empty file proves the session protocol was ignored.
  Format: `Date` / `Issue or Learning` / `Root Cause` / `Prevention Rule`.
