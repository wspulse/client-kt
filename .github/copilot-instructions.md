# Copilot Instructions — wspulse/client-kt

## Project Overview

wspulse/client-kt is a **WebSocket client library for Kotlin (JVM + Android)** with automatic reconnection and exponential backoff. Published as `com.github.wspulse:client-kt` via [JitPack](https://jitpack.io/#wspulse/client-kt). Uses **Ktor CIO** for WebSocket transport, **kotlinx-coroutines** for async lifecycle, **SLF4J** for logging, and **org.json** as the default JSON codec (hidden from consumers).

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
    - `feat/<name>` or `feature/<name>` — new feature
    - `refactor/<name>` — restructure without behaviour change
    - `bugfix/<name>` — bug fix
    - `fix/<name>` — quick fix (e.g. config, docs, CI)
    - `chore/<name>` — maintenance, CI/CD, dependencies, docs
    - CI triggers on all branch prefixes above and on PRs targeting `main`/`develop`. Tags do **not** trigger CI (the tag is created after CI already passed). Open a PR into `develop`; `develop` requires status checks to pass.
- **Tests**: located in `src/test/kotlin/`. Cover happy path and at least one error path. Required for new public functions. Integration tests use a Go echo server from `testserver/`.
  - **Test-first for bug fixes**: **mandatory** — see Critical Rule 9 for the required step-by-step procedure. Do not touch production code without a prior failing test.
- **API compatibility**:
  - Public symbols are a contract. Changing or removing any public identifier is a breaking change requiring a major version bump.
  - Adding a method to a public interface breaks all external implementations — treat it as a breaking change.
  - Mark deprecated symbols with `@Deprecated("Use Xxx instead")` before removal.
- **Error format**: exception messages prefixed with `wspulse: <context>`.
- **Dependency policy**: `kotlinx-coroutines-core` and `slf4j-api` are `api` scope (transitively exposed to consumers). Ktor and org.json are `implementation` scope (hidden from consumers). Justify any new external dependency explicitly in the PR description.
- **File encoding**: all files must be UTF-8 without BOM. Do not use any other encoding.

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

## PR Comment Review — MANDATORY

When handling PR review comments, **every unresponded comment must be analyzed and responded to**. No comment may be silently ignored.

### 1. Fetch unresponded comments

Pull all comments that have not received a reply from the PR author. Bot-generated summaries (e.g. Copilot review overview) may be skipped; individual line comments from bots must still be evaluated.

### 2. Analyze each comment

Evaluate against:

| Criterion | Question |
|-----------|----------|
| **Validity** | Is the observation correct? Is the suggestion reasonable? |
| **Severity** | Is it a bug, a correctness issue, a design concern, or a style/preference nitpick? |
| **Cost** | What is the effort to address? Does the change introduce risk or scope creep? |

### 3. Decide and respond

Every comment must receive exactly one response:

- **`Fixed in {hash}. {what changed and why}`** — adopt and fix immediately. Bug and correctness issues must use this path unless the fix requires a separate PR due to scope.
- **`Tracked in TODOS.md — {reason for deferring}`** — adopt but defer. Add entry to repo root `TODOS.md` with context and PR comment link.
- **`Won't fix. {clear reasoning}`** — reject the suggestion with explanation.
- **`Not applicable — {explanation}`** — the comment does not apply (already handled, misunderstanding, duplicate, or already tracked in TODOS.md).

Duplicate or related comments may reference each other: `Same reasoning as {reference} above — {brief}`.

### 4. Zero unresponded comments before merge

The PR must have zero unaddressed comments before merge. This is a hard gate.

## Session Protocol

> Files under `doc/local/` are git-ignored and must **never** be committed.
> This includes plan files (`doc/local/plan/`), review records, and the AI learning log (`doc/local/ai-learning.md`).

### Start of every session — MANDATORY

**Do these steps before writing any code:**

1. Read `doc/local/ai-learning.md` **in full** to recall past mistakes. If the file is missing or empty, create it with the table header (see format below) before proceeding.
2. Check `doc/local/plan/` for any in-progress plan and read it fully.

### During feature work

For any new feature or multi-file fix: save a plan to `doc/local/plan/<feature-name>.md` **before starting**. Keep it updated with completed steps throughout the session.

### Review records

After conducting any review (code review, plan review, design review, PR review, etc.), record the findings for cross-session context:

- **Where to write**: this repo's `doc/local/`. If working in a multi-module workspace, also write to the workspace root's `doc/local/`.
- **Single truth**: write the full record in one location; the other location keeps a brief summary with a file path reference to the full record.
- **Acceptable formats**:
  1. Update the relevant plan file in `doc/local/plan/` with the review outcome.
  2. Dedicated review file in `doc/local/` if no relevant plan exists.
- **What to record**: review type, key findings, decisions made, action items, and resolution status.

### End of every session — MANDATORY

**Before closing the session, complete this checklist without exception:**

1. Append at least one entry to `doc/local/ai-learning.md` — **even if no mistakes were made**. Record what you confirmed, what technique worked, or what you observed. An empty file is a sign of non-compliance.
2. Update any in-progress plan in `doc/local/plan/` to reflect completed steps.
3. Verify `make check` passes in every module you edited.

**Entry format** for `doc/local/ai-learning.md`:

```
| Date       | Issue or Learning | Root Cause | Prevention Rule |
| ---------- | ----------------- | ---------- | --------------- |
| YYYY-MM-DD | <what happened or what you learned> | <why it happened> | <how to avoid it next time> |
```

**Writing to `ai-learning.md` is not optional. It is the primary cross-session improvement mechanism. An empty file proves the session protocol was ignored.**

[contract-if]: https://github.com/wspulse/.github/blob/main/doc/contracts/client/interface.md
[contract-bh]: https://github.com/wspulse/.github/blob/main/doc/contracts/client/behaviour.md
