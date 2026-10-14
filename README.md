# craftleste

> A Fabric mod that runs the genuine Celeste (2018) inside Minecraft, rendered in an embedded Chromium browser on a flat in-game GUI screen.

[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)

## Description

A Fabric mod for Minecraft 26.2 that runs the genuine Celeste (2018) from inside Minecraft on an in-game GUI screen. The player supplies their own Celeste.exe (FNA build) plus the Content folder; the mod copies them into the Minecraft instance, fetches the Webleste wasm runtime at runtime, serves both over a local cross-origin-isolated HTTP server, and renders the game in an embedded Chromium browser (Rinku) on a flat, framed GUI window with Minecraft input mapped through. No Celeste code, assets, FMOD binaries, or Webleste build files ship in the jar or the repo.

## Table of Contents

- [Installation](#installation)
- [Usage](#usage)


- [License](#license)

## Installation

Players need their own copy of Celeste 1.4.0.0 (FNA build) — no game code, assets, or runtimes ship with this mod.

1. Install Minecraft 26.2 with Fabric Loader 0.19.5 and fabric-api 0.161.0+26.2.
2. Drop `craftleste.jar` (and the prerequisite Cloth Config) into the `mods/` folder.
3. On first run, point the mod at your Celeste executable or its `Content` folder; the Webleste runtime is fetched once from its official origin.

Building from source:

```bash
git clone https://github.com/CoderMan73/craftleste
cd craftleste
./gradlew build   # jar lands in build/libs
```

## Usage

1. Launch the Minecraft client with the mod installed.
2. Open the craftleste screen with its keybind (configurable via Cloth Config / Mod Menu).
3. On first launch, supply your Celeste files; the game runs on the flat, framed GUI window with Minecraft mouse/keyboard input mapped into it.

Development and contributing setup lives in [CONTRIBUTING.md](CONTRIBUTING.md); the milestone plan lives in [ROADMAP.md](ROADMAP.md).

## Security

Security policy to be added; see SECURITY.md when available.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, coding conventions, and the pull request process.

## License

GNU General Public License v3.0 - see [LICENSE](LICENSE) for details.

## Credits

- Standard Readme spec - https://github.com/RichardLitt/standard-readme
- shields.io - https://shields.io/

> Guidance from `references/readme-guide.md` (standard-readme + shields.io). This README is a
> starting skeleton; enrich from PLAN.md before publishing.
