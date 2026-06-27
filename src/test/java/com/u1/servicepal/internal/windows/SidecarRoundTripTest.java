package com.u1.servicepal.internal.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SidecarRoundTripTest {

	private final SidecarWriter writer = new SidecarWriter();
	private final SidecarReader reader = new SidecarReader();

	@Test
	void serviceSpecRoundTrips() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.api")
				.displayName("API")
				.command("C:\\app\\api.exe", "--port", "8080")
				.asUser("svcuser")
				.workingDirectory(Path.of("C:\\app"))
				.env("FOO", "bar")
				.stdout(Path.of("C:\\logs\\out.log"))
				.restart(RestartPolicy.ON_FAILURE)
				.autoStart(true)
				.build();

		final String json = writer.render(spec, ServiceKind.SERVICE);
		final ServiceSpec back = reader.toSpec(reader.parse(json));

		assertEquals("com.example.api", back.id());
		assertEquals("API", back.displayName());
		assertEquals(java.util.List.of("C:\\app\\api.exe", "--port", "8080"), back.command());
		assertEquals(RunAs.Kind.NAMED_USER, back.runAs().kind());
		assertEquals("svcuser", back.runAs().userName());
		assertEquals(Path.of("C:\\app"), back.workingDirectory());
		assertEquals("bar", back.environment().get("FOO"));
		assertEquals(Path.of("C:\\logs\\out.log"), back.stdout());
		assertEquals(RestartPolicy.ON_FAILURE, back.restart());
		assertTrue(back.autoStart());
		assertTrue(reader.isManaged(reader.parse(json)));
		assertEquals(ServiceKind.SERVICE, reader.kindOf(reader.parse(json)));
	}

	@Test
	void scheduleRoundTrips() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("job")
				.command("C:\\app\\job.exe")
				.asSystemDaemon()
				.schedule(Schedule.dailyAt(2, 15))
				.build();

		final String json = writer.render(spec, ServiceKind.TASK);
		final ServiceSpec back = reader.toSpec(reader.parse(json));

		assertEquals(ServiceKind.TASK, reader.kindOf(reader.parse(json)));
		assertTrue(back.schedule() instanceof com.u1.servicepal.model.CalendarSchedule);
		final com.u1.servicepal.model.CalendarSchedule cal =
				(com.u1.servicepal.model.CalendarSchedule) back.schedule();
		assertEquals(Integer.valueOf(2), cal.spec().hour());
		assertEquals(Integer.valueOf(15), cal.spec().minute());
	}
}
