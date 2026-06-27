package com.u1.servicepal.internal.windows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A test fake for {@link Scm}: records calls and tracks an in-memory set of services. */
public final class RecordingScm implements Scm {

	public final List<String> calls = new ArrayList<>();
	public final Set<String> services = new HashSet<>();
	public final Map<String, ServiceControlStatus> statuses = new HashMap<>();
	public final Map<String, String> binPaths = new HashMap<>();
	public final Map<String, WinStartType> startTypes = new HashMap<>();
	public final Map<String, String> descriptions = new HashMap<>();

	@Override
	public boolean exists(final String name) {
		return services.contains(name);
	}

	@Override
	public void create(final String name, final String displayName, final String binPath,
			final WinStartType startType, final String account, final String password) {
		calls.add("create " + name);
		services.add(name);
		binPaths.put(name, binPath);
		startTypes.put(name, startType);
	}

	@Override
	public void updateConfig(final String name, final String binPath, final WinStartType startType,
			final String account, final String password) {
		calls.add("updateConfig " + name);
		binPaths.put(name, binPath);
		startTypes.put(name, startType);
	}

	@Override
	public void setDescription(final String name, final String description) {
		calls.add("setDescription " + name);
		descriptions.put(name, description);
	}

	@Override
	public void setStartType(final String name, final WinStartType startType) {
		calls.add("setStartType " + name + " " + startType);
		startTypes.put(name, startType);
	}

	@Override
	public void start(final String name) {
		calls.add("start " + name);
	}

	@Override
	public void stop(final String name) {
		calls.add("stop " + name);
	}

	@Override
	public void delete(final String name) {
		calls.add("delete " + name);
		services.remove(name);
	}

	@Override
	public ServiceControlStatus queryStatus(final String name) {
		if (!services.contains(name)) {
			return null;
		}
		return statuses.getOrDefault(name,
				new ServiceControlStatus(ServiceControlStatus.STOPPED, null, null));
	}
}
