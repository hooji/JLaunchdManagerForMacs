package com.u1.servicepal.internal.windows;

import com.u1.servicepal.Capabilities;
import com.u1.servicepal.DefinitionIOException;
import com.u1.servicepal.Installation;
import com.u1.servicepal.NativeCommandException;
import com.u1.servicepal.Platform;
import com.u1.servicepal.ServiceNotFoundException;
import com.u1.servicepal.UnmanagedServiceException;
import com.u1.servicepal.internal.Backend;
import com.u1.servicepal.internal.exec.DefaultCommandRunner;
import com.u1.servicepal.model.Discovery;
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
 * Windows backend. Routes by job shape (tension T2): a long-running spec becomes a
 * <strong>service</strong> registered with the SCM whose {@code binPath} is the bundled pure-Java
 * {@link ServiceHost} (which speaks the SCM protocol and supervises the real command), while a
 * <strong>scheduled</strong> spec ({@code schedule() != null}) becomes a <strong>Task Scheduler
 * task</strong> running the command directly. Either way a sidecar JSON in
 * {@code %ProgramData%\ServicePal\<id>.json} holds the full definition (the source of truth for
 * {@code read()} and the managed marker, and what the host reads at runtime).
 *
 * <p><strong>SYSTEM_WIDE only for v1</strong> ({@code perUserInstall=false}); a PER_USER spec
 * fails fast before reaching here. All install/delete operations require Administrator.
 */
public final class WindowsBackend implements Backend {

	private static final String HOST_CLASS = "com.u1.servicepal.internal.windows.ServiceHost";
	private static final String SIDECAR_SUFFIX = ".json";

	private final Scm scm;
	private final TaskScheduler taskScheduler;
	private final Path sidecarDir;
	private final String javawPath;
	private final String jarPath;
	private final SidecarWriter sidecarWriter = new SidecarWriter();
	private final SidecarReader sidecarReader = new SidecarReader();
	private final TaskXmlWriter taskXmlWriter = new TaskXmlWriter();

	public WindowsBackend(final Scm scm, final TaskScheduler taskScheduler, final Path sidecarDir,
			final String javawPath, final String jarPath) {
		this.scm = scm;
		this.taskScheduler = taskScheduler;
		this.sidecarDir = sidecarDir;
		this.javawPath = javawPath;
		this.jarPath = jarPath;
	}

	public static WindowsBackend createDefault() {
		final String programData = System.getenv("ProgramData");
		final Path dir = Path.of(programData != null ? programData
				: System.getProperty("user.home", "."), "ServicePal");
		final String javaw = Path.of(System.getProperty("java.home", ""), "bin", "javaw.exe")
				.toString();
		return new WindowsBackend(new FfmScm(), new SchtasksScheduler(new DefaultCommandRunner()),
				dir, javaw, resolveJarPath());
	}

	@Override
	public Platform platform() {
		return Platform.WINDOWS;
	}

