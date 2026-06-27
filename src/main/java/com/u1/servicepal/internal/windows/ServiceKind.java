package com.u1.servicepal.internal.windows;

/**
 * How a Windows-installed job is realized: a long-running {@code SERVICE} (registered with the SCM
 * and supervised by the bundled {@link ServiceHost}) or a {@code TASK} (Task Scheduler, used for
 * scheduled jobs — any command, no service protocol). {@code WindowsBackend} routes by job shape.
 */
public enum ServiceKind {
	SERVICE,
	TASK
}
