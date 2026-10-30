# Hooks

Place an executable at `~/.config/shelley/hooks/<name>` (or set
`SHELLEY_HOOKS_DIR`) and Shelley will run it at the matching point. Hooks talk
plain stdin/stdout, so they can be shell, Python, or anything else.

| Hook | stdin | stdout | on failure |
|---|---|---|---|
| `system-prompt` | rendered system prompt | replacement prompt | turn is aborted |
| `new-conversation` | JSON | JSON of fields to override | creation is aborted |
| `chat-message` | JSON | JSON with `message` | send is aborted |
| `end-of-turn` | JSON | ignored | logged only |
| `status-bar` | JSON | JSON `{body,bgcolor,color}` | error shown in the row |
| `slash/<cmd>` | JSON + env | the message that replaces `/cmd …` | error shown to the sender |

A hook that fails aborts its operation on purpose: a hook that quietly stopped
running removes the guarantee it was installed to provide. `end-of-turn` is the
exception because nothing is left to abort.

Hooks time out after 30 seconds and are killed.

All five run inside a turn now, not just `status-bar`: `system-prompt` before
the first model call, `new-conversation` and the slash commands as a message
arrives, `chat-message` on follow-ups, `end-of-turn` when the turn ends.
Secrets are removed from the `headers` a hook sees — `cookie`, `set-cookie`,
`authorization` and `proxy-authorization` never reach the script.

## Slash commands

Put an executable at `~/.config/shelley/hooks/slash/<command>`. A message whose
first word is `/<command>` then runs it instead of going to the model:

| Output | Effect |
|---|---|
| text | replaces the message and the turn runs with it |
| nothing | the command is handled; **no turn runs** |
| non-zero exit | the error is reported; the message is not sent |

The hook gets `{command, args, raw_message, conversation_id, is_new_conversation,
cwd, model, user_email}` on stdin, and the same values in the environment as
`SHELLEY_SLASH_COMMAND`, `SHELLEY_SLASH_ARGS`, `SHELLEY_CONVERSATION_ID`,
`SHELLEY_CWD`, `SHELLEY_MODEL`, `SHELLEY_USER_EMAIL`, because a shell script is
easier to write that way.

```bash
#!/bin/bash
# ~/.config/shelley/hooks/slash/deploy
# /deploy <env> — print the command the agent should run, or do it yourself and
# print nothing.
case "$SHELLEY_SLASH_ARGS" in
  staging) echo "Deploy to staging: run ./deploy.sh staging and report the URL." ;;
  *) echo "Usage: /deploy staging|prod" ;;
esac
```

## Examples

Rewrite every system prompt to add a repository convention:

```bash
#!/bin/bash
# ~/.config/shelley/hooks/system-prompt
cat
printf '\n\nAlways run `make test` before claiming a change works.\n'
```

Refuse to send messages containing a secret:

```bash
#!/bin/bash
# ~/.config/shelley/hooks/chat-message
in=$(cat)
case "$in" in
  *sk-live-*) echo "refusing to send an API key" >&2; exit 1 ;;
esac
jq -c . <<<"$in"
```

Show the branch and dirty file count in the conversation's status bar:

```bash
#!/bin/bash
# ~/.config/shelley/hooks/status-bar
jq -c '{}' >/dev/null
branch=$(git branch --show-current 2>/dev/null)
dirty=$(git status --porcelain 2>/dev/null | wc -l)
printf '{"body":"%s · %s changed","bgcolor":"#1f6feb","color":"#fff"}\n' "${branch:-no branch}" "$dirty"
```
