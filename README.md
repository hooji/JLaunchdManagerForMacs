# ServicePalForJava

A clean, immutable-first Java API for **creating and managing OS-level background
services / daemons** with one uniform surface across **macOS (launchd), Linux
(systemd & OpenRC), and Windows (SCM + Task Scheduler)**.

> Repo is still named `JLaunchdManagerForMacs` (historical); it will be renamed
> *ServicePalForJava*. Root package is `com.u1.servicepal`.

## Status

Design is complete (see `docs/`). Three of the four backends are implemented:

| Area | macOS | systemd | Windows | OpenRC |
|------|:----:|:-------:|:-------:|:------:|
| **Discovery / inspection** (`list`, `read`, `status`) | ✅ | ✅ | ✅ | ⬜ |
| **Mutation** (`install`, `start`, `enable`, …) | ✅ | ✅ | ✅ | ⬜ |

macOS (launchd), Linux/systemd, and Windows are implemented end-to-end — discovery,
inspection, and mutation — alongside the full cross-platform model, platform detection, and a
discovery CLI. On Windows, long-running daemons run as SCM services supervised by a bundled
pure-Java FFM service host, and scheduled jobs go to Task Scheduler. **OpenRC** is the last
backend and throws a clear `UnsupportedOperationException` for now; systemd `.timer` scheduling
is also still pending.

## Build & test

```sh
mvn verify
```

Requires **JDK 25** (the Windows FFM service host uses `java.lang.foreign`, final in JDK 22;
25 is the first LTS with it final). The macOS/systemd paths are subprocess + file I/O and stay
21-compatible, but the single jar builds on 25. On Windows, run consuming apps (and the CLI)
with `--enable-native-access=ALL-UNNAMED` (JEP 472) so the SCM/FFM calls don't warn.

## Try the discovery CLI

```sh
mvn -q package
java --enable-native-access=ALL-UNNAMED -jar target/servicepal.jar            # list all
java --enable-native-access=ALL-UNNAMED -jar target/servicepal.jar --managed  # only ours
```

On macOS it enumerates the launchd jobs in `~/Library/LaunchAgents` and
`/Library/LaunchDaemons`, on systemd the units under `/etc/systemd/system` and
`~/.config/systemd/user`, and on Windows the ServicePal-managed services (sidecars under
`%ProgramData%\ServicePal`), each enriched with live state. On OpenRC it prints a friendly
"not implemented yet" note.

## Releases

Releases are automated (see `.github/workflows/`):

- **Tag Release** (`release.yml`) — on a pushed `v*` tag (or manual dispatch), builds and
  publishes a GitHub Release with `servicepal-<version>.jar` (runnable fat jar) and
  `servicepal-<version>-sources.jar`. The version comes from the tag via `-Drevision`.
- **Bump version and release** (`version-bump.yml`) — when a PR is merged into `main`,
  computes the next version from the latest `v*` tag (patch by default; `release:minor` /
  `release:major` PR labels override; `release:skip` label or `[skip release]` in the title
  opts out), pushes the tag, and dispatches Tag Release. Also runnable manually.

## Layout

- `docs/research/` — per-platform research + the cross-platform synthesis.
- `docs/design/api-design.md` — the API design (the three-tier uniformity model).
- `docs/ROADMAP.md` — deferred items.
- `src/main/java/com/u1/servicepal/` — the library (`ServiceManager`, `model/`, `internal/`).
- `CLAUDE.md` — project knowledge base for contributors and AI agents.
