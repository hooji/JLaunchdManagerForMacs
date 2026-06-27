package com.u1.servicepal.internal.windows;

import com.u1.servicepal.model.CalendarSchedule;
import com.u1.servicepal.model.CalendarSpec;
import com.u1.servicepal.model.IntervalSchedule;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;
import java.time.Duration;
import java.util.List;

/**
 * Renders a scheduled {@link ServiceSpec} (one whose {@code schedule()} is non-null) to Task
 * Scheduler 1.2 XML for {@code schtasks /create /xml}. Task Scheduler runs any executable, so —
 * unlike the SCM daemon path — there is no service host: the command runs directly.
 *
 * <p>The managed marker is the {@code <URI>} / a registration string; we tag the task
 * {@code Description} with {@code [ServicePal]} so discovery can recognize ours.
 */
public final class TaskXmlWriter {

	/** Marker embedded in the task description so discovery recognizes our tasks. */
	public static final String DESCRIPTION_MARKER = "[ServicePal]";

	private static final String NS = "http://schemas.microsoft.com/windows/2004/02/mit/task";
	private static final String EPOCH = "2020-01-01T";

	public String render(final ServiceSpec spec) {
		final Schedule schedule = spec.schedule();
		if (schedule == null) {
			throw new IllegalArgumentException("not a scheduled spec: " + spec.id());
		}
		final StringBuilder sb = new StringBuilder();
		sb.append("<?xml version=\"1.0\" encoding=\"UTF-16\"?>\n");
		sb.append("<Task version=\"1.2\" xmlns=\"").append(NS).append("\">\n");

		sb.append("\t<RegistrationInfo>\n");
		sb.append("\t\t<Description>").append(xml(description(spec)))
				.append("</Description>\n");
		sb.append("\t\t<URI>\\").append(xml(spec.id())).append("</URI>\n");
		sb.append("\t</RegistrationInfo>\n");

		sb.append("\t<Triggers>\n");
		appendTrigger(sb, schedule);
		sb.append("\t</Triggers>\n");

		appendPrincipal(sb, spec.runAs());

		sb.append("\t<Settings>\n");
		sb.append("\t\t<MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>\n");
		sb.append("\t\t<StartWhenAvailable>true</StartWhenAvailable>\n");
		sb.append("\t\t<Enabled>").append(spec.autoStart() ? "true" : "false")
				.append("</Enabled>\n");
		sb.append("\t\t<ExecutionTimeLimit>PT0S</ExecutionTimeLimit>\n");
		sb.append("\t</Settings>\n");

		sb.append("\t<Actions Context=\"Author\">\n");
		sb.append("\t\t<Exec>\n");
		final List<String> command = spec.command();
		sb.append("\t\t\t<Command>").append(xml(command.get(0))).append("</Command>\n");
		final String arguments = joinArguments(command);
		if (!arguments.isEmpty()) {
			sb.append("\t\t\t<Arguments>").append(xml(arguments)).append("</Arguments>\n");
		}
		if (spec.workingDirectory() != null) {
			sb.append("\t\t\t<WorkingDirectory>").append(xml(spec.workingDirectory().toString()))
					.append("</WorkingDirectory>\n");
		}
		sb.append("\t\t</Exec>\n");
		sb.append("\t</Actions>\n");

		sb.append("</Task>\n");
		return sb.toString();
	}

	private static String description(final ServiceSpec spec) {
		final String base = spec.description() != null ? spec.description() : spec.displayName();
		return base + " " + DESCRIPTION_MARKER;
	}

	private static void appendTrigger(final StringBuilder sb, final Schedule schedule) {
		if (schedule instanceof IntervalSchedule interval) {
			appendIntervalTrigger(sb, interval.period());
		} else if (schedule instanceof CalendarSchedule calendar) {
			appendCalendarTrigger(sb, calendar.spec());
		} else {
			throw new IllegalArgumentException("unsupported schedule: " + schedule);
		}
	}

