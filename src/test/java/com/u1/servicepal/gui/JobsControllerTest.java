package com.u1.servicepal.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.Capabilities;
import com.u1.servicepal.Platform;
import com.u1.servicepal.model.ServiceSpec;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Swing-free save logic: when an edit should restart a running job to apply the change. */
class JobsControllerTest {

	private static final Capabilities CAPS =
			new Capabilities(true, true, true, true, true, true, true, true, false);

	private static DemoServiceManager manager() {
		return new DemoServiceManager(Platform.MACOS_LAUNCHD, CAPS);
	}

	private static ServiceSpec spec(final String id, final String command) {
		return ServiceSpec.builder().id(id).command(command).build();
	}

	@Test
	void newJobIsInstalledEnabledAndStarted() {
		final DemoServiceManager mgr = manager();
		JobsController.applySave(mgr, null, spec("a", "/bin/x"), false, true);
		assertEquals(List.of("install a", "enable a", "start a"), mgr.calls);
	}

	@Test
	void editingARunningJobWithAChangedCommandRestartsIt() {
		final DemoServiceManager mgr = manager();
		JobsController.applySave(mgr, spec("a", "/bin/old"), spec("a", "/bin/new"), true, true);
		// restart (not a plain start) so the new command actually takes effect on every platform.
		assertEquals(List.of("install a", "enable a", "restart a"), mgr.calls);
	}

	@Test
	void renamingARunningJobDoesNotBounceIt() {
		final DemoServiceManager mgr = manager();
		final ServiceSpec before = ServiceSpec.builder().id("a").displayName("Old").command("/bin/x").build();
		final ServiceSpec after = before.toBuilder().displayName("New").build();
		JobsController.applySave(mgr, before, after, true, true);
		// A cosmetic-only change leaves the running process alone.
		assertEquals(List.of("install a", "enable a"), mgr.calls);
	}

	@Test
	void editingAStoppedJobStartsIt() {
		final DemoServiceManager mgr = manager();
		JobsController.applySave(mgr, spec("a", "/bin/old"), spec("a", "/bin/new"), false, true);
		assertEquals(List.of("install a", "enable a", "start a"), mgr.calls);
	}

	@Test
	void autoStartOffInstallsAndDisables() {
		final DemoServiceManager mgr = manager();
		JobsController.applySave(mgr, null, spec("a", "/bin/x"), false, false);
		assertEquals(List.of("install a", "disable a"), mgr.calls);
	}

	@Test
	void runtimeChangedTracksRuntimeFieldsNotTheDisplayName() {
		final ServiceSpec base = ServiceSpec.builder().id("a").displayName("X").command("/bin/x").build();
		assertTrue(JobsController.runtimeChanged(null, base), "a brand-new job counts as changed");
		assertFalse(JobsController.runtimeChanged(base, base));
		assertFalse(JobsController.runtimeChanged(base, base.toBuilder().displayName("Y").build()),
				"renaming is not a runtime change");
		assertTrue(JobsController.runtimeChanged(base, base.toBuilder().command("/bin/y").build()));
		assertTrue(JobsController.runtimeChanged(base,
				base.toBuilder().workingDirectory(Path.of("/tmp")).build()));
	}
}
