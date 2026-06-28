package com.u1.servicepal.internal.macos;

import com.u1.servicepal.NativeCommandException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/** A test fake for {@link Launchctl}: records mutation calls and returns canned runtime state. */
public final class RecordingLaunchctl implements Launchctl {

	public final List<String> calls = new ArrayList<>();
	public boolean bootoutFails = false;

	/** Number of leading {@code bootstrap} calls that throw (to simulate the reload race). */
	public int bootstrapFailures = 0;
	/** Exit code for those simulated failures (5 = EIO, the transient race; non-5/37 = permanent). */
	public int bootstrapFailureExitCode = 5;
	public String bootstrapFailureMessage = "Bootstrap failed: 5: Input/output error";
	private int bootstrapCalls = 0;

	private final BiFunction<LaunchdDomain, String, ServiceRuntime> runtimeFn;

	public RecordingLaunchctl() {
		this((domain, label) -> ServiceRuntime.unknown());
	}

	public RecordingLaunchctl(final BiFunction<LaunchdDomain, String, ServiceRuntime> runtimeFn) {
		this.runtimeFn = runtimeFn;
	}

	@Override
	public ServiceRuntime runtime(final LaunchdDomain domain, final String label) {
		return runtimeFn.apply(domain, label);
	}

	@Override
	public void bootstrap(final LaunchdDomain domain, final Path plist) {
		bootstrapCalls++;
		if (bootstrapCalls <= bootstrapFailures) {
			throw new NativeCommandException(List.of("launchctl", "bootstrap"),
					bootstrapFailureExitCode, bootstrapFailureMessage);
		}
		calls.add("bootstrap " + domain + " " + plist);
	}

	@Override
	public void bootout(final LaunchdDomain domain, final String label) {
		if (bootoutFails) {
			throw new NativeCommandException(List.of("launchctl", "bootout"), 3, "not loaded");
		}
		calls.add("bootout " + domain + " " + label);
	}

	@Override
	public void kickstart(final LaunchdDomain domain, final String label, final boolean restart) {
		calls.add("kickstart" + (restart ? " -k" : "") + " " + domain + " " + label);
	}

	@Override
	public void killService(final LaunchdDomain domain, final String label, final String signal) {
		calls.add("kill " + signal + " " + domain + " " + label);
	}

	@Override
	public void enable(final LaunchdDomain domain, final String label) {
		calls.add("enable " + domain + " " + label);
	}

	@Override
	public void disable(final LaunchdDomain domain, final String label) {
		calls.add("disable " + domain + " " + label);
	}
}
