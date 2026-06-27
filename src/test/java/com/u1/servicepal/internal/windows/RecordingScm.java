package com.u1.servicepal.internal.windows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-memory {@link Scm} fake: records verb calls and simulates the SCM database so the Windows
 * backend exercises off-Windows (the real {@link FfmScm} only works on Windows).
 */
public final class RecordingScm implements Scm {

	public final List<String> calls = new ArrayList<>();
	private final Map<String, Entry> services = new LinkedHashMap<>();

	private static final class Entry {
		int startType;
		int state = ServiceControlStatus.SERVICE_STOPPED;
		Integer pid;
		Integer exit = 0;
		String description;
		String account;
	}

	@Override
	public boolean exists(final String name) {
		return services.containsKey(name);
	}

	@Override
	public void create(final String name, final String displayName, final String binPath,
			final int startType, final String account, final String password) {
		calls.add("create " + name + " start=" + startType + " account=" + account);
		final Entry e = new Entry();
		e.startType = startType;
		e.account = account;
		services.put(name, e);
	}

	@Override
	public void delete(final String name) {
		calls.add("delete " + name);
		services.remove(name);
	}

	@Override
	public void start(final String name) {
		calls.add("start " + name);
		final Entry e = require(name);
		e.state = ServiceControlStatus.SERVICE_RUNNING;
		e.pid = 4321;
	}

	@Override
	public void stop(final String name) {
		calls.add("stop " + name);
		final Entry e = require(name);
		e.state = ServiceControlStatus.SERVICE_STOPPED;
		e.pid = null;
	}

	@Override
	public void setStartType(final String name, final int startType) {
		calls.add("setStartType " + name + " " + startType);
		require(name).startType = startType;
	}

	@Override
	public void setDescription(final String name, final String description) {
		calls.add("setDescription " + name);
		require(name).description = description;
	}

	@Override
	public ServiceControlStatus queryStatus(final String name) {
		final Entry e = services.get(name);
		if (e == null) {
			return ServiceControlStatus.notFound();
		}
		return new ServiceControlStatus(e.state, e.pid, e.exit);
	}

	@Override
	public Integer queryStartType(final String name) {
		final Entry e = services.get(name);
		return e == null ? null : Integer.valueOf(e.startType);
	}

	public String description(final String name) {
		final Entry e = services.get(name);
		return e == null ? null : e.description;
	}

	public String account(final String name) {
		final Entry e = services.get(name);
		return e == null ? null : e.account;
	}

	private Entry require(final String name) {
		final Entry e = services.get(name);
		if (e == null) {
			throw new IllegalStateException("no such service: " + name);
		}
		return e;
	}
}
