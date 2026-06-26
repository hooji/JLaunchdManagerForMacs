# CLAUDE.md — JLaunchdManagerForMacs

Project knowledge base for AI agents working on this repo. Keep this current.

## What this is

A small, clean Java library for **creating and managing launchd entries on macOS**.
No UI — API only. The library:

- Writes/reads launchd `.plist` files with regular file I/O.
- Resolves the correct on-disk location using launchd best practices (see below).
- Invokes the `launchctl` binary as a subprocess for load/unload/start/stop/status.
- Serializes/deserializes the Apple property-list format with a dedicated plist library.

**Status:** _Design phase._ The API sketch below is a proposal pending owner approval.
No implementation code has been written yet. Do not start implementing until the
sketch is approved.

## Repo facts

- Single Git repo, working branch: `claude/java-launchd-api-design-zkcty4`.
- GitHub repo: `hooji/jlaunchdmanagerformacs`.
- Started from an empty repository.

## Coding conventions (owner-mandated)

- **`final` by default**: all method arguments and local variables are `final`
  unless mutation is genuinely required.
- Prefer immutable value objects (records / builders) for the domain model.
- Clean, minimal, "dead simple" public surface. Hide launchctl/plist mechanics
  behind a small facade.

## Key technical decisions & research

### 1. Where launchd files go (researched)

launchd distinguishes **Agents** (run on behalf of a logged-in user, may touch the
GUI) from **Daemons** (run system-wide at boot, as root, no GUI). Standard locations:

| Location                        | Kind            | Runs as / when                         | Our scope name  |
|---------------------------------|-----------------|----------------------------------------|-----------------|
| `~/Library/LaunchAgents`        | User agent      | Current user, at their login           | `USER_AGENT`    |
| `/Library/LaunchAgents`         | Global agent    | Every user, at login (admin-installed) | `GLOBAL_AGENT`  |
| `/Library/LaunchDaemons`        | System daemon   | At boot, as root (admin-installed)     | `SYSTEM_DAEMON` |
| `/System/Library/Launch*`       | OS-owned        | **Off-limits** — never write here      | —               |

Writing to `/Library/...` requires admin/root; `~/Library/LaunchAgents` does not.
The library resolves the directory from the chosen scope and the `Label`
(`<dir>/<label>.plist`, e.g. `~/Library/LaunchAgents/com.example.backup.plist`).

### 2. launchctl invocation — use the MODERN subcommands

The legacy `launchctl load/unload/start/stop/list` subcommands are deprecated
(since ~10.10/10.11). Use the domain-aware subcommands:

| Action            | Modern command                                            |
|-------------------|-----------------------------------------------------------|
| Load / install    | `launchctl bootstrap <domain> <plist-path>`               |
| Unload / remove   | `launchctl bootout <domain>/<label>`                      |
| Start / restart   | `launchctl kickstart [-k] <domain>/<label>`               |
| Stop              | `launchctl kill <signal> <domain>/<label>`                |
| Enable / disable  | `launchctl enable|disable <domain>/<label>`               |
| Status / query    | `launchctl print <domain>/<label>`                        |

**Domain targets** (`<domain>`):
- User agent  → `gui/<uid>` (Aqua session; GUI-capable). `<uid>` via `id -u`.
- System daemon → `system`.
- Global agent → `gui/<uid>` per logged-in user.

### 3. Plist format library — use `dd-plist`, NOT Jackson

The owner asked about Jackson 2.x. **Recommendation: use `dd-plist` instead.**

- Apple plists are XML with a DTD-defined `<dict><key>…</key><value/></dict>`
  shape that does **not** map cleanly to Jackson's element/POJO model — you'd be
  writing custom serializers and fighting the format.
- `dd-plist` (`com.googlecode.plist:dd-plist`, latest `1.28`) is purpose-built:
  reads/writes XML **and** binary **and** ASCII plists, tiny, zero-dependency,
  actively maintained. `PropertyListParser` + `NSDictionary`/`NSArray`/etc.
- We keep `dd-plist` confined behind `PlistCodec` so the rest of the code never
  imports it directly (easy to swap later).

If a strong reason emerges to use Jackson anyway, revisit — but the default is
`dd-plist`.

### 4. Build & platform

- **Maven**, Java 17 LTS (records + sealed types available; good fit for
  immutable model). Could move to 21 if useful.
- Runtime obviously requires macOS for the launchctl calls; unit tests stub the
  process runner so they run anywhere. The `launchctl` process layer is behind an
  interface for exactly this reason.

## Proposed package layout

```
com.jlaunchd
├── LaunchdManager        // FACADE — the one class callers normally use
├── LaunchdScope          // USER_AGENT | GLOBAL_AGENT | SYSTEM_DAEMON (dir + domain)
├── LaunchdException      // unchecked; wraps I/O + process failures
├── model
│   ├── LaunchdJob        // immutable job definition; LaunchdJob.builder(label)
│   ├── KeepAlive         // true | { conditions }
│   ├── CalendarInterval  // minute/hour/day/weekday/month (factories: dailyAt, etc.)
│   └── ProcessType       // Background | Interactive | Adaptive | Standard
├── plist
│   └── PlistCodec        // LaunchdJob <-> .plist via dd-plist (only file that imports it)
└── launchctl
    ├── Launchctl         // interface over the launchctl binary (stub in tests)
    ├── DefaultLaunchctl  // ProcessBuilder impl
    └── JobStatus         // installed / loaded / running / pid / lastExitCode
```

### Facade sketch (pending approval)

```java
final LaunchdManager mgr = LaunchdManager.create();

final LaunchdJob job = LaunchdJob.builder("com.example.backup")
        .programArguments("/usr/local/bin/backup", "--daily")
        .runAtLoad(true)
        .startCalendarInterval(CalendarInterval.dailyAt(3, 0))
        .standardOutPath("/tmp/backup.log")
        .standardErrorPath("/tmp/backup.err")
        .build();

mgr.install(job, LaunchdScope.USER_AGENT);          // write plist + bootstrap
mgr.start("com.example.backup", LaunchdScope.USER_AGENT);   // kickstart
final JobStatus st = mgr.status("com.example.backup", LaunchdScope.USER_AGENT);
mgr.uninstall("com.example.backup", LaunchdScope.USER_AGENT); // bootout + delete
```

Facade responsibilities: `install`, `uninstall`, `start`, `stop`, `restart`,
`enable`, `disable`, `status`, `list(scope)`, `read(label, scope)`,
`isInstalled(label, scope)`. Each operation = (resolve path) + (file I/O via
`PlistCodec`) + (one or more `Launchctl` calls).

## Open questions for the owner

1. `gui/<uid>` vs `user/<uid>` for agents — default to `gui` (GUI-capable). OK?
2. Java 17 vs 21 baseline.
3. Should mutating ops throw on failure (proposed) or return result objects?
4. How rich should `JobStatus` be — parsing `launchctl print` output is messy;
   start minimal (installed/loaded/running/pid/lastExitCode)?

## Sources

- launchctl modern subcommands: https://ss64.com/mac/launchctl.html ,
  https://www.alansiu.net/2023/11/15/launchctl-new-subcommand-basics-for-macos/ ,
  https://gist.github.com/masklinn/a532dfe55bdeab3d60ab8e46ccc38a68
- dd-plist: https://github.com/3breadt/dd-plist ,
  https://central.sonatype.com/artifact/com.googlecode.plist/dd-plist
