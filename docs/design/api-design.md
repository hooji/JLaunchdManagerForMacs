# API Design — universal service manager (step 2 proposal)

> **Status:** Proposal for owner approval. No implementation yet. Supersedes the
> single-platform sketch in `CLAUDE.md`. Built on the research in `docs/research/`
> (read `cross-platform-synthesis.md` first).
>
> **Decisions baked in** (owner-approved): pure-Java FFM service host on Windows (bundled,
> runnable, the default path); **JDK 25** baseline; **systemd + OpenRC** both shipped in v1
> as *separate platforms* (no public pluggable SPI); **fail-fast** on capability gaps.
>
> **Code style** (owner-mandated, applies to every sketch below and all real code): `final`
> by default; **no `var`**; **no `Optional`** (use `null`, document nullability; collections
> never null); **no Java Streams**; **tabs** for indentation.

---

## 1. The central question: how uniform can this be?

**Verdict: three tiers.** The design's whole job is to keep Tier 1 huge, Tier 2 clean and
out of the way, and Tier 3 loud and early.

| Tier | What | How it's exposed |
|---|---|---|
| **1. Uniform core** (~90% of real use) | lifecycle verbs; `id`, `command`, env, working dir, **run-as identity**, log files, autostart, restart policy (`NEVER`/`ON_FAILURE`/`ALWAYS`), calendar/interval schedule, status core | the plain `ServiceManager` + `ServiceSpec` — identical on every platform |
| **2. Platform-unique power** | the long tail of per-OS knobs (macOS `ProcessType`, systemd sandboxing/cgroups/ordering, Windows accounts/recovery, OpenRC supervisor) | optional, **typed, namespaced option blocks** (`.mac(...)`, `.systemd(...)`, `.windows(...)`, `.openrc(...)`) — applied on their platform, ignored elsewhere, each with sensible defaults |
| **3. Capability gaps** | things a platform simply cannot do (calendar schedule on OpenRC; per-user identity on OpenRC; conditional keep-alive off systemd/macOS) | `Capabilities` query + **fail-fast** `UnsupportedFeatureException` at `install()` with an actionable message |

So: **yes, the common path is uniform**; the platform-specific bits are neither hidden nor
in your face — they sit in clearly-labelled side rooms you only enter on purpose.

The simple path, identical on macOS, systemd, OpenRC, and Windows:

```java
final ServiceManager mgr = ServiceManager.forThisPlatform();

final ServiceSpec backup = ServiceSpec.builder("com.example.backup")
		.command("/usr/local/bin/backup", "--daily")
		.asSystemDaemon()
		.restart(RestartPolicy.ON_FAILURE)
		.stdout(Path.of("/var/log/backup.log"))
		.autoStart(true)
		.build();

mgr.install(backup);                 // render definition + register with the OS
mgr.start("com.example.backup");     // run now
final ServiceStatus st = mgr.status("com.example.backup");
mgr.uninstall("com.example.backup"); // stop + deregister + delete definition
```

That block compiles to a launchd plist + `launchctl`, a systemd unit + `systemctl`, an OpenRC
script + `rc-service`, or a Windows service (via the bundled Java host) + SCM — with no code
change.

---

## 2. Run-as identity (replaces the old `Scope`)

A service's **identity** answers "as whom, and in whose management domain, does this run?" —
and on every platform the identity choice *implies* the domain, so they are one concept, set
fluently on the builder with a sensible default. (This replaces the earlier `Scope` enum **and**
the separate `runAsUser` field, which were redundant.)

Three cross-platform choices — these cover the core; you didn't miss any:

| Builder call | Meaning | Management domain (implied) |
|---|---|---|
| `.asCurrentUser()` *(default)* | run as the user running the JVM | user-agent domain (no admin needed) |
| `.asUser("www-data")` | system-registered, **drops to** that user | system domain (admin to install) |
| `.asSystemDaemon()` | run as root / `LocalSystem` | system domain (admin to install) |

Per-platform realization:

