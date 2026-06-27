package com.u1.servicepal.internal.windows;

import com.u1.servicepal.Capabilities;
import com.u1.servicepal.DefinitionIOException;
import com.u1.servicepal.Installation;
import com.u1.servicepal.Platform;
import com.u1.servicepal.ServiceNotFoundException;
import com.u1.servicepal.UnmanagedServiceException;
import com.u1.servicepal.internal.Backend;
import com.u1.servicepal.internal.exec.DefaultCommandRunner;
import com.u1.servicepal.model.Discovery;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import com.u1.servicepal.model.options.WindowsOptions;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Windows backend. Routes by job shape: a long-running daemon ({@code schedule() == null}) is a
 * Windows <em>service</em> driven through the SCM ({@link Scm}); a scheduled job goes to Task
 * Scheduler ({@link TaskScheduler}). A Windows service binary must speak the SCM control
 * protocol, so daemons are registered with our bundled pure-Java {@link ServiceHost} as their
 * {@code binPath}; the host supervises the real command.
 *
 * <p>Every managed service has a sidecar JSON ({@code %ProgramData%\ServicePal\<id>.json}) that
 * carries the full spec — the SCM only stores a command line. The sidecar is our definition file
 * (the analog of the macOS plist / systemd unit) and the managed marker.
 *
 * <p>Windows v1 is SYSTEM_WIDE only ({@code perUserInstall = false}); per-user services are a
 * follow-up. The {@link com.u1.servicepal.internal.DefaultServiceManager} capability gate rejects
 * a PER_USER spec before it reaches here.
 */
public final class WindowsBackend implements Backend {

	private final Scm scm;
	private final TaskScheduler tasks;
	private final Path sidecarDir;
	private final String javawPath;
	private final String jarPath;

	private final SidecarReader reader = new SidecarReader();
	private final SidecarWriter writer = new SidecarWriter();
	private final TaskXmlWriter taskWriter = new TaskXmlWriter();

	public WindowsBackend(final Scm scm, final TaskScheduler tasks, final Path sidecarDir,
			final String javawPath, final String jarPath) {
		this.scm = scm;
		this.tasks = tasks;
		this.sidecarDir = sidecarDir;
		this.javawPath = javawPath;
		this.jarPath = jarPath;
	}

	public static WindowsBackend createDefault() {
		final String programData = System.getenv("ProgramData");
		final Path base = Path.of(programData != null ? programData : "C:\\ProgramData");
		final Path dir = base.resolve("ServicePal");
		final DefaultCommandRunner runner = new DefaultCommandRunner();
		return new WindowsBackend(new FfmScm(), new SchtasksScheduler(runner), dir,
				resolveJavaw(), resolveJar());
	}

	@Override
	public Platform platform() {
		return Platform.WINDOWS;
	}

	@Override
	public Capabilities capabilities() {
		// per-user is a follow-up; conditional keep-alive has no SCM analog. Task Scheduler
		// gives calendar + interval; the host gives keep-alive + log redirection; the SCM gives
		// structured status.
		return new Capabilities(false, true, true, true, true, true, false, true, true);
	}

	@Override
	public List<Installation> supportedInstallations() {
		return List.of(Installation.SYSTEM_WIDE);
	}

	@Override
	public Discovery discover(final Installation installation) {
		final List<ServiceStatus> services = new ArrayList<>();
		final List<String> unreadable = new ArrayList<>();
		if (installation != Installation.SYSTEM_WIDE || !Files.isDirectory(sidecarDir)) {
			return new Discovery(services, unreadable);
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(sidecarDir, "*.json")) {
			for (final Path file : stream) {
				if (!Files.isRegularFile(file)) {
					continue;
				}
				try {
					services.add(buildStatus(reader.parseFile(file), stem(file)));
				} catch (final DefinitionIOException e) {
					unreadable.add(file.toString());
				}
			}
		} catch (final IOException e) {
			// unreadable directory — skip
		}
		return new Discovery(services, unreadable);
	}

	@Override
	public ServiceSpec read(final String id, final Installation installation) {
		final Path file = findSidecar(id, installation);
		if (file == null) {
			return null;
		}
		return reader.toSpec(reader.parseFile(file), id);
	}

	@Override
	public String readNative(final String id, final Installation installation) {
		final Path file = findSidecar(id, installation);
		if (file == null) {
			return null;
		}
		final Map<String, Object> sidecar = reader.parseFile(file);
		if (reader.scheduled(sidecar)) {
			final String xml = tasks.queryXml(id);
			if (xml != null) {
				return xml;
			}
		}
		try {
			return Files.readString(file);
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to read " + file, e);
		}
	}

