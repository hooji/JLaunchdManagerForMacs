package com.u1.servicepal.internal.windows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A test fake for {@link TaskScheduler}: records calls and tracks an in-memory set of tasks. */
public final class RecordingTaskScheduler implements TaskScheduler {

	public final List<String> calls = new ArrayList<>();
	public final Set<String> tasks = new HashSet<>();
	public final Map<String, String> xml = new HashMap<>();
	public final Map<String, TaskInfo> infos = new HashMap<>();

	@Override
	public boolean exists(final String name) {
		return tasks.contains(name);
	}

	@Override
	public void createFromXml(final String name, final String xmlText) {
		calls.add("create " + name);
		tasks.add(name);
		xml.put(name, xmlText);
	}

	@Override
	public void delete(final String name) {
		calls.add("delete " + name);
		tasks.remove(name);
	}

	@Override
	public void run(final String name) {
		calls.add("run " + name);
	}

	@Override
	public void end(final String name) {
		calls.add("end " + name);
	}

	@Override
	public void setEnabled(final String name, final boolean enabled) {
		calls.add("setEnabled " + name + " " + enabled);
	}

	@Override
	public TaskInfo query(final String name) {
		if (!tasks.contains(name)) {
			return null;
		}
		return infos.getOrDefault(name, new TaskInfo("Ready"));
	}
}
