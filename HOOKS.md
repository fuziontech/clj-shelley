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

A hook that fails aborts its operation on purpose: a hook that quietly stopped
running removes the guarantee it was installed to provide. `end-of-turn` is the
exception because nothing is left to abort.

Hooks time out after 30 seconds and are killed.

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
