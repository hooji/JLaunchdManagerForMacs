package com.u1.servicepal.internal.openrc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.Installation;
import com.u1.servicepal.UnmanagedServiceException;
import com.u1.servicepal.model.Discovery;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import com.u1.servicepal.model.options.OpenRcOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenRcBackendTest {

	private static final String ID = "com.u1.servicepal.test.svc";

	private Path initDir;
	private Path runlevelsDir;
	private RecordingRcService rcService;
	private OpenRcBackend backend;

	@BeforeEach
	void setUp(@TempDir final Path tmp) throws IOException {
		initDir = Files.createDirectory(tmp.resolve("init.d"));
		runlevelsDir = Files.createDirectory(tmp.resolve("runlevels"));
		Files.createDirectory(runlevelsDir.resolve("default"));
		// our service reports started; anything else not-found
		rcService = new RecordingRcService(service -> ID.equals(service)
				? new RcStatus("started", " * status: started")
				: RcStatus.notFound());
		backend = new OpenRcBackend(rcService, initDir, runlevelsDir);
	}

	private static ServiceSpec systemSpec() {
		return ServiceSpec.builder()
				.id(ID)
				.command("/bin/sleep", "60")
				.asSystemDaemon()
				.autoStart(true)
				.build();
	}

	@Test
	void installWritesExecutableScriptWithMarker() {
		backend.install(systemSpec(), false);

		final Path file = initDir.resolve(ID);
		assertTrue(Files.isRegularFile(file));
		final String text = readText(file);
		assertTrue(text.contains(RcScriptReader.MANAGED_MARKER));
		assertTrue(text.contains("command=\"/bin/sleep\""));
		if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
			assertTrue(Files.isExecutable(file));
		}
	}

	@Test
	void discoversInstalledScriptWithLiveState() {
		backend.install(systemSpec(), false);

		final Discovery d = backend.discover(Installation.SYSTEM_WIDE);
		assertEquals(1, d.services().size());
		final ServiceStatus s = d.services().get(0);
		assertEquals(ID, s.id());
		assertTrue(s.managed());
		assertEquals(RunState.RUNNING, s.state());
		assertEquals(Installation.SYSTEM_WIDE, s.installation());
	}

	@Test
	void enabledReflectsRunlevelSymlink() throws IOException {
		backend.install(systemSpec(), false);
		assertFalse(backend.status(ID, Installation.SYSTEM_WIDE).enabled());

		// rc-update add would create this; emulate the resulting runlevel entry.
		Files.writeString(runlevelsDir.resolve("default").resolve(ID), "");
		assertTrue(backend.status(ID, Installation.SYSTEM_WIDE).enabled());
	}

	@Test
	void lifecycleVerbsHitRcService() {
		backend.install(systemSpec(), false);
		backend.enable(ID, Installation.SYSTEM_WIDE);
		backend.start(ID, Installation.SYSTEM_WIDE);
		backend.restart(ID, Installation.SYSTEM_WIDE);
		backend.stop(ID, Installation.SYSTEM_WIDE);
		backend.disable(ID, Installation.SYSTEM_WIDE);

		assertTrue(rcService.calls.contains("add " + ID + " default"));
		assertTrue(rcService.calls.contains("start " + ID));
		assertTrue(rcService.calls.contains("restart " + ID));
		assertTrue(rcService.calls.contains("stop " + ID));
		assertTrue(rcService.calls.contains("del " + ID + " default"));
	}

	@Test
	void enableUsesRunlevelFromOpenRcOptions() {
		backend.install(ServiceSpec.builder()
				.id(ID)
				.command("/bin/sleep", "60")
				.asSystemDaemon()
				.openrc(OpenRcOptions.builder().runlevel("boot").build())
				.build(), false);
		backend.enable(ID, Installation.SYSTEM_WIDE);

		assertTrue(rcService.calls.contains("add " + ID + " boot"));
	}

	@Test
	void uninstallStopsDelsAndDeletes() {
		backend.install(systemSpec(), false);
		backend.uninstall(ID, Installation.SYSTEM_WIDE, false);

		assertFalse(Files.exists(initDir.resolve(ID)));
		assertTrue(rcService.calls.contains("stop " + ID));
		assertTrue(rcService.calls.contains("del " + ID + " default"));
	}

	@Test
	void refusesToOverwriteUnmanaged() throws IOException {
		Files.writeString(initDir.resolve(ID), "#!/sbin/openrc-run\ncommand=/bin/false\n");
		assertThrows(UnmanagedServiceException.class, () -> backend.install(systemSpec(), false));
		backend.install(systemSpec(), true);   // allowed with the override
		assertTrue(readText(initDir.resolve(ID)).contains(RcScriptReader.MANAGED_MARKER));
	}

	@Test
	void readRoundTripsCoreFields() {
		backend.install(ServiceSpec.builder()
				.id(ID)
				.command("/bin/sleep", "60")
				.asUser("www-data")
				.build(), false);

		final ServiceSpec back = backend.read(ID, Installation.SYSTEM_WIDE);
		assertEquals(java.util.List.of("/bin/sleep", "60"), back.command());
		assertEquals("www-data", back.runAs().userName());
	}

	@Test
	void readUnknownReturnsNull() {
		assertNull(backend.read("com.nope", Installation.SYSTEM_WIDE));
		assertNull(backend.status("com.nope", Installation.SYSTEM_WIDE));
	}

	@Test
	void perUserIsNotSupported() {
		backend.install(systemSpec(), false);
		assertNull(backend.status(ID, Installation.PER_USER));
		assertEquals(0, backend.discover(Installation.PER_USER).services().size());
	}

	private static String readText(final Path file) {
		try {
			return Files.readString(file);
		} catch (final IOException e) {
			throw new RuntimeException(e);
		}
	}
}
