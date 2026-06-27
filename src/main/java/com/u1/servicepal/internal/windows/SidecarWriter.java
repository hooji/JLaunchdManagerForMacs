package com.u1.servicepal.internal.windows;

import com.u1.servicepal.model.CalendarSchedule;
import com.u1.servicepal.model.CalendarSpec;
import com.u1.servicepal.model.IntervalSchedule;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders a {@link ServiceSpec} to the Windows sidecar JSON (written to
 * {@code %ProgramData%\ServicePal\<id>.json}). The sidecar is the source of truth for
 * {@code read()} and the managed-by marker, and is what {@link ServiceHost} reads at runtime to
 * learn the real command to supervise. Counterpart to {@link SidecarReader}.
 */
public final class SidecarWriter {

	public String render(final ServiceSpec spec, final ServiceKind kind) {
		final Map<String, Object> root = new LinkedHashMap<>();
		root.put(SidecarReader.MANAGED_KEY, Boolean.TRUE);
		root.put("kind", kind.name());
		root.put("id", spec.id());
		root.put("displayName", spec.displayName());
		if (spec.description() != null) {
			root.put("description", spec.description());
		}
		root.put("command", new ArrayList<String>(spec.command()));
		if (spec.workingDirectory() != null) {
			root.put("workingDirectory", spec.workingDirectory().toString());
		}
		if (!spec.environment().isEmpty()) {
			root.put("environment", new LinkedHashMap<String, String>(spec.environment()));
		}
		root.put("runAs", runAs(spec.runAs()));
		if (spec.stdout() != null) {
			root.put("stdout", spec.stdout().toString());
		}
		if (spec.stderr() != null) {
			root.put("stderr", spec.stderr().toString());
		}
		root.put("autoStart", spec.autoStart());
		root.put("restart", spec.restart().name());
		if (spec.schedule() != null) {
			root.put("schedule", schedule(spec.schedule()));
		}
		return Json.write(root);
	}

	private static Map<String, Object> runAs(final RunAs runAs) {
		final Map<String, Object> m = new LinkedHashMap<>();
		m.put("kind", runAs.kind().name());
		if (runAs.userName() != null) {
			m.put("user", runAs.userName());
		}
		return m;
	}

	private static Map<String, Object> schedule(final Schedule schedule) {
		final Map<String, Object> m = new LinkedHashMap<>();
		if (schedule instanceof IntervalSchedule interval) {
			m.put("type", "interval");
			m.put("periodSeconds", interval.period().toSeconds());
		} else if (schedule instanceof CalendarSchedule calendar) {
			final CalendarSpec spec = calendar.spec();
			m.put("type", "calendar");
			putIfPresent(m, "minute", spec.minute());
			putIfPresent(m, "hour", spec.hour());
			putIfPresent(m, "dayOfMonth", spec.dayOfMonth());
			putIfPresent(m, "month", spec.month());
			putIfPresent(m, "dayOfWeek", spec.dayOfWeek());
		}
		return m;
	}

	private static void putIfPresent(final Map<String, Object> m, final String key,
			final Integer value) {
		if (value != null) {
			m.put(key, value.longValue());
		}
	}
}
