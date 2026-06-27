package com.u1.servicepal.internal.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.Installation;
import com.u1.servicepal.UnmanagedServiceException;
import com.u1.servicepal.model.Discovery;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowsBackendTest {

	private static final String ID = "com.u1.servicepal.test.svc";

	private Path sidecarDir;
	private RecordingScm scm;
	private RecordingTaskScheduler tasks;
	private WindowsBackend backend;

	@BeforeEach
	void setUp(@TempDir final Path tmp) throws IOException {
		sidecarDir = Files.createDirectory(tmp.resolve("ServicePal"));
		scm = new RecordingScm();
		tasks = new RecordingTaskScheduler();
		backend = new WindowsBackend(scm, tasks, sidecarDir,
				"C:\\jdk\\bin\\javaw.exe", "C:\\app\\servicepal.jar");
	}

	private static ServiceSpec serviceSpec() {
		return ServiceSpec.builder()
				.id(ID)
				.command("C:\\app\\daemon.exe", "--run")
				.asSystemDaemon()
				.autoStart(true)
				.build();
	}

	@Test
	void installServiceWritesSidecarAndCreatesService() {
		backend.install(serviceSpec(), false);

		final Path file = sidecarDir.resolve(ID + ".json");
		assertTrue(Files.isRegularFile(file));
		assertTrue(scm.services.contains(ID));
		assertTrue(scm.calls.contains("create " + ID));
		assertTrue(scm.calls.contains("setDescription " + ID));
		// binPath launches the FFM host with native access enabled.
		final String binPath = scm.binPaths.get(ID);
		assertTrue(binPath.contains("--enable-native-access=ALL-UNNAMED"), binPath);
		assertTrue(binPath.contains("com.u1.servicepal.internal.windows.ServiceHost"), binPath);
		assertTrue(binPath.contains("--id " + ID), binPath);
	}

	@Test
	void scheduledSpecRoutesToTaskNotService() {
		backend.install(ServiceSpec.builder()
				.id(ID)
				.command("C:\\app\\report.exe")
				.asSystemDaemon()
				.schedule(Schedule.dailyAt(3, 30))
				.build(), false);

		assertTrue(tasks.tasks.contains(ID));
		assertFalse(scm.services.contains(ID));
		assertTrue(tasks.xml.get(ID).contains("<CalendarTrigger>"));
		assertTrue(tasks.xml.get(ID).contains("<ScheduleByDay>"));
	}

	@Test
	void discoverFindsManagedServiceWithLiveState() {
		backend.install(serviceSpec(), false);
		scm.statuses.put(ID, new ServiceControlStatus(ServiceControlStatus.RUNNING, 4321, null));

		final Discovery d = backend.discover(Installation.SYSTEM_WIDE);
		assertEquals(1, d.services().size());
		final ServiceStatus s = d.services().get(0);
		assertEquals(ID, s.id());
		assertTrue(s.managed());
		assertEquals(RunState.RUNNING, s.state());
		assertEquals(Integer.valueOf(4321), s.pid());
	}

	@Test
	void lifecycleVerbsHitScm() {
		backend.install(serviceSpec(), false);
		backend.enable(ID, Installation.SYSTEM_WIDE);
		backend.start(ID, Installation.SYSTEM_WIDE);
		backend.restart(ID, Installation.SYSTEM_WIDE);
		backend.stop(ID, Installation.SYSTEM_WIDE);
		backend.disable(ID, Installation.SYSTEM_WIDE);

		assertTrue(scm.calls.contains("setStartType " + ID + " AUTO"));
		assertTrue(scm.calls.contains("start " + ID));
		assertTrue(scm.calls.contains("stop " + ID));
		assertTrue(scm.calls.contains("setStartType " + ID + " DISABLED"));
	}

	@Test
	void enableDisableTrackedBySidecarAutoStart() {
		backend.install(serviceSpec(), false);
		backend.disable(ID, Installation.SYSTEM_WIDE);
		assertFalse(backend.status(ID, Installation.SYSTEM_WIDE).enabled());
		backend.enable(ID, Installation.SYSTEM_WIDE);
		assertTrue(backend.status(ID, Installation.SYSTEM_WIDE).enabled());
	}

	@Test
	void scheduledLifecycleHitsTaskScheduler() {
		backend.install(ServiceSpec.builder()
				.id(ID)
				.command("C:\\app\\report.exe")
				.asSystemDaemon()
				.schedule(Schedule.every(java.time.Duration.ofMinutes(15)))
				.build(), false);
		backend.start(ID, Installation.SYSTEM_WIDE);
		backend.stop(ID, Installation.SYSTEM_WIDE);

		assertTrue(tasks.calls.contains("run " + ID));
		assertTrue(tasks.calls.contains("end " + ID));
	}

	@Test
	void uninstallStopsDeletesAndRemovesSidecar() {
		backend.install(serviceSpec(), false);
		backend.uninstall(ID, Installation.SYSTEM_WIDE, false);

		assertFalse(Files.exists(sidecarDir.resolve(ID + ".json")));
		assertFalse(scm.services.contains(ID));
		assertTrue(scm.calls.contains("stop " + ID));
		assertTrue(scm.calls.contains("delete " + ID));
	}

	@Test
	void refusesToOverwriteUnmanagedService() {
		// A pre-existing service with no managed sidecar.
		scm.services.add(ID);
		assertThrows(UnmanagedServiceException.class, () -> backend.install(serviceSpec(), false));
		backend.install(serviceSpec(), true);   // allowed with the override
		assertTrue(Files.isRegularFile(sidecarDir.resolve(ID + ".json")));
		assertTrue(scm.calls.contains("updateConfig " + ID));   // existing → config update
	}

	@Test
	void readRoundTripsCoreFields() {
		backend.install(serviceSpec(), false);

		final ServiceSpec back = backend.read(ID, Installation.SYSTEM_WIDE);
		assertEquals(java.util.List.of("C:\\app\\daemon.exe", "--run"), back.command());
		assertEquals(com.u1.servicepal.model.RunAs.Kind.SYSTEM_DAEMON, back.runAs().kind());
	}

	@Test
	void readUnknownReturnsNull() {
		assertNull(backend.read("com.nope", Installation.SYSTEM_WIDE));
		assertNull(backend.status("com.nope", Installation.SYSTEM_WIDE));
	}

	@Test
	void perUserIsNotSupported() {
		backend.install(serviceSpec(), false);
		assertNull(backend.status(ID, Installation.PER_USER));
		assertEquals(0, backend.discover(Installation.PER_USER).services().size());
	}
}
