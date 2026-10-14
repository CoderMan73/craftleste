# Contributing to craftleste

Thank you for your interest in contributing to craftleste! We welcome contributions from the community.

## Table of Contents

- [Getting Started](#getting-started)
- [How to Report a Bug](#how-to-report-a-bug)
- [How to Suggest Enhancements](#how-to-suggest-enhancements)
- [Development Environment](#development-environment)
- [Coding Conventions](#coding-conventions)
- [Pull Request Process](#pull-request-process)

## Getting Started

See the [README.md](README.md) for installation and usage instructions. craftleste is a Fabric mod for Minecraft 26.2 that runs the genuine Celeste (2018) from inside Minecraft on an in-game GUI screen: the player supplies their own Celeste.exe (FNA build) plus the Content folder, the mod fetches the Webleste wasm runtime at runtime, serves both over a local cross-origin-isolated HTTP server, and renders the game in an embedded Chromium browser (Rinku) on a flat, framed GUI window with Minecraft input mapped through. No Celeste code, assets, FMOD binaries, or Webleste build files ship in the jar or the repo.

## How to Report a Bug

1. Check existing issues on the repository to avoid duplicates.
2. Open a bug report using the issue template.
3. Include: expected behavior, actual behavior, reproduction steps, environment details.

## How to Suggest Enhancements

- Use the feature request/enhancement template.
- Encourage testing of suggested enhancements before review.

## Development Environment

1. Install prerequisites: Java 25 (compile toolchain) and a Git client. Everything else (Fabric Loom, dependencies) comes through the Gradle wrapper.
2. Clone the repository:
   ```bash
   git clone https://github.com/CoderMan73/craftleste
   cd craftleste
   ```
3. Generate IDE configuration:
   ```bash
   ./gradlew genIntelliJ
   ```
4. Run a dev client to test changes:
   ```bash
   ./gradlew runClient
   ```
5. Run tests:
   ```bash
   ./gradlew check
   ```

Windows notes: chain PowerShell commands with `;` (5.1 has no `&&`), and existing files are CRLF.

## Coding Conventions

- **Commit messages**: Conventional Commits (`feat:`, `fix:`, `docs:`, `ci:`, ...), subject in the imperative mood, one change per commit. First line should be a concise summary (limit 50 characters). Wrap the body at 72 characters. Bodies that record a dead end are the point of the history; reference issues and PRs.
  ```bash
  feat: add local HTTP server for Webleste serving

  Serves the Webleste port and player assets with COOP/COEP/CORP headers on localhost. Closes #12.
  ```
- **Code style**: Follow standard language conventions. Write self-documenting code; avoid inline comments unless explaining non-obvious behavior.

## Pull Request Process

1. Fork the repository.
2. Create a branch: `git checkout -b feat/my-feature`
3. Make your changes and ensure tests pass.
4. Commit your changes with a clear, imperative message.
5. Push to your branch: `git push origin feat/my-feature`
6. Open a pull request and address review feedback.

> Guidance from `references/contributing-guide.md`. Fill from PLAN.md before publishing.
