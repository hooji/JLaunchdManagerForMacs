package com.u1.servicepal.internal.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;
import java.time.DayOfWeek;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TaskXmlWriterTest {

	private final TaskXmlWriter writer = new TaskXmlWriter();

	private static ServiceSpec spec(final Schedule schedule) {
		return ServiceSpec.builder()
				.id("job")
				.command("C:\\app\\job.exe", "--once")
				.asSystemDaemon()
				.schedule(schedule)
				.build();
	}

	@Test
	void dailyRendersCalendarTriggerScheduleByDay() {
		final String xml = writer.render(spec(Schedule.dailyAt(3, 5)));
		assertTrue(xml.contains("<CalendarTrigger>"), xml);
		assertTrue(xml.contains("<ScheduleByDay>"), xml);
		assertTrue(xml.contains("2020-01-01T03:05:00"), xml);
		assertTrue(xml.contains("<Command>C:\\app\\job.exe</Command>"), xml);
		assertTrue(xml.contains("<Arguments>--once</Arguments>"), xml);
		assertTrue(xml.contains(TaskXmlWriter.MARKER), xml);
	}

	@Test
	void weeklyRendersDayOfWeek() {
		final String xml = writer.render(spec(Schedule.weeklyAt(DayOfWeek.MONDAY, 9, 0)));
		assertTrue(xml.contains("<ScheduleByWeek>"), xml);
		assertTrue(xml.contains("<Monday />"), xml);
	}

	@Test
	void monthlyRendersDayOfMonth() {
		final String xml = writer.render(spec(Schedule.monthlyAt(15, 6, 30)));
		assertTrue(xml.contains("<ScheduleByMonth>"), xml);
		assertTrue(xml.contains("<Day>15</Day>"), xml);
	}

	@Test
	void intervalRendersRepeatingTimeTrigger() {
		final String xml = writer.render(spec(Schedule.every(Duration.ofMinutes(15))));
		assertTrue(xml.contains("<TimeTrigger>"), xml);
		assertTrue(xml.contains("<Interval>PT15M</Interval>"), xml);
		assertTrue(xml.contains("<StopAtDurationEnd>false</StopAtDurationEnd>"), xml);
	}

	@Test
	void isoDurationFormatsAndFloors() {
		assertEquals("PT15M", TaskXmlWriter.isoDuration(900));
		assertEquals("PT1H", TaskXmlWriter.isoDuration(3600));
		assertEquals("PT1H30M", TaskXmlWriter.isoDuration(5400));
		assertEquals("PT1M", TaskXmlWriter.isoDuration(5));   // floored to the 1-minute minimum
	}
}