| Identity | macOS | systemd | OpenRC | Windows |
|---|---|---|---|---|
| current user | agent in `~/Library/LaunchAgents`, `gui/<uid>` | `systemctl --user`, `~/.config/systemd/user/` | **unsupported → fail-fast** (no per-user services) | per-session / current-user context |
| named user | system daemon + `UserName=<name>` | system unit + `User=<name>` | `start-stop-daemon --user <name>` | service account `<name>` (+ secret via `.windows(...)`) |
| system daemon | `/Library/LaunchDaemons`, root | system manager, `User=root` | system runlevel, root | `LocalSystem` |

Backed by a small **inspectable** value type (used by `read()`/status and the renderers; you
normally never touch it directly):

```java
public final class RunAs {
	public enum Kind { CURRENT_USER, NAMED_USER, SYSTEM_DAEMON }

	public static RunAs currentUser();
	public static RunAs namedUser(final String userName);
	public static RunAs systemDaemon();

	public Kind kind();
	public String userName();   // null unless kind() == NAMED_USER
}
```

The builder exposes the fluent shortcuts (`.asCurrentUser()`, `.asUser(String)`,
`.asSystemDaemon()`) plus a `.runAs(RunAs)` setter; they are mutually exclusive (last one
wins). The getter `spec.runAs()` is **never null** (defaults to `RunAs.currentUser()`).

> **Notes.** `asUser` takes a **name** (`String`) — the portable form (`UserName=`/`User=`/
> service account). A numeric uid is *nix-specific and, if ever needed, becomes a detail on
> `.systemd(...)`/`.mac(...)`, not core. Windows `LocalService`/`NetworkService` are lesser
> system accounts exposed via `.windows(...).account(...)`, not the cross-platform core.

---

## 3. The facade — `ServiceManager`

One manager per process; **not** scope-bound (identity lives in the spec). Lifecycle calls
take just the `id`; the manager **resolves the domain** for an `id` by looking in the
current-user domain first, then the system domain (documented; see open question Q1).

```java
public interface ServiceManager {

	// --- construction ---
	static ServiceManager forThisPlatform();   // detect OS + init system
	static ServiceManager forPlatform(final Platform platform);   // explicit (tests/cross-render)

	Platform platform();
	Capabilities capabilities();               // feature queries (see §7)

	// --- definition lifecycle ---
	void install(final ServiceSpec spec);      // UPSERT: create or update + reconcile state
	void uninstall(final String id);           // stop if running + deregister + delete definition
	boolean isInstalled(final String id);

	// --- discovery & inspection (see §5) ---
	List<ServiceStatus> list();                // all services visible in reachable domains
	List<ServiceStatus> listManaged();         // only services THIS library created (marker)
	boolean isManaged(final String id);
	ServiceSpec read(final String id);         // parsed spec, or null if not installed
	String readNative(final String id);        // verbatim plist/unit/script text, or null

	// --- the two orthogonal axes (kept separate; see §3.1) ---
	void enable(final String id);              // start at boot/login (persistence)
	void disable(final String id);
	void start(final String id);               // run now
	void stop(final String id);
	void restart(final String id);

	// --- convenience (the common "do it all" case) ---
	void installEnableStart(final ServiceSpec spec);   // install + enable + start

	// --- query ---
	ServiceStatus status(final String id);     // never null; installed()==false if absent
}
```

Mutating ops **throw** on failure (`ServiceException` hierarchy, §8) — chosen over result
objects for a clean call site; failures here are exceptional and usually unrecoverable
(permission denied, malformed spec, native command failure).

### 3.1 `enable`/`start` are separate; `install` is upsert
`enable`/`disable` (boot persistence) and `start`/`stop` (run now) are **separate verbs** —
the systemd-faithful model (resolves tension **T3**). `install` does *not* implicitly enable or
start; `autoStart` in the spec is the value `enable` writes, and `installEnableStart` is the
combined convenience launchd users expect. **`install` is upsert**: if the service already
exists it rewrites the definition and reconciles registration; otherwise it creates it. Note
that updating a *running* service does not restart the live process — call `restart(id)` to
apply changes (§5.3).

