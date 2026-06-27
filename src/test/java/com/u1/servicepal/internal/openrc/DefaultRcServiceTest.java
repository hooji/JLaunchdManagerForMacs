package com.u1.servicepal.internal.openrc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.u1.servicepal.internal.exec.CommandResult;
import org.junit.jupiter.api.Test;

class DefaultRcServiceTest {

	@Test
	void parsesStartedStoppedCrashed() {
		assertEquals("started",
				DefaultRcService.parseStatus(new CommandResult(0, " * status: started\n", "")).status());
		assertEquals("stopped",
				DefaultRcService.parseStatus(new CommandResult(3, " * status: stopped\n", "")).status());
		assertEquals("crashed",
				DefaultRcService.parseStatus(new CommandResult(1, " * status: crashed\n", "")).status());
	}

	@Test
	void fallsBackToExitCodeWhenTextIsUnrecognized() {
		assertEquals("started",
				DefaultRcService.parseStatus(new CommandResult(0, "", "")).status());
		assertNull(DefaultRcService.parseStatus(new CommandResult(1, "", "boom")).status());
	}
}
