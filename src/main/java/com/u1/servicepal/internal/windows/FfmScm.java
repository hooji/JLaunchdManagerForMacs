package com.u1.servicepal.internal.windows;

import com.u1.servicepal.NativeCommandException;
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
 * The real {@link Scm}: reaches the Windows Service Control Manager in {@code advapi32.dll}
 * through Java FFM ({@code java.lang.foreign}). Hand-written bindings for the ~11 functions a
 * single service's lifecycle needs (all the {@code …W} wide variants). Restricted native access
 * — the consuming app must run with {@code --enable-native-access}.
 *
 * <p>Off-Windows this class is never loaded (the manager picks a recording fake), so the
 * {@code Advapi32.dll} lookup in the constructor only runs on Windows.
 */
public final class FfmScm implements Scm {

	// --- Win32 constants ---
	private static final int SC_MANAGER_ALL_ACCESS = 0xF003F;
	private static final int SERVICE_ALL_ACCESS = 0xF01FF;
	private static final int SERVICE_WIN32_OWN_PROCESS = 0x10;
	private static final int SERVICE_ERROR_NORMAL = 0x1;
	private static final int SERVICE_NO_CHANGE = 0xFFFFFFFF;
	private static final int SC_STATUS_PROCESS_INFO = 0;
	private static final int SERVICE_CONFIG_DESCRIPTION = 1;
	private static final int SERVICE_CONTROL_STOP = 0x1;

	private static final int ERROR_SERVICE_DOES_NOT_EXIST = 1060;
	private static final int ERROR_SERVICE_ALREADY_RUNNING = 1056;
	private static final int ERROR_SERVICE_NOT_ACTIVE = 1062;

	private static final ValueLayout.OfInt DWORD = ValueLayout.JAVA_INT;
	private static final java.lang.foreign.AddressLayout PTR = ValueLayout.ADDRESS;

