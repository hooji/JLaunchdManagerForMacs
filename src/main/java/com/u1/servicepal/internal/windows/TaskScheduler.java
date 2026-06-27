package com.u1.servicepal.internal.windows;

/**
 * The Task Scheduler seam (stub in tests). Real impl is {@link SchtasksScheduler}, which drives
 * {@code schtasks.exe}; tests use a recording fake. Scheduled jobs ({@code spec.schedule() != null})
 * route here instead of the SCM, because Task Scheduler runs any executable without the service
 * control protocol.
 */
public interface TaskScheduler {

	/** Whether a scheduled task with this name exists. */
	boolean exists(String name);

	/** Create (or replace) a task from the given Task Scheduler XML. */
	void create(String name, String taskXml);

	void delete(String name);

	/** Run the task now. */
	void run(String name);

	/** Stop a running task instance. */
	void stop(String name);

	/** Verbatim {@code schtasks /query /xml} output, or {@code null} when absent. */
	String queryXml(String name);
}
