package com.u1.servicepal.internal.openrc;

import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.options.OpenRcOptions;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link ServiceSpec} to an OpenRC init script (POSIX shell sourcing
 * {@code /sbin/openrc-run}). The write-side counterpart to {@link RcScriptReader}; stamps the
 * managed marker so discovery can recognize our scripts.
 *
 * <p>Restart policy maps to the supervisor: {@code NEVER} → {@code start-stop-daemon} (a
 * backgrounded one-shot, no respawn); {@code ON_FAILURE}/{@code ALWAYS} → {@code supervise-daemon}
 * (respawns the daemon), with {@code ALWAYS} lifting the respawn cap. An explicit
 * {@code .openrc().supervisor(...)} overrides the derived choice.
 */
public final class RcScriptWriter {

	/** The runlevel an enabled service is added to when none is specified. */
	public static final String DEFAULT_RUNLEVEL = "default";

	public String render(final ServiceSpec spec) {
		final OpenRcOptions opts = spec.openrc();
		final StringBuilder sb = new StringBuilder();

		sb.append("#!/sbin/openrc-run\n");
		sb.append(RcScriptReader.MANAGED_MARKER).append('\n');
		sb.append("# ").append(RcScriptReader.RUNLEVEL_MARKER_PREFIX).append(runlevel(opts))
				.append('\n');
		sb.append("# Managed by ServicePal — edits may be overwritten on the next install.\n");
		sb.append('\n');

		sb.append("description=").append(quote(spec.displayName())).append('\n');
		sb.append("command=").append(quote(spec.command().get(0))).append('\n');
		final String args = argsOf(spec.command());
		if (!args.isEmpty()) {
			sb.append("command_args=").append(quote(args)).append('\n');
		}
		if (spec.runAs().kind() == RunAs.Kind.NAMED_USER) {
			sb.append("command_user=").append(quote(spec.runAs().userName())).append('\n');
		}
		if (spec.workingDirectory() != null) {
			sb.append("directory=").append(quote(spec.workingDirectory().toString())).append('\n');
		}
		// Track the daemon by pid so start/stop/status work for a foreground command.
		sb.append("pidfile=").append(quote("/run/${RC_SVCNAME}.pid")).append('\n');

		if (supervised(spec, opts)) {
			sb.append("supervisor=").append(quote("supervise-daemon")).append('\n');
			if (spec.restart() == RestartPolicy.ALWAYS) {
				sb.append("respawn_max=0\n");   // 0 = no cap: keep respawning indefinitely
			}
		} else {
			// start-stop-daemon won't return until the command exits unless we background it.
			sb.append("command_background=true\n");
		}

		if (spec.stdout() != null) {
			sb.append("output_log=").append(quote(spec.stdout().toString())).append('\n');
		}
		if (spec.stderr() != null) {
			sb.append("error_log=").append(quote(spec.stderr().toString())).append('\n');
		}
		for (final Map.Entry<String, String> e : spec.environment().entrySet()) {
			sb.append("export ").append(e.getKey()).append('=').append(quote(e.getValue()))
					.append('\n');
		}

		sb.append('\n');
		sb.append("depend() {\n");
		final List<String> need = opts != null ? opts.need() : List.of();
		if (!need.isEmpty()) {
			sb.append('\t').append("need ").append(String.join(" ", need)).append('\n');
		}
		sb.append("}\n");
		return sb.toString();
	}

	/** The runlevel this script enables into (option override, else {@link #DEFAULT_RUNLEVEL}). */
	static String runlevel(final OpenRcOptions opts) {
		if (opts != null && opts.runlevel() != null && !opts.runlevel().isBlank()) {
			return opts.runlevel();
		}
		return DEFAULT_RUNLEVEL;
	}

	private static boolean supervised(final ServiceSpec spec, final OpenRcOptions opts) {
		if (opts != null && opts.supervisor() != null) {
			return opts.supervisor() == OpenRcOptions.Supervisor.SUPERVISE_DAEMON;
		}
		return spec.restart() != RestartPolicy.NEVER;
	}

	private static String argsOf(final List<String> command) {
		final StringBuilder sb = new StringBuilder();
		for (int i = 1; i < command.size(); i++) {
			if (i > 1) {
				sb.append(' ');
			}
			sb.append(command.get(i));
		}
		return sb.toString();
	}

	/** Double-quote a shell value, escaping backslashes and quotes (variables still expand). */
	private static String quote(final String value) {
		final StringBuilder sb = new StringBuilder("\"");
		for (int i = 0; i < value.length(); i++) {
			final char c = value.charAt(i);
			if (c == '\\' || c == '"') {
				sb.append('\\');
			}
			sb.append(c);
		}
		return sb.append('"').toString();
	}
}
