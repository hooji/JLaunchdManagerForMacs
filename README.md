# ServicePalForJava

A clean, immutable-first Java API for **creating and managing OS-level background
services / daemons** with one uniform surface across **macOS (launchd), Linux
(systemd & OpenRC), and Windows (SCM + Task Scheduler)**.

> Repo is still named `JLaunchdManagerForMacs` (historical); it will be renamed
> *ServicePalForJava*. Root package is `com.u1.servicepal`.

## Status

Design is complete (see `docs/`). **All four backends are implemented** end-to-end —
discovery, inspection, and mutation:

| Area | macOS | systemd | OpenRC | Windows |
|------|:----:|:-------:|:------:|:-------:|
| **Discovery / inspection** (`list`, `read`, `status`) | ✅ | ✅ | ✅ | ✅ |
| **Mutation** (`install`, `start`, `enable`, …) | ✅ | ✅ | ✅ | ✅ |

- **macOS** — `.plist` files + `launchctl`.
- **Linux/systemd** — `.service` units + `systemctl` (`.timer` scheduling deferred).
- **Linux/OpenRC** — `/etc/init.d` scripts + `rc-service`/`rc-update` (SYSTEM_WIDE only).
- **Windows** — SCM via a bundled **pure-Java FFM service host** (no shipped binaries) for
  daemons, and **Task Scheduler** for scheduled jobs (SYSTEM_WIDE only in v1).

## Build & test

```sh
mvn verify
```

Requires **JDK 25+**: the Windows backend uses the `java.lang.foreign` (FFM) API, final in
JDK 22 — 25 is the first LTS with it final. The macOS/systemd/OpenRC paths are pure
subprocess + file I/O and remain 21-compatible, but the single jar targets 25.

**Native access (Windows).** FFM is a restricted operation under JEP 472, so an app that
uses the Windows backend must launch the JVM with `--enable-native-access=ALL-UNNAMED`
(otherwise you get a runtime warning today, an error in a future release). The service
host's own command line includes this flag automatically.

## Try the discovery CLI

```sh
mvn -q package
java -jar target/servicepal.jar            # list all discovered services
java -jar target/servicepal.jar --managed  # only services ServicePal created
```

It detects the platform and enumerates services: launchd jobs (macOS), `.service` units
(systemd), `/etc/init.d` scripts (OpenRC), or the services/tasks ServicePal knows about
(Windows), each enriched with live state. In an init-less container (no supported service
manager) it fails fast with a clear message.

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
