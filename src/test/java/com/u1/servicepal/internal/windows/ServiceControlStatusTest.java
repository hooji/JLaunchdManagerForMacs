package com.u1.servicepal.internal.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.u1.servicepal.model.RunState;
import org.junit.jupiter.api.Test;

class ServiceControlStatusTest {

	@Test
	void mapsRunningWithPid() {
		final ServiceControlStatus s = ServiceControlStatus.of(4 /* RUNNING */, 1234, 0);
		assertEquals(RunState.RUNNING, s.state());
		assertEquals(Integer.valueOf(1234), s.pid());
		assertNull(s.lastExitCode(), "exit code is not meaningful while running");
	}

	@Test
	void mapsStoppedWithExitCode() {
		final ServiceControlStatus s = ServiceControlStatus.of(1 /* STOPPED */, 0, 1);
		assertEquals(RunState.STOPPED, s.state());
		assertNull(s.pid(), "no pid when stopped");
		assertEquals(Integer.valueOf(1), s.lastExitCode());
	}

	@Test
	void mapsPendingStates() {
		assertEquals(RunState.STARTING, ServiceControlStatus.of(2 /* START_PENDING */, 0, 0).state());
		assertEquals(RunState.STOPPING, ServiceControlStatus.of(3 /* STOP_PENDING */, 0, 0).state());
	}
}
