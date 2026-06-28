package com.u1.servicepal.internal.openrc;

import com.u1.servicepal.Platform;
import com.u1.servicepal.UnsupportedFeatureException;
import com.u1.servicepal.model.CalendarSchedule;
import com.u1.servicepal.model.CalendarSpec;
import com.u1.servicepal.model.IntervalSchedule;
import com.u1.servicepal.model.Schedule;
import java.time.Duration;

/**
 * Maps a {@link Schedule} to/from the bits OpenRC scheduling needs: a side-band marker stored in the
 * init script (so the schedule round-trips exactly, like macOS/systemd do) and the cron line crond
 * actually runs.
 *
 * <p>Cron expresses calendar schedules cleanly. Intervals only fit when the period divides evenly
 * into a minute or an hour (a cron step field); other intervals (e.g. "every 90 minutes") have no
 * cron form and {@link #toCronLine} fails fast.
 */
final class CronSchedule {

	private CronSchedule() {
	}

	/** Encode a schedule for the {@code X-ServicePal-Schedule} init-script marker. */
	static String encodeMarker(final Schedule schedule) {
		if (schedule instanceof IntervalSchedule interval) {
			return "interval:" + interval.period().toSeconds();
		}
		final CalendarSpec s = ((CalendarSchedule) schedule).spec();
		return "calendar:" + nz(s.minute()) + "," + nz(s.hour()) + "," + nz(s.dayOfMonth())
				+ "," + nz(s.month()) + "," + nz(s.dayOfWeek());
	}

	/** Decode the {@code X-ServicePal-Schedule} marker value, or {@code null} if unparseable. */
	static Schedule decodeMarker(final String marker) {
		if (marker == null) {
			return null;
		}
		if (marker.startsWith("interval:")) {
			final Integer seconds = intOrNull(marker.substring("interval:".length()));
			return seconds == null || seconds <= 0 ? null : Schedule.every(Duration.ofSeconds(seconds));
		}
		if (marker.startsWith("calendar:")) {
			final String[] p = marker.substring("calendar:".length()).split(",", -1);
			if (p.length != 5) {
				return null;
			}
			return Schedule.calendar(new CalendarSpec(intOrNull(p[0]), intOrNull(p[1]),
					intOrNull(p[2]), intOrNull(p[3]), intOrNull(p[4])));
		}
		return null;
	}

	/** The {@code minute hour day-of-month month day-of-week} cron fields for this schedule. */
	static String toCronLine(final Schedule schedule) {
		if (schedule instanceof CalendarSchedule calendar) {
			final CalendarSpec s = calendar.spec();
			return field(s.minute()) + " " + field(s.hour()) + " " + field(s.dayOfMonth())
					+ " " + field(s.month()) + " " + field(s.dayOfWeek());
		}
		final IntervalSchedule interval = (IntervalSchedule) schedule;
		final long seconds = interval.period().toSeconds();
		if (seconds % 60 != 0) {
			throw inexpressible(interval);
		}
		final long minutes = seconds / 60;
		if (minutes < 60) {
			if (60 % minutes != 0) {
				throw inexpressible(interval);   // e.g. every 7 minutes — cron can't express it
			}
			return "*/" + minutes + " * * * *";
		}
		if (minutes % 60 != 0) {
			throw inexpressible(interval);   // e.g. every 90 minutes
		}
		final long hours = minutes / 60;
		if (hours < 24 && 24 % hours == 0) {
			return "0 */" + hours + " * * *";
		}
		if (hours == 24) {
			return "0 0 * * *";   // once a day
		}
		throw inexpressible(interval);
	}

	private static UnsupportedFeatureException inexpressible(final IntervalSchedule interval) {
		return new UnsupportedFeatureException(
				"interval schedule of " + interval.period() + " (cron needs a period dividing a "
						+ "minute or an hour)", Platform.LINUX_OPENRC);
	}

	private static String field(final Integer value) {
		return value == null ? "*" : value.toString();
	}

	private static String nz(final Integer value) {
		return value == null ? "" : value.toString();
	}

	private static Integer intOrNull(final String raw) {
		final String s = raw.strip();
		if (s.isEmpty()) {
			return null;
		}
		try {
			return Integer.valueOf(s);
		} catch (final NumberFormatException e) {
			return null;
		}
	}
}
