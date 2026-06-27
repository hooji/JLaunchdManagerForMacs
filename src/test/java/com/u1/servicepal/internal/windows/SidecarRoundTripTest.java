package com.u1.servicepal.internal.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.ServiceSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SidecarRoundTripTest {

	private final SidecarWriter writer = new SidecarWriter();
	private final SidecarReader reader = new SidecarReader();

	@Test
	void roundTripsAServiceSpec() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.api")
				.displayName("Acme API")
				.command("C:\\app\\api.exe", "--port", "8080")
				.asUser("svc-acme")
				.restart(RestartPolicy.ON_FAILURE)
				.workingDirectory(Path.of("C:\\app"))
				.env("LOG", "info")
				.autoStart(true)
				.build();

		final Map<String, Object> sidecar = reader.parse(writer.render(spec, false));
		assertTrue(reader.isManaged(sidecar));
		assertEquals(SidecarReader.KIND_SERVICE, reader.kind(sidecar));
		assertTrue(reader.autoStart(sidecar));

		final ServiceSpec back = reader.toSpec(sidecar, "com.example.api");
		assertEquals(List.of("C:\\app\\api.exe", "--port", "8080"), back.command());
		assertEquals(RunAs.namedUser("svc-acme"), back.runAs());
		assertEquals(RestartPolicy.ON_FAILURE, back.restart());
		assertEquals(Path.of("C:\\app"), back.workingDirectory());
		assertEquals("info", back.environment().get("LOG"));
		assertEquals("Acme API", back.displayName());
	}

	@Test
	void marksScheduledJobsAsTasks() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.job")
				.command("C:\\app\\job.exe")
				.asSystemDaemon()
				.build();
		final Map<String, Object> sidecar = reader.parse(writer.render(spec, true));
		assertEquals(SidecarReader.KIND_TASK, reader.kind(sidecar));
	}

	@Test
	void unmanagedSidecarIsRecognized() {
		// A JSON object without our marker must not read as managed.
		final Map<String, Object> foreign = reader.parse("{ \"id\": \"x\" }");
		assertFalse(reader.isManaged(foreign));
	}
}
