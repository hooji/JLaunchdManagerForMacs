package com.u1.servicepal.internal.openrc;

/**
 * The OpenRC control seam (stub in tests): {@code rc-service} for run-now lifecycle and
 * {@code rc-update} for boot persistence (add/remove a service to/from a runlevel). OpenRC is
 * SYSTEM_WIDE-only, so — unlike {@code systemctl} — there is no per-user/system switch here.
 */
public interface RcService {

	/** {@code rc-update add <service> <runlevel>} — enable at boot. */
	void add(String service, String runlevel);

	/** {@code rc-update del <service> <runlevel>} — disable at boot. */
	void del(String service, String runlevel);

	/** {@code rc-service <service> start}. */
	void start(String service);

	/** {@code rc-service <service> stop}. */
	void stop(String service);

	/** {@code rc-service <service> restart}. */
	void restart(String service);

	/** {@code rc-service <service> status}. Never null. */
	RcStatus status(String service);
}
