# clj-shelley HTTP/SSE API

The contract between the server, the web UI, and any other client. Modelled on
the original's `API.md`; where this differs, this file is what our own code
implements.

All routes are under `/api` unless noted.

## Version and health

- `GET /version` → `{tag, commit, capabilities: [...]}`. Capabilities advertise
  optional features: `thinking-levels`, `queued-messages`, `compaction`,
  `background-jobs`, `mcp`, `subagents`.
- `GET /api/health` → `{status, model}`.

## Conversations

Unless noted, archived conversations are excluded.

- `GET /api/conversations?limit=&offset=&q=` → `{conversations: [row]}`.
  `q` runs full-text search over message text.
- `GET /api/conversations/archived` → archived rows.
- `GET /api/conversations/snapshot` → `{conversations: [row-with-state], hash}`.
- `POST /api/conversations/new` → create and start a turn.
  Body: `{message, model, cwd, conversation_options}`.
  `conversation_options` accepts `thinking_level` (`off|minimal|low|medium|high|xhigh|max`),
  `tool_overrides` (`{name: "on"|"off"}`), `disable_all_tools`, `profile`, `system_prompt`.
- `GET /api/conversation/:id` → `{conversation, messages}`; messages carry
  `message_id, sequence_id, type, llm_data, user_data, usage_data, created_at`.
  `llm_data` is a JSON string.
- `GET /api/conversation/:id/stream` → SSE. First event replays the transcript
  (`{type:"history", messages:[...]}`), then live events (below). Survives
  reconnect via `Last-Event-ID`.
- `POST /api/conversation/:id/chat` → `{message}`. If a turn is running the
  message is queued and spliced in before the next model call (`{status:"queued"}`).
- `POST /api/conversation/:id/cancel`.
- `POST /api/conversation/:id/archive` → `{archived: true|false}`.
- `POST /api/conversation/:id/change-dir` → `{path}`.
- `POST /api/conversation/:id/tags` → `{add: [], remove: []}`.
- `GET /api/conversation-by-slug/:slug`.
- `POST /api/conversation/:id/compact` → compact the context now.
- `GET /api/stream2` → unified SSE of conversation-list patches (RFC-6902).

### Row shape (`ConversationWithState`)

`conversation_id, slug, created_at, updated_at, cwd, archived, model, parent_conversation_id,
conversation_options, current_generation, agent_working, user_initiated, title,
working, subagent_count, preview, tags[]` plus optional `git_repo_root, git_commit, git_subject`.

## Events (SSE)

Message and turn events, each with `conversation_id`:

| type | fields |
| --- | --- |
| `text-delta` | `text` |
| `thinking-delta` | `text` |
| `tool-start` | `id, name, input` |
| `tool-progress` | `id, text` |
| `tool-end` | `id, name, is-error, content[], elapsed-ms, ui?` |
| `message-user` | `text, reply_id` (agent-initiated chat) |
| `queued` | `text` |
| `warning` | `text` |
| `error` | `text, retryable` |
| `compaction-start` / `compaction-end` | counts |
| `turn-end` | `status: complete|cancelled|error` |
| `state` | `agent_working` |

## Files and workspace

- `GET /api/find-files?q=&cwd=&limit=`
- `GET /api/list-directory?path=`
- `GET /api/file?path=` → `{path, content, truncated}`
- `PUT /api/file` → `{path, content}`
- `GET /api/git/status?cwd=` → `{repo_root, branch, commit, subject, dirty}`
- `GET /api/git/diff?cwd=&path=` → unified diff text
- `GET /iframe/:id` → a rendered artefact from `output_iframe`.

## Terminals

- `GET /api/exec-ws` (websocket) → `{cmd}` spawns a pty; JSON frames
  `{type:"input"|"output"|"resize"|"exit"}`.

## MCP

- `GET /api/mcp/servers` → configured servers with status.
- `POST /api/mcp/servers` → add/update `{name, command, args, env}`.
- `DELETE /api/mcp/servers/:name`.
- `GET /api/mcp/servers/:name/tools`.
- `POST /api/mcp/servers/:name/call` → `{tool, arguments}`.

## Models, settings, integrations

- `GET /api/model-providers` → providers and available models.
- `GET /api/custom-models`, `POST /api/custom-models`.
- `GET /api/model-costs` → per-model pricing used for usage accounting.
- `GET /api/settings`, `POST /api/settings`.
- `GET /api/profiles`, `POST /api/profiles/:name` (activate).
- `GET /api/feature-flags`, `POST /api/feature-flags/:name`.
- `GET /api/integrations` → attached channels (Slack, notify, …).
- `POST /api/integrations/slack/test`.
- `GET /api/usage?conversation=` → tokens and cost for a conversation.
