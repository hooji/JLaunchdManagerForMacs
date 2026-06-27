package com.u1.servicepal.internal.windows;

import com.u1.servicepal.DefinitionIOException;
import com.u1.servicepal.model.CalendarSpec;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the Windows sidecar JSON back into our model. Counterpart to {@link SidecarWriter}; also
 * the parser {@link ServiceHost} uses at runtime to recover the command it must supervise.
 */
public final class SidecarReader {

	/** Marker key (top-level boolean) stamped into sidecars we create. */
	public static final String MANAGED_KEY = "servicePalManaged";

	@SuppressWarnings("unchecked")
	public Map<String, Object> parse(final String json) {
		final Object root = Json.parse(json);
		if (!(root instanceof Map)) {
			throw new DefinitionIOException("sidecar root is not a JSON object", null);
		}
		return (Map<String, Object>) root;
	}

	public Map<String, Object> parseFile(final Path file) {
		try {
			return parse(Files.readString(file));
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to read sidecar " + file, e);
		}
	}

	public boolean isManaged(final Map<String, Object> json) {
		return Boolean.TRUE.equals(json.get(MANAGED_KEY));
	}

	public ServiceKind kindOf(final Map<String, Object> json) {
		return "TASK".equals(str(json.get("kind"))) ? ServiceKind.TASK : ServiceKind.SERVICE;
	}

	public ServiceSpec toSpec(final Map<String, Object> json) {
		final ServiceSpec.Builder b = ServiceSpec.builder();
		final String id = str(json.get("id"));
		if (id != null) {
			b.id(id);
		}
		final String displayName = str(json.get("displayName"));
		if (displayName != null) {
			b.displayName(displayName);
		}
		final String description = str(json.get("description"));
		if (description != null) {
			b.description(description);
		}

		final List<String> command = stringList(json.get("command"));
		if (command.isEmpty()) {
			throw new DefinitionIOException("sidecar has no command", null);
		}
		b.command(command);

		final String wd = str(json.get("workingDirectory"));
		if (wd != null) {
			b.workingDirectory(Path.of(wd));
		}
		final Map<String, String> env = stringMap(json.get("environment"));
		if (!env.isEmpty()) {
			b.environment(env);
		}
		b.runAs(runAs(json.get("runAs")));
		final String stdout = str(json.get("stdout"));
		if (stdout != null) {
			b.stdout(Path.of(stdout));
		}
		final String stderr = str(json.get("stderr"));
		if (stderr != null) {
			b.stderr(Path.of(stderr));
		}
		b.autoStart(Boolean.TRUE.equals(json.get("autoStart")));
		b.restart(restart(str(json.get("restart"))));
		final Schedule schedule = schedule(json.get("schedule"));
		if (schedule != null) {
			b.schedule(schedule);
		}
		return b.build();
	}

	private static RunAs runAs(final Object value) {
		if (!(value instanceof Map<?, ?> map)) {
			return RunAs.currentUser();
		}
		final String kind = str(map.get("kind"));
		if ("NAMED_USER".equals(kind)) {
			return RunAs.namedUser(str(map.get("user")));
		}
		if ("SYSTEM_DAEMON".equals(kind)) {
			return RunAs.systemDaemon();
		}
		return RunAs.currentUser();
	}

	private static RestartPolicy restart(final String value) {
		if (value == null) {
			return RestartPolicy.NEVER;
		}
		return switch (value) {
			case "ALWAYS" -> RestartPolicy.ALWAYS;
			case "ON_FAILURE" -> RestartPolicy.ON_FAILURE;
			default -> RestartPolicy.NEVER;
		};
	}

	private static Schedule schedule(final Object value) {
		if (!(value instanceof Map<?, ?> map)) {
			return null;
		}
		final String type = str(map.get("type"));
		if ("interval".equals(type)) {
			final Long seconds = asLong(map.get("periodSeconds"));
			return Schedule.every(Duration.ofSeconds(seconds == null ? 0 : seconds));
		}
		if ("calendar".equals(type)) {
			return Schedule.calendar(new CalendarSpec(
					asInt(map.get("minute")), asInt(map.get("hour")),
					asInt(map.get("dayOfMonth")), asInt(map.get("month")),
					asInt(map.get("dayOfWeek"))));
		}
		return null;
	}

	private static List<String> stringList(final Object value) {
		final List<String> out = new ArrayList<>();
		if (value instanceof List<?> list) {
			for (final Object item : list) {
				out.add(String.valueOf(item));
			}
		}
		return out;
	}

	private static Map<String, String> stringMap(final Object value) {
		final Map<String, String> out = new LinkedHashMap<>();
		if (value instanceof Map<?, ?> map) {
			for (final Map.Entry<?, ?> e : map.entrySet()) {
				out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
			}
		}
		return out;
	}

	private static String str(final Object value) {
		return value == null ? null : value.toString();
	}

	private static Long asLong(final Object value) {
		return value instanceof Number n ? n.longValue() : null;
	}

	private static Integer asInt(final Object value) {
		return value instanceof Number n ? n.intValue() : null;
	}
}
