package com.u1.servicepal.internal.openrc;

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
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Linux/OpenRC backend. Writes init scripts to {@code /etc/init.d} and drives {@code rc-service}
 * (run-now) and {@code rc-update} (boot persistence, via runlevel symlinks under
 * {@code /etc/runlevels}). OpenRC has no first-class per-user services in our model, so this
 * backend is <strong>SYSTEM_WIDE only</strong> (the capability {@code perUserInstall} is false,
 * so a PER_USER spec fails fast before reaching here). There is no native scheduler either, so
 * calendar/interval schedules are rejected up front.
 */
public final class OpenRcBackend implements Backend {

	private final RcService rcService;
	private final Path initDir;
	private final Path runlevelsDir;
	private final RcScriptReader reader = new RcScriptReader();
	private final RcScriptWriter writer = new RcScriptWriter();

	public OpenRcBackend(final RcService rcService, final Path initDir, final Path runlevelsDir) {
		this.rcService = rcService;
		this.initDir = initDir;
		this.runlevelsDir = runlevelsDir;
	}

	public static OpenRcBackend createDefault() {
		return new OpenRcBackend(new DefaultRcService(new DefaultCommandRunner()),
				Path.of("/etc/init.d"), Path.of("/etc/runlevels"));
	}

	@Override
	public Platform platform() {
		return Platform.LINUX_OPENRC;
	}

	@Override
	public Capabilities capabilities() {
		// No per-user install, no native scheduler, no reliable pid/exit (structuredStatus).
		// keepAlive via supervise-daemon; conditional keep-alive is not modelled.
		return new Capabilities(false, true, true, false, false, true, false, true, false);
	}

	@Override
	public List<Installation> supportedInstallations() {
		return List.of(Installation.SYSTEM_WIDE);
	}

	@Override
	public Discovery discover(final Installation installation) {
		final List<ServiceStatus> services = new ArrayList<>();
		final List<String> unreadable = new ArrayList<>();
		if (installation != Installation.SYSTEM_WIDE || !Files.isDirectory(initDir)) {
			return new Discovery(services, unreadable);
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(initDir)) {
			for (final Path file : stream) {
				if (Files.isDirectory(file)) {
					continue;
				}
				try {
					services.add(buildStatus(file.getFileName().toString(), reader.read(file)));
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
		final Path file = findScript(id, installation);
		return file == null ? null : reader.toSpec(reader.read(file), id);
	}

	@Override
	public String readNative(final String id, final Installation installation) {
		final Path file = findScript(id, installation);
		return file == null ? null : reader.read(file);
	}

	@Override
	public ServiceStatus status(final String id, final Installation installation) {
		final Path file = findScript(id, installation);
		return file == null ? null : buildStatus(id, reader.read(file));
	}

	// --- mutation ---

	@Override
	public void install(final ServiceSpec spec, final boolean overwriteUnmanaged) {
		final Path file = initDir.resolve(spec.id());
		if (Files.isRegularFile(file) && !overwriteUnmanaged
				&& !reader.isManaged(reader.read(file))) {
			throw new UnmanagedServiceException(spec.id());
		}
		try {
			Files.createDirectories(initDir);
			Files.writeString(file, writer.render(spec));
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to write " + file, e);
		}
		makeExecutable(file);
	}

	@Override
	public void uninstall(final String id, final Installation installation,
			final boolean unmanagedOk) {
		final Path file = findScript(id, installation);
		if (file == null) {
			throw new ServiceNotFoundException(id);
		}
		final String text = reader.read(file);
		if (!unmanagedOk && !reader.isManaged(text)) {
			throw new UnmanagedServiceException(id);
		}
		ignoreFailure(() -> rcService.stop(id));
		ignoreFailure(() -> rcService.del(id, reader.runlevel(text)));
		try {
			Files.deleteIfExists(file);
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to delete " + file, e);
		}
	}

	@Override
	public void enable(final String id, final Installation installation) {
		rcService.add(id, reader.runlevel(reader.read(requireScript(id, installation))));
	}

	@Override
	public void disable(final String id, final Installation installation) {
		rcService.del(id, reader.runlevel(reader.read(requireScript(id, installation))));
	}

	@Override
	public void start(final String id, final Installation installation) {
		requireScript(id, installation);
		rcService.start(id);
	}

	@Override
	public void stop(final String id, final Installation installation) {
		requireScript(id, installation);
		rcService.stop(id);
	}

	@Override
	public void restart(final String id, final Installation installation) {
		requireScript(id, installation);
		rcService.restart(id);
	}

	private ServiceStatus buildStatus(final String id, final String text) {
		final boolean managed = reader.isManaged(text);
		final RcStatus rs = rcService.status(id);
		return new ServiceStatus(id, Installation.SYSTEM_WIDE, true, isEnabled(id), managed,
				runState(rs.state()), null, null, rs.raw());
	}

	private static RunState runState(final String state) {
		return switch (state) {
			case "started" -> RunState.RUNNING;
			case "stopped", "inactive" -> RunState.STOPPED;
			case "starting" -> RunState.STARTING;
			case "stopping" -> RunState.STOPPING;
			case "crashed" -> RunState.FAILED;
			default -> RunState.UNKNOWN;
		};
	}

	/** Enabled = the service is symlinked into some runlevel directory under {@link #runlevelsDir}. */
	private boolean isEnabled(final String id) {
		if (!Files.isDirectory(runlevelsDir)) {
			return false;
		}
		try (DirectoryStream<Path> levels = Files.newDirectoryStream(runlevelsDir)) {
			for (final Path level : levels) {
				if (Files.isDirectory(level)
						&& Files.exists(level.resolve(id), LinkOption.NOFOLLOW_LINKS)) {
					return true;
				}
			}
		} catch (final IOException e) {
			// unreadable — treat as not enabled
		}
		return false;
	}

	private Path findScript(final String id, final Installation installation) {
		if (installation != Installation.SYSTEM_WIDE) {
			return null;
		}
		final Path candidate = initDir.resolve(id);
		return Files.isRegularFile(candidate) ? candidate : null;
	}

	private Path requireScript(final String id, final Installation installation) {
		final Path file = findScript(id, installation);
		if (file == null) {
			throw new ServiceNotFoundException(id);
		}
		return file;
	}

	/** OpenRC won't run a non-executable init script; make ours {@code rwxr-xr-x}. Best-effort. */
	private static void makeExecutable(final Path file) {
		final Set<PosixFilePermission> perms = EnumSet.of(
				PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
				PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
				PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
				PosixFilePermission.OTHERS_EXECUTE);
		try {
			Files.setPosixFilePermissions(file, perms);
		} catch (final UnsupportedOperationException | IOException e) {
			// Non-POSIX filesystem (e.g. a Windows CI runner running unit tests) — ignore.
			file.toFile().setExecutable(true, false);
		}
	}

	private static void ignoreFailure(final Runnable action) {
		try {
			action.run();
		} catch (final NativeCommandException ignored) {
			// best-effort during teardown
		}
	}
}
