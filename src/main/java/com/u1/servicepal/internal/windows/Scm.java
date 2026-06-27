package com.u1.servicepal.internal.windows;

/**
 * The Windows Service Control Manager seam (stub in tests). Real impl is {@link FfmScm}, which
 * reaches {@code advapi32.dll} via Java FFM; tests use a recording fake so the backend exercises
 * off-Windows. All operations are by service name and open/close their own SCM + service handles.
 *
 * <p>Start types use the Win32 {@code dwStartType} convention: {@code 2 = SERVICE_AUTO_START},
 * {@code 3 = SERVICE_DEMAND_START}, {@code 4 = SERVICE_DISABLED}.
 */
public interface Scm {

	int SERVICE_AUTO_START = 2;
	int SERVICE_DEMAND_START = 3;
	int SERVICE_DISABLED = 4;

	/** Whether a service with this name exists. */
	boolean exists(String name);

	/**
	 * Create a service. {@code binPath} is the full command line the SCM launches (our
	 * {@link ServiceHost}). {@code account} is the {@code lpServiceStartName} (e.g.
	 * {@code null}/{@code "LocalSystem"}, {@code ".\\user"}, {@code "NT AUTHORITY\\LocalService"});
	 * {@code password} may be {@code null}.
	 */
	void create(String name, String displayName, String binPath, int startType,
			String account, String password);

	void delete(String name);

	void start(String name);

	/** Send {@code SERVICE_CONTROL_STOP}. */
	void stop(String name);

	/** Change the configured start type ({@link #SERVICE_AUTO_START} etc.). */
	void setStartType(String name, int startType);

	/** Set the service description (we stamp a managed marker into it). */
	void setDescription(String name, String description);

	/** Live status, or {@link ServiceControlStatus#notFound()} when the service is absent. */
	ServiceControlStatus queryStatus(String name);

	/** Configured {@code dwStartType}, or {@code null} when the service is absent. */
	Integer queryStartType(String name);
}
