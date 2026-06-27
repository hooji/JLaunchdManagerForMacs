package com.u1.servicepal.internal.windows;

/**
 * Windows service start types (the {@code dwStartType} values written to the SCM). We only use
 * the three that apply to ordinary (non-driver) services. {@code DELAYED_AUTO} is not a distinct
 * start type — it is {@code AUTO} plus a flag set via {@code ChangeServiceConfig2}; for v1 we map
 * it to {@code AUTO}.
 */
enum ServiceStartType {

	AUTO(0x00000002),
	DEMAND(0x00000003),
	DISABLED(0x00000004);

	private final int code;

	ServiceStartType(final int code) {
		this.code = code;
	}

	int code() {
		return code;
	}
}
