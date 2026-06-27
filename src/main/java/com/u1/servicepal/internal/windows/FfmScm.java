package com.u1.servicepal.internal.windows;

import com.u1.servicepal.NativeCommandException;
import com.u1.servicepal.PermissionException;
import com.u1.servicepal.ServiceException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * {@link Scm} backed by Java FFM calls into {@code advapi32.dll}. Windows-only; all binding is
 * lazy (the first actual call resolves the library + function handles) so merely constructing the
 * backend — or running discovery on a machine with no ServicePal services — never triggers a
 * restricted native operation.
 *
 * <p>Every management handle is bound with {@code captureCallState("GetLastError")}, so the
 * captured-state segment is threaded as the leading argument of each call and read back for
 * diagnostics. {@code ERROR_ACCESS_DENIED} (5) maps to {@link PermissionException} (you need
 * Administrator); other failures to {@link NativeCommandException}.
 */
public final class FfmScm implements Scm {

	// Access rights.
	private static final int SC_MANAGER_CONNECT = 0x0001;
	private static final int SC_MANAGER_CREATE_SERVICE = 0x0002;
	private static final int SERVICE_QUERY_STATUS = 0x0004;
	private static final int SERVICE_START = 0x0010;
	private static final int SERVICE_STOP = 0x0020;
	private static final int SERVICE_CHANGE_CONFIG = 0x0002;
	private static final int DELETE = 0x00010000;
	private static final int NEW_SERVICE_ACCESS = DELETE | SERVICE_CHANGE_CONFIG | SERVICE_START
			| SERVICE_STOP | SERVICE_QUERY_STATUS;

	// Service config values.
	private static final int SERVICE_WIN32_OWN_PROCESS = 0x00000010;
	private static final int SERVICE_ERROR_NORMAL = 0x00000001;
	private static final int SERVICE_NO_CHANGE = 0xFFFFFFFF;
	private static final int SERVICE_CONTROL_STOP = 0x00000001;
	private static final int SC_STATUS_PROCESS_INFO = 0;
	private static final int SERVICE_CONFIG_DESCRIPTION = 1;
	private static final int SERVICE_CONFIG_DELAYED_AUTO_START_INFO = 3;

	private static final int ERROR_ACCESS_DENIED = 5;
	private static final int ERROR_SERVICE_DOES_NOT_EXIST = 1060;

	// SERVICE_STATUS_PROCESS: nine DWORDs; dwCurrentState@4, dwWin32ExitCode@12, dwProcessId@28.
	private static final int STATUS_PROCESS_SIZE = 36;
	private static final int OFF_CURRENT_STATE = 4;
	private static final int OFF_WIN32_EXIT = 12;
	private static final int OFF_PROCESS_ID = 28;

	private final Object lock = new Object();
	private volatile boolean initialized;

	private Arena arena;
	private StructLayout captureLayout;
	private VarHandle lastErrorHandle;

	private MethodHandle openScm;
	private MethodHandle createService;
	private MethodHandle openService;
	private MethodHandle changeConfig;
	private MethodHandle changeConfig2;
	private MethodHandle startService;
	private MethodHandle controlService;
	private MethodHandle deleteService;
	private MethodHandle queryStatusEx;
	private MethodHandle closeHandle;   // bound WITHOUT capture (no diagnostics needed)