	@Override
	public Capabilities capabilities() {
		// v1: SYSTEM_WIDE only (perUserInstall=false). Scheduling via Task Scheduler; keep-alive
		// via the host's respawn loop; structured status via QueryServiceStatusEx.
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
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(sidecarDir,
				"*" + SIDECAR_SUFFIX)) {
			for (final Path file : stream) {
				if (!Files.isRegularFile(file)) {
					continue;
				}
				try {
					services.add(buildStatus(idOf(file), sidecarReader.parseFile(file)));
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
		final Map<String, Object> json = readSidecar(id, installation);
		return json == null ? null : sidecarReader.toSpec(json);
	}

	@Override
	public String readNative(final String id, final Installation installation) {
		final Path file = sidecarFile(id, installation);
		if (file == null || !Files.isRegularFile(file)) {
			return null;
		}
		try {
			return Files.readString(file);
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to read " + file, e);
		}
	}

	@Override
	public ServiceStatus status(final String id, final Installation installation) {
		if (installation != Installation.SYSTEM_WIDE) {
			return null;   // Windows v1 is SYSTEM_WIDE only
		}
		final Map<String, Object> json = readSidecar(id, installation);
		if (json == null) {
			// No sidecar: still report a live, unmanaged OS object if one exists.
			if (scm.exists(id) || taskScheduler.exists(id)) {
				return liveStatusWithoutSidecar(id);
			}
			return null;
		}
		return buildStatus(id, json);
	}

	// --- mutation ---

	@Override
	public void install(final ServiceSpec spec, final boolean overwriteUnmanaged) {
		final ServiceKind kind = spec.schedule() != null ? ServiceKind.TASK : ServiceKind.SERVICE;
		final String id = spec.id();

		final boolean managedSidecar = isManagedSidecar(id);
		final boolean osExists = scm.exists(id) || taskScheduler.exists(id);
		if (osExists && !managedSidecar && !overwriteUnmanaged) {
			throw new UnmanagedServiceException(id);
		}

		writeSidecar(id, sidecarWriter.render(spec, kind));

		if (kind == ServiceKind.TASK) {
			taskScheduler.createFromXml(id, taskXmlWriter.render(spec));
			return;
		}
		final String binPath = binPath(id);
		final WinStartType startType = startTypeFor(spec);
		final String account = accountFor(spec);
		if (scm.exists(id)) {
			scm.updateConfig(id, binPath, startType, account, null);
		} else {
			scm.create(id, spec.displayName(), binPath, startType, account, null);
		}
		scm.setDescription(id, TaskXmlWriter.MARKER + " " + spec.displayName());
	}

	@Override
	public void uninstall(final String id, final Installation installation,
			final boolean unmanagedOk) {
		final boolean sidecarPresent = sidecarFile(id, installation) != null
				&& Files.isRegularFile(sidecarFile(id, installation));
		final boolean svc = scm.exists(id);
		final boolean task = taskScheduler.exists(id);
		if (!sidecarPresent && !svc && !task) {
			throw new ServiceNotFoundException(id);
		}
		if (!unmanagedOk && !isManagedSidecar(id)) {
			throw new UnmanagedServiceException(id);
		}
		if (svc) {
			ignoreFailure(() -> scm.stop(id));
			ignoreFailure(() -> scm.delete(id));
		}
		if (task) {
			ignoreFailure(() -> taskScheduler.end(id));
			ignoreFailure(() -> taskScheduler.delete(id));
		}
		deleteSidecar(id);
	}

	@Override
	public void enable(final String id, final Installation installation) {
		if (kindOf(id, installation) == ServiceKind.TASK) {
			requireTask(id);
			taskScheduler.setEnabled(id, true);
		} else {
			requireService(id);
			scm.setStartType(id, WinStartType.AUTO);
		}
		setSidecarAutoStart(id, installation, true);
	}

	@Override
	public void disable(final String id, final Installation installation) {
		if (kindOf(id, installation) == ServiceKind.TASK) {
			requireTask(id);
			taskScheduler.setEnabled(id, false);
		} else {
			requireService(id);
			scm.setStartType(id, WinStartType.DISABLED);
		}
		setSidecarAutoStart(id, installation, false);
	}

	@Override
	public void start(final String id, final Installation installation) {
		if (kindOf(id, installation) == ServiceKind.TASK) {
			requireTask(id);
			taskScheduler.run(id);
		} else {
			requireService(id);
			scm.start(id);
		}
	}

	@Override
	public void stop(final String id, final Installation installation) {
		if (kindOf(id, installation) == ServiceKind.TASK) {
			requireTask(id);
			taskScheduler.end(id);
		} else {
			requireService(id);
			scm.stop(id);
		}
	}

	@Override
	public void restart(final String id, final Installation installation) {
		stop(id, installation);
		start(id, installation);
	}

	// --- status helpers ---

	private ServiceStatus buildStatus(final String id, final Map<String, Object> json) {
		final boolean managed = sidecarReader.isManaged(json);
		final boolean autoStart = Boolean.TRUE.equals(json.get("autoStart"));
		if (sidecarReader.kindOf(json) == ServiceKind.TASK) {
			final TaskInfo ti = taskScheduler.query(id);
			final RunState state = ti == null ? RunState.UNKNOWN : ti.runState();
			final boolean enabled = ti == null ? autoStart : !ti.disabled();
			return new ServiceStatus(id, Installation.SYSTEM_WIDE, true, enabled, managed, state,
					null, null, null);
		}
		final ServiceControlStatus scs = scm.queryStatus(id);
		final RunState state = scs == null ? RunState.UNKNOWN : scs.runState();
		final Integer pid = scs == null ? null : scs.pid();
		final Integer exit = scs == null ? null : scs.win32ExitCode();
		// enabled (boot persistence) tracks the sidecar's autoStart, which enable/disable rewrite.
		return new ServiceStatus(id, Installation.SYSTEM_WIDE, true, autoStart, managed, state,
				pid, exit, null);
	}

	private ServiceStatus liveStatusWithoutSidecar(final String id) {
		final ServiceControlStatus scs = scm.queryStatus(id);
		if (scs != null) {
			return new ServiceStatus(id, Installation.SYSTEM_WIDE, true, false, false,
					scs.runState(), scs.pid(), scs.win32ExitCode(), null);
		}
		final TaskInfo ti = taskScheduler.query(id);
		final RunState state = ti == null ? RunState.UNKNOWN : ti.runState();
		return new ServiceStatus(id, Installation.SYSTEM_WIDE, true, false, false, state, null,
				null, null);
	}

	// --- binPath & mapping ---

	/** The service binPath: javaw launching the FFM host with native access enabled. */
	String binPath(final String id) {
		return "\"" + javawPath + "\" --enable-native-access=ALL-UNNAMED -cp \"" + jarPath
				+ "\" " + HOST_CLASS + " --id " + id;
	}

	private static WinStartType startTypeFor(final ServiceSpec spec) {
		final WindowsOptions opts = spec.windows();
		if (opts != null && opts.startType() != null) {
			return switch (opts.startType()) {
				case AUTO -> WinStartType.AUTO;
				case DELAYED_AUTO -> WinStartType.DELAYED_AUTO;
				case MANUAL -> WinStartType.MANUAL;
				case DISABLED -> WinStartType.DISABLED;
			};
		}
		return spec.autoStart() ? WinStartType.AUTO : WinStartType.MANUAL;
	}

	private static String accountFor(final ServiceSpec spec) {
		// null => LocalSystem (the CreateService default). Named user → that account.
		return spec.runAs().userName();
	}

	// --- sidecar I/O ---

	private ServiceKind kindOf(final String id, final Installation installation) {
		final Map<String, Object> json = readSidecar(id, installation);
		if (json != null) {
			return sidecarReader.kindOf(json);
		}
		return taskScheduler.exists(id) && !scm.exists(id) ? ServiceKind.TASK : ServiceKind.SERVICE;
	}

	private Map<String, Object> readSidecar(final String id, final Installation installation) {
		final Path file = sidecarFile(id, installation);
		if (file == null || !Files.isRegularFile(file)) {
			return null;
		}
		return sidecarReader.parseFile(file);
	}

	private boolean isManagedSidecar(final String id) {
		final Path file = sidecarFile(id, Installation.SYSTEM_WIDE);
		if (file == null || !Files.isRegularFile(file)) {
			return false;
		}
		try {
			return sidecarReader.isManaged(sidecarReader.parseFile(file));
		} catch (final DefinitionIOException e) {
			return false;
		}
	}

	private void setSidecarAutoStart(final String id, final Installation installation,
			final boolean autoStart) {
		final Path file = sidecarFile(id, installation);
		if (file == null || !Files.isRegularFile(file)) {
			return;
		}
		final Map<String, Object> json = sidecarReader.parseFile(file);
		json.put("autoStart", autoStart);
		writeSidecar(id, Json.write(json));
	}

	private void writeSidecar(final String id, final String json) {
		final Path file = sidecarDir.resolve(id + SIDECAR_SUFFIX);
		try {
			Files.createDirectories(sidecarDir);
			Files.writeString(file, json);
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to write " + file, e);
		}
	}

	private void deleteSidecar(final String id) {
		final Path file = sidecarDir.resolve(id + SIDECAR_SUFFIX);
		try {
			Files.deleteIfExists(file);
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to delete " + file, e);
		}
	}

	private Path sidecarFile(final String id, final Installation installation) {
		if (installation != Installation.SYSTEM_WIDE) {
			return null;
		}
		return sidecarDir.resolve(id + SIDECAR_SUFFIX);
	}

	private void requireService(final String id) {
		if (!scm.exists(id)) {
			throw new ServiceNotFoundException(id);
		}
	}

	private void requireTask(final String id) {
		if (!taskScheduler.exists(id)) {
			throw new ServiceNotFoundException(id);
		}
	}

	private static String idOf(final Path file) {
		final String name = file.getFileName().toString();
		return name.endsWith(SIDECAR_SUFFIX)
				? name.substring(0, name.length() - SIDECAR_SUFFIX.length()) : name;
	}

	private static String resolveJarPath() {
		try {
			return Path.of(WindowsBackend.class.getProtectionDomain().getCodeSource()
					.getLocation().toURI()).toString();
		} catch (final Exception e) {
			return "servicepal.jar";
		}
	}

	private static void ignoreFailure(final Runnable action) {
		try {
			action.run();
		} catch (final NativeCommandException | DefinitionIOException ignored) {
			// best-effort during teardown
		}
	}
}
