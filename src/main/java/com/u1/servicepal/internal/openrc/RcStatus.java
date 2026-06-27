package com.u1.servicepal.internal.openrc;

/**
 * Runtime state from {@code rc-service <name> status}. OpenRC reports a coarse status word and
 * (unlike systemd) no reliable pid/exit-code, so {@link OpenRcBackend} maps {@link #state()}
 * onto our model and leaves pid/exit null — honest about the gap (capability
 * {@code structuredStatus} is false for OpenRC).
 *
 * @param state one of {@code started} / {@code stopped} / {@code crashed} / {@code inactive} /
 *              {@code starting} / {@code stopping} / {@code unknown}
 * @param raw   the verbatim {@code rc-service status} output, or {@code null}
 */
public record RcStatus(String state, String raw) {

	/** A service rc-service doesn't recognize (or that produced no parseable status). */
	public static RcStatus notFound() {
		return new RcStatus("unknown", null);
	}
}