	private void ensureInit() {
		if (initialized) {
			return;
		}
		synchronized (lock) {
			if (initialized) {
				return;
			}
			final Linker linker = Linker.nativeLinker();
			arena = Arena.ofShared();
			final SymbolLookup advapi32 = SymbolLookup.libraryLookup("Advapi32.dll", arena);
			captureLayout = Linker.Option.captureStateLayout();
			lastErrorHandle = captureLayout.varHandle(
					MemoryLayout.PathElement.groupElement("GetLastError"));
			final Linker.Option capture = Linker.Option.captureCallState("GetLastError");

			final ValueLayout.OfInt i = ValueLayout.JAVA_INT;
			final var p = ValueLayout.ADDRESS;

			openScm = linker.downcallHandle(symbol(advapi32, "OpenSCManagerW"),
					FunctionDescriptor.of(p, p, p, i), capture);
			createService = linker.downcallHandle(symbol(advapi32, "CreateServiceW"),
					FunctionDescriptor.of(p, p, p, p, i, i, i, i, p, p, p, p, p, p), capture);
			openService = linker.downcallHandle(symbol(advapi32, "OpenServiceW"),
					FunctionDescriptor.of(p, p, p, i), capture);
			changeConfig = linker.downcallHandle(symbol(advapi32, "ChangeServiceConfigW"),
					FunctionDescriptor.of(i, p, i, i, i, p, p, p, p, p, p, p), capture);
			changeConfig2 = linker.downcallHandle(symbol(advapi32, "ChangeServiceConfig2W"),
					FunctionDescriptor.of(i, p, i, p), capture);
			startService = linker.downcallHandle(symbol(advapi32, "StartServiceW"),
					FunctionDescriptor.of(i, p, i, p), capture);
			controlService = linker.downcallHandle(symbol(advapi32, "ControlService"),
					FunctionDescriptor.of(i, p, i, p), capture);
			deleteService = linker.downcallHandle(symbol(advapi32, "DeleteService"),
					FunctionDescriptor.of(i, p), capture);
			queryStatusEx = linker.downcallHandle(symbol(advapi32, "QueryServiceStatusEx"),
					FunctionDescriptor.of(i, p, i, p, i, p), capture);
			closeHandle = linker.downcallHandle(symbol(advapi32, "CloseServiceHandle"),
					FunctionDescriptor.of(i, p));

			initialized = true;
		}
	}

	private static MemorySegment symbol(final SymbolLookup lib, final String name) {
		return lib.find(name).orElseThrow(
				() -> new ServiceException("advapi32 symbol not found: " + name));
	}

	@Override
	public boolean exists(final String name) {
		ensureInit();
		try (Arena a = Arena.ofConfined()) {
			final MemorySegment cap = a.allocate(captureLayout);
			final MemorySegment scm = openManager(a, cap, SC_MANAGER_CONNECT);
			try {
				final MemorySegment svc = ptr(openService, cap, scm, wide(a, name),
						SERVICE_QUERY_STATUS);
				if (isNull(svc)) {
					return lastError(cap) != ERROR_SERVICE_DOES_NOT_EXIST;
				}
				close(svc);
				return true;
			} finally {
				close(scm);
			}
		}
	}

	@Override
	public void create(final String name, final String displayName, final String binPath,
			final WinStartType startType, final String account, final String password) {
		ensureInit();
		try (Arena a = Arena.ofConfined()) {
			final MemorySegment cap = a.allocate(captureLayout);
			final MemorySegment scm = openManager(a, cap,
					SC_MANAGER_CONNECT | SC_MANAGER_CREATE_SERVICE);
			try {
				final MemorySegment svc = ptr(createService, cap, scm, wide(a, name),
						wide(a, displayName), NEW_SERVICE_ACCESS, SERVICE_WIN32_OWN_PROCESS,
						startType.code(), SERVICE_ERROR_NORMAL, wide(a, binPath), MemorySegment.NULL,
						MemorySegment.NULL, MemorySegment.NULL, wide(a, account), wide(a, password));
				if (isNull(svc)) {
					throw failure("CreateServiceW", name, lastError(cap));
				}
				try {
					if (startType.delayed()) {
						setDelayedAutoStart(a, cap, svc);
					}
				} finally {
					close(svc);
				}
			} finally {
				close(scm);
			}
		}
	}

