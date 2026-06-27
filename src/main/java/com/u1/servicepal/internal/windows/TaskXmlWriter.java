package com.u1.servicepal.internal.windows;

import com.u1.servicepal.model.CalendarSchedule;
import com.u1.servicepal.model.CalendarSpec;
import com.u1.servicepal.model.IntervalSchedule;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;
import java.util.List;

/**
 * Renders a scheduled {@link ServiceSpec} to Task Scheduler 1.2 XML (registered via
 * {@code schtasks /create /xml}). Covers the cross-platform {@code Schedule} shapes: interval
 * (a {@code TimeTrigger} with an indefinite repetition) and calendar (daily / weekly / monthly
 * {@code CalendarTrigger}s). The managed marker rides in the {@code <Description>}.
 */
public final class TaskXmlWriter {

	/** Description prefix that marks a task as ours (mirrors the service Description marker). */
	public static final String MARKER = "[ServicePal]";

	private static final String NS = "http://schemas.microsoft.com/windows/2004/02/mit/task";

	public String render(final ServiceSpec spec) {
		final Schedule schedule = spec.schedule();
		if (schedule == null) {
			throw new IllegalArgumentException("a task requires a schedule");
		}
		final StringBuilder sb = new StringBuilder();
		sb.append("<?xml version=\"1.0\" encoding=\"UTF-16\"?>\n");
		sb.append("<Task version=\"1.2\" xmlns=\"").append(NS).append("\">\n");

		sb.append("\t<RegistrationInfo>\n");
		sb.append("\t\t<Description>").append(esc(MARKER + " " + spec.displayName()))
				.append("</Description>\n");
		sb.append("\t</RegistrationInfo>\n");

		sb.append("\t<Triggers>\n");
		appendTrigger(sb, schedule);
		sb.append("\t</Triggers>\n");

		sb.append("\t<Principals>\n");
		sb.append("\t\t<Principal id=\"Author\">\n");
		sb.append("\t\t\t<UserId>").append(esc(principal(spec.runAs()))).append("</UserId>\n");
		sb.append("\t\t\t<RunLevel>HighestAvailable</RunLevel>\n");
		sb.append("\t\t</Principal>\n");
		sb.append("\t</Principals>\n");

		sb.append("\t<Settings>\n");
		sb.append("\t\t<MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>\n");
		sb.append("\t\t<Enabled>").append(spec.autoStart()).append("</Enabled>\n");
		sb.append("\t\t<StartWhenAvailable>true</StartWhenAvailable>\n");
		sb.append("\t\t<AllowStartOnDemand>true</AllowStartOnDemand>\n");
		sb.append("\t</Settings>\n");

		sb.append("\t<Actions Context=\"Author\">\n");
		sb.append("\t\t<Exec>\n");
		sb.append("\t\t\t<Command>").append(esc(spec.command().get(0))).append("</Command>\n");
		final String args = arguments(spec.command());
		if (!args.isEmpty()) {
			sb.append("\t\t\t<Arguments>").append(esc(args)).append("</Arguments>\n");
		}
		if (spec.workingDirectory() != null) {
			sb.append("\t\t\t<WorkingDirectory>").append(esc(spec.workingDirectory().toString()))
					.append("</WorkingDirectory>\n");
		}
		sb.append("\t\t</Exec>\n");
		sb.append("\t</Actions>\n");

		sb.append("</Task>\n");
		return sb.toString();
	}

