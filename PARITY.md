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
| OpenAI Responses API | `llm/oai` | todo |
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
| CLI: upgrade, db lock, highlights, exescroll | yes | partial (db lock, skill, hooks) |
| Persistent dtach terminals | yes | todo |
| Sandboxing of tool execution | yes | partial (opt-in bwrap, `SHELLEY_SANDBOX=1`) |
| Slack / channel integrations | yes | partial (webhook out, signed events in, exe.dev notify) |
| Docker/container harness | yes | todo |
