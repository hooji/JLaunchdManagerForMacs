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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The bundled pure-Java Windows service host — the answer to the SCM-protocol quirk (a plain
 * {@code java -jar} can't be a service). Registered as a service's {@code binPath}
 * ({@code "<javaw>" --enable-native-access=ALL-UNNAMED -cp "<jar>" <thisClass> --id <id>}); on
 * start the SCM launches it, and it uses FFM <em>upcalls</em> to speak the protocol
 * ({@code StartServiceCtrlDispatcherW} → {@code ServiceMain}, {@code RegisterServiceCtrlHandlerExW}
 * → handler, {@code SetServiceStatus}) while {@code CreateProcess}-supervising the real command
 * read from the sidecar JSON. This <em>is</em> a real Windows service, in pure Java.
 *
 * <p>Only meaningful on Windows, launched by the SCM. {@link RestartPolicy} is implemented as a
 * respawn loop around the child (the primary keep-alive mechanism).
 */
public final class ServiceHost {

	// Service states / controls (winsvc.h).
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

	private final String id;
	private final ServiceSpec spec;
	private final Path logFile;

	private final Arena arena = Arena.ofShared();
	private Linker linker;
	private MethodHandle registerHandler;
	private MethodHandle setServiceStatus;
	private MemorySegment statusHandle = MemorySegment.NULL;
	private final MemorySegment statusBuf;

	private volatile Process child;
	private volatile boolean stopRequested;
	private final CountDownLatch stopLatch = new CountDownLatch(1);

	private ServiceHost(final String id, final ServiceSpec spec) {
		this.id = id;
		this.spec = spec;
		this.logFile = sidecarDir().resolve(id + ".host.log");
		this.statusBuf = arena.allocate(28);   // SERVICE_STATUS: 7 DWORDs
	}

	public static void main(final String[] args) {
		final String id = argValue(args, "--id");
		if (id == null) {
			System.err.println("ServiceHost requires --id <service-id>");
			System.exit(2);
			return;
		}
		final ServiceSpec spec;
		try {
			spec = loadSpec(id);
		} catch (final RuntimeException | IOException e) {
			System.err.println("ServiceHost could not load sidecar for " + id + ": " + e);
			System.exit(3);
			return;
		}
		new ServiceHost(id, spec).run();
	}

	private void run() {
		log("host starting for id=" + id + " command=" + spec.command());
		linker = Linker.nativeLinker();
		final SymbolLookup advapi32 = SymbolLookup.libraryLookup("Advapi32.dll", arena);

		final MethodHandle dispatcher = linker.downcallHandle(
				advapi32.find("StartServiceCtrlDispatcherW").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
		registerHandler = linker.downcallHandle(
				advapi32.find("RegisterServiceCtrlHandlerExW").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
						ValueLayout.ADDRESS));
		setServiceStatus = linker.downcallHandle(
				advapi32.find("SetServiceStatus").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
						ValueLayout.ADDRESS));

		final MemorySegment serviceMainStub = upcall("serviceMain",
				MethodType.methodType(void.class, int.class, MemorySegment.class),
				FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

		// SERVICE_TABLE_ENTRYW[2]: { {name, ServiceMain}, {NULL, NULL} }.
		final MemorySegment table = arena.allocate(32);
		table.set(ValueLayout.ADDRESS, 0, arena.allocateFrom(id, StandardCharsets.UTF_16LE));
		table.set(ValueLayout.ADDRESS, 8, serviceMainStub);
		table.set(ValueLayout.ADDRESS, 16, MemorySegment.NULL);
		table.set(ValueLayout.ADDRESS, 24, MemorySegment.NULL);

		try {
			final int ok = (int) dispatcher.invoke(table);
			if (ok == 0) {
				log("StartServiceCtrlDispatcherW failed (not launched by the SCM?)");
				System.exit(4);
			}
		} catch (final Throwable t) {
			log("dispatcher threw: " + t);
			System.exit(5);
		}
		log("host exiting for id=" + id);
	}

	/** Upcall target: the SCM's service entry point. Runs on a dispatcher thread. (Package-private
	 * so {@code MethodHandles.Lookup#findVirtual} can bind it — private methods aren't virtual.) */
	@SuppressWarnings("unused")
	void serviceMain(final int argc, final MemorySegment argv) {
		try {
			final MemorySegment handlerStub = upcall("handlerEx",
					MethodType.methodType(int.class, int.class, int.class, MemorySegment.class,
							MemorySegment.class),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
							ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			statusHandle = (MemorySegment) registerHandler.invoke(
					arena.allocateFrom(id, StandardCharsets.UTF_16LE), handlerStub,
					MemorySegment.NULL);
			if (statusHandle.address() == 0L) {
				log("RegisterServiceCtrlHandlerExW failed");
				return;
			}

			report(SERVICE_START_PENDING, 0, 1, 3000);
			startChild();
			report(SERVICE_RUNNING, SERVICE_ACCEPT_STOP | SERVICE_ACCEPT_SHUTDOWN, 0, 0);
			superviseInBackground();

			stopLatch.await();   // block until a STOP control (or the child gave up) releases us
			report(SERVICE_STOPPED, 0, 0, 0);
		} catch (final Throwable t) {
			log("serviceMain error: " + t);
			report(SERVICE_STOPPED, 0, 0, 1);
		}
	}

	/** Upcall target: the SCM control handler. Package-private for the same findVirtual reason. */
	@SuppressWarnings("unused")
	int handlerEx(final int control, final int eventType, final MemorySegment eventData,
			final MemorySegment context) {
		switch (control) {
			case SERVICE_CONTROL_STOP, SERVICE_CONTROL_SHUTDOWN -> {
				log("stop/shutdown control received");
				stopRequested = true;
				report(SERVICE_STOP_PENDING, 0, 1, 5000);
				terminateChild();
				stopLatch.countDown();
			}
			case SERVICE_CONTROL_INTERROGATE -> {
				/* status already current */
			}
			default -> {
				/* unhandled controls are simply acknowledged */
			}
		}
		return NO_ERROR;
	}

	// --- child supervision ---

	private void startChild() throws IOException {
		final ProcessBuilder pb = new ProcessBuilder(spec.command());
		if (spec.workingDirectory() != null) {
			pb.directory(spec.workingDirectory().toFile());
		}
		final Map<String, String> env = pb.environment();
		env.putAll(spec.environment());
		if (spec.stdout() != null) {
			pb.redirectOutput(spec.stdout().toFile());
		}
		if (spec.stderr() != null) {
			pb.redirectError(spec.stderr().toFile());
		}
		child = pb.start();
		log("child started pid=" + child.pid());
	}

	private void superviseInBackground() {
		final Thread t = new Thread(this::superviseLoop, "servicepal-supervisor");
		t.setDaemon(true);
		t.start();
	}

	private void superviseLoop() {
		while (!stopRequested) {
			final int exit;
			try {
				exit = child.waitFor();
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			if (stopRequested) {
				return;
			}
			log("child exited code=" + exit + " policy=" + spec.restart());
			if (!shouldRespawn(spec.restart(), exit)) {
				stopLatch.countDown();   // service stops on its own; serviceMain reports STOPPED
				return;
			}
			try {
				Thread.sleep(1000);   // small backoff to avoid a tight crash loop
				if (stopRequested) {
					return;
				}
				startChild();
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (final IOException e) {
				log("respawn failed: " + e);
				stopLatch.countDown();
				return;
			}
		}
	}

	private static boolean shouldRespawn(final RestartPolicy policy, final int exitCode) {
		return switch (policy) {
			case ALWAYS -> true;
			case ON_FAILURE -> exitCode != 0;
			case NEVER -> false;
		};
	}

	private void terminateChild() {
		final Process c = child;
		if (c == null) {
			return;
		}
		c.destroy();
		try {
			if (!c.waitFor(5, TimeUnit.SECONDS)) {
				c.destroyForcibly();
			}
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			c.destroyForcibly();
		}
	}

	// --- SCM status reporting ---

	private synchronized void report(final int state, final int controlsAccepted,
			final int checkPoint, final int waitHint) {
		statusBuf.set(ValueLayout.JAVA_INT, 0, SERVICE_WIN32_OWN_PROCESS);
		statusBuf.set(ValueLayout.JAVA_INT, 4, state);
		statusBuf.set(ValueLayout.JAVA_INT, 8, controlsAccepted);
		statusBuf.set(ValueLayout.JAVA_INT, 12, NO_ERROR);
		statusBuf.set(ValueLayout.JAVA_INT, 16, 0);
		statusBuf.set(ValueLayout.JAVA_INT, 20, checkPoint);
		statusBuf.set(ValueLayout.JAVA_INT, 24, waitHint);
		if (statusHandle.address() == 0L) {
			return;
		}
		try {
			setServiceStatus.invoke(statusHandle, statusBuf);
		} catch (final Throwable t) {
			log("SetServiceStatus failed: " + t);
		}
	}

	// --- FFM plumbing ---

	private MemorySegment upcall(final String method, final MethodType type,
			final FunctionDescriptor descriptor) {
		try {
			final MethodHandle handle = MethodHandles.lookup()
					.findVirtual(ServiceHost.class, method, type).bindTo(this);
			return linker.upcallStub(handle, descriptor, arena);
		} catch (final ReflectiveOperationException e) {
			throw new IllegalStateException("failed to bind upcall " + method, e);
		}
	}

	// --- sidecar loading ---

	private static ServiceSpec loadSpec(final String id) throws IOException {
		final Path file = sidecarDir().resolve(id + ".json");
		final String json = Files.readString(file);
		return new SidecarReader().toSpec(new SidecarReader().parse(json));
	}

	private static Path sidecarDir() {
		final String programData = System.getenv("ProgramData");
		return Path.of(programData != null ? programData : System.getProperty("user.home", "."),
				"ServicePal");
	}

	private static String argValue(final String[] args, final String name) {
		for (int i = 0; i < args.length - 1; i++) {
			if (name.equals(args[i])) {
				return args[i + 1];
			}
		}
		return null;
	}

	private void log(final String message) {
		final String line = LocalDateTime.now() + " " + message + System.lineSeparator();
		try {
			Files.writeString(logFile, line,
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		} catch (final IOException ignored) {
			// diagnostics are best-effort
		}
	}
}
