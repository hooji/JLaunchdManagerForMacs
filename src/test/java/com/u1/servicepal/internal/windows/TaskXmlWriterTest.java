package com.u1.servicepal.internal.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;
import java.time.DayOfWeek;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskXmlWriterTest {

	private final TaskXmlWriter writer = new TaskXmlWriter();

	private static ServiceSpec scheduled(final Schedule schedule) {
		return ServiceSpec.builder()
				.id("com.u1.servicepal.task")
				.command("C:\\app\\backup.exe", "--daily", "out dir")
				.asSystemDaemon()
				.schedule(schedule)
				.build();
	}

	@Test
	void rendersDailyCalendarTrigger() {
		final String xml = writer.render(scheduled(Schedule.dailyAt(3, 30)));
		assertTrue(xml.contains("<CalendarTrigger>"));
		assertTrue(xml.contains("<ScheduleByDay>"));
		assertTrue(xml.contains("03:30:00"));
		assertTrue(xml.contains(TaskXmlWriter.DESCRIPTION_MARKER));
		assertTrue(xml.contains("<UserId>S-1-5-18</UserId>"));   // LocalSystem
		assertTrue(xml.contains("<Command>C:\\app\\backup.exe</Command>"));
	}

	@Test
	void rendersWeeklyTriggerWithDay() {
		final String xml = writer.render(scheduled(Schedule.weeklyAt(DayOfWeek.MONDAY, 9, 0)));
		assertTrue(xml.contains("<ScheduleByWeek>"));
		assertTrue(xml.contains("<Monday />"));
	}

	@Test
	void rendersIntervalTrigger() {
		final String xml = writer.render(scheduled(Schedule.every(Duration.ofMinutes(5))));
		assertTrue(xml.contains("<TimeTrigger>"));
		assertTrue(xml.contains("<Interval>PT5M</Interval>"));
		assertTrue(xml.contains("<StopAtDurationEnd>false</StopAtDurationEnd>"));
	}

	@Test
	void quotesArgumentsWithSpaces() {
		final String args = TaskXmlWriter.joinArguments(
				List.of("prog.exe", "--daily", "out dir"));
		assertEquals("--daily \"out dir\"", args);
	}

	@Test
	void isoDurationFormats() {
		assertEquals("PT5M", TaskXmlWriter.isoDuration(Duration.ofMinutes(5)));
		assertEquals("PT1H30M", TaskXmlWriter.isoDuration(Duration.ofMinutes(90)));
		assertEquals("PT30S", TaskXmlWriter.isoDuration(Duration.ofSeconds(30)));
		assertEquals("PT2H", TaskXmlWriter.isoDuration(Duration.ofHours(2)));
	}
}
