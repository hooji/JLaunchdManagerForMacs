package com.u1.servicepal.internal.windows;

import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.ServiceSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link ServiceSpec} to the Windows sidecar JSON written to
 * {@code %ProgramData%\ServicePal\<id>.json}. The SCM only stores a command line and start type;
 * the sidecar carries the full spec so {@link ServiceHost} can supervise the real command and so
 * {@link SidecarReader} can reconstruct a {@code ServiceSpec} for {@code read()}. Stamps the
 * managed marker ({@link SidecarReader#MANAGED_KEY}) so discovery recognizes our services.
 */
public final class SidecarWriter {

	/** Current sidecar schema version. */
	public static final int SCHEMA_VERSION = 1;

	public String render(final ServiceSpec spec) {
		final Map<String, Object> root = new LinkedHashMap<>();
		root.put(SidecarReader.MANAGED_KEY, Boolean.TRUE);
		root.put("schemaVersion", String.valueOf(SCHEMA_VERSION));
		root.put("kind", spec.schedule() != null ? "SCHEDULED" : "DAEMON");
		root.put("id", spec.id());
		root.put("displayName", spec.displayName());
		root.put("description", spec.description());

		final List<Object> command = new ArrayList<>();
		for (final String arg : spec.command()) {
			command.add(arg);
		}
		root.put("command", command);

		root.put("workingDirectory",
				spec.workingDirectory() == null ? null : spec.workingDirectory().toString());

		final Map<String, Object> env = new LinkedHashMap<>();
		for (final Map.Entry<String, String> e : spec.environment().entrySet()) {
			env.put(e.getKey(), e.getValue());
		}
		root.put("environment", env);

		final RunAs runAs = spec.runAs();
		root.put("runAs", runAs.kind().name());
		root.put("userName", runAs.userName());

		root.put("stdout", spec.stdout() == null ? null : spec.stdout().toString());
		root.put("stderr", spec.stderr() == null ? null : spec.stderr().toString());
		root.put("autoStart", Boolean.valueOf(spec.autoStart()));
		root.put("restart", spec.restart().name());
		return Json.write(root);
	}
}
