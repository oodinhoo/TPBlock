# Contributing

Thanks for helping. This page says how to get a change into TPBlock.

## Before you start

- Bugs and ideas go through the [issue forms](https://github.com/oodinhoo/TPBlock/issues/new/choose).
  For a sizeable change, open an issue first so we agree on the approach before you write code.
- Security problems never go in a public issue: see [SECURITY.md](SECURITY.md).

## Build and test

Requires **JDK 25**.

```powershell
.\gradlew.bat build      # Windows
./gradlew build          # Linux, macOS
```

The build must pass with no compiler warning. Drop the jar from `build/libs/` into a
**Paper 26.2** server to try your change, and say in the pull request how you checked it in game.

## Code

- Match the surrounding code: Java 25, one flat package `fr.tpblock`, same naming and comment style.
- A deliberate shortcut with a known limit gets a `ponytail:` comment that names the limit and the
  way out.

## Commits and pull requests

- Branch from `main`, one topic per pull request.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/): `feat:`,
  `fix:`, `refactor:`, `perf:`, `test:`, `docs:`, `build:`, `chore:`, then a short summary in the
  imperative.
- The CI must pass. Pull requests are squash-merged: the title becomes the commit on `main`.

By contributing, you agree that your contribution is licensed under the [MIT License](LICENSE) and
that you follow the [Code of Conduct](CODE_OF_CONDUCT.md).
