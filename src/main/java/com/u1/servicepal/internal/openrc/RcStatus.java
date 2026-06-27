package com.u1.servicepal.internal.openrc;

/**
 * Runtime state of an OpenRC service, parsed from {@code rc-service <name> status}. OpenRC has
 * no machine-readable status format, so this carries only the coarse status word; the
 * {@link OpenRcBackend} maps it onto our model and reads the pidfile separately for a PID.
 *
 * @param status the raw status word ({@code started} / {@code stopped} / {@code crashed} /
 *               {@code starting} / {@code stopping} / {@code inactive}), or {@code null} if
 *               unknown / not found
 */
public record RcStatus(String status) {

	/** A service OpenRC doesn't know about (not installed / no status). */
	public static RcStatus notFound() {
		return new RcStatus(null);
	}
}
