package com.u1.servicepal.internal.windows;

import com.u1.servicepal.model.RestartPolicy;
import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * The bundled pure-Java service host — the answer to the pivotal Windows quirk: a service binary
 * must speak the SCM control protocol or it dies with error 1053. Registered as a daemon's
 * {@code binPath}, this runnable class speaks that protocol via FFM upcalls
 * ({@code StartServiceCtrlDispatcherW} → {@code ServiceMain} → {@code RegisterServiceCtrlHandlerExW}
 * → {@code SetServiceStatus}) and supervises the <em>real</em> command (read from the sidecar
 * JSON) as a child process, honoring {@link RestartPolicy} with a respawn loop.
 *
 * <p>Launched by the SCM as:
 * <pre>"&lt;javaw&gt;" --enable-native-access=ALL-UNNAMED -cp "&lt;jar&gt;"
 *     com.u1.servicepal.internal.windows.ServiceHost --id &lt;service-id&gt;</pre>
 *
 * <p>Run with {@code --console} (or outside the SCM) it supervises the child in the foreground,
 * which is handy for manual debugging.
 */
public final class ServiceHost {

	// SCM service states
	private static final int SERVICE_STOPPED = 1;
	private static final int SERVICE_START_PENDING = 2;
	private static final int SERVICE_STOP_PENDING = 3;
	private static final int SERVICE_RUNNING = 4;
	// control codes
	private static final int SERVICE_CONTROL_STOP = 1;
	private static final int SERVICE_CONTROL_INTERROGATE = 4;
	private static final int SERVICE_CONTROL_SHUTDOWN = 5;
	// type / accepted-controls / errors
	private static final int SERVICE_WIN32_OWN_PROCESS = 0x10;
	private static final int SERVICE_ACCEPT_STOP = 0x1;
	private static final int SERVICE_ACCEPT_SHUTDOWN = 0x4;
	private static final int NO_ERROR = 0;
	private static final int ERROR_FAILED_SERVICE_CONTROLLER_CONNECT = 1063;

	private static final ValueLayout.OfInt DWORD = ValueLayout.JAVA_INT;
	private static final java.lang.foreign.AddressLayout PTR = ValueLayout.ADDRESS;

	private final String id;
	private final SidecarReader reader = new SidecarReader();
	private final Map<String, Object> sidecar;

	private final Arena arena = Arena.ofShared();
	private final Linker linker = Linker.nativeLinker();
	private MethodHandle setServiceStatus;
	private MethodHandle registerHandler;
	private MethodHandle startDispatcher;

	private volatile MemorySegment statusHandle = MemorySegment.NULL;
	private volatile Process child;
	private volatile boolean stopRequested;
	private final CountDownLatch stopped = new CountDownLatch(1);
	private int checkPoint;

	private ServiceHost(final String id) {
		this.id = id;
		this.sidecar = reader.parseFile(sidecarPath(id));
	}

	public static void main(final String[] args) {
		String id = null;
		boolean console = false;
		for (int i = 0; i < args.length; i++) {
			if ("--id".equals(args[i]) && i + 1 < args.length) {
				id = args[++i];
			} else if ("--console".equals(args[i])) {
				console = true;
			}
		}
		if (id == null) {
			System.err.println("ServiceHost: --id <service-id> is required");
			System.exit(2);
			return;
		}
		final ServiceHost host = new ServiceHost(id);
		if (console) {
			host.runConsole();
		} else {
			host.runAsService();
		}
	}

	// --- service mode ---

	private void runAsService() {
		bindNatives();
		final MemorySegment serviceMainStub = upcall("serviceMain",
				MethodType.methodType(void.class, int.class, MemorySegment.class),
				FunctionDescriptor.ofVoid(DWORD, PTR));

		// SERVICE_TABLE_ENTRYW[2]: {name, ServiceMain}, {NULL, NULL}. Two pointers per entry.
		final MemorySegment table = arena.allocate(PTR.byteSize() * 4);
		table.set(PTR, 0, arena.allocateFrom(id, StandardCharsets.UTF_16LE));
		table.set(PTR, PTR.byteSize(), serviceMainStub);
		table.set(PTR, PTR.byteSize() * 2, MemorySegment.NULL);
		table.set(PTR, PTR.byteSize() * 3, MemorySegment.NULL);

		try {
			final int ok = (int) startDispatcher.invoke(table);
			if (ok == 0) {
				// Not started by the SCM (e.g. launched from a console) → fall back.
				System.err.println("ServiceHost: StartServiceCtrlDispatcherW failed (error "
						+ ERROR_FAILED_SERVICE_CONTROLLER_CONNECT + " if run outside the SCM);"
						+ " supervising in the foreground instead.");
				runConsole();
			}
		} catch (final Throwable t) {
			throw new IllegalStateException("ServiceHost dispatcher failed", t);
		}
	}

	/** The SCM-invoked {@code ServiceMain}. Runs on an SCM dispatcher thread. */
	@SuppressWarnings("unused")
	private void serviceMain(final int argc, final MemorySegment argv) {
		try {
			final MemorySegment handlerStub = upcall("handler",
					MethodType.methodType(int.class, int.class, int.class, MemorySegment.class,
							MemorySegment.class),
					FunctionDescriptor.of(DWORD, DWORD, DWORD, PTR, PTR));
			statusHandle = (MemorySegment) registerHandler.invoke(
					arena.allocateFrom(id, StandardCharsets.UTF_16LE), handlerStub,
					MemorySegment.NULL);

			report(SERVICE_START_PENDING, 3000);
			final Thread supervisor = new Thread(this::superviseLoop, "servicepal-supervisor");
			supervisor.setDaemon(true);
			supervisor.start();
			report(SERVICE_RUNNING, 0);

			stopped.await();
			report(SERVICE_STOP_PENDING, 3000);
			terminateChild();
			supervisor.join(5000);
			report(SERVICE_STOPPED, 0);
		} catch (final Throwable t) {
			report(SERVICE_STOPPED, 0);
		}
	}

