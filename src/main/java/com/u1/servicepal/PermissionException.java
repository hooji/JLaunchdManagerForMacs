package com.u1.servicepal;

/**
 * An operation needs elevated privileges (root / administrator) that the current process lacks —
 * e.g. installing a SYSTEM_WIDE service, or any Windows SCM management call without Administrator
 * (Win32 {@code ERROR_ACCESS_DENIED}).
 */
public final class PermissionException extends ServiceException {

	private static final long serialVersionUID = 1L;

	public PermissionException(final String message) {
		super(message);
	}

	public PermissionException(final String message, final Throwable cause) {
		super(message, cause);
	}
}
