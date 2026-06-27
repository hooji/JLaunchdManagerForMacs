package com.u1.servicepal.internal;

import com.u1.servicepal.Capabilities;
import com.u1.servicepal.Installation;
import com.u1.servicepal.Platform;
import com.u1.servicepal.model.Discovery;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import java.util.List;

/**
 * Defensive fallback for a {@link Platform} with no wired backend. All four current platforms
 * (macOS, systemd, OpenRC, Windows) have real backends, so this is now unreachable from
 * {@link DefaultServiceManager#create} — it remains only as a safety net (and a source of each
 * platform's intended {@link Capabilities}) should a new platform be added before its backend.
 */
public final class UnimplementedBackend implements Backend {

	private final Platform platform;

	public UnimplementedBackend(final Platform platform) {
		this.platform = platform;
	}

	@Override
	public Platform platform() {
		return platform;
	}

	@Override
	public Capabilities capabilities() {
		return defaultFor(platform);
	}

	@Override
	public List<Installation> supportedInstallations() {
		if (platform == Platform.LINUX_OPENRC) {
			return List.of(Installation.SYSTEM_WIDE);
		}
		return List.of(Installation.PER_USER, Installation.SYSTEM_WIDE);
	}

	@Override
	public Discovery discover(final Installation installation) {
		throw notImplemented();
	}

	@Override
	public ServiceSpec read(final String id, final Installation installation) {
		throw notImplemented();
	}

	@Override
	public String readNative(final String id, final Installation installation) {
		throw notImplemented();
	}

	@Override
	public ServiceStatus status(final String id, final Installation installation) {
		throw notImplemented();
	}

	@Override
	public void install(final ServiceSpec spec, final boolean overwriteUnmanaged) {
		throw notImplemented();
	}

	@Override
	public void uninstall(final String id, final Installation installation,
			final boolean unmanagedOk) {
		throw notImplemented();
	}

	@Override
	public void enable(final String id, final Installation installation) {
		throw notImplemented();
	}

	@Override
	public void disable(final String id, final Installation installation) {
		throw notImplemented();
	}

	@Override
	public void start(final String id, final Installation installation) {
		throw notImplemented();
	}

	@Override
	public void stop(final String id, final Installation installation) {
		throw notImplemented();
	}

	@Override
	public void restart(final String id, final Installation installation) {
		throw notImplemented();
	}

	private UnsupportedOperationException notImplemented() {
		return new UnsupportedOperationException("the " + platform + " backend is not yet"
				+ " implemented; macOS, Linux/systemd and Linux/OpenRC are supported");
	}

	/** Intended capabilities per platform (informational until the backend lands). */
	public static Capabilities defaultFor(final Platform platform) {
		switch (platform) {
			case LINUX_SYSTEMD:
				return new Capabilities(true, true, true, true, true, true, true, true, true);
			case LINUX_OPENRC:
				return new Capabilities(false, true, true, false, false, true, false, true, false);
			case WINDOWS:
				return new Capabilities(true, true, true, true, true, true, false, true, true);
			case MACOS_LAUNCHD:
			default:
				return new Capabilities(true, true, true, true, true, true, true, true, false);
		}
	}
}
