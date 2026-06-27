# Windows implementation plan — handoff for the next session

This is the detailed, actionable plan for building the **Windows** backend. macOS (launchd)
and Linux (systemd) are already implemented end-to-end and **validated on real systems via the
CI probe**; Windows and OpenRC remain. Windows is the biggest job (FFM + the SCM protocol),
hence this dedicated plan.

**Read first:** `docs/research/windows-services.md` (SCM + Task Scheduler + the quirk),
`docs/research/java-ffm-native-access.md` (FFM maturity), `docs/design/api-design.md` §10 (the
Java service host), and the **already-built macOS/systemd backends** as the pattern to mirror
(`src/main/java/com/u1/servicepal/internal/{macos,systemd}/`).

---

## 0. The pattern to follow (don't reinvent it)

macOS and systemd established a clean, proven shape. Windows must follow it exactly:

| Piece | macOS | systemd | **Windows (to build)** |
|---|---|---|---|
| Backend (`internal/Backend`) | `LaunchdBackend` | `SystemdBackend` | `WindowsBackend` (routes service ⇄ task) |
| Native seam (interface, stubbed in tests) | `Launchctl` | `Systemctl` | `Scm` (advapi32) + `TaskScheduler` (schtasks) |
| Real seam impl | `DefaultLaunchctl` | `DefaultSystemctl` | `FfmScm` + `SchtasksScheduler` |
| Definition writer | `PlistWriter` | `UnitWriter` | `SidecarWriter` (JSON) + `TaskXmlWriter` |
| Definition reader | `PlistReader` | `UnitReader` | `SidecarReader` |
| Test fake | `RecordingLaunchctl` | `RecordingSystemctl` | `RecordingScm` / `RecordingTaskScheduler` |
| Wire-up | `DefaultServiceManager.create()` → `MACOS_LAUNCHD` | → `LINUX_SYSTEMD` | → `WINDOWS` |
| Validation | probe `SelfTestCli` (macOS, PER_USER) | probe `SelfTestCli` (Linux, sudo) | probe `SelfTestCli` (Windows, admin) |

**Invariants every backend already honors** (keep them): the managed-by marker so we recognize
our own services; `RunAs` → `Installation`; `Capabilities` + fail-fast; `Discovery(services,
unreadable)`; all native access behind a stubbable interface so unit tests run off-platform.

---

## 1. The pivotal quirk (recap)

A Windows **service binary must speak the SCM control protocol** — point a service's `binPath`
at `java -jar app.jar` and it dies with **error 1053** ("did not respond in a timely fashion").
So:
- **Daemons** (long-running) run via a **bundled pure-Java FFM `ServiceHost`** (a runnable class
  in our jar) that *does* speak the protocol and supervises the real command as a child.
- **Scheduled jobs** (`spec.schedule() != null`) go to **Task Scheduler** — any exe works, no
  protocol. `WindowsBackend` routes by job shape.

This is the owner-approved design (no compiled binaries; the host is pure Java via FFM upcalls).

---

## 2. Build / JDK changes (do these first)

- **Bump the baseline to JDK 25.** FFM is final in JDK 22; 25 is the approved LTS floor. In
  `pom.xml`, `<maven.compiler.release>` **21 → 25**. In `.github/workflows/{ci,release,probe}.yml`,
  `setup-java` **21 → 25** (temurin 25 is GA). The macOS/systemd/OpenRC code is 21-compatible,
  but the single jar moves to 25 for the FFM paths. (A lower-JDK Mac/Linux-only build stays a
  roadmap item — `docs/ROADMAP.md`.)
- **Native access flag (JEP 472).** FFM is a restricted operation; the consuming app must pass
  `--enable-native-access=ALL-UNNAMED`. The host's own `binPath` must include it, and the
  probe's Windows self-test must too. Document it in `README.md`.
- `jextract` (separate download) can generate advapi32 bindings, but hand-writing the ~10
  functions we need is simpler and keeps the build dependency-free — recommend hand-written.

---

## 3. `internal/windows/` layout & responsibilities

