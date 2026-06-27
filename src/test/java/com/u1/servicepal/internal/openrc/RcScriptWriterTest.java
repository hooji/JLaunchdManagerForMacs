package com.u1.servicepal.internal.openrc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.options.OpenRcOptions;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RcScriptWriterTest {

	private final RcScriptWriter writer = new RcScriptWriter();

	@Test
	void rendersShebangCommandAndMarker() {
		final String script = writer.render(ServiceSpec.builder()
				.id("com.example.api")
				.command("/usr/local/bin/api", "--port", "8080")
				.asSystemDaemon()
				.build());

		assertTrue(script.startsWith("#!/sbin/openrc-run\n"), script);
		assertTrue(script.contains(RcScriptReader.MANAGED_MARKER), script);
		assertTrue(script.contains("command=\"/usr/local/bin/api\""), script);
		assertTrue(script.contains("command_args=\"--port 8080\""), script);
		// NEVER restart → backgrounded one-shot, not supervised.
		assertTrue(script.contains("command_background=true"), script);
		assertFalse(script.contains("supervise-daemon"), script);
	}

	@Test
	void alwaysRestartUsesSuperviseDaemonWithNoCap() {
		final String script = writer.render(ServiceSpec.builder()
				.id("svc")
				.command("/bin/daemon")
				.asSystemDaemon()
				.restart(RestartPolicy.ALWAYS)
				.build());

		assertTrue(script.contains("supervisor=\"supervise-daemon\""), script);
		assertTrue(script.contains("respawn_max=0"), script);
	}

	@Test
	void namedUserWorkingDirEnvAndLogsRendered() {
		final String script = writer.render(ServiceSpec.builder()
				.id("svc")
				.command("/bin/daemon")
				.asUser("www-data")
				.workingDirectory(Path.of("/var/lib/svc"))
				.env("FOO", "bar")
				.stdout(Path.of("/var/log/svc.log"))
				.stderr(Path.of("/var/log/svc.err"))
				.build());

		assertTrue(script.contains("command_user=\"www-data\""), script);
		assertTrue(script.contains("directory=\"/var/lib/svc\""), script);
		assertTrue(script.contains("export FOO=\"bar\""), script);
		assertTrue(script.contains("output_log=\"/var/log/svc.log\""), script);
		assertTrue(script.contains("error_log=\"/var/log/svc.err\""), script);
	}

	@Test
	void openRcOptionsDriveRunlevelNeedsAndSupervisor() {
		final String script = writer.render(ServiceSpec.builder()
				.id("svc")
				.command("/bin/daemon")
				.asSystemDaemon()
				.restart(RestartPolicy.NEVER)
				.openrc(OpenRcOptions.builder()
						.supervisor(OpenRcOptions.Supervisor.SUPERVISE_DAEMON)
						.need("net")
						.runlevel("boot")
						.build())
				.build());

		// Explicit supervisor overrides the NEVER-derived start-stop-daemon choice.
		assertTrue(script.contains("supervisor=\"supervise-daemon\""), script);
		assertTrue(script.contains("need net"), script);
		assertTrue(script.contains(RcScriptReader.RUNLEVEL_MARKER_PREFIX + "boot"), script);
	}
}
