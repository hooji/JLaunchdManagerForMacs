package com.u1.servicepal.model.options;

import java.util.ArrayList;
import java.util.List;

/**
 * Windows-specific options (Tier 2). Only consulted on Windows. A representative subset for
 * now; account/recovery/task details are expanded when the Windows backend lands.
 */
public final class WindowsOptions {

	public enum StartType { AUTO, DELAYED_AUTO, MANUAL, DISABLED }

	private final StartType startType;
	private final List<String> dependsOn;
	private final String account;
	private final String password;

	private WindowsOptions(final Builder b) {
		this.startType = b.startType;
		this.dependsOn = List.copyOf(b.dependsOn);
		this.account = b.account;
		this.password = b.password;
	}

	public StartType startType() {
		return startType;
	}

	public List<String> dependsOn() {
		return dependsOn;
	}

	/**
	 * The SCM logon account ({@code lpServiceStartName}), e.g. {@code "NT AUTHORITY\\LocalService"}
	 * or {@code "NT AUTHORITY\\NetworkService"}. Nullable; when null the account is derived from
	 * {@code RunAs} (system daemon &rarr; LocalSystem, named user &rarr; that user). An explicit
	 * value here overrides that derivation.
	 */
	public String account() {
		return account;
	}

	/**
	 * Password for a named-user service/task ({@code asUser(...)}); {@code null} for
	 * {@code LocalSystem} and virtual/managed accounts. Nullable.
	 */
	public String password() {
		return password;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private StartType startType;
		private final List<String> dependsOn = new ArrayList<>();
		private String account;
		private String password;

		public Builder startType(final StartType value) {
			this.startType = value;
			return this;
		}

		public Builder dependsOn(final String service) {
			this.dependsOn.add(service);
			return this;
		}

		public Builder account(final String value) {
			this.account = value;
			return this;
		}

		public Builder password(final String value) {
			this.password = value;
			return this;
		}

		public WindowsOptions build() {
			return new WindowsOptions(this);
		}
	}
}
