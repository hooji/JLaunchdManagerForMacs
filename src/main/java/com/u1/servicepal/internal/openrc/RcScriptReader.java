package com.u1.servicepal.internal.openrc;

import com.u1.servicepal.DefinitionIOException;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.ServiceSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses an OpenRC init script back into our model. Best-effort: only the simple
 * {@code key="value"} variable assignments we emit are understood; hand-authored scripts that
 * drive the daemon from {@code start()}/{@code stop()} functions won't fully round-trip (the
 * verbatim text is still available via {@code readNative}).
 */
public final class RcScriptReader {

	/** Marker comment stamped into scripts we create. */
	public static final String MANAGED_MARKER = "# X-ServicePal-Managed: 1";

	/** Prefix (within a comment) recording the runlevel an enabled service belongs to. */
	public static final String RUNLEVEL_MARKER_PREFIX = "X-ServicePal-Runlevel: ";

	public String read(final Path file) {
		try {
			return Files.readString(file);
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to read init script " + file, e);
		}
	}

	public boolean isManaged(final String text) {
		return text.contains(MANAGED_MARKER);
	}

	/** The runlevel recorded in our marker comment, or {@link RcScriptWriter#DEFAULT_RUNLEVEL}. */
	public String runlevel(final String text) {
		for (final String raw : text.split("\n")) {
			final int at = raw.indexOf(RUNLEVEL_MARKER_PREFIX);
			if (at >= 0) {
				final String value = raw.substring(at + RUNLEVEL_MARKER_PREFIX.length()).strip();
				if (!value.isEmpty()) {
					return value;
				}
			}
		}
		return RcScriptWriter.DEFAULT_RUNLEVEL;
	}

	/** Parse top-level {@code key=value} assignments (quotes stripped). Comments/functions ignored. */
	public Map<String, String> parseVars(final String text) {
		final Map<String, String> vars = new LinkedHashMap<>();
		for (final String raw : text.split("\n")) {
			final String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			final int eq = line.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			final String key = line.substring(0, eq).strip();
			if (!isAssignmentKey(key)) {
				continue;   // skip anything that isn't a bare shell identifier (e.g. "if [ x" )
			}
			vars.put(key, unquote(line.substring(eq + 1).strip()));
		}
		return vars;
	}

	/** Map a parsed script into a {@link ServiceSpec}. {@code id} is the script's filename. */
	public ServiceSpec toSpec(final String text, final String id) {
		final Map<String, String> vars = parseVars(text);
		final ServiceSpec.Builder b = ServiceSpec.builder().id(id);

		final String description = vars.get("description");
		if (description != null) {
			b.displayName(description);
		}

		final String command = vars.get("command");
		if (command == null || command.isBlank()) {
			throw new DefinitionIOException("init script has no command= variable", null);
		}
		final List<String> argv = new ArrayList<>();
		argv.add(command);
		final String commandArgs = vars.get("command_args");
		if (commandArgs != null && !commandArgs.isBlank()) {
			for (final String token : commandArgs.strip().split("\\s+")) {
				if (!token.isEmpty()) {
					argv.add(token);
				}
			}
		}
		b.command(argv);

		final String user = vars.get("command_user");
		if (user != null && !user.isBlank()) {
			b.asUser(stripGroup(user));   // OpenRC allows "user:group"; we model the user only
		} else {
			b.asSystemDaemon();
		}

		final String dir = vars.get("directory");
		if (dir != null && !dir.isBlank()) {
			b.workingDirectory(Path.of(dir));
		}
		if (vars.get("output_log") != null) {
			b.stdout(Path.of(vars.get("output_log")));
		}
		if (vars.get("error_log") != null) {
			b.stderr(Path.of(vars.get("error_log")));
		}
		b.restart(restartOf(vars));
		return b.build();
	}

	private static RestartPolicy restartOf(final Map<String, String> vars) {
		if (!"supervise-daemon".equals(vars.get("supervisor"))) {
			return RestartPolicy.NEVER;
		}
		return "0".equals(vars.get("respawn_max")) ? RestartPolicy.ALWAYS : RestartPolicy.ON_FAILURE;
	}

	private static String stripGroup(final String user) {
		final int colon = user.indexOf(':');
		return colon < 0 ? user : user.substring(0, colon);
	}

	private static boolean isAssignmentKey(final String key) {
		for (int i = 0; i < key.length(); i++) {
			final char c = key.charAt(i);
			if (!(Character.isLetterOrDigit(c) || c == '_')) {
				return false;
			}
		}
		return !key.isEmpty();
	}

	private static String unquote(final String value) {
		if (value.length() >= 2
				&& (value.charAt(0) == '"' || value.charAt(0) == '\'')
				&& value.charAt(value.length() - 1) == value.charAt(0)) {
			final String inner = value.substring(1, value.length() - 1);
			return inner.replace("\\\"", "\"").replace("\\\\", "\\");
		}
		return value;
	}
}