---

## 4. The domain model — `ServiceSpec`

Immutable value object, `final` throughout, built with a builder, **no `Optional`** (absent =
`null`; collections never null). Reverse-DNS `id` is the canonical handle, normalized to each
platform's naming rules internally (kept verbatim where the platform allows — systemd unit
names and Windows service names both tolerate dots).

```java
public final class ServiceSpec {
	// ---- identity ----
	String id();                       // required, e.g. "com.example.backup" (canonical handle)
	String displayName();              // nullable (Windows DisplayName, systemd Description)
	String description();              // nullable

	// ---- what to run (uniform) ----
	List<String> command();            // required, non-empty; program + args; program absolute
	Path workingDirectory();           // nullable
	Map<String,String> environment();  // never null; empty if none
	RunAs runAs();                     // never null; default RunAs.currentUser()  (see §2)

	// ---- I/O (uniform) ----
	Path stdout();                     // nullable (launchd StandardOutPath / systemd file:)
	Path stderr();                     // nullable

	// ---- lifecycle policy (uniform core) ----
	boolean autoStart();               // value written by enable() (start at boot/login)
	RestartPolicy restart();           // never null; default NEVER
	Schedule schedule();               // nullable; non-null => scheduled job (timer/Task/cron)

	// ---- platform escape hatches (Tier 2; all nullable) ----
	MacOptions mac();
	SystemdOptions systemd();
	WindowsOptions windows();
	OpenRcOptions openrc();

	Builder toBuilder();               // derive a builder from this spec (read→modify→install)
	static Builder builder(final String id);
}
```

### 4.1 `RestartPolicy` (the keep-alive core)

```java
public enum RestartPolicy { NEVER, ON_FAILURE, ALWAYS }
```

| Policy | launchd | systemd | Windows (host) | OpenRC |
|---|---|---|---|---|
| `NEVER` | no KeepAlive | `Restart=no` | host doesn't respawn | `start-stop-daemon` |
| `ON_FAILURE` | `KeepAlive={SuccessfulExit=false}` | `Restart=on-failure` | host respawns on non-zero exit | `supervise-daemon` |
| `ALWAYS` | `KeepAlive=true` | `Restart=always` (+`StartLimitIntervalSec=0`) | host respawns always | `supervise-daemon` |

Rich/conditional keep-alive (launchd `Crashed`/`NetworkState`/`PathState`; systemd
`on-watchdog`/`on-abnormal`) lives in the option blocks (Tier 2).

### 4.2 `Schedule` (calendar + interval)

A non-null `schedule` routes the job to the scheduling backend (systemd `.timer`, Windows Task
Scheduler, launchd `StartCalendarInterval`). On OpenRC there is no native scheduler →
**fail-fast** (documented future cron fallback, see roadmap).

```java
public sealed interface Schedule permits CalendarSchedule, IntervalSchedule {
	static Schedule everyMinutes(final int n);
	static Schedule every(final Duration period);                 // interval
	static Schedule dailyAt(final int hour, final int minute);    // calendar
	static Schedule weeklyAt(final DayOfWeek day, final int h, final int m);
	static Schedule monthlyAt(final int dayOfMonth, final int h, final int m);
	static Schedule calendar(final CalendarSpec spec);            // full control
}
```

