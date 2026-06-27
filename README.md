# ServicePalForJava

A clean, immutable-first Java API for **creating and managing OS-level background
services / daemons** with one uniform surface across **macOS (launchd), Linux
(systemd & OpenRC), and Windows (SCM + Task Scheduler)**.

> Repo is still named `JLaunchdManagerForMacs` (historical); it will be renamed
> *ServicePalForJava*. Root package is `com.u1.servicepal`.

## Status

Design is complete and approved (see `docs/design/api-design.md`). Implementation is
in progress, one platform backend at a time:

| Area | macOS | systemd | OpenRC | Windows |
|------|:----:|:-------:|:------:|:-------:|
| **Discovery / inspection** (`list`, `read`, `status`) | ✅ | ✅ | ✅ | ⬜ |
| **Mutation** (`install`, `start`, `enable`, …) | ✅ | ✅ | ✅ | ⬜ |

The current build implements the **macOS launchd**, **Linux systemd**, and **Linux
OpenRC** backends end-to-end — discovery, inspection, and mutation (`install`/`uninstall`,
`start`/`stop`/`restart`, `enable`/`disable`) — plus the full cross-platform model,
platform detection, and two CLIs. The remaining backend (**Windows**) reports its
platform and capabilities but throws a clear `UnsupportedOperationException` on use until
implemented.

Known limitations:

- systemd **scheduled jobs** (`.timer` units) are deferred, so systemd reports
  `calendar`/`interval` capabilities as `false` and a scheduled spec fails fast there.
- OpenRC is **SYSTEM_WIDE-only** (no per-user services) and has no native scheduler, so it
  reports `perUserInstall`/`calendar`/`interval` as `false`; supervised restart maps to
  `supervise-daemon`, which respawns on any exit (ON_FAILURE and ALWAYS coincide).

## Build & test

```sh
mvn verify
```

Requires JDK 21+ for now (the implemented macOS/systemd paths are subprocess + file I/O,
no FFM). The baseline rises to **JDK 25** when the Windows FFM service host lands (FFM is
final in JDK 22; 25 is the first LTS with it final).

## Try the discovery CLI

```sh
mvn -q package
java -jar target/servicepal.jar            # list all discovered services
java -jar target/servicepal.jar --managed  # only services ServicePal created
```

- On **macOS** it enumerates the launchd jobs in `~/Library/LaunchAgents`,
  `/Library/LaunchDaemons`, and `/Library/LaunchAgents`, enriched with live state from
  domain-targeted `launchctl print`.
- On **Linux/systemd** it enumerates units in the user (`--user`) and system managers,
  enriched with live state from `systemctl show`.
- On **Linux/OpenRC** it enumerates init scripts in `/etc/init.d`, enriched with live state
  from `rc-service <name> status`.
- On platforms whose backend is not implemented yet (Windows) it prints a friendly
  "coming next" note and exits 0.

A second CLI, `com.u1.servicepal.cli.SelfTestCli`, runs a real install→start→uninstall
lifecycle against the live OS; the CI probe uses it to validate mutation on real systems.

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
- `docs/design/windows-implementation-plan.md` — the Windows backend build plan.
- `docs/ROADMAP.md` — deferred items.
- `src/main/java/com/u1/servicepal/` — the library: the public surface
  (`ServiceManager`, `model/`, `model/options/`) and the per-platform backends
  (`internal/macos`, `internal/systemd`, …).
- `CLAUDE.md` — project knowledge base for contributors and AI agents.
