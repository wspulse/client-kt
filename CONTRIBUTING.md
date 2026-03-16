# Contributing to wspulse/client-kt

Thank you for your interest in contributing. This document describes the process and conventions expected for all contributions.

## Before You Start

- Open an issue to discuss significant changes before starting work.
- For bug fixes, write a failing test that reproduces the issue before modifying production code. The PR must include this test.
- For new features, confirm scope and API design in an issue first.

## Development Setup

```bash
git clone https://github.com/wspulse/client-kt
cd client-kt
./gradlew build
```

Requires: JDK 17+, Gradle 8.12+ (wrapper included).

## Pre-Commit Checklist

Run `make check` before every commit. It runs in order:

1. `make lint` — runs ktlint check; must pass with zero warnings
2. `make test` — runs all tests; must pass

If any step fails, do not commit.

## Commit Messages

Follow the format in [`.github/instructions/commit-message-instructions.md`](.github/instructions/commit-message-instructions.md):

```
<type>: <subject>

1.<reason> → <change>
```

All commit messages must be in English.

## Naming Conventions

- **Interface and class names** must use full words — no abbreviations. Write `Connection`, not `Conn`.
- **Variable and parameter names** follow standard Kotlin style: short names for local scope (`conn`, `fn`, `err`), descriptive names for class-level identifiers.

## Coroutine Safety

`send()` and `close()` must remain safe for concurrent use from any coroutine or thread. All shared state must be protected by `AtomicBoolean`/`Channel`/`Mutex`. Every launched coroutine must have an explicit exit condition. `close()` must guarantee all child coroutines have completed before returning.

## API Compatibility

wspulse/client-kt follows semantic versioning. Any change that removes, renames, or alters the signature of a public symbol is a **breaking change** and requires a major version bump.

- Before removing a symbol, mark it with `@Deprecated("Use Xxx instead")` in a minor release.
- Adding a method to a public interface is also a breaking change.
- When in doubt, add a new symbol alongside the old one.

## Pull Request Guidelines

- One PR per logical change.
- Do not reformat code unrelated to your change — it creates noise in the diff.
- All CI checks must pass before review.
- Describe what changed and why, not just what the diff shows.
