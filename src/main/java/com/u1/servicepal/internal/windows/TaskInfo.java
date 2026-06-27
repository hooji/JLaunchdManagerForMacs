package com.u1.servicepal.internal.windows;

import com.u1.servicepal.model.RunState;

/**
 * Live state of a scheduled task, from {@code schtasks /query}. Tasks are fire-on-trigger, so the
 * states we care about are {@code Running} (an instance is executing), {@code Ready} (armed, idle),
 * and {@code Disabled}.
 *
 * @param status the schtasks status word ({@code Running} / {@code Ready} / {@code Disabled} / …)
 */
public record TaskInfo(String status) {

	public boolean disabled() {
		return "Disabled".equalsIgnoreCase(status);
	}

	public RunState runState() {
		if ("Running".equalsIgnoreCase(status)) {
			return RunState.RUNNING;
		}
		if ("Ready".equalsIgnoreCase(status) || "Disabled".equalsIgnoreCase(status)) {
			return RunState.STOPPED;
		}
		return RunState.UNKNOWN;
	}
}
