package com.u1.servicepal.internal.windows;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;
import java.time.DayOfWeek;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TaskXmlWriterTest {

	private final TaskXmlWriter writer = new TaskXmlWriter();

	private static ServiceSpec.Builder base() {
		return ServiceSpec.builder()
				.id("com.example.job")
				.displayName("Acme Job")
				.command("C:\\app\\job.exe", "--run")
				.asSystemDaemon();
	}

	@Test
	void rendersDailyCalendarTrigger() {
		final String xml = writer.render(base().schedule(Schedule.dailyAt(3, 30)).build());
		assertTrue(xml.contains("<CalendarTrigger>"));
		assertTrue(xml.contains("<ScheduleByDay><DaysInterval>1</DaysInterval></ScheduleByDay>"));
		assertTrue(xml.contains("<StartBoundary>2020-01-01T03:30:00</StartBoundary>"));
		assertTrue(xml.contains("<Command>C:\\app\\job.exe</Command>"));
		assertTrue(xml.contains("<Arguments>--run</Arguments>"));
		assertTrue(xml.contains("<UserId>S-1-5-18</UserId>"), "system daemon runs as LocalSystem");
	}

	@Test
	void rendersIntervalTimeTrigger() {
		final String xml = writer.render(base().schedule(Schedule.every(Duration.ofMinutes(5)))
				.build());
		assertTrue(xml.contains("<TimeTrigger>"));
		assertTrue(xml.contains("<Interval>PT5M</Interval>"));
		assertTrue(xml.contains("<StopAtDurationEnd>false</StopAtDurationEnd>"));
	}

	@Test
	void rendersWeeklyTrigger() {
		final String xml = writer.render(
				base().schedule(Schedule.weeklyAt(DayOfWeek.MONDAY, 9, 0)).build());
		assertTrue(xml.contains("<ScheduleByWeek>"));
		assertTrue(xml.contains("<Monday />"));
	}

	@Test
	void quotesArgumentsContainingSpaces() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.job")
				.command("C:\\app\\job.exe", "--path", "C:\\Program Files\\x", "--flag")
				.asSystemDaemon()
				.schedule(Schedule.dailyAt(1, 0))
				.build();
		final String xml = writer.render(spec);
		// The space-containing arg is wrapped in quotes (then XML-escaped); the bare one is not.
		assertTrue(xml.contains("--path &quot;C:\\Program Files\\x&quot; --flag"));
	}

	@Test
	void namedUserPrincipalUsesPasswordLogon() {
		final String xml = writer.render(
				base().asUser("svc-acme").schedule(Schedule.dailyAt(1, 0)).build());
		assertTrue(xml.contains("<UserId>svc-acme</UserId>"));
		assertTrue(xml.contains("<LogonType>Password</LogonType>"));
	}
}
