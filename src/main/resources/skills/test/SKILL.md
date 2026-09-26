---
name: test
description: Detect the project build, run tests and diagnose failures
allowedTools: [Bash, ReadFile, Glob, Grep]
mode: inline
---
Inspect project instructions and build files to detect the build system and relevant test command.
For Maven projects run mvn test; for other builds use the project's documented test command.
Respect permissions. On failure compare assertions, expected behavior and related implementation before
deciding whether the defect is in production code or the test. Report the evidence and relevant paths.
Report test counts and failures from actual output. Report measured coverage only when a coverage report
exists; otherwise explicitly say coverage is 未测量. Never invent coverage percentages.
Additional user guidance: $ARGUMENTS
