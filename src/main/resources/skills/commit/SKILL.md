---
name: commit
description: Inspect changes and create a focused conventional commit
allowedTools: [Bash, ReadFile, Glob, Grep]
mode: inline
---
Inspect git status, git diff (unstaged) and git diff --cached (staged) before proposing a commit.
Respect existing permission checks and repository instructions. Do not discard unrelated work.
Inspect both staged and unstaged files; confirm the intended scope when unrelated files are already staged.
Exclude secrets, credentials and sensitive files. Stage each intended file explicitly with git add -- <file>.
Never use git add -A. If more than 10 files changed, suggest splitting the work into focused commits.
Use conventional commit format, an English title no longer than 72 characters, explaining the purpose.
Run relevant checks, commit only the intended changes, then inspect status and report the commit hash.
Additional user guidance: $ARGUMENTS
