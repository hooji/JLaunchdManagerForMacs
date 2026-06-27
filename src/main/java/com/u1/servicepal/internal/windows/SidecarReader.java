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
 * Parses a Windows sidecar JSON (see {@link SidecarWriter}) back into our model. Provides both a
 * full {@link ServiceSpec} reconstruction (for {@code read()}) and the narrow accessors
 * {@link ServiceHost} needs to supervise the child (command, working dir, env, log paths,
 * restart policy).
 */
public final class SidecarReader {

	/** Marker key stamped into sidecars we create (its presence ⇒ managed). */
	public static final String MANAGED_KEY = "servicePalManaged";

	public Map<String, Object> parseFile(final Path file) {
		final String text;
		try {
			text = Files.readString(file);
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to read sidecar " + file, e);
		}
		try {
			return Json.parseObject(text);
		} catch (final RuntimeException e) {
			throw new DefinitionIOException("malformed sidecar " + file, e);
		}
	}

	public boolean isManaged(final Map<String, Object> sidecar) {
		return Boolean.TRUE.equals(sidecar.get(MANAGED_KEY));
	}

	public List<String> command(final Map<String, Object> sidecar) {
		final List<String> out = new ArrayList<>();
		final Object value = sidecar.get("command");
		if (value instanceof List<?> list) {
			for (final Object o : list) {
				out.add(String.valueOf(o));
			}
		}
		if (out.isEmpty()) {
			throw new DefinitionIOException("sidecar has no command", null);
		}
		return out;
	}

	/** Nullable. */
	public String stringField(final Map<String, Object> sidecar, final String key) {
		final Object value = sidecar.get(key);
		return value == null ? null : String.valueOf(value);
	}

	public boolean autoStart(final Map<String, Object> sidecar) {
		return Boolean.TRUE.equals(sidecar.get("autoStart"));
	}

	/** Whether this sidecar describes a Task Scheduler job (vs an SCM daemon). */
	public boolean scheduled(final Map<String, Object> sidecar) {
		return "SCHEDULED".equals(stringField(sidecar, "kind"));
	}

	public RestartPolicy restart(final Map<String, Object> sidecar) {
		final Object value = sidecar.get("restart");
		if (value == null) {
			return RestartPolicy.NEVER;
		}
		try {
			return RestartPolicy.valueOf(String.valueOf(value));
		} catch (final IllegalArgumentException e) {
			return RestartPolicy.NEVER;
		}
	}

	@SuppressWarnings("unchecked")
	public Map<String, String> environment(final Map<String, Object> sidecar) {
		final java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
		final Object value = sidecar.get("environment");
		if (value instanceof Map<?, ?> map) {
			for (final Map.Entry<?, ?> e : map.entrySet()) {
				out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
			}
		}
		return out;
	}

	/** Reconstruct a full {@link ServiceSpec}. {@code id} is taken from the sidecar's file stem. */
	public ServiceSpec toSpec(final Map<String, Object> sidecar, final String id) {
		final ServiceSpec.Builder b = ServiceSpec.builder().id(id);

		final String displayName = stringField(sidecar, "displayName");
		if (displayName != null) {
			b.displayName(displayName);
		}
		final String description = stringField(sidecar, "description");
		if (description != null) {
			b.description(description);
		}

		b.command(command(sidecar));

		final String workingDirectory = stringField(sidecar, "workingDirectory");
		if (workingDirectory != null) {
			b.workingDirectory(Path.of(workingDirectory));
		}
		b.environment(environment(sidecar));
		b.runAs(runAs(sidecar));

		final String stdout = stringField(sidecar, "stdout");
		if (stdout != null) {
			b.stdout(Path.of(stdout));
		}
		final String stderr = stringField(sidecar, "stderr");
		if (stderr != null) {
			b.stderr(Path.of(stderr));
		}
		b.autoStart(autoStart(sidecar));
		b.restart(restart(sidecar));
		return b.build();
	}

	private RunAs runAs(final Map<String, Object> sidecar) {
		final String kind = stringField(sidecar, "runAs");
		final String userName = stringField(sidecar, "userName");
		if ("NAMED_USER".equals(kind) && userName != null) {
			return RunAs.namedUser(userName);
		}
		if ("SYSTEM_DAEMON".equals(kind)) {
			return RunAs.systemDaemon();
		}
		return RunAs.currentUser();
	}
}
