package com.u1.servicepal.internal.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.internal.exec.CommandResult;
import com.u1.servicepal.internal.exec.CommandRunner;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchtasksSchedulerTest {

	@Test
	void parsesStatusFromQueryListOutput() {
		assertEquals("Running", SchtasksScheduler.parseStatus("TaskName: \\ServicePal\\x\n"
				+ "Status:                                Running\n"));
		assertEquals("Ready", SchtasksScheduler.parseStatus("Status: Ready"));
		assertEquals("Unknown", SchtasksScheduler.parseStatus("no status here"));
	}

	@Test
	void queryReturnsNullWhenTaskMissing() {
		final TaskScheduler sched = new SchtasksScheduler(cmd -> new CommandResult(1, "", "ERROR"));
		assertNull(sched.query("nope"));
		assertFalse(sched.exists("nope"));
	}

	@Test
	void lifecycleVerbsBuildSchtasksCommands() {
		final List<List<String>> seen = new ArrayList<>();
		final CommandRunner runner = cmd -> {
			seen.add(cmd);
			return new CommandResult(0, "Status: Ready", "");
		};
		final TaskScheduler sched = new SchtasksScheduler(runner);
		sched.run("job");
		sched.end("job");
		sched.setEnabled("job", false);

		assertEquals(List.of("schtasks", "/run", "/tn", "\\ServicePal\\job"), seen.get(0));
		assertEquals(List.of("schtasks", "/end", "/tn", "\\ServicePal\\job"), seen.get(1));
		assertTrue(seen.get(2).contains("/disable"));
	}

	@Test
	void queryReturnsParsedInfo() {
		final TaskScheduler sched = new SchtasksScheduler(
				cmd -> new CommandResult(0, "Status: Running", ""));
		final TaskInfo info = sched.query("job");
		assertEquals("Running", info.status());
		assertEquals(com.u1.servicepal.model.RunState.RUNNING, info.runState());
	}
}
