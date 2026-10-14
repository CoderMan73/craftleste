# Prompt Guide — craftleste

Copy-paste any of these prompts into a fresh agent session on this repository to start work.
All context is already in the repo (`AGENTS.md`, `ROADMAP.md`, `ai-docs/`, and `PLAN.md` in
the tracking workspace — its path is in `AGENTS.md` > Context Loading). The `/goal` forms let
an agent run autonomously and resume across turns.

## 1. "Figure out what needs to be done"

> Read `AGENTS.md`, `ROADMAP.md`, and the tracking-workspace `PLAN.md`. Report the next 3
> concrete, ordered steps the project needs before any implementation, and name the
> dependency for each. Do not start building until I confirm.

## 2. Start one milestone autonomously (`/goal`)

> Replace the milestone text, then run as a `/goal` so it resumes across turns:

```
/goal Implement M1 from ROADMAP.md "Current Objectives": "<exact milestone title>".
Read AGENTS.md and PLAN.md for constraints (Handoff Boundary, Boundaries > Never), make all
autonomous progress you reasonably can, then report a concrete status (what landed, what is
broken, what needs a decision). Check in at blockers. Do not commit without asking.
```

Example for craftleste:

```
/goal Implement "Scaffolding and dependency integration" — scaffold the Minecraft 26.2 Fabric mod
project with Loom and confirm Rinku resolves in a dev run. Read AGENTS.md and PLAN.md, do all
autonomous work, and report a concrete status on completion or blocker.
```

## 3. Continue implementation autonomously

```
/goal Continue implementing PLAN.md milestones in ROADMAP.md "Current Objectives" order.
Respect AGENTS.md (Boundaries, Key Commands, Gotchas), verify each step with a build/test,
and report a concrete status at the end. Do not commit without asking.
```

## 4. Review the scaffold

```
This repository was just scaffolded by the plan-to-active skill. Open AGENTS.md, ROADMAP.md,
README.md, CONTRIBUTING.md, and docs/PROMPT_GUIDE.md, and list exactly which TODOs or
placeholder lines remain before first development work can begin. Number each item.
```

## Notes for the user

- To resume an autonomous agent later, hand it the same `/goal` prompt; the agent stores state
  and continues from where it left off.
- To change direction, state a new `/goal` in the same session or open a fresh session.
- Prefix any prompt with the project name ("craftleste") so the session titles itself after the
  project rather than after the skill.