	private static void appendTrigger(final StringBuilder sb, final Schedule schedule) {
		if (schedule instanceof IntervalSchedule interval) {
			// A time trigger that repeats indefinitely at the given interval.
			sb.append("\t\t<TimeTrigger>\n");
			sb.append("\t\t\t<StartBoundary>2020-01-01T00:00:00</StartBoundary>\n");
			sb.append("\t\t\t<Enabled>true</Enabled>\n");
			sb.append("\t\t\t<Repetition>\n");
			sb.append("\t\t\t\t<Interval>").append(isoDuration(interval.period().toSeconds()))
					.append("</Interval>\n");
			sb.append("\t\t\t\t<StopAtDurationEnd>false</StopAtDurationEnd>\n");
			sb.append("\t\t\t</Repetition>\n");
			sb.append("\t\t</TimeTrigger>\n");
			return;
		}
		final CalendarSpec spec = ((CalendarSchedule) schedule).spec();
		final int hour = spec.hour() == null ? 0 : spec.hour();
		final int minute = spec.minute() == null ? 0 : spec.minute();
		final String start = String.format("2020-01-01T%02d:%02d:00", hour, minute);

		sb.append("\t\t<CalendarTrigger>\n");
		sb.append("\t\t\t<StartBoundary>").append(start).append("</StartBoundary>\n");
		sb.append("\t\t\t<Enabled>true</Enabled>\n");
		if (spec.dayOfWeek() != null) {
			sb.append("\t\t\t<ScheduleByWeek>\n");
			sb.append("\t\t\t\t<DaysOfWeek>\n");
			sb.append("\t\t\t\t\t<").append(dayOfWeek(spec.dayOfWeek())).append(" />\n");
			sb.append("\t\t\t\t</DaysOfWeek>\n");
			sb.append("\t\t\t\t<WeeksInterval>1</WeeksInterval>\n");
			sb.append("\t\t\t</ScheduleByWeek>\n");
		} else if (spec.dayOfMonth() != null) {
			sb.append("\t\t\t<ScheduleByMonth>\n");
			sb.append("\t\t\t\t<DaysOfMonth>\n");
			sb.append("\t\t\t\t\t<Day>").append(spec.dayOfMonth()).append("</Day>\n");
			sb.append("\t\t\t\t</DaysOfMonth>\n");
			sb.append("\t\t\t</ScheduleByMonth>\n");
		} else {
			sb.append("\t\t\t<ScheduleByDay>\n");
			sb.append("\t\t\t\t<DaysInterval>1</DaysInterval>\n");
			sb.append("\t\t\t</ScheduleByDay>\n");
		}
		sb.append("\t\t</CalendarTrigger>\n");
	}

	/** ISO-8601 duration for Task Scheduler (e.g. {@code PT5M}, {@code PT1H}). Minimum 1 minute. */
	static String isoDuration(final long totalSeconds) {
		long seconds = Math.max(60, totalSeconds);
		final long hours = seconds / 3600;
		seconds %= 3600;
		final long minutes = seconds / 60;
		seconds %= 60;
		final StringBuilder sb = new StringBuilder("PT");
		if (hours > 0) {
			sb.append(hours).append('H');
		}
		if (minutes > 0) {
			sb.append(minutes).append('M');
		}
		if (seconds > 0) {
			sb.append(seconds).append('S');
		}
		return sb.toString();
	}

	private static String dayOfWeek(final int dow) {
		// CalendarSpec uses cron 0-7 (0 and 7 = Sunday).
		return switch (dow % 7) {
			case 0 -> "Sunday";
			case 1 -> "Monday";
			case 2 -> "Tuesday";
			case 3 -> "Wednesday";
			case 4 -> "Thursday";
			case 5 -> "Friday";
			default -> "Saturday";
		};
	}

	private static String principal(final RunAs runAs) {
		if (runAs.kind() == RunAs.Kind.NAMED_USER) {
			return runAs.userName();
		}
		return "SYSTEM";
	}

	private static String arguments(final List<String> command) {
		final StringBuilder sb = new StringBuilder();
		for (int i = 1; i < command.size(); i++) {
			if (i > 1) {
				sb.append(' ');
			}
			sb.append(command.get(i));
		}
		return sb.toString();
	}

	private static String esc(final String s) {
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			final char c = s.charAt(i);
			switch (c) {
				case '&' -> sb.append("&amp;");
				case '<' -> sb.append("&lt;");
				case '>' -> sb.append("&gt;");
				case '"' -> sb.append("&quot;");
				case '\'' -> sb.append("&apos;");
				default -> sb.append(c);
			}
		}
		return sb.toString();
	}
}
