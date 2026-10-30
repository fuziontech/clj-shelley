# Parity matrix

Target: `boldsoftware/shelley` (~187k lines Go, ~75k TS/Vue, 86 server files,
49 migrations, ~40 tools). This file tracks the Clojure reimplementation
against it. Status legend: **done**, **partial**, **todo**.

Last updated by the agent building this; if it disagrees with the code, the code
wins — and the row is a bug.

## Agent loop

| Feature | Original | Here |
| --- | --- | --- |
| Streaming deltas to UI | `OnStreamDelta` | done |
| Parallel tool execution per round | errgroup | done |
| Cancel mid-turn | context cancellation | done |
| Queue user messages mid-turn | `QueueUserMessage` | done |
| Inject messages between rounds | `InjectMessages` | done |
| Auto-compaction near context limit | `compact_in_place`, idle compaction | partial (auto at 80%, tool not exposed) |
| Error messages as first-class rows | `error` message type | done |
| Usage + cost accounting | `usage_data`, `/api/model-costs` | partial (usage.clj prices; `/api/usage`) |
| Interrupted-turn repair | `insertMissingToolResults` | done |
| Git state tracking per turn | `gitstate` | todo |
| Retry with backoff + retry-after | `llm.RetryAfter` | done |

## LLM layer

| Feature | Original | Here |
| --- | --- | --- |
| Anthropic messages | `llm/ant` | done |
| OpenAI chat completions | `llm/oai` | done |
| OpenAI Responses API | `llm/oai` | `llm/openai_responses.clj` — needed, not optional: reasoning models refuse tools on chat completions |
| Gemini | `llm/gem` | done |
| Deterministic test provider | `llm/predictable` | done (`fixture`) |
| Thinking levels | `ThinkingLevel` | done |
| Prompt caching | cache flags on tools/last message | done |
| Citations | `citations.go` | partial (blocks pass through) |
| Retry-after honoured | done in `retry_after.go` | done |

## Tools (original ~40 incl. per-skill)

| Tool | Original | Here |
| --- | --- | --- |
| bash | yes | done |
| bash background jobs | `bashjob.go` | todo |
| patch | yes | done |
| change_dir | yes | done |
| output_iframe | yes | done |
| read_image | yes | done |
| llm_one_shot | yes | done |
| subagent (async, injects completion) | yes | partial (blocking) |
| browser (navigate/eval/screenshot/emulate/network/accessibility/profile) | CDP | todo |
| compact_in_place | yes | todo |
| message_user | yes | todo |
| skills (`skill cat`, discovery, prompt listing) | yes | done |
| MCP server tools | yes | todo |
| file finder / directory listing | server-side | done |

## Persistence

| Feature | Original | Here |
| --- | --- | --- |
| Conversations + messages, append-only | 49 migrations | done |
| Full-text search over messages | FTS5 | todo |
| Slugs | yes | todo |
| Archived conversations | yes | todo |
| Tags | yes | todo |
| Per-conversation options (model, thinking, cwd) | yes | partial |
| Custom models + providers | yes | todo |
| Profiles | yes | todo |
| Drafts, queued messages | yes | todo |
| Background jobs | yes | todo |
| Feature flags | yes | todo |
| Settings | yes | todo |
| Migrations by name | yes | todo |

## Server API

| Endpoint | Original | Here |
| --- | --- | --- |
| `GET /api/conversations` (paging + `q`) | yes | partial |
| `GET /api/conversation/<id>` | yes | done (`/api/conversations/:id`) |
| `GET /api/conversation/<id>/stream` (replay + live) | yes | partial |
| `POST /api/conversation/<id>/chat` | yes | done (`.../messages`) |
| Cancel / archive / slug / tags | yes | partial |
| change_dir, git diffs, worktrees | yes | todo |
| find-files, list-directory, file read/write | yes | todo |
| Image upload, message images | yes | todo |
| Exec/terminal websocket (`/api/exec-ws`) | yes | todo |
| MCP server management | yes | todo |
| Model providers / costs / custom models | yes | todo |
| Integrations (Slack, notify) | yes | todo |
| Cache key, debug endpoints | yes | todo |

