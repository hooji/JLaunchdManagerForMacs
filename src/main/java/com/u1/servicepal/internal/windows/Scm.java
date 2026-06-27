package com.u1.servicepal.internal.windows;

/**
 * The Service Control Manager seam (advapi32). Stubbed in tests so {@link WindowsBackend}
 * unit-tests off Windows; the real implementation is {@link FfmScm} (Java FFM → advapi32.dll).
 * All operations are SYSTEM_WIDE and require Administrator at runtime.
 */
public interface Scm {

	/** {@code OpenServiceW} — does a service with this name exist? */
	boolean exists(String name);

	/** {@code CreateServiceW} — register a new own-process service. */
	void create(String name, String displayName, String binPath, WinStartType startType,
			String account, String password);

	/** {@code ChangeServiceConfigW} — update binPath/start-type/account of an existing service. */
	void updateConfig(String name, String binPath, WinStartType startType, String account,
			String password);

	/** {@code ChangeServiceConfig2W} — set the description (carries our managed marker). */
	void setDescription(String name, String description);

	/** {@code ChangeServiceConfigW} — change only the start type (enable/disable). */
	void setStartType(String name, WinStartType startType);

	/** {@code StartServiceW}. */
	void start(String name);

	/** {@code ControlService(SERVICE_CONTROL_STOP)}. */
	void stop(String name);

	/** {@code DeleteService} (deferred until handles close / it stops). */
	void delete(String name);

	/** {@code QueryServiceStatusEx} → live state + pid + exit, or {@code null} if not installed. */
	ServiceControlStatus queryStatus(String name);
}
