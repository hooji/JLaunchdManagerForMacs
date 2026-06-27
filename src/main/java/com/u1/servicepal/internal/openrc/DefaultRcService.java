package com.u1.servicepal.internal.openrc;

import com.u1.servicepal.NativeCommandException;
import com.u1.servicepal.internal.exec.CommandResult;
import com.u1.servicepal.internal.exec.CommandRunner;
import java.util.List;
import java.util.Locale;

/** Drives {@code rc-service} / {@code rc-update} via subprocess. */
public final class DefaultRcService implements RcService {

	private final CommandRunner runner;

	public DefaultRcService(final CommandRunner runner) {
		this.runner = runner;
	}

	@Override
	public void add(final String service, final String runlevel) {
		mutate(List.of("rc-update", "add", service, runlevel));
	}

	@Override
	public void del(final String service, final String runlevel) {
		mutate(List.of("rc-update", "del", service, runlevel));
	}

	@Override
	public void start(final String service) {
		mutate(List.of("rc-service", service, "start"));
	}

	@Override
	public void stop(final String service) {
		mutate(List.of("rc-service", service, "stop"));
	}

	@Override
	public void restart(final String service) {
		mutate(List.of("rc-service", service, "restart"));
	}

	@Override
	public RcStatus status(final String service) {
		final CommandResult res = runner.run(List.of("rc-service", service, "status"));
		final String out = merge(res.stdout(), res.stderr());
		if (out.isBlank()) {
			return RcStatus.notFound();
		}
		return new RcStatus(parseState(out), out);
	}

	/**
	 * Map {@code rc-service status} text to a coarse state word. Package-visible for testing.
	 * Order matters: {@code starting}/{@code stopping} are checked before {@code started}/
	 * {@code stopped} (they share a prefix).
	 */
	static String parseState(final String out) {
		final String s = out.toLowerCase(Locale.ROOT);
		if (s.contains("crashed")) {
			return "crashed";
		}
		if (s.contains("starting")) {
			return "starting";
		}
		if (s.contains("stopping")) {
			return "stopping";
		}
		if (s.contains("started")) {
			return "started";
		}
		if (s.contains("stopped")) {
			return "stopped";
		}
		if (s.contains("inactive")) {
			return "inactive";
		}
		return "unknown";
	}

	private static String merge(final String stdout, final String stderr) {
		return (stdout == null ? "" : stdout) + "\n" + (stderr == null ? "" : stderr);
	}

	private void mutate(final List<String> command) {
		final CommandResult res = runner.run(command);
		if (!res.ok()) {
			throw new NativeCommandException(command, res.exitCode(), res.stderr());
		}
	}
}
