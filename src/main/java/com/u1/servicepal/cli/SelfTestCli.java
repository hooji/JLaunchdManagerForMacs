package com.u1.servicepal.cli;

import com.u1.servicepal.Platform;
import com.u1.servicepal.ServiceException;
import com.u1.servicepal.ServiceManager;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;

/**
 * Exercises the real mutation path end-to-end: install → start → inspect → uninstall a
 * throwaway PER_USER agent, printing PASS/FAIL per check. Intended to be RUN on a real Mac
 * (locally or via the CI probe) — it actually talks to launchctl. On non-macOS platforms it
 * prints SKIP. Exits non-zero if any check fails.
 */
public final class SelfTestCli {

	private static final String ID = "com.u1.servicepal.selftest";

	private SelfTestCli() {
	}

	public static void main(final String[] args) {
		final ServiceManager mgr;
		try {
			mgr = ServiceManager.getServiceManager();
		} catch (final ServiceException e) {
			System.out.println("SELFTEST SKIP: could not initialize: " + e.getMessage());
			return;
		}
		final Platform platform = mgr.platform();
		final boolean root = "root".equals(System.getProperty("user.name"));
		final ServiceSpec.Builder builder = ServiceSpec.builder()
				.id(ID)
				.autoStart(true);
		// A long-running command with no console (the supervisor keeps it alive). /bin/sleep on
		// Unix; ping on Windows (sleep doesn't exist there).
		String[] command = {"/bin/sleep", "120"};
		if (platform == Platform.MACOS_LAUNCHD) {
			builder.asCurrentUser();   // a per-user launchd agent; no root needed
		} else if (platform == Platform.LINUX_SYSTEMD) {
			if (!root) {
				System.out.println("SELFTEST SKIP: the systemd self-test installs a system-wide"
						+ " unit and needs sudo (this is " + platform + ", non-root)");
				return;
			}
			builder.asSystemDaemon();
		} else if (platform == Platform.LINUX_OPENRC) {
			if (!root) {
				System.out.println("SELFTEST SKIP: the OpenRC self-test installs a system-wide"
						+ " init script and needs sudo (this is " + platform + ", non-root)");
				return;
			}
			builder.asSystemDaemon();   // OpenRC is SYSTEM_WIDE only
		} else if (platform == Platform.WINDOWS) {
			// SYSTEM_WIDE service supervised by the bundled FFM ServiceHost. Needs Administrator
			// (CreateService) — the windows-latest runner is admin.
			command = new String[] {"ping", "-n", "300", "127.0.0.1"};
			builder.asSystemDaemon();
		} else {
			System.out.println("SELFTEST SKIP: mutation not implemented for " + platform);
			return;
		}
		final String program = command[0];
		final ServiceSpec spec = builder.command(command).build();

		int failures = 0;
		try {
			if (mgr.isInstalled(ID)) {
				mgr.uninstall(ID, true);
			}
			mgr.installEnableStart(spec);

			// Allow a moment for the service to come up (the Windows FFM host has to launch a JVM,
			// register with the SCM, and report RUNNING) before asserting state.
			ServiceStatus st = mgr.status(ID);
			for (int i = 0; i < 30 && st.state() != RunState.RUNNING; i++) {
				Thread.sleep(500);
				st = mgr.status(ID);
			}
			System.out.println("status: installed=" + st.installed() + " managed=" + st.managed()
					+ " state=" + st.state() + " pid=" + st.pid());
			failures += check("installed", st.installed());
			failures += check("managed by us", st.managed());
			failures += check("running", st.state() == RunState.RUNNING);
			failures += check("has pid", st.pid() != null);
			failures += check("isManaged(id)", mgr.isManaged(ID));

			final String raw = mgr.readNative(ID);
			// The managed marker is platform-specific; isManaged(id) above already proves it's
			// recognized. Here just confirm readNative returns this service's definition.
			failures += check("readNative returns the definition", raw != null && raw.contains(ID));

			final ServiceSpec back = mgr.read(ID);
			failures += check("read round-trips command",
					back != null && back.command().contains(program));
		} catch (final Throwable t) {
			System.out.println("SELFTEST ERROR: " + t);
			t.printStackTrace(System.out);
			failures++;
		} finally {
			try {
				if (mgr.isInstalled(ID)) {
					mgr.uninstall(ID, true);
				}
			} catch (final Throwable t) {
				System.out.println("cleanup warning: " + t);
			}
		}

		try {
			failures += check("uninstalled", !mgr.isInstalled(ID));
		} catch (final Throwable t) {
			System.out.println("post-check error: " + t);
			failures++;
		}

		System.out.println(failures == 0 ? "SELFTEST PASS"
				: "SELFTEST FAIL (" + failures + " failing checks)");
		if (failures > 0) {
			System.exit(1);
		}
	}

	private static int check(final String name, final boolean ok) {
		System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] " + name);
		return ok ? 0 : 1;
	}
}
