package com.u1.servicepal.internal.windows;

/**
 * Machine-readable runtime state from the SCM ({@code QueryServiceStatusEx} →
 * {@code SERVICE_STATUS_PROCESS}). Fields are the raw Win32 values; {@link WindowsBackend} maps
 * them onto our model. The parallel of systemd's {@code UnitState} / macOS's {@code ServiceRuntime}.
 *
 * @param currentState  the raw {@code dwCurrentState} (1..7), or {@code 0} when the service is
 *                      not known to the SCM
 * @param pid           {@code dwProcessId}, or {@code null} when not running / unknown
 * @param win32ExitCode {@code dwWin32ExitCode}, or {@code null} when unknown
 */
public record ServiceControlStatus(int currentState, Integer pid, Integer win32ExitCode) {

	// SCM dwCurrentState constants (winsvc.h). 0 is not a valid state — we use it as "absent".
	public static final int SERVICE_STOPPED = 1;
	public static final int SERVICE_START_PENDING = 2;
	public static final int SERVICE_STOP_PENDING = 3;
	public static final int SERVICE_RUNNING = 4;
	public static final int SERVICE_CONTINUE_PENDING = 5;
	public static final int SERVICE_PAUSE_PENDING = 6;
	public static final int SERVICE_PAUSED = 7;

	/** A status for a service the SCM doesn't know about (not installed). */
	public static ServiceControlStatus notFound() {
		return new ServiceControlStatus(0, null, null);
	}

	/** Whether the SCM knows this service. */
	public boolean found() {
		return currentState != 0;
	}
}
