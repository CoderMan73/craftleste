# ai-docs

This directory holds AI field notes, architectural decisions, and lessons learned between
agent sessions. It is **not** human-facing documentation; see `docs/` for that.

## How to record

Each entry should be a short, self-contained file written as if the next agent has no prior
context. Include:

- **Problem**: what was being attempted or what failed.
- **Solution**: what worked (or what did not).
- **Why it matters**: the insight that prevents re-deriving the same answer.

## Conventions

- Use a descriptive filename, prefixed with a date when time-order matters
  (e.g., `2026-craftleste-rinku-api-confirmed.md`).
- Start the file with a one-line summary on the first line.
- Link to PLAN.md sections, RESEARCH.md gaps, or specific source files by path.

## Subdirectories

- `decisions/` - Lightweight architectural decision records (ADRs).
- `notes/` - Debugging discoveries and non-obvious fixes.

Do not store large logs here; point to the file path or paste the relevant excerpt.

> Guidance from `references/agents-guide.md` (ai-docs / Field Notes pattern).
