package com.u1.servicepal.internal.windows;

/**
 * Windows service start type ({@code dwStartType} / the {@code Start} registry value). We model
 * the four user-relevant values; {@code DELAYED_AUTO} is {@code AUTO} plus the delayed-autostart
 * flag (set separately via {@code ChangeServiceConfig2}).
 */
public enum WinStartType {

	AUTO(2),
	DELAYED_AUTO(2),
	MANUAL(3),
	DISABLED(4);

	private final int code;

	WinStartType(final int code) {
		this.code = code;
	}

	/** The Win32 {@code SERVICE_*_START} numeric value. */
	public int code() {
		return code;
	}

	public boolean delayed() {
		return this == DELAYED_AUTO;
	}
}
