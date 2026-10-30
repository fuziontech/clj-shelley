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
| Queue user messages mid-turn | `QueueUserMessage` | todo |
| Inject messages between rounds | `InjectMessages` | todo |
| Auto-compaction near context limit | `compact_in_place`, idle compaction | todo |
| Error messages as first-class rows | `error` message type | todo |
| Usage + cost accounting | `usage_data`, `/api/model-costs` | partial |
| Interrupted-turn repair | `insertMissingToolResults` | done |
| Git state tracking per turn | `gitstate` | todo |
| Retry with backoff + retry-after | `llm.RetryAfter` | partial |

## LLM layer

| Feature | Original | Here |
| --- | --- | --- |
| Anthropic messages | `llm/ant` | done |
| OpenAI chat completions | `llm/oai` | done |
| OpenAI Responses API | `llm/oai` | todo |
| Gemini | `llm/gem` | todo |
| Deterministic test provider | `llm/predictable` | todo |
| Thinking levels | `ThinkingLevel` | todo |
| Prompt caching | cache flags on tools/last message | todo |
| Citations | `citations.go` | todo |
| Retry-after honoured | done in `retry_after.go` | todo |

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
| skills (`skill cat`) | yes | todo |
| MCP server tools | yes | todo |
| file finder / directory listing | server-side | todo |

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
| Conversation list + search | Vue | partial |
| Streaming transcript | Vue | done |
| Markdown + syntax highlighting | shiki | todo |
| Diff viewer for edits | yes | todo |
| Tool call coalescing/rendering | yes | partial |
| File finder, directory picker | yes | todo |
| Model picker, settings, profiles | yes | todo |
| Image paste/attach | yes | todo |
| Terminal pane | yes | todo |
| Subagent/btw views, TOC | yes | todo |
| Keyboard palette | yes | todo |
| i18n | yes | todo |

## Platform

| Feature | Original | Here |
| --- | --- | --- |
| CLI: serve/run | yes | done |
| CLI: upgrade, db lock, highlights, exescroll | yes | todo |
| Persistent dtach terminals | yes | todo |
| Sandboxing of tool execution | yes | todo (none — tools run as your user) |
| Slack / channel integrations | yes | todo |
| Docker/container harness | yes | todo |
