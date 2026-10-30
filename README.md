# clj-shelley

A coding agent in Clojure — a from-scratch reimplementation of the architecture
behind [boldsoftware/shelley](https://github.com/boldsoftware/shelley), built for
feature parity with it.

The original is ~187k lines of Go plus ~75k lines of TypeScript/Vue. `PARITY.md`
is the scoreboard for what has been ported and what has not.

## What it does

- Streams from the model token by token.
- Calls tools, several per round, **in parallel**, and feeds the results back.
- Loops until the model stops asking for tools, splicing in user messages typed
  mid-turn and compacting context when it approaches the model's limit.
- Persists every message to SQLite as it arrives, so a crashed turn is
  recoverable rather than lost.
- Serves a web UI over SSE, and a CLI mode for the terminal.
- Can be cancelled mid-turn.

## Architecture

```
shelley.llm          internal block/message model, SSE transport, retry, dispatch
shelley.llm.openai   OpenAI chat-completions adapter
shelley.llm.anthropic Anthropic Messages adapter
shelley.llm.gemini   Gemini generateContent adapter
shelley.llm.fixture  deterministic provider for tests
shelley.loop         the agent loop
shelley.compact      context compaction
shelley.tools        tool registry and handlers
shelley.db           append-only message store (SQLite, WAL)
shelley.server       JSON API + SSE + UI host
shelley.git          repository introspection
shelley.usage        token and cost accounting
shelley.terminal     pty sessions for the exec pane
```

The central design decision, inherited from the original: **conversations are
vectors of content blocks, not strings**. One assistant message can hold text
and three tool calls at once, which is what makes parallel tool execution
possible. Providers that disagree with that shape (OpenAI splits tool results
into their own messages) are forced into line in their adapter, not in the loop.

```
user ──▶ [ llm request ] ──▶ assistant message ──┬─ no tool calls ──▶ done
              ▲                                  │
              │                                  └─ tool calls ──▶ execute
              │                                                     in parallel
              └──────────── tool results (one user message) ◀───────┘
```

## Running it

Java 21 and the Clojure CLI. No other service needed.

```bash
clojure -M:run serve 8000          # web UI on :8000
clojure -M:run run "do a thing"    # one-off CLI turn, streams to the terminal
```

Model selection, endpoint override:

```bash
CLJ_SHELLEY_MODEL=anthropic/claude-sonnet-5-5 clojure -M:run serve 8000
LLM_BASE_URL=https://api.anthropic.com clojure -M:run serve 8000
```

By default it talks to the exe.dev keyless gateway
(`https://llm-exe-proxy.int.exe.xyz`), so no API key is needed on this VM.

## Tests

```bash
clojure -M:dev -e "(require 'shelley.core-test)(clojure.test/run-tests 'shelley.core-test)"
clojure -M:dev -e "(require 'shelley.loop-test)(clojure.test/run-tests 'shelley.loop-test)"
```

They pin the bugs found while building this — content `:type` returning from
SQLite as a string (which silently sent empty messages), thinking blocks losing
the signature Anthropic requires on replay, cancellation not actually stopping
the loop, and string-keyed tool arguments.

## Safety

`bash` runs with the privileges of the process. Do not point this at a machine
you care about.
