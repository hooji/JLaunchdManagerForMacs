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
	private final String password;

	private WindowsOptions(final Builder b) {
		this.startType = b.startType;
		this.dependsOn = List.copyOf(b.dependsOn);
		this.password = b.password;
	}

	public StartType startType() {
		return startType;
	}

	public List<String> dependsOn() {
		return dependsOn;
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
		private String password;

		public Builder startType(final StartType value) {
			this.startType = value;
			return this;
		}

		public Builder dependsOn(final String service) {
			this.dependsOn.add(service);
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
