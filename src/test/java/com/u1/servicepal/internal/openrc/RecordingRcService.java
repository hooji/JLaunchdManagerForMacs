package com.u1.servicepal.internal.openrc;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** A test fake for {@link RcService}: records calls and returns a canned {@link RcStatus}. */
public final class RecordingRcService implements RcService {

	public final List<String> calls = new ArrayList<>();

	private final Function<String, RcStatus> statusFn;

	public RecordingRcService() {
		this(service -> RcStatus.notFound());
	}

	public RecordingRcService(final Function<String, RcStatus> statusFn) {
		this.statusFn = statusFn;
	}

	@Override
	public void add(final String service, final String runlevel) {
		calls.add("add " + service + " " + runlevel);
	}

	@Override
	public void del(final String service, final String runlevel) {
		calls.add("del " + service + " " + runlevel);
	}

	@Override
	public void start(final String service) {
		calls.add("start " + service);
	}

	@Override
	public void stop(final String service) {
		calls.add("stop " + service);
	}

	@Override
	public void restart(final String service) {
		calls.add("restart " + service);
	}

	@Override
	public RcStatus status(final String service) {
		return statusFn.apply(service);
	}
}