	private final Linker linker = Linker.nativeLinker();
	private final StructLayout captureLayout = Linker.Option.captureStateLayout();
	private final VarHandle lastErrorHandle =
			captureLayout.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));

	private final MethodHandle openScManager;
	private final MethodHandle openService;
	private final MethodHandle createService;
	private final MethodHandle deleteService;
	private final MethodHandle startService;
	private final MethodHandle controlService;
	private final MethodHandle changeServiceConfig;
	private final MethodHandle changeServiceConfig2;
	private final MethodHandle queryServiceStatusEx;
	private final MethodHandle queryServiceConfig;
	private final MethodHandle closeServiceHandle;

	public FfmScm() {
		final SymbolLookup advapi32 = SymbolLookup.libraryLookup("Advapi32.dll", Arena.global());
		final Linker.Option ccs = Linker.Option.captureCallState("GetLastError");

		openScManager = bind(advapi32, "OpenSCManagerW",
				FunctionDescriptor.of(PTR, PTR, PTR, DWORD), ccs);
		openService = bind(advapi32, "OpenServiceW",
				FunctionDescriptor.of(PTR, PTR, PTR, DWORD), ccs);
		createService = bind(advapi32, "CreateServiceW",
				FunctionDescriptor.of(PTR, PTR, PTR, PTR, DWORD, DWORD, DWORD, DWORD,
						PTR, PTR, PTR, PTR, PTR, PTR), ccs);
		deleteService = bind(advapi32, "DeleteService", FunctionDescriptor.of(DWORD, PTR), ccs);
		startService = bind(advapi32, "StartServiceW",
				FunctionDescriptor.of(DWORD, PTR, DWORD, PTR), ccs);
		controlService = bind(advapi32, "ControlService",
				FunctionDescriptor.of(DWORD, PTR, DWORD, PTR), ccs);
		changeServiceConfig = bind(advapi32, "ChangeServiceConfigW",
				FunctionDescriptor.of(DWORD, PTR, DWORD, DWORD, DWORD, PTR, PTR, PTR, PTR, PTR,
						PTR, PTR), ccs);
		changeServiceConfig2 = bind(advapi32, "ChangeServiceConfig2W",
				FunctionDescriptor.of(DWORD, PTR, DWORD, PTR), ccs);
		queryServiceStatusEx = bind(advapi32, "QueryServiceStatusEx",
				FunctionDescriptor.of(DWORD, PTR, DWORD, PTR, DWORD, PTR), ccs);
		queryServiceConfig = bind(advapi32, "QueryServiceConfigW",
				FunctionDescriptor.of(DWORD, PTR, PTR, DWORD, PTR), ccs);
		closeServiceHandle = bind(advapi32, "CloseServiceHandle",
				FunctionDescriptor.of(DWORD, PTR), ccs);
	}

	private MethodHandle bind(final SymbolLookup lookup, final String name,
			final FunctionDescriptor descriptor, final Linker.Option option) {
		return linker.downcallHandle(lookup.find(name).orElseThrow(
				() -> new IllegalStateException("advapi32 missing " + name)), descriptor, option);
	}

	@Override
	public boolean exists(final String name) {
		try (Arena arena = Arena.ofConfined()) {
			final MemorySegment scm = openManager(arena, SC_MANAGER_ALL_ACCESS);
			try {
				final MemorySegment svc = tryOpenService(arena, scm, name, SERVICE_ALL_ACCESS);
				if (isNull(svc)) {
					return false;
				}
				close(arena, svc);
				return true;
			} finally {
				close(arena, scm);
			}
		} catch (final Throwable t) {
			throw rethrow("OpenServiceW", name, t);
		}
	}

	@Override
	public void create(final String name, final String displayName, final String binPath,
			final int startType, final String account, final String password) {
		try (Arena arena = Arena.ofConfined()) {
			final MemorySegment scm = openManager(arena, SC_MANAGER_ALL_ACCESS);
			try {
				final MemorySegment capture = arena.allocate(captureLayout);
				final MemorySegment svc = (MemorySegment) createService.invoke(capture, scm,
						wide(arena, name), wide(arena, displayName), SERVICE_ALL_ACCESS,
						SERVICE_WIN32_OWN_PROCESS, startType, SERVICE_ERROR_NORMAL,
						wide(arena, binPath), MemorySegment.NULL, MemorySegment.NULL,
						MemorySegment.NULL, wide(arena, account), wide(arena, password));
				if (isNull(svc)) {
					throw failure("CreateServiceW", name, lastError(capture));
				}
				close(arena, svc);
			} finally {
				close(arena, scm);
			}
		} catch (final Throwable t) {
			throw rethrow("CreateServiceW", name, t);
		}
	}

	@Override
	public void delete(final String name) {
		withService(name, SERVICE_ALL_ACCESS, "DeleteService", (arena, svc, capture) -> {
			final int ok = (int) deleteService.invoke(capture, svc);
			if (ok == 0) {
				throw failure("DeleteService", name, lastError(capture));
			}
			return null;
		});
	}

	@Override
	public void start(final String name) {
		withService(name, SERVICE_ALL_ACCESS, "StartServiceW", (arena, svc, capture) -> {
			final int ok = (int) startService.invoke(capture, svc, 0, MemorySegment.NULL);
			if (ok == 0) {
				final int err = lastError(capture);
				if (err != ERROR_SERVICE_ALREADY_RUNNING) {
					throw failure("StartServiceW", name, err);
				}
			}
			return null;
		});
	}

	@Override
	public void stop(final String name) {
		withService(name, SERVICE_ALL_ACCESS, "ControlService", (arena, svc, capture) -> {
			final MemorySegment status = arena.allocate(28);   // SERVICE_STATUS
			final int ok = (int) controlService.invoke(capture, svc, SERVICE_CONTROL_STOP, status);
			if (ok == 0) {
				final int err = lastError(capture);
				if (err != ERROR_SERVICE_NOT_ACTIVE) {
					throw failure("ControlService", name, err);
				}
			}
			return null;
		});
	}

	@Override
	public void setStartType(final String name, final int startType) {
		withService(name, SERVICE_ALL_ACCESS, "ChangeServiceConfigW", (arena, svc, capture) -> {
			final int ok = (int) changeServiceConfig.invoke(capture, svc, SERVICE_NO_CHANGE,
					startType, SERVICE_NO_CHANGE, MemorySegment.NULL, MemorySegment.NULL,
					MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
					MemorySegment.NULL);
			if (ok == 0) {
				throw failure("ChangeServiceConfigW", name, lastError(capture));
			}
			return null;
		});
	}

	@Override
	public void setDescription(final String name, final String description) {
		withService(name, SERVICE_ALL_ACCESS, "ChangeServiceConfig2W", (arena, svc, capture) -> {
			// SERVICE_DESCRIPTIONW { LPWSTR lpDescription; } — a struct holding one pointer.
			final MemorySegment info = arena.allocate(PTR);
			info.set(PTR, 0, wide(arena, description));
			final int ok = (int) changeServiceConfig2.invoke(capture, svc,
					SERVICE_CONFIG_DESCRIPTION, info);
			if (ok == 0) {
				throw failure("ChangeServiceConfig2W", name, lastError(capture));
			}
			return null;
		});
	}

	@Override
	public ServiceControlStatus queryStatus(final String name) {
		try (Arena arena = Arena.ofConfined()) {
			final MemorySegment scm = openManager(arena, SC_MANAGER_ALL_ACCESS);
			try {
				final MemorySegment svc = tryOpenService(arena, scm, name, SERVICE_ALL_ACCESS);
				if (isNull(svc)) {
					return ServiceControlStatus.notFound();
				}
				try {
					final MemorySegment buf = arena.allocate(36);   // SERVICE_STATUS_PROCESS
					final MemorySegment needed = arena.allocate(DWORD);
					final MemorySegment capture = arena.allocate(captureLayout);
					final int ok = (int) queryServiceStatusEx.invoke(capture, svc,
							SC_STATUS_PROCESS_INFO, buf, 36, needed);
					if (ok == 0) {
						return ServiceControlStatus.notFound();
					}
					final int state = buf.get(DWORD, 4);
					final int exit = buf.get(DWORD, 12);
					final int pid = buf.get(DWORD, 28);
					return new ServiceControlStatus(state, pid > 0 ? pid : null,
							Integer.valueOf(exit));
				} finally {
					close(arena, svc);
				}
			} finally {
				close(arena, scm);
			}
		} catch (final Throwable t) {
			throw rethrow("QueryServiceStatusEx", name, t);
		}
	}

	@Override
	public Integer queryStartType(final String name) {
		try (Arena arena = Arena.ofConfined()) {
			final MemorySegment scm = openManager(arena, SC_MANAGER_ALL_ACCESS);
			try {
				final MemorySegment svc = tryOpenService(arena, scm, name, SERVICE_ALL_ACCESS);
				if (isNull(svc)) {
					return null;
				}
				try {
					final int size = 8192;
					final MemorySegment buf = arena.allocate(size);
					final MemorySegment needed = arena.allocate(DWORD);
					final MemorySegment capture = arena.allocate(captureLayout);
					final int ok = (int) queryServiceConfig.invoke(capture, svc, buf, size, needed);
					if (ok == 0) {
						return null;
					}
					// QUERY_SERVICE_CONFIGW: dwServiceType(0), dwStartType(4), ...
					return Integer.valueOf(buf.get(DWORD, 4));
				} finally {
					close(arena, svc);
				}
			} finally {
				close(arena, scm);
			}
		} catch (final Throwable t) {
			throw rethrow("QueryServiceConfigW", name, t);
		}
	}

	// --- internals ---

	/** A unit of work that runs against an opened service handle, with a capture segment. */
	private interface ServiceOp {
		Object run(Arena arena, MemorySegment service, MemorySegment capture) throws Throwable;
	}

	private void withService(final String name, final int access, final String fn,
			final ServiceOp op) {
		try (Arena arena = Arena.ofConfined()) {
			final MemorySegment scm = openManager(arena, SC_MANAGER_ALL_ACCESS);
			try {
				final MemorySegment svc = tryOpenService(arena, scm, name, access);
				if (isNull(svc)) {
					throw failure(fn, name, ERROR_SERVICE_DOES_NOT_EXIST);
				}
				try {
					op.run(arena, svc, arena.allocate(captureLayout));
				} finally {
					close(arena, svc);
				}
			} finally {
				close(arena, scm);
			}
		} catch (final Throwable t) {
			throw rethrow(fn, name, t);
		}
	}

	private MemorySegment openManager(final Arena arena, final int access) {
		try {
			final MemorySegment capture = arena.allocate(captureLayout);
			final MemorySegment scm = (MemorySegment) openScManager.invoke(capture,
					MemorySegment.NULL, MemorySegment.NULL, access);
			if (isNull(scm)) {
				throw failure("OpenSCManagerW", "(local)", lastError(capture));
			}
			return scm;
		} catch (final Throwable t) {
			throw rethrow("OpenSCManagerW", "(local)", t);
		}
	}

	private MemorySegment tryOpenService(final Arena arena, final MemorySegment scm,
			final String name, final int access) throws Throwable {
		final MemorySegment capture = arena.allocate(captureLayout);
		return (MemorySegment) openService.invoke(capture, scm, wide(arena, name), access);
	}

	private void close(final Arena arena, final MemorySegment handle) {
		if (isNull(handle)) {
			return;
		}
		try {
			closeServiceHandle.invoke(arena.allocate(captureLayout), handle);
		} catch (final Throwable ignored) {
			// closing a handle should not mask the original outcome
		}
	}

	private int lastError(final MemorySegment capture) {
		return (int) lastErrorHandle.get(capture, 0L);
	}

	private static MemorySegment wide(final Arena arena, final String text) {
		return text == null ? MemorySegment.NULL
				: arena.allocateFrom(text, StandardCharsets.UTF_16LE);
	}

	private static boolean isNull(final MemorySegment segment) {
		return segment == null || segment.address() == 0L;
	}

	private static NativeCommandException failure(final String fn, final String name,
			final int err) {
		return new NativeCommandException(List.of("advapi32:" + fn, name), err,
				"Win32 error " + err);
	}

	private static RuntimeException rethrow(final String fn, final String name, final Throwable t) {
		if (t instanceof NativeCommandException nce) {
			return nce;
		}
		return new NativeCommandException(List.of("advapi32:" + fn, name), -1, String.valueOf(t));
	}
}