```
internal/windows/
├── WindowsBackend.java        implements Backend; routes daemon (Scm) vs scheduled (TaskScheduler)
├── Scm.java                   interface over advapi32 (stub in tests)
├── FfmScm.java                Scm via FFM → advapi32.dll
├── ServiceHost.java           runnable: speaks the SCM protocol via FFM upcalls, supervises child
├── ServiceControlStatus.java  record: state + pid + exit (parallels ServiceRuntime/UnitState)
├── TaskScheduler.java         interface over schtasks (stub in tests)
├── SchtasksScheduler.java     TaskScheduler via schtasks.exe subprocess
├── TaskXmlWriter.java         ServiceSpec → Task Scheduler XML
├── SidecarWriter.java         ServiceSpec → %ProgramData%\ServicePal\<id>.json
└── SidecarReader.java         JSON → ServiceSpec (+ managed marker)
```

### `Scm` (the native seam) — methods to expose
`openManager()`, `create(name, displayName, binPath, startType, account, password)`,
`delete(name)`, `start(name)`, `control(name, STOP)`, `setStartType(name, type)`,
`queryStatus(name) → ServiceControlStatus`, `setDescription(name, text)`,
`enumerate() → List<service names>`. Back it with these **advapi32** exports (all `…W`):
`OpenSCManagerW`, `CreateServiceW`, `OpenServiceW`, `StartServiceW`, `ControlService`,
`ChangeServiceConfigW`/`ChangeServiceConfig2W`, `QueryServiceStatusEx`, `DeleteService`,
`EnumServicesStatusExW`, `CloseServiceHandle`. (Cross-check struct/func shapes against JNA's
`Advapi32`/`Winsvc` even though we use FFM.)

### `ServiceHost` (the hard part — FFM upcalls)
Registered as the service `binPath`:
```
"<javaw.exe>" --enable-native-access=ALL-UNNAMED -cp "<our.jar>" \
    com.u1.servicepal.windows.ServiceHost --id <service-id>
```
On start it must, quickly (<~30s or error 1053):
1. `StartServiceCtrlDispatcherW` with a **`ServiceMain` upcall**.
2. In `ServiceMain`: `RegisterServiceCtrlHandlerExW` with a **handler upcall**
   (`DWORD Handler(DWORD control, DWORD eventType, LPVOID eventData, LPVOID context)`).
3. `SetServiceStatus` → `SERVICE_START_PENDING` → spawn the real command (read from the sidecar
   JSON) via `ProcessBuilder`/`CreateProcess` → `SERVICE_RUNNING`.
4. On `SERVICE_CONTROL_STOP`: `SetServiceStatus(STOP_PENDING)`, terminate the child, then
   `SERVICE_STOPPED`.
5. Implement `RestartPolicy` as a respawn loop around the child (primary mechanism). Optionally
   also set SCM `SERVICE_FAILURE_ACTIONS` as a secondary.

This is the riskiest code — **prototype `ServiceHost` early and validate on the Windows runner
before wiring the rest.**

---

## 4. Mapping & flows

**Identity → Windows:**
- `asSystemDaemon()` → service `ObjectName = LocalSystem`.
- `asUser(name)` → service `ObjectName = name`, password via `WindowsOptions` (account needs
  "Log on as a service"). `LocalService`/`NetworkService` exposed via `WindowsOptions.account`.
- `asCurrentUser()` (PER_USER): **recommend SYSTEM_WIDE-only for Windows v1** — report
  `Capabilities.perUserInstall() = false` initially. Per-user services (`Tmpl_<LUID>`) or a
  logon Task are a follow-up. (Fail-fast already handles a PER_USER spec when the cap is false.)

**Install (daemon):** write sidecar JSON; `CreateServiceW` with the host `binPath`; set
`Description` with a `[ServicePal]` marker; start type from `autoStart` (auto vs demand). Upsert:
if it exists and is managed, rewrite sidecar + `ChangeServiceConfig`; managed-guard via the
Description marker / sidecar (throw `UnmanagedServiceException` unless the override).

**Install (scheduled):** `TaskXmlWriter` → `schtasks /create /xml <file> /tn <id>`.

