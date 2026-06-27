package com.u1.servicepal.internal.windows;

import com.u1.servicepal.model.ServiceSpec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders a {@link ServiceSpec} to the Windows sidecar JSON written to
 * {@code %ProgramData%\ServicePal\<id>.json}. The sidecar is our record of the spec (the SCM
 * registry holds only the host's {@code binPath}, not the real command): the {@link ServiceHost}
 * reads it to learn what to run, {@code read()} reconstructs a spec from it, and its presence is
 * our managed-by marker. The write-side counterpart to {@link SidecarReader}.
 */
public final class SidecarWriter {

	/** @param scheduled true for a Task Scheduler job, false for a service (daemon). */
	public String render(final ServiceSpec spec, final boolean scheduled) {
		final Map<String, Object> o = new LinkedHashMap<>();
		o.put(SidecarReader.MANAGED_KEY, SidecarReader.MANAGED_VALUE);
		o.put(SidecarReader.KIND_KEY, scheduled ? SidecarReader.KIND_TASK : SidecarReader.KIND_SERVICE);
		o.put("id", spec.id());
		o.put("displayName", spec.displayName());
		o.put("description", spec.description());                 // null omitted by Json
		o.put("command", new ArrayList<>(spec.command()));
		o.put("workingDirectory", pathOrNull(spec.workingDirectory()));
		o.put("environment", new LinkedHashMap<String, Object>(spec.environment()));
		o.put("stdout", pathOrNull(spec.stdout()));
		o.put("stderr", pathOrNull(spec.stderr()));
		o.put("autoStart", Boolean.toString(spec.autoStart()));
		o.put("restart", spec.restart().name());
		o.put("runAsKind", spec.runAs().kind().name());
		o.put("runAsUser", spec.runAs().userName());             // null unless NAMED_USER
		return Json.write(o);
	}

	private static Object pathOrNull(final Path path) {
		return path == null ? null : path.toString();
	}
}
