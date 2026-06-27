package com.u1.servicepal.internal.windows;

import com.u1.servicepal.DefinitionIOException;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.ServiceSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses the Windows sidecar JSON back into our model. Best-effort, mirroring the other
 * backends' readers. The read-side counterpart to {@link SidecarWriter}.
 */
public final class SidecarReader {

	/** Marker key; its presence means ServicePal created this service. */
	public static final String MANAGED_KEY = "servicePalManaged";
	public static final String MANAGED_VALUE = "com.u1.servicepal";

	/** Which Windows subsystem this id lives in (so by-id ops route correctly). */
	public static final String KIND_KEY = "kind";
	public static final String KIND_SERVICE = "SERVICE";
	public static final String KIND_TASK = "TASK";

	public Map<String, Object> parse(final String json) {
		return Json.parseObject(json);
	}

	public Map<String, Object> parseFile(final Path file) {
		try {
			return Json.parseObject(Files.readString(file));
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to read sidecar " + file, e);
		}
	}

	public boolean isManaged(final Map<String, Object> sidecar) {
		return MANAGED_VALUE.equals(sidecar.get(MANAGED_KEY));
	}

	/** {@link #KIND_TASK} for a scheduled job, else {@link #KIND_SERVICE} (the default). */
	public String kind(final Map<String, Object> sidecar) {
		return KIND_TASK.equals(sidecar.get(KIND_KEY)) ? KIND_TASK : KIND_SERVICE;
	}

	public boolean autoStart(final Map<String, Object> sidecar) {
		return Boolean.parseBoolean(str(sidecar.get("autoStart")));
	}

	/** Map a parsed sidecar into a {@link ServiceSpec}. {@code id} is the sidecar's stem. */
	public ServiceSpec toSpec(final Map<String, Object> sidecar, final String id) {
		final ServiceSpec.Builder b = ServiceSpec.builder().id(id);

		final String displayName = str(sidecar.get("displayName"));
		if (displayName != null) {
			b.displayName(displayName);
		}
		final String description = str(sidecar.get("description"));
		if (description != null) {
			b.description(description);
		}

		b.command(stringList(sidecar.get("command")));

		final String workingDirectory = str(sidecar.get("workingDirectory"));
		if (workingDirectory != null) {
			b.workingDirectory(Path.of(workingDirectory));
		}
		for (final Map.Entry<String, String> e : stringMap(sidecar.get("environment")).entrySet()) {
			b.env(e.getKey(), e.getValue());
		}
		final String stdout = str(sidecar.get("stdout"));
		if (stdout != null) {
			b.stdout(Path.of(stdout));
		}
		final String stderr = str(sidecar.get("stderr"));
		if (stderr != null) {
			b.stderr(Path.of(stderr));
		}
		b.autoStart(autoStart(sidecar));

		final String restart = str(sidecar.get("restart"));
		if (restart != null) {
			b.restart(parseRestart(restart));
		}

		final String runAsKind = str(sidecar.get("runAsKind"));
		final String runAsUser = str(sidecar.get("runAsUser"));
		if (RunAs.Kind.NAMED_USER.name().equals(runAsKind) && runAsUser != null) {
			b.runAs(RunAs.namedUser(runAsUser));
		} else if (RunAs.Kind.SYSTEM_DAEMON.name().equals(runAsKind)) {
			b.asSystemDaemon();
		} else {
			b.asCurrentUser();
		}
		return b.build();
	}

	private static RestartPolicy parseRestart(final String value) {
		try {
			return RestartPolicy.valueOf(value);
		} catch (final IllegalArgumentException e) {
			return RestartPolicy.NEVER;
		}
	}

	private static String str(final Object value) {
		return value instanceof String ? (String) value : null;
	}

	private static List<String> stringList(final Object value) {
		final List<String> result = new ArrayList<>();
		if (value instanceof List) {
			for (final Object item : (List<?>) value) {
				if (item instanceof String) {
					result.add((String) item);
				}
			}
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, String> stringMap(final Object value) {
		final Map<String, String> result = new java.util.LinkedHashMap<>();
		if (value instanceof Map) {
			for (final Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
				if (e.getValue() instanceof String) {
					result.put(e.getKey(), (String) e.getValue());
				}
			}
		}
		return result;
	}
}
