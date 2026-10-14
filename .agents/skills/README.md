# craftleste - working directory

This directory holds repo-local Kilo Code skills for craftleste.

## Using a local skill

Any folder under `.agents/skills/` that contains a `SKILL.md` is discoverable by Kilo Code
at task start. Skills in this directory override user-level (global) skills of the same name.

## Adding a new local skill

1. Create a new folder, e.g. `.agents/skills/my-checklist/`.
2. Add a `SKILL.md` (frontmatter with `name`/`description` + instructions).
3. Optionally add `scripts/` and `references/`.

Name skills descriptively and keep each to a single purpose.

## Trust

When starting work on a freshly cloned repository, review these skills before relying on them.

> Guidance from `references/local-skills-guide.md`.
