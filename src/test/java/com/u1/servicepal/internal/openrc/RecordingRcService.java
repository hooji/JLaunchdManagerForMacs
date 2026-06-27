package com.u1.servicepal.internal.openrc;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** A test fake for {@link RcService}: records verb calls and returns canned {@link RcStatus}. */
public final class RecordingRcService implements RcService {

	public final List<String> calls = new ArrayList<>();

	private final Function<String, RcStatus> statusFn;

	public RecordingRcService() {
		this(name -> RcStatus.notFound());
	}

	public RecordingRcService(final Function<String, RcStatus> statusFn) {
		this.statusFn = statusFn;
	}

	@Override
	public void add(final String name, final String runlevel) {
		calls.add("add " + name + " " + runlevel);
	}

	@Override
	public void del(final String name, final String runlevel) {
		calls.add("del " + name + " " + runlevel);
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
	public void restart(final String name) {
		calls.add("restart " + name);
	}

	@Override
	public RcStatus status(final String name) {
		return statusFn.apply(name);
	}
}