	/** The SCM control handler. Runs on an SCM thread, distinct from {@code serviceMain}. */
	@SuppressWarnings("unused")
	private int handler(final int control, final int eventType, final MemorySegment eventData,
			final MemorySegment context) {
		switch (control) {
			case SERVICE_CONTROL_STOP, SERVICE_CONTROL_SHUTDOWN -> {
				stopRequested = true;
				report(SERVICE_STOP_PENDING, 3000);
				terminateChild();
				stopped.countDown();
			}
			case SERVICE_CONTROL_INTERROGATE -> report(
					child != null && child.isAlive() ? SERVICE_RUNNING : SERVICE_START_PENDING, 0);
			default -> {
				// other controls are not accepted; nothing to do
			}
		}
		return NO_ERROR;
	}

	private void report(final int state, final int waitHintMillis) {
		final MemorySegment handle = statusHandle;
		if (isNull(handle)) {
			return;
		}
		final MemorySegment status = arena.allocate(28);   // SERVICE_STATUS
		status.set(DWORD, 0, SERVICE_WIN32_OWN_PROCESS);
		status.set(DWORD, 4, state);
		status.set(DWORD, 8, state == SERVICE_RUNNING
				? SERVICE_ACCEPT_STOP | SERVICE_ACCEPT_SHUTDOWN : 0);
		status.set(DWORD, 12, 0);   // dwWin32ExitCode
		status.set(DWORD, 16, 0);   // dwServiceSpecificExitCode
		final boolean pending = state == SERVICE_START_PENDING || state == SERVICE_STOP_PENDING;
		status.set(DWORD, 20, pending ? ++checkPoint : 0);
		status.set(DWORD, 24, waitHintMillis);
		try {
			setServiceStatus.invoke(handle, status);
		} catch (final Throwable ignored) {
			// a failed status report shouldn't crash the host
		}
	}

	// --- child supervision (shared by service + console modes) ---

	private void superviseLoop() {
		final RestartPolicy policy = reader.restart(sidecar);
		while (!stopRequested) {
			final int exit = runChildOnce();
			if (stopRequested) {
				break;
			}
			final boolean again = switch (policy) {
				case ALWAYS -> true;
				case ON_FAILURE -> exit != 0;
				case NEVER -> false;
			};
			if (!again) {
				break;
			}
			sleep(1000);   // simple backoff between respawns
		}
	}

	private int runChildOnce() {
		try {
			final ProcessBuilder pb = new ProcessBuilder(reader.command(sidecar));
			final String workdir = reader.stringField(sidecar, "workingDirectory");
			if (workdir != null) {
				pb.directory(new File(workdir));
			}
			final Map<String, String> env = reader.environment(sidecar);
			if (!env.isEmpty()) {
				pb.environment().putAll(env);
			}
			pb.redirectOutput(redirect(reader.stringField(sidecar, "stdout")));
			pb.redirectError(redirect(reader.stringField(sidecar, "stderr")));
			final Process process = pb.start();
			child = process;
			return process.waitFor();
		} catch (final IOException e) {
			return -1;
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			return -1;
		}
	}

	private static ProcessBuilder.Redirect redirect(final String path) {
		return path == null ? ProcessBuilder.Redirect.DISCARD
				: ProcessBuilder.Redirect.appendTo(new File(path));
	}

	private void terminateChild() {
		final Process process = child;
		if (process != null && process.isAlive()) {
			process.destroy();
			try {
				if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
					process.destroyForcibly();
				}
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				process.destroyForcibly();
			}
		}
	}

	/** Foreground supervision for manual testing (no SCM). */
	private void runConsole() {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			stopRequested = true;
			terminateChild();
		}));
		superviseLoop();
	}

	// --- FFM plumbing ---

	private void bindNatives() {
		final SymbolLookup advapi32 = SymbolLookup.libraryLookup("Advapi32.dll", arena);
		startDispatcher = linker.downcallHandle(
				advapi32.find("StartServiceCtrlDispatcherW").orElseThrow(),
				FunctionDescriptor.of(DWORD, PTR));
		registerHandler = linker.downcallHandle(
				advapi32.find("RegisterServiceCtrlHandlerExW").orElseThrow(),
				FunctionDescriptor.of(PTR, PTR, PTR, PTR));
		setServiceStatus = linker.downcallHandle(
				advapi32.find("SetServiceStatus").orElseThrow(),
				FunctionDescriptor.of(DWORD, PTR, PTR));
	}

	private MemorySegment upcall(final String method, final MethodType type,
			final FunctionDescriptor descriptor) {
		try {
			final MethodHandle target = MethodHandles.lookup()
					.bind(this, method, type);
			return linker.upcallStub(target, descriptor, arena);
		} catch (final ReflectiveOperationException e) {
			throw new IllegalStateException("failed to create upcall for " + method, e);
		}
	}

	private static Path sidecarPath(final String id) {
		final String programData = System.getenv("ProgramData");
		final Path base = Path.of(programData != null ? programData : "C:\\ProgramData");
		return base.resolve("ServicePal").resolve(id + ".json");
	}

	private static boolean isNull(final MemorySegment segment) {
		return segment == null || segment.address() == 0L;
	}

	private static void sleep(final long millis) {
		try {
			Thread.sleep(millis);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
