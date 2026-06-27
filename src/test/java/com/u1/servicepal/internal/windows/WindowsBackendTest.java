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

	private static ServiceSpec daemonSpec() {
		return ServiceSpec.builder()
				.id(ID)
				.command("ping", "-n", "120", "127.0.0.1")
				.asSystemDaemon()
				.autoStart(true)
				.build();
	}

	private static ServiceSpec scheduledSpec() {
		return ServiceSpec.builder()
				.id(ID)
				.command("C:\\app\\backup.exe", "--daily")
				.asSystemDaemon()
				.schedule(Schedule.dailyAt(3, 30))
				.build();
	}

	@Test
	void installWritesSidecarAndCreatesService() {
		backend.install(daemonSpec(), false);

		final Path file = sidecarDir.resolve(ID + ".json");
		assertTrue(Files.isRegularFile(file));
		assertTrue(scm.exists(ID));
		assertTrue(scm.calls.contains("create " + ID + " start=" + Scm.SERVICE_AUTO_START
				+ " account=null"));   // system daemon ⇒ LocalSystem (null account)
		assertTrue(scm.description(ID).contains(TaskXmlWriter.DESCRIPTION_MARKER));
	}

	@Test
	void discoversInstalledDaemonWithLiveState() {
		backend.install(daemonSpec(), false);
		backend.start(ID, Installation.SYSTEM_WIDE);

		final Discovery d = backend.discover(Installation.SYSTEM_WIDE);
		assertEquals(1, d.services().size());
		final ServiceStatus s = d.services().get(0);
		assertEquals(ID, s.id());
		assertTrue(s.managed());
		assertTrue(s.enabled());
		assertEquals(RunState.RUNNING, s.state());
		assertEquals(Integer.valueOf(4321), s.pid());
	}

	@Test
	void lifecycleVerbsHitScm() {
		backend.install(daemonSpec(), false);
		backend.enable(ID, Installation.SYSTEM_WIDE);
		backend.start(ID, Installation.SYSTEM_WIDE);
		backend.restart(ID, Installation.SYSTEM_WIDE);
		backend.stop(ID, Installation.SYSTEM_WIDE);
		backend.disable(ID, Installation.SYSTEM_WIDE);

		assertTrue(scm.calls.contains("setStartType " + ID + " " + Scm.SERVICE_AUTO_START));
		assertTrue(scm.calls.contains("start " + ID));
		assertTrue(scm.calls.contains("stop " + ID));
		assertTrue(scm.calls.contains("setStartType " + ID + " " + Scm.SERVICE_DISABLED));
	}

	@Test
	void uninstallStopsDeletesAndRemovesSidecar() {
		backend.install(daemonSpec(), false);
		backend.uninstall(ID, Installation.SYSTEM_WIDE, false);

		assertFalse(Files.exists(sidecarDir.resolve(ID + ".json")));
		assertFalse(scm.exists(ID));
		assertTrue(scm.calls.contains("delete " + ID));
	}

	@Test
	void refusesToOverwriteUnmanaged() throws IOException {
		// A foreign sidecar (no managed marker) blocks install unless the override is passed.
		Files.writeString(sidecarDir.resolve(ID + ".json"), "{\"id\":\"" + ID + "\"}");
		assertThrows(UnmanagedServiceException.class, () -> backend.install(daemonSpec(), false));

		backend.install(daemonSpec(), true);
		assertTrue(scm.exists(ID));
	}

	@Test
	void routesScheduledJobToTaskScheduler() {
		backend.install(scheduledSpec(), false);

		assertTrue(tasks.exists(ID));
		assertTrue(tasks.calls.contains("create " + ID));
		assertFalse(scm.exists(ID));   // a scheduled job is NOT an SCM service

		final ServiceStatus s = backend.status(ID, Installation.SYSTEM_WIDE);
		assertTrue(s.managed());
		assertTrue(s.installed());
	}

	@Test
	void scheduledStartRunsTask() {
		backend.install(scheduledSpec(), false);
		backend.start(ID, Installation.SYSTEM_WIDE);
		assertTrue(tasks.calls.contains("run " + ID));
	}

	@Test
	void readRoundTripsSpec() {
		backend.install(daemonSpec(), false);
		final ServiceSpec back = backend.read(ID, Installation.SYSTEM_WIDE);
		assertEquals(ID, back.id());
		assertTrue(back.command().contains("ping"));
		assertTrue(back.autoStart());
	}

	@Test
	void readNativeReturnsDefinitionContainingId() {
		backend.install(daemonSpec(), false);
		final String raw = backend.readNative(ID, Installation.SYSTEM_WIDE);
		assertTrue(raw != null && raw.contains(ID));
	}

	@Test
	void readUnknownReturnsNull() {
		assertNull(backend.read("com.nope", Installation.SYSTEM_WIDE));
		assertNull(backend.status("com.nope", Installation.SYSTEM_WIDE));
	}

	@Test
	void hostBinPathHasNativeAccessAndHostClass() {
		final String binPath = WindowsBackend.hostBinPath(
				"C:\\jdk\\bin\\javaw.exe", "C:\\app\\servicepal.jar", ID);
		assertTrue(binPath.contains("--enable-native-access=ALL-UNNAMED"));
		assertTrue(binPath.contains(ServiceHost.class.getName()));
		assertTrue(binPath.contains("--id " + ID));
		assertTrue(binPath.contains("\"C:\\jdk\\bin\\javaw.exe\""));
	}
}