A spec with **both** a `schedule` and `restart(ALWAYS)` is contradictory on every backend (a
thing can't be both "fires on a schedule" and "always running") → fail-fast at build/validate.

### 4.3 `ServiceStatus` (small common core, honest about gaps)

```java
public final class ServiceStatus {
	String id();
	boolean installed();
	boolean enabled();          // boot persistence
	boolean managed();          // created by this library (marker present)?
	RunState state();           // RUNNING | STOPPED | STARTING | STOPPING | FAILED | UNKNOWN
	Integer pid();              // nullable
	Integer lastExitCode();     // nullable
	String raw();               // nullable native dump (launchctl print / systemctl show / sc query)
}
```

Weak platforms (launchd text, SysV) populate what they can and leave the rest `null` rather
than fabricating — resolves tension **T6**.

---

## 5. Discovery, inspection, and modification

These were the three gaps called out in review. All three are first-class.

### 5.1 Discovery — and telling "ours" apart from the rest
Every definition we render carries a **managed-by marker**, so the library can distinguish
services it created from pre-existing ones:

| Platform | Marker |
|---|---|
| macOS | a custom plist key `com.jlaunchd.Managed` (= the lib version) |
| systemd | `X-JLaunchd-Managed=1` in `[Unit]` (systemd ignores unknown `X-` keys) |
| OpenRC | a sentinel comment + description var in the init script |
| Windows | a tag in the sidecar JSON + a marker prefix in the service Description |

- `list()` returns **all** services visible in reachable domains.
- `listManaged()` / `isManaged(id)` filter to the ones we created.
- This also gives a safety hook: management ops can refuse (or warn) on services we didn't
  create. Proposed default — `uninstall` operates on whatever `id` you name, but a
  `requireManaged` mode is available for callers who want the guard. (Open question Q4.)

### 5.2 Inspection — load current settings
- `read(id)` parses the live native definition back into a `ServiceSpec` (core fields +
  recognized option-block keys), or returns **`null`** if not installed. It is best-effort:
  exotic hand-authored keys we don't model are **not** silently dropped from view —
- `readNative(id)` returns the **verbatim** plist/unit/script text, so nothing is hidden even
  when `read()` can't round-trip it. (Honest about lossiness — tension **T6**.)

### 5.3 Modification — change and save
Read → modify a copy (specs are immutable) → upsert → optionally restart:

```java
final ServiceSpec current = mgr.read("com.example.backup");      // null if absent
if (current != null) {
	final ServiceSpec updated = current.toBuilder()
			.command("/usr/local/bin/backup", "--hourly")
			.schedule(Schedule.hourly())
			.build();
	mgr.install(updated);                 // upsert: rewrite definition + reconcile
	mgr.restart("com.example.backup");    // apply to the running instance
}
```

`toBuilder()` makes the read→modify→install loop natural; `install` being upsert means there's
no separate `update` verb to learn. Some platforms require the `restart` (or a re-`enable`) for
changes to take effect; the backend documents which, and `install` performs any required
`daemon-reload`/re-register itself.

---

## 6. Platform option blocks (Tier 2 — typed escape hatches) and their defaults

Each block is an immutable builder, attached only when needed, **only consulted on its own
platform**. When you omit it, the backend applies **sensible defaults** (below). Sketch:

```java
final ServiceSpec spec = ServiceSpec.builder("com.example.api")
		.command("/usr/local/bin/api")
		.restart(RestartPolicy.ALWAYS)
		.mac(MacOptions.builder()
				.processType(ProcessType.BACKGROUND)
				.lowPriorityIO(true)
				.keepAliveWhen(KeepAliveCondition.CRASHED)
				.throttleInterval(Duration.ofSeconds(10))
				.build())
		.systemd(SystemdOptions.builder()
				.type(SystemdType.NOTIFY)
				.after("network-online.target").wants("network-online.target")
				.memoryMax("512M").cpuQuota("50%").tasksMax(64)
				.restartSec(Duration.ofSeconds(2))
				.noNewPrivileges(true).protectSystem(ProtectSystem.STRICT)
				.build())
		.windows(WindowsOptions.builder()
				.account(WindowsAccount.localService())     // or .user("DOMAIN\\svc", secret)
				.startType(WindowsStartType.DELAYED_AUTO)
				.dependsOn("Tcpip")
				.build())
		.openrc(OpenRcOptions.builder()
				.supervisor(OpenRcSupervisor.SUPERVISE_DAEMON)
				.need("net").after("firewall")
				.runlevel("default")
				.build())
		.build();
```

**What each block carries (summary; full key sets in the per-platform research docs):**

- **`MacOptions`** — `processType`, `lowPriorityIO`, `nice`, `throttleInterval`, conditional
  `keepAliveWhen(...)`, `abandonProcessGroup`, `sessionType`, `watchPaths`, `queueDirectories`.
- **`SystemdOptions`** — `type`, ordering/deps (`after`/`before`/`requires`/`wants`/`bindsTo`),
  `restartSec`, `startLimitIntervalSec`, cgroup limits (`memoryMax`/`cpuQuota`/`tasksMax`),
  sandboxing (`protectSystem`/`privateTmp`/`noNewPrivileges`), `nice`, `oomScoreAdjust`,
  `slice`, timer extras (`persistent`, `randomizedDelay`, `accuracy`).
- **`WindowsOptions`** — `account`, `startType`, `dependsOn`, `recovery`, `serviceSidType`; Task
  extras (`runOnlyIfIdle`, `wakeToRun`, `runIfMissed`, `executionTimeLimit`, `priority`).
- **`OpenRcOptions`** — `supervisor`, deps (`need`/`use`/`after`/`before`/`provide`), `runlevel`,
  `respawnMax`/`respawnPeriod`, `pidfile`.

### 6.1 Default behavior per platform (when no option block is given)

Defaults are a **starting point** — refined as we gain experience; making them configurable is
a roadmap item.

| Aspect | macOS | systemd | OpenRC | Windows |
|---|---|---|---|---|
| service "type"/mode | (launchd job) | **`Type=exec`** (detects bad binary/user; better than `simple`) | `supervise-daemon` if `restart != NEVER`, else `start-stop-daemon` | own-process service via the Java host |
| `autoStart=true` | `RunAtLoad=true` | `enable` → `WantedBy=multi-user.target` (system) / `default.target` (user) | `rc-update add … default` | start type `auto` |
| `autoStart=false` | `RunAtLoad=false` | not enabled | not added to a runlevel | start type `manual` |
| stdout/stderr unset | launchd default | **journal** | OpenRC default logging | inherited by host (no redirect) |
| restart throttle | launchd default | `RestartSec=100ms`; `ALWAYS` adds `StartLimitIntervalSec=0` | default respawn period | host backoff |
| process priority | Background `ProcessType` | none | none | normal |
| scheduled job | `StartCalendarInterval` | `.timer` + `oneshot .service` | **fail-fast** | Task Scheduler (no host) |

---

## 7. Capabilities & fail-fast

```java
public interface Capabilities {
	boolean userIdentity();          // false on OpenRC
	boolean systemIdentity();
	boolean namedUserIdentity();
	boolean calendarSchedule();      // false on OpenRC
	boolean intervalSchedule();
	boolean keepAlive();             // continuous restart
	boolean conditionalKeepAlive();  // mac/systemd only
	boolean logFileRedirection();
	boolean structuredStatus();      // reliable pid+exit code (true: systemd/windows)
}
```

`install()` validates `spec` against `capabilities()` **before** touching the system and throws
`UnsupportedFeatureException` naming exactly what isn't supported and why. No silent
degradation (your call). Opt-in fallbacks (e.g. calendar→cron on OpenRC) are a future roadmap
item, never a default.

---

## 8. Errors

```java
ServiceException                       // unchecked base — wraps everything
├── UnsupportedFeatureException        // capability gap (fail-fast, pre-flight)
├── ServiceNotFoundException           // mutating op on an unknown id
├── PermissionException                // needs root/admin/elevation
├── DefinitionIOException              // writing/reading the plist/unit/script failed
└── NativeCommandException             // launchctl/systemctl/sc/FFM call failed (carries cmd+exit+stderr)
```

---

## 9. Platform detection & the `Platform` enum

```java
public enum Platform { MACOS_LAUNCHD, LINUX_SYSTEMD, LINUX_OPENRC, WINDOWS }
```

`forThisPlatform()` detection order:
- **Windows / macOS:** by `os.name`.
- **Linux:** systemd if `/run/systemd/system` exists (then `/proc/1/comm == systemd`); else
  OpenRC if `/sbin/openrc` / `rc-service` present; else **fail-fast** with a clear message
  (covers the init-less container case — "no supported init system found; PID 1 is `<x>`").

systemd and OpenRC are **distinct platforms** with **distinct backends** (your decision — no
shared Linux SPI). The internal `Backend` interface that each implements is code-org only, not
a public extension point.

---

## 10. Windows specifics — the bundled Java service host

Resolves tension **T1** while honoring "no compiled binaries":

- The jar contains a runnable class, e.g. `com.jlaunchd.windows.ServiceHost`.
- `install()` of a **daemon** registers a service whose `binPath` is
  `"<javaw>" -cp "<our.jar>" com.jlaunchd.windows.ServiceHost --id com.example.api`, and writes
  the spec to a sidecar file (`%ProgramData%\jlaunchd\<id>.json`).
- On start, the SCM launches that host; the host uses **FFM** to call
  `StartServiceCtrlDispatcher` + `RegisterServiceCtrlHandlerEx` (handler is an **FFM upcall**)
  + `SetServiceStatus`, then `CreateProcess`-supervises the real command, mapping STOP→terminate
  child and implementing the `RestartPolicy`. This *is* a real Windows service, in pure Java.
- **Scheduled** jobs skip the host entirely → Task Scheduler runs the command directly (no
  protocol needed). Routing by job shape resolves tension **T2**.
- `status()` reads real state/PID/exit via `QueryServiceStatusEx` (FFM) — structured, no
  `sc query` text parsing.

All FFM and subprocess access sits behind interfaces (`Scm`, `TaskScheduler`, `CommandRunner`)
so the Windows backend unit-tests on Linux/macOS with stubs.

---

## 11. Internal architecture (not public API)

```
com.jlaunchd
├── ServiceManager (iface) · Platform · Capabilities · ServiceException…            ← public
├── model/   ServiceSpec(+Builder) · RunAs · RestartPolicy · Schedule(+CalendarSpec) · ServiceStatus
│   └── options/  MacOptions · SystemdOptions · WindowsOptions · OpenRcOptions       ← public
└── internal/                                                                        ← package-private
	├── Backend (iface): install/uninstall/start/stop/enable/disable/status/list/read
	├── exec/   CommandRunner (stubbable subprocess seam)
	├── macos/    LaunchdBackend · PlistRenderer(dd-plist) · Launchctl
	├── systemd/  SystemdBackend · UnitRenderer · Systemctl
	├── openrc/   OpenRcBackend · RcScriptRenderer · RcService
	└── windows/  WindowsBackend(routes svc⇄task) · Scm(FFM) · TaskScheduler · ServiceHost(FFM)
```

- **Renderers** are per-platform (plist/INI/script/Task-XML) — there is no shared codec
  (tension **T5**). `dd-plist` is confined to `macos/PlistRenderer`.
- The `Backend` SPI is **internal**; we are not advertising a plugin point (your decision).
- Everything native (subprocess *and* FFM) is behind an interface → full off-platform testing.

---

## 12. Naming note

The artifact is universal now but the repo/brand is `JLaunchd…`. Proposed root package
`com.jlaunchd` (keeps the brand; launchd remains the conceptual baseline). Open to a rename
(`com.hooji.svc`, `com.jdaemon`, …) — low stakes, easy to settle.

---

## 13. Open questions for approval

1. **Domain resolution for by-id ops.** With identity in the spec (no scope-bound manager), how
   should `start("id")` locate a service present in multiple domains? Proposed: current-user
   domain first, then system; throw `AmbiguousServiceException` if the same id exists in both.
   OK?
2. **`id` style:** require reverse-DNS, or accept any string and normalize? Proposed: *recommend*
   reverse-DNS, *accept* any, normalize internally.
3. **Option-block on a foreign platform:** silently ignore (proposed) vs warn vs fail-fast.
4. **Managed-only guard:** should `uninstall`/modify refuse non-managed services by default, or
   only when a `requireManaged` mode is set (proposed)?
5. **Root package name** (§12).
6. **Promote anything from Tier 2 into the core?** e.g. is process priority/`nice` common enough
   to be a first-class `ServiceSpec` field rather than living in each option block?