	private static void appendIntervalTrigger(final StringBuilder sb, final Duration period) {
		sb.append("\t\t<TimeTrigger>\n");
		sb.append("\t\t\t<StartBoundary>").append(EPOCH).append("00:00:00</StartBoundary>\n");
		sb.append("\t\t\t<Enabled>true</Enabled>\n");
		sb.append("\t\t\t<Repetition>\n");
		sb.append("\t\t\t\t<Interval>").append(isoDuration(period)).append("</Interval>\n");
		sb.append("\t\t\t\t<StopAtDurationEnd>false</StopAtDurationEnd>\n");
		sb.append("\t\t\t</Repetition>\n");
		sb.append("\t\t</TimeTrigger>\n");
	}

	private static void appendCalendarTrigger(final StringBuilder sb, final CalendarSpec spec) {
		final int hour = spec.hour() != null ? spec.hour() : 0;
		final int minute = spec.minute() != null ? spec.minute() : 0;
		sb.append("\t\t<CalendarTrigger>\n");
		sb.append("\t\t\t<StartBoundary>").append(EPOCH).append(two(hour)).append(':')
				.append(two(minute)).append(":00</StartBoundary>\n");
		sb.append("\t\t\t<Enabled>true</Enabled>\n");
		if (spec.dayOfWeek() != null) {
			sb.append("\t\t\t<ScheduleByWeek>\n");
			sb.append("\t\t\t\t<DaysOfWeek>\n");
			sb.append("\t\t\t\t\t<").append(dayName(spec.dayOfWeek())).append(" />\n");
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

	private static void appendPrincipal(final StringBuilder sb, final RunAs runAs) {
		sb.append("\t<Principals>\n");
		sb.append("\t\t<Principal id=\"Author\">\n");
		if (runAs.kind() == RunAs.Kind.NAMED_USER) {
			sb.append("\t\t\t<UserId>").append(xml(runAs.userName())).append("</UserId>\n");
			sb.append("\t\t\t<LogonType>Password</LogonType>\n");
			sb.append("\t\t\t<RunLevel>LeastPrivilege</RunLevel>\n");
		} else {
			// SYSTEM_DAEMON (and CURRENT_USER, which the capability gate already rejects) → SYSTEM.
			sb.append("\t\t\t<UserId>S-1-5-18</UserId>\n");
			sb.append("\t\t\t<RunLevel>HighestAvailable</RunLevel>\n");
		}
		sb.append("\t\t</Principal>\n");
		sb.append("\t</Principals>\n");
	}

	/** Quote-join command arguments (everything after the program) for the {@code <Arguments>}. */
	static String joinArguments(final List<String> command) {
		final StringBuilder sb = new StringBuilder();
		for (int i = 1; i < command.size(); i++) {
			if (i > 1) {
				sb.append(' ');
			}
			final String arg = command.get(i);
			if (arg.indexOf(' ') >= 0 || arg.indexOf('\t') >= 0) {
				sb.append('"').append(arg).append('"');
			} else {
				sb.append(arg);
			}
		}
		return sb.toString();
	}

	/** Convert a {@link Duration} to an ISO-8601 period Task Scheduler accepts (e.g. {@code PT5M}). */
	static String isoDuration(final Duration period) {
		long seconds = period.getSeconds();
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
		if (seconds > 0 || (hours == 0 && minutes == 0)) {
			sb.append(seconds).append('S');
		}
		return sb.toString();
	}

	private static String dayName(final int dayOfWeek) {
		// CalendarSpec dayOfWeek: 0-7, both 0 and 7 are Sunday (cron/launchd convention).
		final int normalized = dayOfWeek % 7;
		return switch (normalized) {
			case 0 -> "Sunday";
			case 1 -> "Monday";
			case 2 -> "Tuesday";
			case 3 -> "Wednesday";
			case 4 -> "Thursday";
			case 5 -> "Friday";
			default -> "Saturday";
		};
	}

	private static String two(final int value) {
		return value < 10 ? "0" + value : Integer.toString(value);
	}

	private static String xml(final String text) {
		if (text == null) {
			return "";
		}
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			final char c = text.charAt(i);
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