	@Override
	public void updateConfig(final String name, final String binPath, final WinStartType startType,
			final String account, final String password) {
		ensureInit();
		withService(name, SERVICE_CHANGE_CONFIG, (a, cap, svc) -> {
			bool(changeConfig, cap, "ChangeServiceConfigW", name, svc, SERVICE_NO_CHANGE,
					startType.code(), SERVICE_NO_CHANGE, wide(a, binPath), MemorySegment.NULL,
					MemorySegment.NULL, MemorySegment.NULL, wide(a, account), wide(a, password),
					MemorySegment.NULL);
			if (startType.delayed()) {
				setDelayedAutoStart(a, cap, svc);
			}
		});
	}

	@Override
	public void setDescription(final String name, final String description) {
		ensureInit();
		withService(name, SERVICE_CHANGE_CONFIG, (a, cap, svc) -> {
			final MemorySegment info = a.allocate(ValueLayout.ADDRESS);   // SERVICE_DESCRIPTION
			info.set(ValueLayout.ADDRESS, 0, wide(a, description));
			bool(changeConfig2, cap, "ChangeServiceConfig2W", name, svc,
					SERVICE_CONFIG_DESCRIPTION, info);
		});
	}

	@Override
	public void setStartType(final String name, final WinStartType startType) {
		ensureInit();
		withService(name, SERVICE_CHANGE_CONFIG, (a, cap, svc) -> {
			bool(changeConfig, cap, "ChangeServiceConfigW", name, svc, SERVICE_NO_CHANGE,
					startType.code(), SERVICE_NO_CHANGE, MemorySegment.NULL, MemorySegment.NULL,
					MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
					MemorySegment.NULL);
			if (startType.delayed()) {
				setDelayedAutoStart(a, cap, svc);
			}
		});
	}

	@Override
	public void start(final String name) {
		ensureInit();
		withService(name, SERVICE_START, (a, cap, svc) ->
				bool(startService, cap, "StartServiceW", name, svc, 0, MemorySegment.NULL));
	}

	@Override
	public void stop(final String name) {
		ensureInit();
		withService(name, SERVICE_STOP, (a, cap, svc) -> {
			final MemorySegment status = a.allocate(STATUS_PROCESS_SIZE);
			bool(controlService, cap, "ControlService", name, svc, SERVICE_CONTROL_STOP, status);
		});
	}

	@Override
	public void delete(final String name) {
		ensureInit();
		withService(name, DELETE, (a, cap, svc) ->
				bool(deleteService, cap, "DeleteService", name, svc));
	}

	@Override
	public ServiceControlStatus queryStatus(final String name) {
		ensureInit();
		try (Arena a = Arena.ofConfined()) {
			final MemorySegment cap = a.allocate(captureLayout);
			final MemorySegment scm = openManager(a, cap, SC_MANAGER_CONNECT);
			try {
				final MemorySegment svc = ptr(openService, cap, scm, wide(a, name),
						SERVICE_QUERY_STATUS);
				if (isNull(svc)) {
					if (lastError(cap) == ERROR_SERVICE_DOES_NOT_EXIST) {
						return null;
					}
					throw failure("OpenServiceW", name, lastError(cap));
				}
				try {
					final MemorySegment buf = a.allocate(STATUS_PROCESS_SIZE);
					final MemorySegment needed = a.allocate(ValueLayout.JAVA_INT);
					final int ok = invokeInt(queryStatusEx, cap, svc, SC_STATUS_PROCESS_INFO, buf,
							STATUS_PROCESS_SIZE, needed);
					if (ok == 0) {
						throw failure("QueryServiceStatusEx", name, lastError(cap));
					}
					final int state = buf.get(ValueLayout.JAVA_INT, OFF_CURRENT_STATE);
					final int exit = buf.get(ValueLayout.JAVA_INT, OFF_WIN32_EXIT);
					final int pid = buf.get(ValueLayout.JAVA_INT, OFF_PROCESS_ID);
					return new ServiceControlStatus(state, pid == 0 ? null : pid,
							state == ServiceControlStatus.STOPPED ? exit : null);
				} finally {
					close(svc);
				}
			} finally {
				close(scm);
			}
		}
	}

	// --- invocation plumbing (capture segment is always the leading argument) ---

