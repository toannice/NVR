<!--
SYNC IMPACT REPORT
==================
Version change: [unversioned] → 1.0.0
Modified principles: All new (initial constitution from template)
Added sections:
  - Core Principles (5 principles)
  - Development Workflow
  - AI Assistant Conventions
  - Governance
Templates updated:
  ✅ plan-template.md — Constitution Check section aligns
  ✅ spec-template.md — no changes required
  ✅ tasks-template.md — no changes required
Follow-up TODOs: none
-->

# my-nvr Constitution

## Core Principles

### I. Simplicity First (NON-NEGOTIABLE)

Code MUST be kept clean and simple at all times.
- Prefer the simplest solution that works; complexity MUST be justified
- No premature abstractions, unused parameters, or dead code
- Three similar lines beats a premature helper; extract only when the pattern repeats 4+ times
- YAGNI: do not design for hypothetical future requirements

### II. CLAUDE.md is Always Current

CLAUDE.md MUST be updated to reflect any architectural or workflow change made during a session.
- At the START of every new chat session (or after context is cleared), CLAUDE.md MUST be reviewed and updated first before any other work begins
- CLAUDE.md is the single source of truth for project structure, commands, and conventions

### III. Git Commit Discipline

After every meaningful change (especially CLAUDE.md updates), a git commit MUST be created.
- Commit message format: `edit|update|new|delete : <description of work>`
- Examples: `update : CLAUDE.md with new routing structure`, `new : researcher agent`, `delete : unused docker volume`
- Commits MUST be atomic — one logical change per commit

### IV. Frontend Change Notification

Any change to `frontend/` MUST be announced to the user BEFORE implementation begins.
- State clearly: what will change, which files are affected, and the expected visual/behavioral impact
- Wait for explicit user confirmation before touching any frontend file

### V. Language Convention

All AI assistant responses MUST be written in Vietnamese, regardless of the language used in the user's message.
- Code, file paths, commands, and technical identifiers remain in their original language
- Only natural-language prose is in Vietnamese

## Development Workflow

### Session Start Protocol

1. Review and update `CLAUDE.md` if anything is stale or missing
2. Commit the CLAUDE.md update with `update : CLAUDE.md — session refresh`
3. Then proceed with the session's actual work

### Frontend Change Protocol

1. Announce the planned change (files, impact) to the user
2. Wait for explicit confirmation
3. Implement the change
4. Commit with `update : frontend — <description>`

### Commit Protocol

- Format: `edit|update|new|delete : <the work>`
- Run `git status` before committing to avoid staging unintended files
- Stage specific files by name, not `git add -A`

## AI Assistant Conventions

- Responses: always in Vietnamese
- Code and identifiers: always in original language (English/Kotlin/TypeScript)
- Researcher agent (`researcher.md`): max 500 words, always ends with a clear Recommendation and reason

## Governance

This constitution supersedes all other practices in this repository.
Amendments require:
1. Updating this file with version increment
2. Updating `CLAUDE.md` to reflect any changed workflow
3. A git commit: `update : constitution vX.Y.Z — <summary>`

All code reviews and AI-assisted sessions MUST verify compliance with Principles I–V before closing.

**Version**: 1.0.0 | **Ratified**: 2026-05-05 | **Last Amended**: 2026-05-05
