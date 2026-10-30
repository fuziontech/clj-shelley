# Working on clj-shelley (read this first)

We are porting `boldsoftware/shelley` (Go + Vue, ~260k lines) to Clojure with
**feature parity** as the goal. A reference clone of the original is at
`/tmp/shelley-ref` — read it whenever a behaviour is ambiguous. It is the spec.

`PARITY.md` is the scoreboard. `README.md` describes what exists so far.

## Run it

```bash
clojure -M:run serve 8000           # web UI + API
clojure -M:run run "prompt"         # one-shot CLI turn
clojure -M:dev -e "(require 'shelley.core-test)(clojure.test/run-tests 'shelley.core-test)"
```

Model calls go to the exe.dev keyless gateway (`https://llm-exe-proxy.int.exe.xyz`),
default model `anthropic/claude-sonnet-5-5`. JVM startup is ~8s; a test run is
~60s. Do not leave servers running on :8000 unless you started one there.

## Layout

```
src/shelley/llm.clj         block/message model, SSE transport, retry, dispatch
src/shelley/llm/*.clj       provider wire adapters
src/shelley/loop.clj        the agent loop
src/shelley/tools.clj       tool registry + handlers
src/shelley/db.clj          SQLite persistence
src/shelley/server.clj      HTTP API + SSE + UI host
src/shelley/main.clj        CLI entry points
resources/index.html        the UI
test/shelley/*.clj          tests
```

## Conventions

- Everything is a plain map. No records, no protocols unless there are two
  implementations and the dispatch is genuinely polymorphic.
- Docstrings explain *why*, not what. Match the surrounding prose.
- Tests pin real bugs we hit. When you fix something subtle, add the test.
- Failures must reach the model as tool-results, not exceptions.
- Never silently swallow an exception; emit an event or log it.
- Keep the UI dependency-free unless a CDN library is unavoidable.

## Coordination

Do not edit files outside your assigned ownership. If you need a change in
someone else's file, write the exact patch in your report instead of applying
it. Adding a new namespace inside your own directory is always fine.

Do not edit `deps.edn`, `PARITY.md`, `README.md`, or `resources/index.html`
unless it is yours. Report the diary/new deps you need.

When you finish: state what landed, what is missing, and update your section's
rows conceptually in your report (the coordinator updates PARITY.md).