	@Override
	public ServiceStatus status(final String id, final Installation installation) {
		final Path file = findSidecar(id, installation);
		if (file == null) {
			return null;
		}
		return buildStatus(reader.parseFile(file), id);
	}

	// --- mutation ---

	@Override
	public void install(final ServiceSpec spec, final boolean overwriteUnmanaged) {
		final String id = spec.id();
		final Path file = sidecarPath(id);
		final boolean scheduled = spec.schedule() != null;

		final boolean sidecarExists = Files.isRegularFile(file);
		if (sidecarExists) {
			if (!overwriteUnmanaged && !reader.isManaged(reader.parseFile(file))) {
				throw new UnmanagedServiceException(id);
			}
		} else if (!overwriteUnmanaged && foreignExists(id, scheduled)) {
			// A pre-existing service/task we didn't create (no sidecar) — don't clobber it.
			throw new UnmanagedServiceException(id);
		}

		try {
			Files.createDirectories(sidecarDir);
			Files.writeString(file, writer.render(spec));
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to write " + file, e);
		}

		if (scheduled) {
			tasks.create(id, taskWriter.render(spec));
		} else {
			installDaemon(spec, id);
		}
	}

	private void installDaemon(final ServiceSpec spec, final String id) {
		// Upsert: the SCM can't rewrite a binPath in place through our seam, so replace.
		if (scm.exists(id)) {
			ignoreFailure(() -> scm.stop(id));
			scm.delete(id);
		}
		final WindowsOptions opts = spec.windows();
		final String binPath = hostBinPath(javawPath, jarPath, id);
		final int startType = startType(spec, opts);
		final String account = account(spec.runAs(), opts);
		final String password = opts != null ? opts.password() : null;
		scm.create(id, spec.displayName(), binPath, startType, account, password);
		scm.setDescription(id, describe(spec));
	}

	@Override
	public void uninstall(final String id, final Installation installation,
			final boolean unmanagedOk) {
		final Path file = findSidecar(id, installation);
		final boolean scheduled;
		if (file != null) {
			final Map<String, Object> sidecar = reader.parseFile(file);
			if (!unmanagedOk && !reader.isManaged(sidecar)) {
				throw new UnmanagedServiceException(id);
			}
			scheduled = reader.scheduled(sidecar);
		} else if (scm.exists(id)) {
			if (!unmanagedOk) {
				throw new UnmanagedServiceException(id);
			}
			scheduled = false;
		} else if (tasks.exists(id)) {
			if (!unmanagedOk) {
				throw new UnmanagedServiceException(id);
			}
			scheduled = true;
		} else {
			throw new ServiceNotFoundException(id);
		}

		if (scheduled) {
			ignoreFailure(() -> tasks.delete(id));
		} else {
			ignoreFailure(() -> scm.stop(id));
			ignoreFailure(() -> scm.delete(id));
		}
		if (file != null) {
			try {
				Files.deleteIfExists(file);
			} catch (final IOException e) {
				throw new DefinitionIOException("failed to delete " + file, e);
			}
		}
	}

	@Override
	public void enable(final String id, final Installation installation) {
		if (isScheduled(id, installation)) {
			return;   // a task's enabled-ness lives in its XML; no-op for v1.
		}
		scm.setStartType(requireDaemon(id, installation), Scm.SERVICE_AUTO_START);
	}

	@Override
	public void disable(final String id, final Installation installation) {
		if (isScheduled(id, installation)) {
			return;
		}
		scm.setStartType(requireDaemon(id, installation), Scm.SERVICE_DISABLED);
	}

	@Override
	public void start(final String id, final Installation installation) {
		if (isScheduled(id, installation)) {
			tasks.run(requireInstalled(id, installation));
		} else {
			scm.start(requireDaemon(id, installation));
		}
	}

	@Override
	public void stop(final String id, final Installation installation) {
		if (isScheduled(id, installation)) {
			tasks.stop(requireInstalled(id, installation));
		} else {
			scm.stop(requireDaemon(id, installation));
		}
	}

	@Override
	public void restart(final String id, final Installation installation) {
		stop(id, installation);
		start(id, installation);
	}

	// --- helpers ---

	private ServiceStatus buildStatus(final Map<String, Object> sidecar, final String id) {
		final boolean managed = reader.isManaged(sidecar);
		if (reader.scheduled(sidecar)) {
			final boolean enabled = reader.autoStart(sidecar);
			final RunState state = tasks.exists(id) ? RunState.UNKNOWN : RunState.STOPPED;
			return new ServiceStatus(id, Installation.SYSTEM_WIDE, true, enabled, managed, state,
					null, null, null);
		}
		final ServiceControlStatus st = scm.queryStatus(id);
		final Integer startType = scm.queryStartType(id);
		final boolean enabled = startType != null && startType == Scm.SERVICE_AUTO_START;
		return new ServiceStatus(id, Installation.SYSTEM_WIDE, true, enabled, managed,
				runState(st), st.pid(), st.win32ExitCode(), null);
	}

