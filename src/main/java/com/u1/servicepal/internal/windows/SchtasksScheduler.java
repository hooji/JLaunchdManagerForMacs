package com.u1.servicepal.internal.windows;

import com.u1.servicepal.DefinitionIOException;
import com.u1.servicepal.NativeCommandException;
import com.u1.servicepal.internal.exec.CommandResult;
import com.u1.servicepal.internal.exec.CommandRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Drives {@code schtasks.exe} via subprocess. The real {@link TaskScheduler}. */
public final class SchtasksScheduler implements TaskScheduler {

	private final CommandRunner runner;

	public SchtasksScheduler(final CommandRunner runner) {
		this.runner = runner;
	}

	@Override
	public boolean exists(final String name) {
		return runner.run(List.of("schtasks", "/query", "/tn", name)).ok();
	}

	@Override
	public void create(final String name, final String taskXml) {
		final Path tmp;
		try {
			tmp = Files.createTempFile("servicepal-task-", ".xml");
			// Task Scheduler XML is declared UTF-16; write it as such so schtasks parses it.
			Files.write(tmp, taskXml.getBytes(StandardCharsets.UTF_16LE));
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to stage task XML for " + name, e);
		}
		try {
			mutate(List.of("schtasks", "/create", "/tn", name, "/xml", tmp.toString(), "/f"));
		} finally {
			try {
				Files.deleteIfExists(tmp);
			} catch (final IOException ignored) {
				// temp cleanup is best-effort
			}
		}
	}

	@Override
	public void delete(final String name) {
		mutate(List.of("schtasks", "/delete", "/tn", name, "/f"));
	}

	@Override
	public void run(final String name) {
		mutate(List.of("schtasks", "/run", "/tn", name));
	}

	@Override
	public void stop(final String name) {
		mutate(List.of("schtasks", "/end", "/tn", name));
	}

	@Override
	public String queryXml(final String name) {
		final CommandResult res = runner.run(
				List.of("schtasks", "/query", "/tn", name, "/xml", "ONE"));
		return res.ok() ? res.stdout() : null;
	}

	private void mutate(final List<String> command) {
		final CommandResult res = runner.run(new ArrayList<>(command));
		if (!res.ok()) {
			throw new NativeCommandException(command, res.exitCode(), res.stderr());
		}
	}
}
