package com.u1.servicepal.internal.windows;

import java.nio.file.Path;

/** Shared Windows locations. The sidecar directory is used by both the backend and the host. */
final class WindowsPaths {

	private WindowsPaths() {
	}

	/** {@code %ProgramData%\ServicePal} — where per-service sidecar JSON + host logs live. */
	static Path sidecarDir() {
		return Path.of(System.getenv().getOrDefault("ProgramData", "C:\\ProgramData"))
				.resolve("ServicePal");
	}
}