**Other verbs:** `enable`/`disable` → `ChangeServiceConfig` start type auto/disabled;
`start`→`StartServiceW`; `stop`→`ControlService(STOP)`; `restart`→stop+start;
`uninstall`→stop + `DeleteService` + delete sidecar (or `schtasks /delete`).

**Status:** `QueryServiceStatusEx` → `SERVICE_STATUS_PROCESS`: `dwCurrentState`→`RunState`,
`dwProcessId`→pid, `dwWin32ExitCode`→lastExit. Windows gives **structured status**
(`structuredStatus = true`).

**Discovery:** `EnumServicesStatusExW` (or `sc query`) → filter to ours by the Description
marker / sidecar presence for `listManaged`.

---

## 5. Validation (mirror the macOS/systemd self-test loop)

- **Unit tests** (off-Windows, stubbed `Scm`/`TaskScheduler`): sidecar JSON round-trip; the
  `binPath` string construction; routing (daemon vs scheduled); managed guard; Task XML shape.
- **`SelfTestCli`**: add a Windows branch — `asSystemDaemon()`, and a **long-running Windows
  command** (`/bin/sleep` won't exist). Use e.g. `ping -n 120 127.0.0.1` (runs ~120s without a
  console). Needs Administrator — GitHub `windows-latest` runs as an admin account, so
  `CreateService` should work; if not, add an elevation step.
- **`probe.yml` Windows job**: run `SelfTestCli` (with `--enable-native-access=ALL-UNNAMED`).
  The real FFM `ServiceHost` is validated by the runner doing install→start→`RUNNING`+pid→
  uninstall. Read the `SELFTEST PASS`/`FAIL` line in the job log and iterate (push to branch,
  re-run probe) — **open the PR only once green** (see CLAUDE.md PR-timing rule).

**Watch for:** error 1053 (host too slow to `SetServiceStatus`); `ERROR_ACCESS_DENIED` (not
admin); `ERROR_SERVICE_MARKED_FOR_DELETE` (delete is deferred — a delete-then-immediately-
recreate races); the `sc create key= value` spacing quirk *if* you add an `sc.exe` fallback.

---

## 6. FFM cheat-sheet (java.lang.foreign)

```java
final Linker linker = Linker.nativeLinker();
final SymbolLookup advapi32 = SymbolLookup.libraryLookup("Advapi32.dll", arena);
final MethodHandle createService = linker.downcallHandle(
        advapi32.find("CreateServiceW").orElseThrow(),
        FunctionDescriptor.of(ADDRESS, /* hSCManager */ ADDRESS, /* name */ ADDRESS, ...),
        Linker.Option.captureCallState("GetLastError"));   // capture GetLastError for diagnostics
```
- **Wide strings:** `arena.allocateFrom("text", StandardCharsets.UTF_16LE)` (appends the NUL).
- **Structs** (`SERVICE_STATUS_PROCESS`, `QUERY_SERVICE_CONFIG`): `MemoryLayout.structLayout(...)`
  + `VarHandle`s for fields; allocate with `arena.allocate(LAYOUT)`.
- **Upcalls** (host's `ServiceMain` + handler): `linker.upcallStub(methodHandle, descriptor,
  arena)` → pass the resulting `MemorySegment` (function pointer) to the Win32 call.
- `Arena.ofConfined()` for short calls; the host needs an `Arena.ofShared()`/lifetime that spans
  the service's run.

---

## 7. Open decisions to settle next session

1. **PER_USER on Windows:** SYSTEM_WIDE-only v1 (recommended) vs per-user service vs logon Task.
2. **`sc.exe` subprocess fallback** for a JDK-21 "Windows-lite" (no FFM, weaker status) — vs
   FFM-only. (Roadmap; the macOS/systemd builds are subprocess, so a fallback keeps symmetry.)
3. **WinSW** as an alternative host (roadmap option per owner decision; the FFM host is the
   default).
4. After Windows: **systemd `.timer`** support and **OpenRC** are the remaining backend gaps
   (OpenRC is small — subprocess + shell-script renderer, no FFM; closer to the systemd shape).

When Windows lands, the library hits **step 5** (assemble all four behind the facade) — which is
mostly already true, since the facade + manager are platform-agnostic and just need the
`WindowsBackend` wired into `DefaultServiceManager.create()`.
