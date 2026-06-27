package com.u1.servicepal.internal.windows;

import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.ServiceSpec;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;

/**
 * The bundled pure-Java Windows service host. Registered as a service's {@code binPath}, it
 * speaks the SCM control protocol via FFM (a {@code ServiceMain} upcall, a control-handler
 * upcall, and {@code SetServiceStatus}) — which a plain {@code java -jar} cannot do (error 1053)
 * — and supervises the real command (from the sidecar JSON) as a child process, implementing the
 * {@link RestartPolicy}. This is what makes "run any command as a Windows service" work without
 * shipping a compiled binary.
 *
 * <p>Runs only on Windows (it loads {@code Advapi32.dll}); it compiles everywhere. It writes a
 * diagnostic log next to the sidecar ({@code <id>.host.log}) since a service has no console.
 *
 * <p>Invoked as:
 * {@code javaw --enable-native-access=ALL-UNNAMED -cp <jar> <thisclass> --id <service-id>}
 */
public final class ServiceHost {

	// dwServiceType / state / controls (winsvc.h).
	private static final int SERVICE_WIN32_OWN_PROCESS = 0x00000010;
	private static final int SERVICE_STOPPED = 0x00000001;
	private static final int SERVICE_START_PENDING = 0x00000002;
	private static final int SERVICE_STOP_PENDING = 0x00000003;
	private static final int SERVICE_RUNNING = 0x00000004;
	private static final int SERVICE_ACCEPT_STOP = 0x00000001;
	private static final int SERVICE_ACCEPT_SHUTDOWN = 0x00000004;
	private static final int SERVICE_CONTROL_STOP = 0x00000001;
	private static final int SERVICE_CONTROL_SHUTDOWN = 0x00000005;
	private static final int SERVICE_CONTROL_INTERROGATE = 0x00000004;
	private static final int NO_ERROR = 0;

	// SERVICE_STATUS field offsets (7 DWORDs).
	private static final long ST_TYPE = 0;
	private static final long ST_STATE = 4;
	private static final long ST_CONTROLS = 8;
	private static final long ST_WIN32_EXIT = 12;
	private static final long ST_CHECKPOINT = 20;
	private static final long ST_WAITHINT = 24;
	private static final long SERVICE_STATUS_SIZE = 28;

	private final String id;
	private final ServiceSpec spec;
	private final Path logFile;

	private final Arena arena = Arena.ofShared();   // upcall stubs + status buffer live for the run
	private final Linker linker = Linker.nativeLinker();
	private MethodHandle registerHandler;
	private MethodHandle setServiceStatus;
	private MemorySegment statusHandle = MemorySegment.NULL;
	private MemorySegment statusBuffer;

	private volatile boolean stopRequested;
	private volatile Process child;
	private int checkpoint;

	private ServiceHost(final String id) {
		this.id = id;
		this.logFile = WindowsPaths.sidecarDir().resolve(id + ".host.log");
		this.spec = new SidecarReader().toSpec(
				new SidecarReader().parseFile(WindowsPaths.sidecarDir().resolve(id + ".json")), id);
	}

