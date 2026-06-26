# API Design — universal service manager (step 2 proposal)

> **Status:** Proposal for owner approval. No implementation yet. Supersedes the
> single-platform sketch in `CLAUDE.md`. Built on the research in `docs/research/`
> (read `cross-platform-synthesis.md` first).
>
> **Decisions baked in** (owner-approved): pure-Java FFM service host on Windows (bundled,
> runnable, the default path); **JDK 25** baseline; **systemd + OpenRC** both shipped in v1
> as *separate platforms* (no public pluggable SPI); **fail-fast** on capability gaps.

---

## 1. The central question: how uniform can this be?

**Verdict: three tiers.** The design's whole job is to keep Tier 1 huge, Tier 2 clean and
out of the way, and Tier 3 loud and early.

| Tier | What | How it's exposed |
|---|---|---|
| **1. Uniform core** (~90% of real use) | lifecycle verbs; `id`, `command`, env, working dir, run-as user, log files, autostart, restart policy (`NEVER`/`ON_FAILURE`/`ALWAYS`), calendar/interval schedule, status core | the plain `ServiceManager` + `ServiceSpec` — identical on every platform |
| **2. Platform-unique power** | the long tail of per-OS knobs (macOS `ProcessType`, systemd sandboxing/cgroups/ordering, Windows accounts/recovery, OpenRC supervisor choice) | optional, **typed, namespaced option blocks** (`.mac(...)`, `.systemd(...)`, `.windows(...)`, `.openrc(...)`) — applied on their platform, ignored elsewhere |
| **3. Capability gaps** | things a platform simply cannot do (calendar schedule on OpenRC; per-user scope on OpenRC; conditional keep-alive off systemd/macOS) | `Capabilities` query + **fail-fast** `UnsupportedFeatureException` at `install()` with an actionable message |

So: **yes, the common path is uniform**; the platform-specific bits are neither hidden nor
in your face — they sit in clearly-labelled side rooms you only enter on purpose.

The simple path, identical on macOS, systemd, OpenRC, and Windows:

```java
ServiceManager mgr = ServiceManager.system();          // or .user()

ServiceSpec backup = ServiceSpec.builder("com.example.backup")
        .command("/usr/local/bin/backup", "--daily")
        .restart(RestartPolicy.ON_FAILURE)
        .stdout(Path.of("/var/log/backup.log"))
        .autoStart(true)
        .build();

mgr.install(backup);                 // render definition + register with the OS
mgr.start("com.example.backup");     // run now
ServiceStatus st = mgr.status("com.example.backup");
mgr.uninstall("com.example.backup"); // stop + deregister + delete definition
```

That block compiles to a launchd plist + `launchctl`, a systemd unit + `systemctl`, an OpenRC
script + `rc-service`, or a Windows service (via the bundled Java host) + SCM — with no code
change.

---

## 2. The facade — `ServiceManager`

A `ServiceManager` is **bound to a scope** (user vs system). This matches how the tools are
actually used — you manage user agents *or* system daemons, not both in one breath — and it
keeps every lifecycle call down to just the service `id`.

```java
public interface ServiceManager {

    // --- construction ---
    static ServiceManager forThisPlatform();          // detect OS+init, default SYSTEM scope
    static ServiceManager user();                     // this platform, USER scope
    static ServiceManager system();                   // this platform, SYSTEM scope
    static ServiceManager forScope(Scope scope);

    Platform platform();                               // which backend was detected
    Scope scope();
    Capabilities capabilities();                       // feature queries (see §6)

    // --- definition lifecycle ---
    void install(ServiceSpec spec);                    // render + register (idempotent-ish)
    void uninstall(String id);                         // stop if running + deregister + delete
    boolean isInstalled(String id);
    Optional<ServiceSpec> read(String id);             // best-effort round-trip (may be lossy)
    List<ServiceStatus> list();                        // services in this scope

    // --- the two orthogonal axes (kept separate; see §5.4) ---
    void enable(String id);                            // start at boot/login (persistence)
    void disable(String id);
    void start(String id);                             // run now
    void stop(String id);
    void restart(String id);

    // --- convenience (covers the common "do it all" case) ---
    default void installEnableStart(ServiceSpec spec) {
        install(spec); enable(spec.id()); start(spec.id());
    }

    // --- query ---
    ServiceStatus status(String id);
}
```

Mutating ops **throw** on failure (`ServiceException` hierarchy, §7) — chosen over result
objects for a clean call site; failures here are exceptional and usually unrecoverable
(permission denied, malformed spec, native command failure).

