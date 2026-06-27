package com.u1.servicepal.internal.openrc;

import com.u1.servicepal.NativeCommandException;
import com.u1.servicepal.internal.exec.CommandResult;
import com.u1.servicepal.internal.exec.CommandRunner;
import java.util.List;
import java.util.Locale;

/** Drives {@code rc-update} and {@code rc-service} via subprocess. */
public final class DefaultRcService implements RcService {

	private final CommandRunner runner;

	public DefaultRcService(final CommandRunner runner) {
		this.runner = runner;
	}

	@Override
	public void add(final String name, final String runlevel) {
		mutate(List.of("rc-update", "add", name, runlevel));
	}

	@Override
	public void del(final String name, final String runlevel) {
		mutate(List.of("rc-update", "del", name, runlevel));
	}

	@Override
	public void start(final String name) {
		mutate(List.of("rc-service", name, "start"));
	}

	@Override
	public void stop(final String name) {
		mutate(List.of("rc-service", name, "stop"));
	}

	@Override
	public void restart(final String name) {
		mutate(List.of("rc-service", name, "restart"));
	}

	@Override
	public RcStatus status(final String name) {
		// `rc-service <name> status` exits non-zero when stopped/crashed — don't treat that as an
		// error; parse the textual status regardless.
		return parseStatus(runner.run(List.of("rc-service", name, "status")));
	}

	/** Map {@code rc-service status} output to a coarse status word. Package-visible for testing. */
	static RcStatus parseStatus(final CommandResult res) {
		final String out = ((res.stdout() == null ? "" : res.stdout()) + "\n"
				+ (res.stderr() == null ? "" : res.stderr())).toLowerCase(Locale.ROOT);
		if (out.contains("crashed")) {
			return new RcStatus("crashed");
		}
		if (out.contains("started")) {
			return new RcStatus("started");
		}
		if (out.contains("stopping")) {
			return new RcStatus("stopping");
		}
		if (out.contains("starting")) {
			return new RcStatus("starting");
		}
		if (out.contains("stopped")) {
			return new RcStatus("stopped");
		}
		if (out.contains("inactive")) {
			return new RcStatus("inactive");
		}
		// Nothing recognizable in the text: fall back to the exit code (0 == started).
		return new RcStatus(res.ok() ? "started" : null);
	}

	private void mutate(final List<String> command) {
		final CommandResult res = runner.run(command);
		if (!res.ok()) {
			throw new NativeCommandException(command, res.exitCode(), res.stderr());
		}
	}
}
