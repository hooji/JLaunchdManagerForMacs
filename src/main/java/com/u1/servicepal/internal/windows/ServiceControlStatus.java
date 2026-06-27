package com.u1.servicepal.internal.windows;

import com.u1.servicepal.model.RunState;

/**
 * Live runtime facts about a Windows service, from {@code QueryServiceStatusEx} (the
 * {@code SERVICE_STATUS_PROCESS} struct). Windows reports structured status, so unlike the
 * launchd/OpenRC text parsers this carries an exact state, PID, and Win32 exit code.
 *
 * @param state        mapped run state
 * @param pid          process id, or {@code null} when not running ({@code dwProcessId == 0})
 * @param lastExitCode {@code dwWin32ExitCode}, or {@code null} while running
 */
public record ServiceControlStatus(RunState state, Integer pid, Integer lastExitCode) {

	// dwCurrentState values (winsvc.h).
	static final int SERVICE_STOPPED = 0x00000001;
	static final int SERVICE_START_PENDING = 0x00000002;
	static final int SERVICE_STOP_PENDING = 0x00000003;
	static final int SERVICE_RUNNING = 0x00000004;
	static final int SERVICE_CONTINUE_PENDING = 0x00000005;
	static final int SERVICE_PAUSE_PENDING = 0x00000006;
	static final int SERVICE_PAUSED = 0x00000007;

	/** Map the raw {@code SERVICE_STATUS_PROCESS} fields onto our model. */
	public static ServiceControlStatus of(final int currentState, final int pid,
			final int win32ExitCode) {
		final RunState state = switch (currentState) {
			case SERVICE_RUNNING -> RunState.RUNNING;
			case SERVICE_STOPPED, SERVICE_PAUSED -> RunState.STOPPED;
			case SERVICE_START_PENDING, SERVICE_CONTINUE_PENDING -> RunState.STARTING;
			case SERVICE_STOP_PENDING, SERVICE_PAUSE_PENDING -> RunState.STOPPING;
			default -> RunState.UNKNOWN;
		};
		final Integer resolvedPid = pid > 0 ? pid : null;
		final Integer exit = currentState == SERVICE_RUNNING ? null : win32ExitCode;
		return new ServiceControlStatus(state, resolvedPid, exit);
	}
}
