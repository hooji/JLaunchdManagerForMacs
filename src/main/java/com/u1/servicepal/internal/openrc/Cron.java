package com.u1.servicepal.internal.openrc;

/**
 * The cron seam (stub in tests): OpenRC has no native scheduler, so a scheduled job's execution is
 * a cron entry. Thin on purpose — it reads and replaces the whole crontab; the backend splices its
 * own managed block in and out, preserving any other entries.
 */
public interface Cron {

	/** The current crontab text, or {@code ""} when there is no crontab. */
	String read();

	/** Replace the crontab with {@code text}. */
	void write(String text);
}
