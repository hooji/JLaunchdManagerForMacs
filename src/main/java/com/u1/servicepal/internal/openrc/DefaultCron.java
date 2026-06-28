package com.u1.servicepal.internal.openrc;

import com.u1.servicepal.DefinitionIOException;
import com.u1.servicepal.NativeCommandException;
import com.u1.servicepal.internal.exec.CommandResult;
import com.u1.servicepal.internal.exec.CommandRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads and replaces the (root) crontab via the {@code crontab} command. {@code crontab -l} prints
 * the current entries; {@code crontab <file>} installs a replacement. OpenRC is SYSTEM_WIDE-only and
 * runs elevated, so this targets root's crontab.
 */
public final class DefaultCron implements Cron {

	private final CommandRunner runner;

	public DefaultCron(final CommandRunner runner) {
		this.runner = runner;
	}

	@Override
	public String read() {
		// `crontab -l` exits non-zero ("no crontab for <user>") when none exists — treat as empty.
		final CommandResult res = runner.run(List.of("crontab", "-l"));
		return res.ok() && res.stdout() != null ? res.stdout() : "";
	}

	@Override
	public void write(final String text) {
		try {
			final Path tmp = Files.createTempFile("servicepal-cron", ".tab");
			try {
				Files.writeString(tmp, text);
				final List<String> command = List.of("crontab", tmp.toString());
				final CommandResult res = runner.run(command);
				if (!res.ok()) {
					throw new NativeCommandException(command, res.exitCode(), res.stderr());
				}
			} finally {
				Files.deleteIfExists(tmp);
			}
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to update the crontab", e);
		}
	}
}
