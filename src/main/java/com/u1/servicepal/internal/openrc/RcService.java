package com.u1.servicepal.internal.openrc;

/**
 * The OpenRC command seam (stub in tests): {@code rc-update} for boot persistence and
 * {@code rc-service} for run-now lifecycle. OpenRC is SYSTEM_WIDE-only, so there is no
 * per-user/system distinction to thread through (unlike {@code Systemctl}).
 */
public interface RcService {

	/** {@code rc-update add <name> <runlevel>} — enable at boot. */
	void add(String name, String runlevel);

	/** {@code rc-update del <name> <runlevel>} — disable at boot. */
	void del(String name, String runlevel);

	/** {@code rc-service <name> start}. */
	void start(String name);

	/** {@code rc-service <name> stop}. */
	void stop(String name);

	/** {@code rc-service <name> restart}. */
	void restart(String name);

	/** {@code rc-service <name> status}, parsed. Never null; tolerant of a non-zero exit. */
	RcStatus status(String name);
}
