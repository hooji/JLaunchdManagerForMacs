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
| **Discovery / inspection** (`list`, `read`, `status`) | ✅ | ✅ | ✅ | ✅ |
| **Mutation** (`install`, `start`, `enable`, …) | ✅ | ✅ | ✅ | ✅ |

All four backends are implemented end-to-end — discovery, inspection, and mutation
(`install`/`uninstall`, `start`/`stop`/`restart`, `enable`/`disable`) — behind one
cross-platform model, with runtime platform detection and two CLIs. On Windows, a daemon
runs as a real service via a bundled **pure-Java FFM service host** (which speaks the SCM
control protocol — a plain `java -jar` cannot, failing with error 1053), and a *scheduled*
job is routed to **Task Scheduler** instead. The macOS/systemd/OpenRC paths are validated
on real systems by the CI probe; the Windows FFM host is validated by the probe's
`SelfTestCli` on the `windows-latest` runner.

Known limitations:

- systemd **scheduled jobs** (`.timer` units) are deferred, so systemd reports
  `calendar`/`interval` capabilities as `false` and a scheduled spec fails fast there.
- OpenRC is **SYSTEM_WIDE-only** (no per-user services) and has no native scheduler, so it
  reports `perUserInstall`/`calendar`/`interval` as `false`; supervised restart maps to
  `supervise-daemon`, which respawns on any exit (ON_FAILURE and ALWAYS coincide).
- Windows is **SYSTEM_WIDE-only** in v1 (`perUserInstall` is `false`); discovery is
  scoped to the services ServicePal created (machine-wide enumeration of third-party
  services is a follow-up). Per-platform extras like per-user services and WinSW hosting
  are on the roadmap.

## Build & test

```sh
mvn verify
```

Requires **JDK 25** (the Windows backend uses the Foreign Function & Memory API, final in
JDK 22; 25 is the first LTS with it final). The macOS/systemd/OpenRC code stays
source-compatible with JDK 21, so a lower-JDK Mac/Linux-only build remains a roadmap item.
Consuming apps on Windows must grant native access (`--enable-native-access=ALL-UNNAMED`);
the runnable jar already declares it in its manifest.

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
- On **Windows** it lists the services ServicePal created (from the sidecars in
  `%ProgramData%\ServicePal`), enriched with live state from `QueryServiceStatusEx`.

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
