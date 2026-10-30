---
name: commit-hygiene
description: Use when committing, amending, rebasing, or writing a commit message in a repository you did not create.
when: about to run git commit, git amend, or change history
---

# Commit hygiene

Match the repository, not your habits. Two commands tell you what it wants:

```bash
git log --oneline -20          # message style: tense, prefix, length
git log -3 --stat --format=%B   # body style: what it documents
```

## Rules

1. **One logical change per commit.** A refactor and a behaviour change are
   two commits, even when the diff mixes them. `git add -p` is how you split.
2. **Subject line**: imperative mood, no trailing period, under 72 characters.
   `Fix duplicate tool results on cancel`, not `Fixed duplicates.`
3. **Body**, when the change is not obvious: why, not what. The diff already
   says what. Wrap at 72 columns.
4. **Never commit** secrets, `.env` files, generated artefacts, or editor
   backups. Check `git status` before `git add -A`; prefer naming paths.
5. **Do not amend or force-push** a commit someone else may have based work
   on, unless the user asked for it in this conversation.

## Before you commit

```bash
git diff --staged --stat
```

If the file list surprises you, you staged too much.
