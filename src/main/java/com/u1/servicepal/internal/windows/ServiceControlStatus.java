package com.u1.servicepal.internal.windows;

import com.u1.servicepal.model.RunState;

/**
 * Live status of a Windows service, from {@code QueryServiceStatusEx} →
 * {@code SERVICE_STATUS_PROCESS}. Windows reports structured status, so (unlike launchd/OpenRC)
 * pid and exit code are reliable.
 *
 * @param currentState the Win32 {@code SERVICE_*} state code (1=STOPPED … 7=PAUSED)
 * @param pid          the service process id, or {@code null} when not running (Win32 reports 0)
 * @param win32ExitCode the last Win32 exit code, or {@code null} if not meaningful
 */
public record ServiceControlStatus(int currentState, Integer pid, Integer win32ExitCode) {

	public static final int STOPPED = 1;
	public static final int START_PENDING = 2;
	public static final int STOP_PENDING = 3;
	public static final int RUNNING = 4;
	public static final int CONTINUE_PENDING = 5;
	public static final int PAUSE_PENDING = 6;
	public static final int PAUSED = 7;

	public RunState runState() {
		return switch (currentState) {
			case RUNNING, CONTINUE_PENDING, PAUSED -> RunState.RUNNING;
			case START_PENDING -> RunState.STARTING;
			case STOP_PENDING, PAUSE_PENDING -> RunState.STOPPING;
			case STOPPED -> RunState.STOPPED;
			default -> RunState.UNKNOWN;
		};
	}
}
