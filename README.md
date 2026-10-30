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
shelley.llm.openai_responses  OpenAI Responses adapter (reasoning models + tools)
shelley.llm.anthropic Anthropic Messages adapter
shelley.llm.gemini   Gemini generateContent adapter
shelley.llm.fixture  deterministic provider for tests
shelley.loop         the agent loop
shelley.compact      context compaction
shelley.tools        tool registry and handlers
shelley.db           append-only message store (SQLite, WAL)
shelley.server       JSON API + SSE + UI host (138 routes, see API.md)
shelley.btw          side conversations that do not pollute the transcript
shelley.git          repository introspection
shelley.usage        token and cost accounting
shelley.terminal     pty sessions for the exec pane
shelley.skills       Agent Skills discovery and prompt listing
shelley.hooks        user hooks (system prompt, messages, status bar)
shelley.sandbox      opt-in bubblewrap isolation for tool execution
shelley.channels     Slack and exe.dev notification delivery
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

## Verifying it

```bash
scripts/e2e.sh 8000      # against a running server: one line per check
```

The script creates a conversation, makes the agent run a real command, and
asserts the file it was told to write exists — an agent that answers plausibly
without acting fails this. It then walks the workspace, model, settings and
control endpoints. Its exit status is the number of failures.

## Running it on this machine

```bash
sudo systemctl start clj-shelley     # port 8000, log in ./server.log
sudo systemctl status clj-shelley
```

Then open the UI. On exe.dev that is the VM's proxy URL for port 8000; locally it
is <http://localhost:8000/>. The service restarts on failure and survives a
reboot.

The model comes from `CLJ_SHELLEY_MODEL` in the unit file, or from the settings
panel (which wins, because it is a choice a person made).

### Models: the gateway, and models of your own

Two kinds of model work here, and the difference is one row in the `models`
table:

- **Gateway models** (`anthropic/claude-sonnet-5-5`, `openai/gpt-6.1-sol`) are
  reached through the exe.dev LLM gateway, which routes by model id and needs no
  key in the VM.
- **Custom models** carry their own endpoint, key, and wire name. Adding one
  (via `POST /api/custom-models` or the settings panel) registers a route, so
  the id you store is what you ask for and `api.deepseek.com` is where the
  request goes — the gateway never sees it.

This instance is configured for **DeepSeek V4.1 Flash on DeepSeek's own platform
API**, not a reseller:

```
model_id    deepseek-flash-api-deepseek-com
endpoint    https://api.deepseek.com
model_name  deepseek-flash          # what goes on the wire
provider    openai                  # OpenAI-compatible chat completions
```

The key lives in the database, never in this repository. The route is rebuilt at
startup and whenever a custom model changes, so editing the row is enough —
nothing has to be restarted by hand if you go through the API.

## Tests

```bash
clojure -M:dev -e "(require 'shelley.core-test)(clojure.test/run-tests 'shelley.core-test)"
clojure -M:dev -e "(require 'shelley.loop-test)(clojure.test/run-tests 'shelley.loop-test)"
```

94 tests / 666 assertions, plus a server suite. They pin the bugs found while building this — content `:type` returning from
SQLite as a string (which silently sent empty messages), thinking blocks losing
the signature Anthropic requires on replay, cancellation not actually stopping
the loop, and string-keyed tool arguments.

## Skills and hooks

Skills are `SKILL.md` directories discovered in `resources/skills`,
`~/.config/agents/skills`, `~/.agents/skills`, `~/.config/clj-shelley/skills`
and `./.shelley/skills`. Only names and descriptions enter the system prompt;
the body is read on demand.

```bash
clojure -M:run skill            # list what is discoverable
clojure -M:run skill cat schedule
```

Hooks are executables under `~/.config/shelley/hooks/`; see `HOOKS.md` for the
five hook points and their stdin/stdout contracts.

## Safety

Tool execution runs with the privileges of the process by default, which is what
makes an agent able to install packages, run tests, and fix its own failures.

Setting `SHELLEY_SANDBOX=1` wraps `bash` in bubblewrap instead: the workspace
and `/tmp` are writable, the rest of the filesystem is read-only, and `/tmp` is
private to the command. Network stays shared, because cutting it off breaks
package installs, git remotes, and the model calls tools make.

Do not point either mode at a machine you care about. Do not run it as root.

## Parity tracking

```bash
clojure -M scripts/parity.clj /path/to/boldsoftware/shelley
```

prints implemented-vs-upstream counts for HTTP routes, tools and migrations, and
writes `PARITY-GAPS.md`. The point is that parity is measured rather than
claimed; a renamed route counts as missing so the output is a worklist, not a
verdict.

## What it does

An agent you can watch work. The pieces, and where they live:

| Piece | Namespace |
| --- | --- |
| Turn loop, history, compaction, cancellation | `shelley.loop`, `shelley.compact` |
| Providers (Anthropic, OpenAI chat + Responses, Gemini, scripted fixture) | `shelley.llm`, `shelley.llm.*` |
| Tools: bash (background jobs), patch, files, browser, subagent, MCP, skills, iframes | `shelley.tools*` |
| SQLite persistence, 49 migrations matching the original's schema | `shelley.db` |
| HTTP + SSE API, 138 routes | `shelley.server*` |
| Web UI (buildless Vue 3) | `resources/index.html`, `resources/ui/` |
| CLI: serve, chat, ls, show, new, models, skill | `shelley.cli`, `shelley.main` |
| User hooks, agent skills, sandboxing, channels | `shelley.hooks`, `shelley.skills`, `shelley.sandbox`, `shelley.channels` |
| Side conversations that do not pollute the transcript ("btw") | `shelley.btw` |

`PARITY.md` tracks this against the Go original line by line, including the
parts that are deliberately not ported and why.
