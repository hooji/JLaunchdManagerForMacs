package com.u1.servicepal.internal.openrc;

/** A test fake for {@link Cron}: holds the crontab text in memory. */
public final class RecordingCron implements Cron {

	public String crontab = "";

	@Override
	public String read() {
		return crontab;
	}

	@Override
	public void write(final String text) {
		crontab = text;
	}
}
