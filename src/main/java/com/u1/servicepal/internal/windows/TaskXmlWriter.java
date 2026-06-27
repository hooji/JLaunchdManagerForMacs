package com.u1.servicepal.internal.windows;

import com.u1.servicepal.model.CalendarSchedule;
import com.u1.servicepal.model.CalendarSpec;
import com.u1.servicepal.model.IntervalSchedule;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;

/**
 * Renders a scheduled {@link ServiceSpec} to Task Scheduler XML (schema namespace
 * {@code .../2004/02/mit/task}). Calendar specs become {@code <CalendarTrigger>}; an interval
 * becomes a {@code <TimeTrigger>} with a {@code <Repetition>}. The principal comes from
 * {@link RunAs} (SYSTEM for a system daemon, the named user otherwise). A fixed past
 * {@code StartBoundary} keeps the output deterministic (and lets a repetition begin immediately).
 */
public final class TaskXmlWriter {

	private static final String START_BOUNDARY_DATE = "2020-01-01";
	private static final String LOCAL_SYSTEM_SID = "S-1-5-18";

	public String render(final ServiceSpec spec) {
		final Schedule schedule = spec.schedule();
		final StringBuilder sb = new StringBuilder();
		sb.append("<?xml version=\"1.0\" encoding=\"UTF-16\"?>\n");
		sb.append("<Task version=\"1.2\" "
				+ "xmlns=\"http://schemas.microsoft.com/windows/2004/02/mit/task\">\n");

		sb.append("\t<RegistrationInfo>\n");
		sb.append("\t\t<Description>").append(xml(spec.displayName())).append("</Description>\n");
		sb.append("\t</RegistrationInfo>\n");

		sb.append("\t<Triggers>\n");
		appendTrigger(sb, schedule);
		sb.append("\t</Triggers>\n");

		appendPrincipal(sb, spec.runAs());

		sb.append("\t<Settings>\n");
		sb.append("\t\t<Enabled>").append(spec.autoStart()).append("</Enabled>\n");
		sb.append("\t\t<MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>\n");
		// "run if missed" — fire on next availability if the scheduled time was missed.
		sb.append("\t\t<StartWhenAvailable>true</StartWhenAvailable>\n");
		sb.append("\t\t<ExecutionTimeLimit>PT0S</ExecutionTimeLimit>\n");
		sb.append("\t</Settings>\n");

		sb.append("\t<Actions Context=\"Author\">\n");
		sb.append("\t\t<Exec>\n");
		sb.append("\t\t\t<Command>").append(xml(spec.command().get(0))).append("</Command>\n");
		final String arguments = argumentsOf(spec);
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

	private void appendTrigger(final StringBuilder sb, final Schedule schedule) {
		if (schedule instanceof IntervalSchedule interval) {
			sb.append("\t\t<TimeTrigger>\n");
			sb.append("\t\t\t<StartBoundary>").append(START_BOUNDARY_DATE)
					.append("T00:00:00</StartBoundary>\n");
			sb.append("\t\t\t<Enabled>true</Enabled>\n");
			sb.append("\t\t\t<Repetition>\n");
			sb.append("\t\t\t\t<Interval>").append(interval.period().toString())
					.append("</Interval>\n");
			sb.append("\t\t\t\t<StopAtDurationEnd>false</StopAtDurationEnd>\n");
			sb.append("\t\t\t</Repetition>\n");
			sb.append("\t\t</TimeTrigger>\n");
		} else if (schedule instanceof CalendarSchedule calendar) {
			appendCalendarTrigger(sb, calendar.spec());
		}
	}

	private void appendCalendarTrigger(final StringBuilder sb, final CalendarSpec spec) {
		final int hour = spec.hour() != null ? spec.hour() : 0;
		final int minute = spec.minute() != null ? spec.minute() : 0;
		sb.append("\t\t<CalendarTrigger>\n");
		sb.append("\t\t\t<StartBoundary>").append(START_BOUNDARY_DATE).append('T')
				.append(two(hour)).append(':').append(two(minute)).append(":00</StartBoundary>\n");
		sb.append("\t\t\t<Enabled>true</Enabled>\n");
		if (spec.dayOfWeek() != null) {
			sb.append("\t\t\t<ScheduleByWeek>\n");
			sb.append("\t\t\t\t<DaysOfWeek><").append(weekday(spec.dayOfWeek()))
					.append(" /></DaysOfWeek>\n");
			sb.append("\t\t\t\t<WeeksInterval>1</WeeksInterval>\n");
			sb.append("\t\t\t</ScheduleByWeek>\n");
		} else if (spec.dayOfMonth() != null) {
			sb.append("\t\t\t<ScheduleByMonth>\n");
			sb.append("\t\t\t\t<DaysOfMonth><Day>").append(spec.dayOfMonth())
					.append("</Day></DaysOfMonth>\n");
			sb.append("\t\t\t\t<Months>").append(months(spec.month())).append("</Months>\n");
			sb.append("\t\t\t</ScheduleByMonth>\n");
		} else {
			sb.append("\t\t\t<ScheduleByDay><DaysInterval>1</DaysInterval></ScheduleByDay>\n");
		}
		sb.append("\t\t</CalendarTrigger>\n");
	}

	private void appendPrincipal(final StringBuilder sb, final RunAs runAs) {
		sb.append("\t<Principals>\n");
		sb.append("\t\t<Principal id=\"Author\">\n");
		if (runAs.kind() == RunAs.Kind.NAMED_USER) {
			sb.append("\t\t\t<UserId>").append(xml(runAs.userName())).append("</UserId>\n");
			sb.append("\t\t\t<LogonType>Password</LogonType>\n");
		} else {
			// System daemon (and the fail-fast-guarded current-user case): run as LocalSystem.
			sb.append("\t\t\t<UserId>").append(LOCAL_SYSTEM_SID).append("</UserId>\n");
		}
		sb.append("\t\t\t<RunLevel>HighestAvailable</RunLevel>\n");
		sb.append("\t\t</Principal>\n");
		sb.append("\t</Principals>\n");
	}

	private static String argumentsOf(final ServiceSpec spec) {
		final StringBuilder sb = new StringBuilder();
		for (int i = 1; i < spec.command().size(); i++) {
			if (i > 1) {
				sb.append(' ');
			}
			sb.append(spec.command().get(i));
		}
		return sb.toString();
	}

	/** Map a 0-7 day (0/7 = Sunday, cron/launchd convention) to a Task Scheduler element name. */
	private static String weekday(final int dayOfWeek) {
		return switch (dayOfWeek) {
			case 1 -> "Monday";
			case 2 -> "Tuesday";
			case 3 -> "Wednesday";
			case 4 -> "Thursday";
			case 5 -> "Friday";
			case 6 -> "Saturday";
			default -> "Sunday";   // 0 or 7
		};
	}

	private static String months(final Integer month) {
		if (month != null && month >= 1 && month <= 12) {
			return "<" + MONTHS[month - 1] + " />";
		}
		final StringBuilder sb = new StringBuilder();
		for (final String m : MONTHS) {
			sb.append('<').append(m).append(" />");
		}
		return sb.toString();
	}

	private static final String[] MONTHS = {
		"January", "February", "March", "April", "May", "June",
		"July", "August", "September", "October", "November", "December"
	};

	private static String two(final int value) {
		return value < 10 ? "0" + value : Integer.toString(value);
	}

	private static String xml(final String text) {
		final StringBuilder sb = new StringBuilder(text.length());
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
