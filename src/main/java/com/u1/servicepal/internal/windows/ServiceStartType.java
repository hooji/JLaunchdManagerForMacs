package com.u1.servicepal.internal.windows;

/**
 * Windows service start types (the {@code dwStartType} values written to the SCM) for ordinary
 * (non-driver) services. {@code AUTO_DELAYED} is not a distinct {@code dwStartType} — it is
 * {@code AUTO} (code {@code 0x2}) plus the delayed-auto-start flag applied via
 * {@code ChangeServiceConfig2}; {@link #delayed()} marks it so {@code FfmScm} sets that flag.
 */
enum ServiceStartType {

	AUTO(0x00000002, false),
	AUTO_DELAYED(0x00000002, true),
	DEMAND(0x00000003, false),
	DISABLED(0x00000004, false);

	private final int code;
	private final boolean delayed;

	ServiceStartType(final int code, final boolean delayed) {
		this.code = code;
		this.delayed = delayed;
	}

	int code() {
		return code;
	}

	/** Whether the delayed-auto-start flag should be set (only for {@link #AUTO_DELAYED}). */
	boolean delayed() {
		return delayed;
	}
}
