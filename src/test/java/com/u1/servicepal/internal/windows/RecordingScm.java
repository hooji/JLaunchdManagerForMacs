package com.u1.servicepal.internal.windows;

import com.u1.servicepal.model.RunState;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A test fake for {@link Scm}: records calls and tracks which services "exist". */
public final class RecordingScm implements Scm {

	public final List<String> calls = new ArrayList<>();
	public final Set<String> services = new LinkedHashSet<>();

	public String lastBinPath;
	public String lastAccount;
	public String lastPassword;
	public String lastDisplayName;
	public ServiceStartType lastStartType;
	public ServiceControlStatus status = new ServiceControlStatus(RunState.RUNNING, 1234, null);

	@Override
	public boolean exists(final String name) {
		return services.contains(name);
	}

	@Override
	public void create(final String name, final String displayName, final String binPath,
			final ServiceStartType startType, final String account, final String password,
			final List<String> dependsOn) {
		calls.add("create " + name);
		services.add(name);
		lastBinPath = binPath;
		lastStartType = startType;
		lastAccount = account;
		lastPassword = password;
	}

	@Override
	public void delete(final String name) {
		calls.add("delete " + name);
		services.remove(name);
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
	public void setStartType(final String name, final ServiceStartType startType) {
		calls.add("setStartType " + name + " " + startType);
		lastStartType = startType;
	}

	@Override
	public void updateConfig(final String name, final String binPath,
			final ServiceStartType startType, final String account, final String password,
			final String displayName) {
		calls.add("updateConfig " + name);
		lastBinPath = binPath;
		lastStartType = startType;
		lastAccount = account;
		lastPassword = password;
		lastDisplayName = displayName;
	}

	@Override
	public void setDescription(final String name, final String description) {
		calls.add("setDescription " + name);
	}

	@Override
	public ServiceControlStatus queryStatus(final String name) {
		return services.contains(name) ? status : null;
	}
}