> Note `enable`/`disable` (boot persistence) and `start`/`stop` (run now) are **separate
> verbs** — the systemd-faithful model. `install` does *not* implicitly enable or start;
> `autoStart` in the spec is honored by the `installEnableStart` convenience and is the value
> `enable` writes. This resolves tension **T3** without lying to systemd or surprising
> launchd users (who get the combined behavior via the convenience method).

---

## 3. The domain model — `ServiceSpec`

Immutable value object, `final` throughout, built with a builder. Reverse-DNS `id` is the
canonical handle and is normalized to each platform's naming rules internally (kept verbatim
where the platform allows — systemd unit names and Windows service names both tolerate dots).

```java
public final class ServiceSpec {
    // ---- identity ----
    String id();                       // required, e.g. "com.example.backup" (canonical handle)
    Optional<String> displayName();    // human label (Windows DisplayName, systemd Description)
    Optional<String> description();

    // ---- what to run (uniform) ----
    List<String> command();            // program + args; required; program should be absolute
    Optional<Path> workingDirectory();
    Map<String,String> environment();
    Optional<String> runAsUser();      // SYSTEM scope: run the service as this OS user

    // ---- I/O (uniform) ----
    Optional<Path> stdout();           // launchd StandardOutPath / systemd StandardOutput=file:
    Optional<Path> stderr();

    // ---- lifecycle policy (uniform core) ----
    boolean autoStart();               // value written by enable() (start at boot/login)
    RestartPolicy restart();           // NEVER | ON_FAILURE | ALWAYS
    Optional<Schedule> schedule();     // present => this is a scheduled job (timer/Task/cron)

    // ---- platform escape hatches (Tier 2; all optional) ----
    Optional<MacOptions> mac();
    Optional<SystemdOptions> systemd();
    Optional<WindowsOptions> windows();
    Optional<OpenRcOptions> openrc();

    static Builder builder(String id);
}
```

### 3.1 `RestartPolicy` (the keep-alive core)

```java
public enum RestartPolicy { NEVER, ON_FAILURE, ALWAYS }
```

| Policy | launchd | systemd | Windows (host) | OpenRC |
|---|---|---|---|---|
| `NEVER` | (no KeepAlive, no RunAtLoad respawn) | `Restart=no` | host doesn't respawn | `start-stop-daemon` |
| `ON_FAILURE` | `KeepAlive={SuccessfulExit=false}` | `Restart=on-failure` | host respawns on non-zero exit | `supervise-daemon` (retry) |
| `ALWAYS` | `KeepAlive=true` | `Restart=always` (+`StartLimitIntervalSec=0`) | host respawns always | `supervise-daemon` |

Rich/conditional keep-alive (launchd `Crashed`/`NetworkState`/`PathState`; systemd
`on-watchdog`/`on-abnormal`) lives in the option blocks (Tier 2).

### 3.2 `Schedule` (calendar + interval)

Presence of a `schedule` routes the job to the scheduling backend (systemd `.timer`, Windows
Task Scheduler, launchd `StartCalendarInterval`). On OpenRC there is no native scheduler →
**fail-fast** (with a documented future cron fallback, see roadmap).

```java
public sealed interface Schedule permits CalendarSchedule, IntervalSchedule {
    static Schedule everyMinutes(int n);
    static Schedule every(Duration period);                 // interval
    static Schedule dailyAt(int hour, int minute);          // calendar
    static Schedule weeklyAt(DayOfWeek day, int h, int m);
    static Schedule monthlyAt(int dayOfMonth, int h, int m);
    static Schedule calendar(CalendarSpec spec);            // full control
}

// CalendarSpec: optional minute/hour/dayOfMonth/month/dayOfWeek fields (cron-like),
// rendered to OnCalendar= (systemd), <CalendarTrigger> (Windows), StartCalendarInterval (mac).
```

