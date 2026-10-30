---
name: commit-highlights
description: Use when the user or project guidance (AGENTS.md) asks for "commit highlights" or "highlights". Ranks a commit's changed files by importance and pins short comments to notable lines, stored as a git note and shown in Shelley's diff view.
---

Commit highlights are a lightweight reviewer's guide to one commit, stored as
JSON in the git note ref `shelley-highlights`. Shelley's diff view lists the
commit's files in the given order instead of alphabetically, and marks each
highlighted line with its comment.

## Workflow

Write highlights right after making a commit.

1. Start from a scaffold listing every changed file:

   ```
   shelley highlights scaffold [-C <repo-dir>] <commit> > highlights.json
   ```

2. Edit it:

   ```json
   {
     "version": 1,
     "files": ["server/model.go", "server/handler.go", "server/handler_test.go", "go.sum"],
     "highlights": [
       {"file": "server/model.go", "line": 42, "comment": "Nullable: old rows predate this column."},
       {"file": "server/handler.go", "line": 118, "comment": "Holds the lock across the write so readers never see a half-built index."}
     ]
   }
   ```

   - `files` must list every changed file exactly once (the new path for
     renames), most important first.
   - Each highlight's `line` counts from 1 in the file as of the commit, so
     deleted files can't carry highlights.
   - A comment is plain text, one line, at most 200 characters.

3. Verify, then attach:

   ```
   shelley highlights verify [-C <repo-dir>] <commit> highlights.json
   shelley highlights attach [-C <repo-dir>] <commit> highlights.json
   ```

   `attach` verifies and writes the note; re-running overwrites it.
   `shelley highlights show <commit>` prints the stored note. Amending changes
   the commit hash; re-attach afterward.

## Choosing the order and highlights

- Order files by where a reviewer should spend attention: data model, schema,
  and API first; then core logic; then tests; then generated files, lock
  files, and boilerplate.
- Highlight only what deserves a second look: a non-obvious decision, a
  subtle invariant, a risky edge case, a place you were unsure of. A handful
  per commit is typical; none is fine for a trivial commit.
- Point at the exact line the comment is about, usually a changed line.
- Say why or what to notice; never restate the code.
