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

/** Drives {@code schtasks.exe} via subprocess. Tasks live under a {@code \ServicePal\} folder. */
public final class SchtasksScheduler implements TaskScheduler {

	private static final String FOLDER = "\\ServicePal\\";

	private final CommandRunner runner;

	public SchtasksScheduler(final CommandRunner runner) {
		this.runner = runner;
	}

	@Override
	public boolean exists(final String name) {
		return runner.run(queryCommand(name)).ok();
	}

	@Override
	public void createFromXml(final String name, final String xml) {
		final Path file = writeXml(name, xml);
		try {
			mutate(List.of("schtasks", "/create", "/tn", taskPath(name), "/xml", file.toString(),
					"/f"));
		} finally {
			try {
				Files.deleteIfExists(file);
			} catch (final IOException ignored) {
				// temp file cleanup is best-effort
			}
		}
	}

	@Override
	public void delete(final String name) {
		mutate(List.of("schtasks", "/delete", "/tn", taskPath(name), "/f"));
	}

	@Override
	public void run(final String name) {
		mutate(List.of("schtasks", "/run", "/tn", taskPath(name)));
	}

	@Override
	public void end(final String name) {
		mutate(List.of("schtasks", "/end", "/tn", taskPath(name)));
	}

	@Override
	public void setEnabled(final String name, final boolean enabled) {
		mutate(List.of("schtasks", "/change", "/tn", taskPath(name),
				enabled ? "/enable" : "/disable"));
	}

	@Override
	public TaskInfo query(final String name) {
		final CommandResult res = runner.run(queryCommand(name));
		if (!res.ok() || res.stdout() == null) {
			return null;
		}
		return new TaskInfo(parseStatus(res.stdout()));
	}

	/** Parse the {@code Status:} field from {@code schtasks /query /fo LIST}. Package-visible. */
	static String parseStatus(final String out) {
		for (final String raw : out.split("\n")) {
			final String line = raw.strip();
			if (line.toLowerCase(java.util.Locale.ROOT).startsWith("status:")) {
				return line.substring(line.indexOf(':') + 1).strip();
			}
		}
		return "Unknown";
	}

	private static List<String> queryCommand(final String name) {
		final List<String> cmd = new ArrayList<>();
		cmd.add("schtasks");
		cmd.add("/query");
		cmd.add("/tn");
		cmd.add(taskPath(name));
		cmd.add("/fo");
		cmd.add("LIST");
		return cmd;
	}

	private static String taskPath(final String name) {
		return FOLDER + name;
	}

	private Path writeXml(final String name, final String xml) {
		try {
			final Path file = Files.createTempFile("servicepal-task-", ".xml");
			// schtasks /xml reads a UTF-16 file; write LE with a BOM to match the XML declaration.
			final byte[] bom = {(byte) 0xFF, (byte) 0xFE};
			final byte[] body = xml.getBytes(StandardCharsets.UTF_16LE);
			final byte[] bytes = new byte[bom.length + body.length];
			System.arraycopy(bom, 0, bytes, 0, bom.length);
			System.arraycopy(body, 0, bytes, bom.length, body.length);
			Files.write(file, bytes);
			return file;
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to stage task XML for " + name, e);
		}
	}

	private void mutate(final List<String> command) {
		final CommandResult res = runner.run(command);
		if (!res.ok()) {
			throw new NativeCommandException(command, res.exitCode(), res.stderr());
		}
	}
}