	private static RunState runState(final ServiceControlStatus st) {
		return switch (st.currentState()) {
			case ServiceControlStatus.SERVICE_RUNNING -> RunState.RUNNING;
			case ServiceControlStatus.SERVICE_STOPPED, ServiceControlStatus.SERVICE_PAUSED ->
					RunState.STOPPED;
			case ServiceControlStatus.SERVICE_START_PENDING,
					ServiceControlStatus.SERVICE_CONTINUE_PENDING -> RunState.STARTING;
			case ServiceControlStatus.SERVICE_STOP_PENDING,
					ServiceControlStatus.SERVICE_PAUSE_PENDING -> RunState.STOPPING;
			default -> RunState.UNKNOWN;   // 0 ⇒ sidecar present but no live SCM service
		};
	}

	/** Whether a service/task we did not create already occupies this id. */
	private boolean foreignExists(final String id, final boolean scheduled) {
		return scheduled ? tasks.exists(id) : scm.exists(id);
	}

	private boolean isScheduled(final String id, final Installation installation) {
		final Path file = findSidecar(id, installation);
		return file != null && reader.scheduled(reader.parseFile(file));
	}

	private String requireDaemon(final String id, final Installation installation) {
		requireInstalled(id, installation);
		return id;
	}

	private String requireInstalled(final String id, final Installation installation) {
		if (findSidecar(id, installation) == null && !scm.exists(id) && !tasks.exists(id)) {
			throw new ServiceNotFoundException(id);
		}
		return id;
	}

	private Path findSidecar(final String id, final Installation installation) {
		if (installation != Installation.SYSTEM_WIDE) {
			return null;
		}
		final Path candidate = sidecarPath(id);
		return Files.isRegularFile(candidate) ? candidate : null;
	}

	private Path sidecarPath(final String id) {
		return sidecarDir.resolve(id + ".json");
	}

	private static String describe(final ServiceSpec spec) {
		final String base = spec.description() != null ? spec.description() : spec.displayName();
		return base + " " + TaskXmlWriter.DESCRIPTION_MARKER;
	}

	private static int startType(final ServiceSpec spec, final WindowsOptions opts) {
		if (opts != null && opts.startType() != null) {
			return switch (opts.startType()) {
				case AUTO, DELAYED_AUTO -> Scm.SERVICE_AUTO_START;
				case MANUAL -> Scm.SERVICE_DEMAND_START;
				case DISABLED -> Scm.SERVICE_DISABLED;
			};
		}
		return spec.autoStart() ? Scm.SERVICE_AUTO_START : Scm.SERVICE_DEMAND_START;
	}

	/** The SCM {@code lpServiceStartName}, or {@code null} for LocalSystem. */
	private static String account(final RunAs runAs, final WindowsOptions opts) {
		if (opts != null && opts.account() != null) {
			return opts.account();
		}
		if (runAs.kind() == RunAs.Kind.NAMED_USER) {
			final String name = runAs.userName();
			// Qualify a bare name to the local machine; leave domain/built-in names as given.
			return name.contains("\\") ? name : ".\\" + name;
		}
		return null;   // LocalSystem
	}

	/**
	 * The command line registered as the service's {@code binPath}: launch {@link ServiceHost}
	 * with native access enabled, pointed at this service's id. Package-visible for testing.
	 */
	static String hostBinPath(final String javaw, final String jar, final String id) {
		return "\"" + javaw + "\" --enable-native-access=ALL-UNNAMED -cp \"" + jar + "\" "
				+ ServiceHost.class.getName() + " --id " + id;
	}

	private static String resolveJavaw() {
		final String javaHome = System.getProperty("java.home", "");
		return Path.of(javaHome, "bin", "javaw.exe").toString();
	}

	private static String resolveJar() {
		try {
			return Path.of(WindowsBackend.class.getProtectionDomain().getCodeSource()
					.getLocation().toURI()).toString();
		} catch (final Exception e) {
			return "servicepal.jar";   // best-effort fallback; the probe runs from the built jar
		}
	}

	private static String stem(final Path file) {
		final String name = file.getFileName().toString();
		return name.endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : name;
	}

	private static void ignoreFailure(final Runnable action) {
		try {
			action.run();
		} catch (final RuntimeException ignored) {
			// best-effort during teardown / replace
		}
	}
}
