package com.u1.servicepal.internal.windows;

/**
 * The Task Scheduler seam ({@code schtasks.exe}). Stubbed in tests; the real implementation is
 * {@link SchtasksScheduler}. Used for scheduled jobs (a non-null {@code Schedule}), which — unlike
 * services — run any command directly with no SCM protocol. Task names are the bare service id;
 * the implementation places them under a {@code \ServicePal\} folder.
 */
public interface TaskScheduler {

	/** Whether a task with this id exists. */
	boolean exists(String name);

	/** {@code schtasks /create /tn <name> /xml <file>} — register from a full XML definition. */
	void createFromXml(String name, String xml);

	/** {@code schtasks /delete /tn <name> /f}. */
	void delete(String name);

	/** {@code schtasks /run /tn <name>} — start now. */
	void run(String name);

	/** {@code schtasks /end /tn <name>} — stop a running instance. */
	void end(String name);

	/** {@code schtasks /change /tn <name> /enable|/disable}. */
	void setEnabled(String name, boolean enabled);

	/** {@code schtasks /query} → status, or {@code null} if the task does not exist. */
	TaskInfo query(String name);
}
