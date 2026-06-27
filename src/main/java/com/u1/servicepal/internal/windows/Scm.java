package com.u1.servicepal.internal.windows;

import java.util.List;

/**
 * The Service Control Manager seam (stub in tests). Backed by {@code advapi32} via FFM
 * ({@link FfmScm}); a {@code RecordingScm} fake lets the {@link WindowsBackend} unit-test
 * off-Windows. All operations target machine-wide ({@code SYSTEM_WIDE}) services — the only
 * installation Windows supports in v1.
 */
public interface Scm {

	/** Does a service with this name exist? (OpenService succeeds.) */
	boolean exists(String name);

	/**
	 * Create a service.
	 *
	 * @param name        the service (key) name
	 * @param displayName the human-readable display name
	 * @param binPath     the full {@code ImagePath} (our service host invocation)
	 * @param startType   auto / demand / disabled
	 * @param account     logon account ({@code null} = {@code LocalSystem})
	 * @param password    the account password ({@code null} for LocalSystem / virtual accounts)
	 * @param dependsOn   service dependencies (may be empty)
	 */
	void create(String name, String displayName, String binPath, ServiceStartType startType,
			String account, String password, List<String> dependsOn);

	/** Delete a service ({@code DeleteService}; deferred until handles close). */
	void delete(String name);

	/** Start a service ({@code StartServiceW}). */
	void start(String name);

	/** Send the STOP control ({@code ControlService}). */
	void stop(String name);

	/** Change only the start type ({@code ChangeServiceConfigW}). */
	void setStartType(String name, ServiceStartType startType);

	/** Set the description ({@code ChangeServiceConfig2W}); carries our managed marker too. */
	void setDescription(String name, String description);

	/** Live status ({@code QueryServiceStatusEx}), or {@code null} if the service is not installed. */
	ServiceControlStatus queryStatus(String name);
}
