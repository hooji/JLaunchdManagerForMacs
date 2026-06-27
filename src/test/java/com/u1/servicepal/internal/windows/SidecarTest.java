package com.u1.servicepal.internal.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.ServiceSpec;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SidecarTest {

	private final SidecarWriter writer = new SidecarWriter();
	private final SidecarReader reader = new SidecarReader();

	@Test
	void roundTripsFullSpec() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.u1.servicepal.test")
				.displayName("Test Service")
				.description("a \"quoted\" description\nwith newline")
				.command("C:\\app\\run.exe", "--flag", "value with spaces")
				.workingDirectory(Path.of("C:\\app"))
				.env("KEY", "VAL")
				.env("OTHER", "x=y")
				.asSystemDaemon()
				.stdout(Path.of("C:\\logs\\out.log"))
				.stderr(Path.of("C:\\logs\\err.log"))
				.autoStart(true)
				.restart(RestartPolicy.ON_FAILURE)
				.build();

		final String json = writer.render(spec);
		final Map<String, Object> parsed = Json.parseObject(json);

		assertTrue(reader.isManaged(parsed));
		assertFalse(reader.scheduled(parsed));
		assertEquals(java.util.List.of("C:\\app\\run.exe", "--flag", "value with spaces"),
				reader.command(parsed));
		assertEquals("C:\\app", reader.stringField(parsed, "workingDirectory"));
		assertEquals(Map.of("KEY", "VAL", "OTHER", "x=y"), reader.environment(parsed));
		assertTrue(reader.autoStart(parsed));
		assertEquals(RestartPolicy.ON_FAILURE, reader.restart(parsed));

		final ServiceSpec back = reader.toSpec(parsed, "com.u1.servicepal.test");
		assertEquals("Test Service", back.displayName());
		assertEquals("a \"quoted\" description\nwith newline", back.description());
		assertEquals(RunAs.Kind.SYSTEM_DAEMON, back.runAs().kind());
		assertEquals(Path.of("C:\\logs\\out.log"), back.stdout());
	}

	@Test
	void marksScheduledKind() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.u1.servicepal.sched")
				.command("C:\\app\\backup.exe")
				.asSystemDaemon()
				.schedule(com.u1.servicepal.model.Schedule.everyMinutes(15))
				.build();
		final Map<String, Object> parsed = Json.parseObject(writer.render(spec));
		assertTrue(reader.scheduled(parsed));
	}

	@Test
	void namedUserRoundTrips() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.u1.servicepal.user")
				.command("C:\\app\\run.exe")
				.asUser("svcuser")
				.build();
		final ServiceSpec back = reader.toSpec(Json.parseObject(writer.render(spec)),
				"com.u1.servicepal.user");
		assertEquals(RunAs.Kind.NAMED_USER, back.runAs().kind());
		assertEquals("svcuser", back.runAs().userName());
	}

	@Test
	void jsonHandlesEmptyContainers() {
		assertEquals(Map.of(), Json.parseObject("{}"));
		assertEquals(java.util.List.of(), Json.parse("[]"));
		assertEquals("a\tb", ((Map<?, ?>) Json.parse("{\"k\":\"a\\tb\"}")).get("k"));
	}
}
