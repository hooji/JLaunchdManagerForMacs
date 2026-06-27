package com.u1.servicepal.internal.windows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An in-memory {@link TaskScheduler} fake: records calls and stores task XML by name. */
public final class RecordingTaskScheduler implements TaskScheduler {

	public final List<String> calls = new ArrayList<>();
	private final Map<String, String> taskXml = new LinkedHashMap<>();

	@Override
	public boolean exists(final String name) {
		return taskXml.containsKey(name);
	}

	@Override
	public void create(final String name, final String xml) {
		calls.add("create " + name);
		taskXml.put(name, xml);
	}

	@Override
	public void delete(final String name) {
		calls.add("delete " + name);
		taskXml.remove(name);
	}

	@Override
	public void run(final String name) {
		calls.add("run " + name);
	}

	@Override
	public void stop(final String name) {
		calls.add("stop " + name);
	}

	@Override
	public String queryXml(final String name) {
		return taskXml.get(name);
	}
}