A spec with **both** a `schedule` and a continuous `restart` policy (`ALWAYS`) is contradictory
on every backend (a thing can't be both "fires on a schedule" and "always running") → fail-fast
at build/validate time.

### 3.3 `ServiceStatus` (small common core, honest about gaps)

```java
public record ServiceStatus(
        String id,
        boolean installed,
        boolean enabled,            // boot persistence
        RunState state,             // RUNNING | STOPPED | STARTING | STOPPING | FAILED | UNKNOWN
        OptionalInt pid,
        OptionalInt lastExitCode,
        Optional<String> raw        // native dump (launchctl print / systemctl show / sc query)
) {}
```

Weak platforms (launchd text, SysV) populate what they can and leave the rest empty rather
than fabricating — resolves tension **T6**.

---

## 4. Platform option blocks (Tier 2 — the typed escape hatches)

Each is an immutable builder, attached only when needed, and **only consulted on its own
platform**. This is how we expose platform-unique power without a `Map<String,String>` and
without polluting the core. Sketch of the surface (not exhaustive — full key sets in the
per-platform research docs):

```java
spec = ServiceSpec.builder("com.example.api")
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
        .account(WindowsAccount.localService())          // or .user("DOMAIN\\svc", secret)
        .startType(WindowsStartType.DELAYED_AUTO)
        .dependsOn("Tcpip")
        .recovery(Recovery.restart(Duration.ofSeconds(60)).then(Recovery.restart(...)))
        .build())
    .openrc(OpenRcOptions.builder()
        .supervisor(OpenRcSupervisor.SUPERVISE_DAEMON)
        .need("net").after("firewall")
        .runlevel("default")
        .build())
    .build();
```

**What each block carries (summary):**

- **`MacOptions`** — `processType`, `lowPriorityIO`, `nice`, `throttleInterval`, conditional
  `keepAliveWhen(...)` (Crashed / SuccessfulExit / NetworkState / PathState / OtherJob),
  `abandonProcessGroup`, `sessionType`, `watchPaths`, `queueDirectories`, `startOnMount`.
- **`SystemdOptions`** — `type` (simple/exec/forking/oneshot/notify), ordering & deps
  (`after`/`before`/`requires`/`wants`/`bindsTo`), `restartSec`, `startLimitIntervalSec`,
  cgroup limits (`memoryMax`/`cpuQuota`/`tasksMax`/`ioWeight`), sandboxing
  (`protectSystem`/`privateTmp`/`noNewPrivileges`/`readOnlyPaths`), `nice`, `oomScoreAdjust`,
  `slice`, timer extras (`persistent`, `randomizedDelay`, `accuracy`).
- **`WindowsOptions`** — `account` (LocalSystem/LocalService/NetworkService/virtual/user+secret),
  `startType` (auto/delayed-auto/manual/disabled), `dependsOn`, `recovery` actions,
  `serviceSidType`; and Task-Scheduler extras for scheduled jobs (`runOnlyIfIdle`, `wakeToRun`,
  `runIfMissed`, `executionTimeLimit`, `priority`, `runLevel`).
- **`OpenRcOptions`** — `supervisor` (supervise-daemon vs start-stop-daemon), dependencies
  (`need`/`use`/`after`/`before`/`provide`), `runlevel`, `respawnMax`/`respawnPeriod`,
  `pidfile`.

**Policy:** an option block is silently **ignored** on a foreign platform (you asked for it by
name; that's expected). A *core* feature the target can't honor is **fail-fast** (§6). These
two rules are different on purpose.

---

## 5. Worked examples (the same intent, four platforms)

### 5.1 "Run my server, keep it alive, start at boot" (uniform — no option blocks)
```java
ServiceManager.system().installEnableStart(
    ServiceSpec.builder("com.acme.gateway")
        .command("/opt/acme/gateway", "--port", "8080")
        .restart(RestartPolicy.ALWAYS)
        .stdout(Path.of("/var/log/acme/gateway.log"))
        .autoStart(true)
        .build());
```
→ **mac:** plist (`KeepAlive=true`, `RunAtLoad=true`) + `launchctl bootstrap`+`kickstart`.
→ **systemd:** unit (`Restart=always`, `WantedBy=multi-user.target`) + `daemon-reload`+`enable --now`.
→ **openrc:** script (`supervise-daemon`) + `rc-update add` + `rc-service start`.
→ **windows:** registers a service whose binary is the **bundled Java host**, which supervises
   `/opt/acme/gateway --port 8080` and respawns it; SCM start type = auto.

### 5.2 "Run a backup every day at 03:00" (uniform schedule)
```java
ServiceManager.system().install(
    ServiceSpec.builder("com.acme.backup")
        .command("/opt/acme/backup", "--daily")
        .schedule(Schedule.dailyAt(3, 0))
        .build());
```
→ **mac:** `StartCalendarInterval`. **systemd:** `.timer`(`OnCalendar=*-*-* 03:00:00`) +
   `oneshot .service`. **windows:** Task Scheduler `<CalendarTrigger>`. **openrc:**
   `UnsupportedFeatureException("OpenRC has no native scheduler; calendar schedules are
   unsupported on this platform")`.

### 5.3 Using platform power deliberately (Tier 2)
See the big example in §4 — same `command`, but pinned memory on systemd, a service account on
Windows, IO priority on macOS. Each backend reads only its own block.

---

## 6. Capabilities & fail-fast

```java
public interface Capabilities {
    boolean userScope();                 // false on OpenRC
    boolean systemScope();
    boolean calendarSchedule();          // false on OpenRC
    boolean intervalSchedule();
    boolean keepAlive();                 // continuous restart
    boolean conditionalKeepAlive();      // mac/systemd only
    boolean logFileRedirection();
    boolean structuredStatus();          // pid+exitcode reliably (true: systemd/windows)
    boolean runAsArbitraryUser();
}
```

`install()` validates `spec` against `capabilities()` **before** touching the system and throws
`UnsupportedFeatureException` listing exactly what isn't supported and why. No silent
degradation (your call). Fallbacks (e.g. calendar→cron on OpenRC) are a future opt-in, not a
default.

---

## 7. Errors

```java
ServiceException                       // unchecked base — wraps everything
├── UnsupportedFeatureException        // capability gap (fail-fast, pre-flight)
├── ServiceNotFoundException           // op on an unknown id
├── PermissionException                // needs root/admin/elevation
├── DefinitionIOException              // writing/reading the plist/unit/script failed
└── NativeCommandException             // launchctl/systemctl/sc/FFM call failed (carries cmd+exit+stderr)
```

---

## 8. Platform detection & the `Platform` enum

```java
public enum Platform { MACOS_LAUNCHD, LINUX_SYSTEMD, LINUX_OPENRC, WINDOWS }
```

`forThisPlatform()` detection order:
- **Windows / macOS:** by `os.name`.
- **Linux:** systemd if `/run/systemd/system` exists (then `/proc/1/comm == systemd`); else
  OpenRC if `/sbin/openrc` / `rc-service` present; else **fail-fast** with a clear message
  (covers the init-less container case — "no supported init system found; PID 1 is `<x>`").

systemd and OpenRC are **distinct platforms** with **distinct backends** (your decision — no
shared Linux SPI). The `Backend` interface that each implements is an **internal** code-org
seam, not a public extension point.

---

## 9. Windows specifics — the bundled Java service host

Resolves tension **T1** while honoring "no compiled binaries":

- The jar contains a runnable class, e.g. `com.jlaunchd.windows.ServiceHost`.
- `install()` of a **daemon** registers a service whose `binPath` is
  `"<javaw>" -cp "<our.jar>" com.jlaunchd.windows.ServiceHost --id com.acme.gateway`, and
  writes the spec to a small sidecar file (`%ProgramData%\jlaunchd\<id>.json`).
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

## 10. Internal architecture (not public API)

```
com.jlaunchd
├── ServiceManager (iface) · Scope · Platform · Capabilities · ServiceException…   ← public
├── model/   ServiceSpec(+Builder) · RestartPolicy · Schedule(+CalendarSpec) · ServiceStatus
│   └── options/  MacOptions · SystemdOptions · WindowsOptions · OpenRcOptions      ← public
└── internal/                                                                       ← package-private
    ├── Backend (iface): install/uninstall/start/stop/enable/disable/status/list
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

## 11. Naming note

The artifact is universal now but the repo/brand is `JLaunchd…`. Proposed root package
`com.jlaunchd` (keeps the brand; launchd remains the conceptual baseline). Open to a rename
(`com.hooji.svc`, `com.jdaemon`, …) — low stakes, easy to settle.

---

## 12. Open questions for approval

1. **Scope-bound manager** (`ServiceManager.system().start(id)`) vs **scope-as-parameter** on
   every call vs **scope-in-spec**. I propose scope-bound (cleanest call site). OK?
2. **`enable`/`start` as separate verbs** + an `installEnableStart` convenience (proposed), vs
   a single combined `install` that always enables+starts. I propose separate (systemd-honest).
3. **`id` style:** require reverse-DNS, or accept any string and normalize? I propose
   *recommend* reverse-DNS, *accept* any, normalize internally.
4. **Option-block on foreign platform:** silently ignore (proposed) vs warn vs fail-fast.
5. **Root package name** (§11).
6. Anything in Tier 2 you want promoted into the uniform core (e.g. is `nice`/priority common
   enough to be a first-class `ServiceSpec` field)?