	private interface ServiceAction {
		void run(Arena a, MemorySegment cap, MemorySegment svc) throws Throwable;
	}

	private void withService(final String name, final int access, final ServiceAction action) {
		try (Arena a = Arena.ofConfined()) {
			final MemorySegment cap = a.allocate(captureLayout);
			final MemorySegment scm = openManager(a, cap, SC_MANAGER_CONNECT);
			try {
				final MemorySegment svc = ptr(openService, cap, scm, wide(a, name), access);
				if (isNull(svc)) {
					throw failure("OpenServiceW", name, lastError(cap));
				}
				try {
					action.run(a, cap, svc);
				} finally {
					close(svc);
				}
			} finally {
				close(scm);
			}
		} catch (final ServiceException e) {
			throw e;
		} catch (final Throwable t) {
			throw new ServiceException("FFM call failed for service " + name, t);
		}
	}

	private MemorySegment openManager(final Arena a, final MemorySegment cap, final int access) {
		final MemorySegment scm = ptr(openScm, cap, MemorySegment.NULL, MemorySegment.NULL, access);
		if (isNull(scm)) {
			throw failure("OpenSCManagerW", "(scm)", lastError(cap));
		}
		return scm;
	}

	private void setDelayedAutoStart(final Arena a, final MemorySegment cap,
			final MemorySegment svc) {
		final MemorySegment info = a.allocate(ValueLayout.JAVA_INT);   // SERVICE_DELAYED_AUTO_START
		info.set(ValueLayout.JAVA_INT, 0, 1);
		bool(changeConfig2, cap, "ChangeServiceConfig2W", "(delayed-auto)", svc,
				SERVICE_CONFIG_DELAYED_AUTO_START_INFO, info);
	}

	/** Invoke a pointer-returning handle, prepending the capture segment. */
	private MemorySegment ptr(final MethodHandle handle, final MemorySegment cap,
			final Object... args) {
		try {
			return (MemorySegment) handle.invokeWithArguments(prepend(cap, args));
		} catch (final Throwable t) {
			throw new ServiceException("native call failed", t);
		}
	}

	/** Invoke an int-returning handle, prepending the capture segment. */
	private int invokeInt(final MethodHandle handle, final MemorySegment cap, final Object... args) {
		try {
			return (int) handle.invokeWithArguments(prepend(cap, args));
		} catch (final Throwable t) {
			throw new ServiceException("native call failed", t);
		}
	}

	/** Invoke a BOOL-returning handle and throw if it returns 0 (failure). */
	private void bool(final MethodHandle handle, final MemorySegment cap, final String fn,
			final String name, final Object... args) {
		if (invokeInt(handle, cap, args) == 0) {
			throw failure(fn, name, lastError(cap));
		}
	}

	private static Object[] prepend(final MemorySegment cap, final Object[] args) {
		final Object[] all = new Object[args.length + 1];
		all[0] = cap;
		System.arraycopy(args, 0, all, 1, args.length);
		return all;
	}

	private MemorySegment wide(final Arena a, final String s) {
		return s == null ? MemorySegment.NULL : a.allocateFrom(s, StandardCharsets.UTF_16LE);
	}

	private static boolean isNull(final MemorySegment seg) {
		return seg == null || seg.address() == 0L;
	}

	private void close(final MemorySegment handle) {
		if (isNull(handle)) {
			return;
		}
		try {
			closeHandle.invoke(handle);
		} catch (final Throwable ignored) {
			// closing handles is best-effort
		}
	}

	private int lastError(final MemorySegment cap) {
		return (int) lastErrorHandle.get(cap, 0L);
	}

	private static ServiceException failure(final String fn, final String name, final int err) {
		if (err == ERROR_ACCESS_DENIED) {
			return new PermissionException(fn + " on '" + name + "' denied (Win32 error 5) —"
					+ " service management requires Administrator");
		}
		return new NativeCommandException(List.of(fn, name), err, "Win32 error " + err);
	}
}
