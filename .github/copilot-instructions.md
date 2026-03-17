# Copilot Instructions — wspulse/client-kt

## Project Overview

wspulse/client-kt is a **WebSocket client library for Kotlin (JVM + Android)** with automatic reconnection and exponential backoff. Published as `com.wspulse:client-kt` on Maven Central. Uses **Ktor CIO** for WebSocket transport, **kotlinx-coroutines** for async lifecycle, and **org.json** as the default JSON codec (hidden from consumers).

## Architecture

- **`WspulseClient.kt`** — `Client` interface (public API: `send`, `close`, `done`) and `WspulseClient.connect()` companion factory. Internal coroutines: `readLoop`, `writeLoop`, `reconnectLoop`, `pingLoop`.
- **`ClientConfig.kt`** — Builder DSL for configuration (callbacks, reconnect, heartbeat, codec).
- **`Codec.kt`** — `Codec` interface, `FrameType` enum, `JsonCodec` default implementation.
- **`Frame.kt`** — `data class Frame(id, event, payload: Any?)`.
- **`Errors.kt`** — `sealed class WspulseException` hierarchy.
- **`Backoff.kt`** — `backoff(attempt, base, max): Duration` with equal jitter.

## Development Workflow

```bash
make fmt        # ktlint format
make lint       # ktlint check
make test       # run unit tests
make check      # lint + test (pre-commit gate)
make test-cover # test with JaCoCo coverage report
make clean      # clean build artifacts
```

## Conventions

- **Kotlin style**: `ktlint`, camelCase functions, PascalCase classes, KDoc on all public symbols, `if`/`when` expressions preferred over statements.
- **Naming**:
  - **Interface and class names** must use full words — no abbreviations. Write `Connection`, not `Conn`; `Configuration`, not `Cfg`.
  - **Variable and parameter names** follow standard Kotlin style: short names for local scope (`conn`, `fn`, `err`), descriptive names for class-level identifiers.
- **Markdown**: no emojis in documentation files.
- **Git**:
  - Follow the commit message rules in [commit-message-instructions.md](./instructions/commit-message-instructions.md).
  - All commit messages in English.
  - Each commit must represent exactly one logical change.
  - Before every commit, run `make check` (runs lint → test in order).
  - **Branch strategy**: never push directly to `develop` or `main`.
    - `feature/<name>` — new feature
    - `refactor/<name>` — restructure without behaviour change
    - `bugfix/<name>` — bug fix
    - `fix/<name>` — quick fix (e.g. config, docs, CI)
    - CI triggers on all four branch prefixes and on PRs targeting `main`/`develop`. Tags do **not** trigger CI (the tag is created after CI already passed). Open a PR into `develop`; `develop` requires status checks to pass.
- **Tests**: located in `src/test/kotlin/`. Cover happy path and at least one error path. Required for new public functions. Integration tests use a Go echo server from `testserver/`.
  - **Test-first for bug fixes**: **mandatory** — see Critical Rule 9 for the required step-by-step procedure. Do not touch production code without a prior failing test.
- **API compatibility**:
  - Public symbols are a contract. Changing or removing any public identifier is a breaking change requiring a major version bump.
  - Adding a method to a public interface breaks all external implementations — treat it as a breaking change.
  - Mark deprecated symbols with `@Deprecated("Use Xxx instead")` before removal.
- **Error format**: exception messages prefixed with `wspulse: <context>`.
- **Dependency policy**: Ktor and org.json are `implementation` scope (hidden from consumers). Justify any new external dependency explicitly in the PR description.

## Critical Rules

1. **Read before write** — always read the target file, the [interface contract][contract-if], and the [behaviour contract][contract-bh] fully before editing.
2. **Minimal changes** — one concern per edit; no drive-by refactors.
3. **No hardcoded secrets** — all configuration via environment variables.
4. **Contract compliance** — API surface and behaviour must match the [interface contract][contract-if] and [behaviour contract][contract-bh]. When in doubt, re-read both contracts.
5. **Backoff formula parity** — must produce the same distribution as all other `wspulse/client-*` libraries. Any deviation is a bug.
6. **Coroutine safety** — `send()` and `close()` must be safe for concurrent use from any coroutine or thread. Shared state protected by `AtomicBoolean`/`Channel`/`Mutex`.
7. **Coroutine lifecycle** — every launched coroutine must have an explicit exit condition. `close()` must guarantee all child coroutines have completed before returning. Scope cancellation is the primary mechanism.
8. **No breaking changes without version bump** — never rename, remove, or change the signature of a public symbol without bumping the major version. When unsure, add alongside the old symbol and deprecate.
9. **STOP — test first, fix second** — when a bug is discovered or reported, do NOT touch production code until a failing test exists. Follow this exact sequence without skipping or reordering:
   1. Write a failing test that reproduces the bug.
   2. Run the test and confirm it **fails** (proving the test actually catches the bug).
   3. Fix the production code.
   4. Run the test again and confirm it **passes**.
   5. Run `make check` to verify nothing else broke.
   6. If you are about to edit production code and no failing test exists yet — stop and go back to step 1.
10. **STOP — before every commit, verify this checklist:**
    1. Run `make check` (fmt → lint → test) and confirm it passes. Skip if the commit contains only non-code changes (e.g. documentation, comments, Markdown).
    2. Run GitHub Copilot code review (`github.copilot.chat.review.changes`) on the working-tree diff and resolve every comment before proceeding.
    3. Commit message follows [commit-message-instructions.md](instructions/commit-message-instructions.md): correct type, subject ≤ 50 chars, numbered body items stating reason → change.
    4. This commit contains exactly one logical change — no unrelated modifications.
    5. If any item fails — fix it before committing.
11. **Accuracy** — if you have questions or need clarification, ask the user. Do not make assumptions without confirming.
12. **Language consistency** — when the user writes in Traditional Chinese, respond in Traditional Chinese; otherwise respond in English.
13. **Throw policy — fail early, never at steady-state runtime** — Enforce errors at the earliest possible phase:
    1. Prefer compile-time enforcement via the type system.
    2. **Setup-time programmer errors** (null handler, empty event name, invalid option): throw `IllegalArgumentException` / `IllegalStateException`. These indicate a caller logic bug; crashing at startup is correct.
    3. **Steady-state runtime** (`send`, `close`, reconnect loops): throw typed `WspulseException` subclasses, never unexpected exceptions.

## Session Protocol

> Files under `doc/local/` are git-ignored and must **never** be committed.
> This applies to both plan files and `doc/local/ai-learning.md`.

- **At the start of every session**: check whether `doc/local/plan/` contains
  an in-progress plan for the current task, and read `doc/local/ai-learning.md`
  (if it exists) to recall past mistakes and techniques before writing any code.
- **Plan mode**: when implementing a new feature or multi-file fix, save a plan
  to `doc/local/plan/<feature-name>.md` before starting. Keep it updated with
  completed steps and any plan changes throughout the session.
- **AI learning log**: at the end of a session where mistakes were made or
  reusable techniques were discovered, append a short entry to
  `doc/local/ai-learning.md`. Entry format:
  `Date` / `Issue or Learning` / `Root Cause` / `Prevention Rule`.
  Append only — never overwrite existing entries.

[contract-if]: https://github.com/wspulse/.github/blob/main/doc/contracts/client-interface.md
[contract-bh]: https://github.com/wspulse/.github/blob/main/doc/contracts/client-behaviour.md
