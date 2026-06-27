package com.u1.servicepal.internal.openrc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.NativeCommandException;
import com.u1.servicepal.internal.exec.CommandResult;
import com.u1.servicepal.internal.exec.CommandRunner;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultRcServiceTest {

	@Test
	void parseStateRecognizesTheStatusWords() {
		assertEquals("started", DefaultRcService.parseState(" * status: started"));
		assertEquals("stopped", DefaultRcService.parseState(" * status: stopped"));
		assertEquals("crashed", DefaultRcService.parseState(" * status: crashed"));
		assertEquals("inactive", DefaultRcService.parseState(" * status: inactive"));
		assertEquals("unknown", DefaultRcService.parseState("no such service"));
	}

	@Test
	void parseStateDistinguishesSharedPrefixes() {
		assertEquals("starting", DefaultRcService.parseState(" * status: starting"));
		assertEquals("stopping", DefaultRcService.parseState(" * status: stopping"));
	}

	@Test
	void statusReturnsParsedStateAndRaw() {
		final RcService svc = new DefaultRcService(
				fixed(new CommandResult(0, " * status: started\n", "")));
		final RcStatus st = svc.status("svc");
		assertEquals("started", st.state());
		assertTrue(st.raw().contains("started"));
	}

	@Test
	void mutatingVerbsBuildExpectedCommands() {
		final List<List<String>> seen = new ArrayList<>();
		final RcService svc = new DefaultRcService(cmd -> {
			seen.add(cmd);
			return new CommandResult(0, "", "");
		});
		svc.start("api");
		svc.add("api", "default");
		assertEquals(List.of("rc-service", "api", "start"), seen.get(0));
		assertEquals(List.of("rc-update", "add", "api", "default"), seen.get(1));
	}

	@Test
	void nonZeroExitThrows() {
		final RcService svc = new DefaultRcService(fixed(new CommandResult(1, "", "boom")));
		assertThrows(NativeCommandException.class, () -> svc.start("svc"));
	}

	private static CommandRunner fixed(final CommandResult result) {
		return command -> result;
	}
}