	public static void main(final String[] args) {
		final String id = parseId(args);
		if (id == null) {
			System.err.println("usage: ServiceHost --id <service-id>");
			System.exit(2);
			return;
		}
		try {
			new ServiceHost(id).run();
		} catch (final Throwable t) {
			// Last resort: nothing else will see this, so make a best effort to record it.
			try {
				Files.writeString(WindowsPaths.sidecarDir().resolve(id + ".host.log"),
						Instant.now() + " FATAL " + t + "\n",
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (final IOException ignored) {
				// give up
			}
			System.exit(1);
		}
	}

	private void run() {
		log("host starting for id=" + id);
		final SymbolLookup advapi32 = SymbolLookup.libraryLookup("Advapi32.dll", arena);
		final var p = ValueLayout.ADDRESS;
		final var i = ValueLayout.JAVA_INT;

		final MethodHandle startDispatcher = linker.downcallHandle(
				advapi32.find("StartServiceCtrlDispatcherW").orElseThrow(),
				FunctionDescriptor.of(i, p));
		registerHandler = linker.downcallHandle(
				advapi32.find("RegisterServiceCtrlHandlerExW").orElseThrow(),
				FunctionDescriptor.of(p, p, p, p));
		setServiceStatus = linker.downcallHandle(
				advapi32.find("SetServiceStatus").orElseThrow(),
				FunctionDescriptor.of(i, p, p));
		statusBuffer = arena.allocate(SERVICE_STATUS_SIZE);

		// SERVICE_TABLE_ENTRYW[2]: { {name, ServiceMain}, {NULL, NULL} }. Each entry = 2 pointers.
		final MemorySegment serviceMainStub = upcall("serviceMain",
				MethodType.methodType(void.class, int.class, MemorySegment.class),
				FunctionDescriptor.ofVoid(i, p));
		final MemorySegment table = arena.allocate(p.byteSize() * 4);
		table.set(p, 0, wide(id));
		table.set(p, p.byteSize(), serviceMainStub);
		table.set(p, p.byteSize() * 2, MemorySegment.NULL);
		table.set(p, p.byteSize() * 3, MemorySegment.NULL);

		try {
			final int ok = (int) startDispatcher.invoke(table);
			if (ok == 0) {
				log("StartServiceCtrlDispatcherW returned false (not launched by the SCM?)");
				System.exit(1);
			}
			log("dispatcher returned; host exiting");
		} catch (final Throwable t) {
			log("dispatcher threw: " + t);
			System.exit(1);
		}
	}

	/** Upcall: the SCM calls this on a dedicated thread once the service is starting. */
	@SuppressWarnings("unused")
	private void serviceMain(final int argc, final MemorySegment argv) {
		try {
			final MemorySegment handlerStub = upcall("handlerEx",
					MethodType.methodType(int.class, int.class, int.class, MemorySegment.class,
							MemorySegment.class),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
							ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			statusHandle = (MemorySegment) registerHandler.invoke(wide(id), handlerStub,
					MemorySegment.NULL);
			if (statusHandle == null || statusHandle.address() == 0) {
				log("RegisterServiceCtrlHandlerExW failed");
				return;
			}
			report(SERVICE_START_PENDING, 0, ++checkpoint, 5000, NO_ERROR);
			child = startChild();
			report(SERVICE_RUNNING, SERVICE_ACCEPT_STOP | SERVICE_ACCEPT_SHUTDOWN, 0, 0, NO_ERROR);
			log("child started, service RUNNING");

			final int exitCode = supervise();

			report(SERVICE_STOPPED, 0, 0, 0, exitCode);
			log("service STOPPED (exit=" + exitCode + ")");
		} catch (final Throwable t) {
			log("serviceMain threw: " + t);
			report(SERVICE_STOPPED, 0, 0, 0, 1);
		}
	}

	/** Upcall: the SCM delivers control requests (STOP/SHUTDOWN/...) here. */
	@SuppressWarnings("unused")
	private int handlerEx(final int control, final int eventType, final MemorySegment eventData,
			final MemorySegment context) {
		switch (control) {
			case SERVICE_CONTROL_STOP, SERVICE_CONTROL_SHUTDOWN -> {
				log("stop/shutdown control received");
				stopRequested = true;
				report(SERVICE_STOP_PENDING, 0, ++checkpoint, 10000, NO_ERROR);
				final Process current = child;
				if (current != null) {
					current.destroy();
				}
			}
			case SERVICE_CONTROL_INTERROGATE -> {
				/* report current state below */
			}
			default -> {
				/* unhandled controls are ignored */
			}
		}
		return NO_ERROR;
	}

	/** Run the child, restarting per the policy until a stop is requested or the policy gives up. */
	private int supervise() throws InterruptedException {
		int lastExit = 0;
		while (!stopRequested) {
			final Process current = child;
			if (current == null) {
				break;
			}
			lastExit = current.waitFor();
			if (stopRequested) {
				break;
			}
			if (!shouldRestart(spec.restart(), lastExit)) {
				break;
			}
			log("child exited (" + lastExit + "); restarting per " + spec.restart());
			Thread.sleep(1000);   // simple backoff
			try {
				child = startChild();
			} catch (final IOException e) {
				log("restart failed: " + e);
				break;
			}
		}
		final Process current = child;
		if (current != null && current.isAlive()) {
			current.destroy();
			if (!current.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
				current.destroyForcibly();
			}
		}
		return lastExit;
	}

	private static boolean shouldRestart(final RestartPolicy policy, final int exitCode) {
		return switch (policy) {
			case NEVER -> false;
			case ON_FAILURE -> exitCode != 0;
			case ALWAYS -> true;
		};
	}

	private Process startChild() throws IOException {
		final ProcessBuilder pb = new ProcessBuilder(spec.command());
		if (spec.workingDirectory() != null) {
			pb.directory(spec.workingDirectory().toFile());
		}
		final Map<String, String> env = pb.environment();
		for (final Map.Entry<String, String> e : spec.environment().entrySet()) {
			env.put(e.getKey(), e.getValue());
		}
		pb.redirectOutput(spec.stdout() != null
				? ProcessBuilder.Redirect.appendTo(spec.stdout().toFile())
				: ProcessBuilder.Redirect.DISCARD);
		pb.redirectError(spec.stderr() != null
				? ProcessBuilder.Redirect.appendTo(spec.stderr().toFile())
				: ProcessBuilder.Redirect.DISCARD);
		return pb.start();
	}

	private synchronized void report(final int state, final int controls, final int checkPoint,
			final int waitHint, final int exitCode) {
		if (statusHandle == null || statusHandle.address() == 0) {
			return;
		}
		statusBuffer.set(ValueLayout.JAVA_INT, ST_TYPE, SERVICE_WIN32_OWN_PROCESS);
		statusBuffer.set(ValueLayout.JAVA_INT, ST_STATE, state);
		statusBuffer.set(ValueLayout.JAVA_INT, ST_CONTROLS, controls);
		statusBuffer.set(ValueLayout.JAVA_INT, ST_WIN32_EXIT, exitCode);
		statusBuffer.set(ValueLayout.JAVA_INT, ST_CHECKPOINT, checkPoint);
		statusBuffer.set(ValueLayout.JAVA_INT, ST_WAITHINT, waitHint);
		try {
			setServiceStatus.invoke(statusHandle, statusBuffer);
		} catch (final Throwable t) {
			log("SetServiceStatus threw: " + t);
		}
	}

	private MemorySegment upcall(final String method, final MethodType type,
			final FunctionDescriptor descriptor) {
		try {
			final MethodHandle handle = MethodHandles.lookup()
					.findVirtual(ServiceHost.class, method, type).bindTo(this);
			return linker.upcallStub(handle, descriptor, arena);
		} catch (final ReflectiveOperationException e) {
			throw new IllegalStateException("could not bind upcall " + method, e);
		}
	}

	private MemorySegment wide(final String s) {
		return arena.allocateFrom(ValueLayout.JAVA_CHAR, (s + "\0").toCharArray());
	}

	private void log(final String message) {
		try {
			Files.createDirectories(logFile.getParent());
			Files.writeString(logFile, Instant.now() + " " + message + "\n",
					StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (final IOException ignored) {
			// diagnostics are best-effort
		}
	}

	private static String parseId(final String[] args) {
		for (int i = 0; i < args.length - 1; i++) {
			if ("--id".equals(args[i])) {
				return args[i + 1];
			}
		}
		return null;
	}
}