## UI

| Feature | Original | Here |
| --- | --- | --- |
| Dark/light theme, responsive layout | yes | done |
| Export conversation to markdown | yes | done |
| Conversation list + search | Vue | done (server `q=` was ignored — see server row) |
| Streaming transcript | Vue | done |
| Markdown + syntax highlighting | shiki | done (highlight.js instead of shiki) |
| Diff viewer for edits | yes | done |
| Tool call coalescing/rendering | yes | done |
| File finder, directory picker | yes | done (⌘P, ⌘⇧G) |
| Model picker, settings, profiles | yes | done |
| Image paste/attach | yes | partial (needs `/api/upload` wired) |
| Terminal pane | yes | done (tmux-backed, ⌘J) |
| Subagent/btw views, TOC | yes | partial (subagent nesting; btw service exists, no route) |
| Keyboard palette | yes | done (⌘K) |
| i18n | yes | todo |

## Platform

| Feature | Original | Here |
| --- | --- | --- |
| User hooks (`~/.config/shelley/hooks/*`) | 5 hooks, stdin/stdout | done |
| Agent Skills discovery + prompt listing | `skills/` | done |
| CLI: serve/run | yes | done |
| CLI: upgrade, db lock, highlights, exescroll | yes | partial (lock, skill, hooks, models, port-file) |
| Persistent terminals (dtach) | yes | done (tmux: survives the client, replays scrollback) |
| Sandboxing of tool execution | yes | done (opt-in bwrap, `SHELLEY_SANDBOX=1`) |
| Slack / channel integrations | yes | partial (webhook out, signed events in, exe.dev notify; not routed) |
| Docker/container harness | yes | todo |

## Known gaps (honest list, from the server workstream's final report)

Everything below answers a route that the original also answers. None of it is
silently missing: each returns an explicit error or an empty result with a note,
except where stated.

| Area | State |
| --- | --- |
| Live sessions / live calls (`/live-session`, `/live-message`, `/call/:id/transcript`) | **stub** — 501 or empty payload. The original streams a live voice call through these. |
| MCP OAuth (`/mcp/login/:id`, `/mcp/oauth/callback`), `POST /:name/call` | **stub** — 501. stdio and streamable-HTTP servers with static credentials work end to end through the `mcp` tool; interactive OAuth does not. |
| `POST /api/models/providers` (custom endpoint discovery) | **shallow** — the gateway catalogue is live-fetched and cached; per-provider pagination is not implemented. |
| `/upgrade`, `/upgrade-headless-shell` | **501** — replacing your own binary from inside the process is a package manager's job. |
| `/exit` | returns `{status:"not_supported"}` — a library should not signal its host. |
| `/debug/pprof*`, `/debug/channels/gateway/*` | **stub** — JVM profiling is not Go pprof; use `jcmd`/JFR. `/debug/histograms`, `/stylebook`, `/mcp`, `/conversation-stream`, `/loremipsum` are real. |
| Turn hooks | the hook *runner* executes on demand and the config is stored; only status-bar hooks run inline during a turn. |
| `readonly` subagents | `bash` is in the read-only toolset because the original puts it there — a read-only subagent can therefore still write through a shell. |
| Event-sourced list stream (`/api/stream2`) | real (history + RFC-6902 patches + 25s ping), but the UI polls instead; both work. |

Deviations that are deliberate, not gaps:

* Rows carry both `id` and `conversation_id`, so a client written against either
  the original or API.md finds its key.
* `llm_data` is a JSON **string** with inline base64 replaced by
  `{displayImageUrl: ...}`; the bytes are still in the row and served from
  `/api/message/:id/image/:i/:sub`.
* SSE frames carry their type **inside** the JSON and never use `event:` lines, so
  a plain `EventSource.onmessage` sees everything. `id:` is `seq:<n>`, and
  `Last-Event-ID`, `?last_event_id=`, `?last_sequence_id=` and `?tail=` are
  honoured (`tail` together with `last_sequence_id` is a 400).
* Errors are always `{"error": "..."}` JSON. No HTML error page ever reaches an
  API client.
