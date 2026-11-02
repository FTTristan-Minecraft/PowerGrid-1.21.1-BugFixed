/*
 * Copyright 2025 patryk3211
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.patryk3211.powergrid.electricity.sim.solver;

import org.patryk3211.powergrid.utility.RateLimitedLog;
import net.minecraft.Util;
import org.patryk3211.powergrid.PowerGrid;
import org.patryk3211.powergrid.collections.ModdedConfigs;
import org.patryk3211.powergrid.config.CSolver;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;

import static org.patryk3211.powergrid.electricity.sim.ElectricalNetwork.LOGGER;

public class NativeMNA implements IMNA {
    private static final String EXPECTED_VERSION = "9";

    private static final int OPERATION_BUFFER_COMMAND_LENGTH = 256;
    private static final int JACOBIAN_COMMAND_SIZE = Integer.BYTES * 2 + Double.BYTES;
    private static final int RHS_COMMAND_SIZE = Integer.BYTES + Double.BYTES;
    private static boolean supported = false;

    private static final String MESSAGE_OK = "Native backend loaded successfully";
    private static final String MESSAGE_FAIL = "Native backend failed to load. Accelerated solver will not be available!";

    private static final Path NATIVES_DIR = Paths.get(".pg-native");

    public final ElectricalNetwork network;

    private long nativePtr;
    // Set whenever the matrix or hooks change, a quiescent network is only skipped when nothing was written.
    private boolean solveRequired = true;
    private boolean rhsWritten = true;
    private boolean lastSolveHadRhs = true;

    private boolean hooksChanged = true;
    protected boolean converged;
    protected int warmUpTicks = 0;
    private int size;

    private final ByteBuffer auxBuf;
    private final ByteBuffer rhsOps;
    private final ByteBuffer jacobianOps;

    private ByteBuffer stateBuffer;
    private final StateAccess stateAccess = new StateAccess();

    public static boolean isSupported() {
        return supported;
    }

    /**
     * @return Platform identifier ("&lt;os&gt;-&lt;arch&gt;") of the running JVM, or null if the operating system isn't supported.
     * It names the folder inside the jar which contains the binary for this platform.
     */
    static String platformId() {
        var os = switch (Util.getPlatform()) {
            case LINUX -> "linux";
            case WINDOWS -> "windows";
            case OSX -> "macos";
            default -> null;
        };
        if (os == null)
            return null;
        var arch = switch (System.getProperty("os.arch", "").toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            case "arm", "armv7", "armv7l", "armv8l" -> "armv7";
            case "riscv64" -> "riscv64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            default -> System.getProperty("os.arch", "unknown").toLowerCase(Locale.ROOT);
        };
        return os + "-" + arch;
    }

    private static String libraryFileName() {
        var extension = switch (Util.getPlatform()) {
            case WINDOWS -> ".dll";
            case OSX -> ".dylib";
            default -> ".so";
        };
        return "libpowergridNative" + EXPECTED_VERSION + extension;
    }

    private static boolean verifyAndMarkSupported() {
        if (verifySupport() == 0) {
            PowerGrid.LOGGER.info(MESSAGE_OK);
            supported = true;
        } else {
            PowerGrid.LOGGER.error(MESSAGE_FAIL);
        }
        return supported;
    }

    public static void tryLoad() {
        try {
            // Try load from path first
            System.loadLibrary("powergridNative" + EXPECTED_VERSION);
            verifyAndMarkSupported();
        } catch (Throwable e) {
            try {
                var platform = platformId();
                if (platform == null) {
                    PowerGrid.LOGGER.error(MESSAGE_FAIL, e);
                    return;
                }
                var libName = libraryFileName();
                Files.createDirectories(NATIVES_DIR);
                // Binaries of different platforms may share the directory (for instance when it is synced between machines).
                var libPath = NATIVES_DIR.resolve(platform + "-" + libName);
                var loaded = true;

                // Always compare with the binary embedded in the jar so that a stale or modified
                // copy left in the natives directory is never loaded.
                byte[] embedded = null;
                var loader = NativeMNA.class.getClassLoader();
                try (var stream = loader.getResourceAsStream("native/" + platform + "/" + libName)) {
                    if (stream != null)
                        embedded = stream.readAllBytes();
                }
                if (embedded != null) {
                    if (!Files.exists(libPath) || !Arrays.equals(Files.readAllBytes(libPath), embedded)) {
                        var temporary = Files.createTempFile(NATIVES_DIR, libName, ".tmp");
                        try {
                            Files.write(temporary, embedded);
                            Files.move(temporary, libPath, StandardCopyOption.REPLACE_EXISTING);
                        } finally {
                            Files.deleteIfExists(temporary);
                        }
                        PowerGrid.LOGGER.info("Extracted native binary '{}' for {} from jar", libName, platform);
                    }
                } else if (!Files.exists(libPath)) {
                    PowerGrid.LOGGER.error("No native solver binary is available for platform '{}'", platform);
                    loaded = false;
                }
                if (loaded) {
                    System.load(libPath.toAbsolutePath().toString());
                    verifyAndMarkSupported();
                }
            } catch (Throwable e2) {
                PowerGrid.LOGGER.error(MESSAGE_FAIL, e2);
            }
        }
        if(!supported)
            PowerGrid.LOGGER.error("Native solver is unavailable, falling back to the Java solver backend");
    }

    public NativeMNA(ElectricalNetwork network) {
        this.network = network;
        // Auxiliary status buffer.
        auxBuf = ByteBuffer.allocateDirect(1);
        // RHS operation buffer (int + double -> row + change)
        rhsOps = ByteBuffer.allocateDirect(RHS_COMMAND_SIZE * OPERATION_BUFFER_COMMAND_LENGTH);
        rhsOps.order(ByteOrder.nativeOrder());
        // Jacobian operation buffer = (int + int + double -> row + column + change)
        jacobianOps = ByteBuffer.allocateDirect(JACOBIAN_COMMAND_SIZE * OPERATION_BUFFER_COMMAND_LENGTH);
        jacobianOps.order(ByteOrder.nativeOrder());
        nativePtr = allocateNativeObject(rhsOps, jacobianOps, OPERATION_BUFFER_COMMAND_LENGTH, auxBuf);
        if(nativePtr == 0)
            throw new IllegalStateException("Failed to allocate the native solver");
    }

    private long ptr() {
        if(nativePtr == 0)
            throw new IllegalStateException("The native solver has already been cleaned up");
        return nativePtr;
    }

    @Override
    public CSolver.SolverBackend type() {
        return CSolver.SolverBackend.NATIVE;
    }

    @Override
    public void cleanup() {
        if(nativePtr == 0)
            return;
        var pointer = nativePtr;
        nativePtr = 0;
        deallocateNativeObject(pointer);
        // State buffer is NOT valid after destructors of native solver have been called.
        stateBuffer = null;
    }

    @Override
    public void setPrecision(double absoluteCriterion, double relativeCriterion, double minimumPrecision, double searchAlpha) {
        solveRequired = true;
        setPrecision(ptr(), absoluteCriterion, relativeCriterion, minimumPrecision, searchAlpha);
    }

    @Override
    public void hooksChanged() {
        hooksChanged = true;
        solveRequired = true;
    }

    @Override
    public void warmUp(int ticks) {
        if(ticks == -1 || warmUpTicks == -1) {
            warmUpTicks = -1;
            return;
        }
        converged = false;
        if(warmUpTicks < ticks)
            warmUpTicks = ticks;
    }

    @Override
    public void allocate(int size) {
        this.size = size;
        solveRequired = true;
        setStateSize(ptr(), size);
        // We need to wait until the backend gives us a handle to the newly allocated buffer.
        stateBuffer = null;

        // Reset modification buffers
        jacobianOps.position(0);
        rhsOps.position(0);
    }

    @Override
    public void jacobianAdd(int row, int column, double value) {
        if(value == 0)
            return;
        solveRequired = true;
        jacobianOps.putInt(row);
        jacobianOps.putInt(column);
        jacobianOps.putDouble(value);

        if(jacobianOps.position() == jacobianOps.capacity()) {
            // Ran out of space, send buffer to native for processing.
            processJacobianBuffer(ptr(), OPERATION_BUFFER_COMMAND_LENGTH);
            jacobianOps.position(0);
        }
    }

    @Override
    public void rhsAdd(int row, double value) {
        rhsWritten = true;
        rhsOps.putInt(row);
        rhsOps.putDouble(value);

        if(rhsOps.position() == rhsOps.capacity()) {
            // Ran out of space, send buffer to native for processing.
            processRHSBuffer(ptr());
            rhsOps.position(0);
        }
    }

    @Override
    public IMatrixAccess stateVector() {
        return stateAccess;
    }

    @SuppressWarnings("unused")
    private int runIterHooks(int iteration, ByteBuffer state) {
        // Run hooks
        network.countUpdates = false;
        this.stateBuffer = state;
        this.stateBuffer.order(ByteOrder.nativeOrder());
        for(var hook : network.innerHooks) {
            hook.startIteration(iteration);
        }
        network.countUpdates = true;

        int pos = jacobianOps.position();
        // Rewind modification buffer.
        jacobianOps.position(0);
        return pos / JACOBIAN_COMMAND_SIZE;
    }

    @SuppressWarnings("unused")
    private void runAddResidual(ByteBuffer residualBuffer) {
        // Run hooks
        residualBuffer.order(ByteOrder.nativeOrder());
        for(var hook : network.innerHooks) {
            hook.addResidual((row, change) -> {
                var offset = row * Double.BYTES;
                double current = residualBuffer.getDouble(offset);
                residualBuffer.putDouble(offset, current - change);
            });
        }
    }

    @SuppressWarnings("unused")
    private void reportConvergenceProblems(double norm, int i, ByteBuffer residualBuffer) {
        if (LOGGER != null) {
            if (ModdedConfigs.logsEnabled()) {
                RateLimitedLog.warn(LOGGER, "native-mna-convergence", "Convergence problems after {} solver iterations, final norm: {}", i, norm);
            }
        } else {
            System.out.printf("Convergence problems after %d solver iterations, final norm: %g\n", i, norm);
        }
        residualBuffer.order(ByteOrder.nativeOrder());
        network.convergenceProblems(norm, new IMatrixAccess() {
            @Override
            public void set(int row, int column, double value) {
                throw new IllegalCallerException("Cannot modify residual");
            }

            @Override
            public double get(int row, int column) {
                return residualBuffer.getDouble(row * Double.BYTES);
            }

            @Override
            public int numRows() {
                return size;
            }

            @Override
            public int numCols() {
                return 1;
            }
        });
    }

    @Override
    public void singleTick() {
        // Skip fully idle solves whenever nothing changed. A solve is only skipped when neither the matrix
        // nor the right hand side were written to since the last solve (an RHS which just dropped to zero
        // still has to be solved for), this keeps the grid stable and avoids pointless work.
        boolean rhsActive = rhsWritten;
        if(!rhsActive && !lastSolveHadRhs && !solveRequired && !hooksChanged && converged
                && rhsOps.position() == 0 && jacobianOps.position() == 0) {
            return;
        }
        lastSolveHadRhs = rhsActive;
        rhsWritten = false;
        solveRequired = false;

        // Finalize modification buffers.
        rhsOps.putInt(-1);

        // Rewind modification buffers.
        rhsOps.position(0);
        int ops = jacobianOps.position() / JACOBIAN_COMMAND_SIZE;
        jacobianOps.position(0);

        if(hooksChanged) {
            boolean hasIterHooks = !network.innerHooks.isEmpty();
            setHookPresence(ptr(), hasIterHooks, hasIterHooks);
            hooksChanged = false;
        }

        // Run native solve routine.
        int maxIterations = network.maxIterations.apply(network.hasHooks());
        stateBuffer = singleTick(ptr(), maxIterations, ops);
        if(stateBuffer != null)
            stateBuffer.order(ByteOrder.nativeOrder());
        byte status = auxBuf.get(0);
        converged = (status & 1) != 0;

        if(converged && warmUpTicks > 0) {
            --warmUpTicks;
            converged = false;
        }
    }

    @Override
    public void zeroRHS() {
        zeroRHS(ptr());
    }

    @Override
    public void zeroState() {
        zeroState(ptr());
        converged = true;
    }

    @Override
    public void jacobianPrepareForWrite() {
        jacobianOps.position(0);
        solveRequired = true;
        zeroJacobian(ptr());
    }

    @Override
    public void finishJacobianWrite() {
        solveRequired = true;
        finishJacobianWrite(ptr(), jacobianOps.position() / JACOBIAN_COMMAND_SIZE);
        jacobianOps.position(0);
    }

    @Override
    public void rowExchange(boolean state) {

    }

    @Override
    public boolean rowExchange() {
        return false;
    }

    @Override
    public boolean isConverged() {
        return converged;
    }

    private class StateAccess implements IMatrixAccess {
        @Override
        public void set(int row, int column, double value) {
            stateBuffer.putDouble(row * Double.BYTES, value);
        }

        @Override
        public double get(int row, int column) {
            // TODO: This seems slow for some reason?
            return stateBuffer.getDouble(row * Double.BYTES);
        }

        @Override
        public double safe_get(int row, int column) {
            if(stateBuffer == null || row * Double.BYTES >= stateBuffer.capacity())
                return 0;
            return IMatrixAccess.super.safe_get(row, column);
        }

        @Override
        public void safe_set(int row, int column, double value) {
            if(stateBuffer == null)
                return;
            IMatrixAccess.super.safe_set(row, column, value);
        }

        @Override
        public int numRows() {
            return size;
        }

        @Override
        public int numCols() {
            return 1;
        }
    }

    private native long allocateNativeObject(Buffer rhsOpBuffer, Buffer jOpBuffer, int cmdCount, Buffer auxBuffer);
    private static native void setStateSize(long ptr, int size);
    private static native void deallocateNativeObject(long ptr);
    private static native void zeroRHS(long ptr);
    private static native void zeroState(long ptr);
    private static native void zeroJacobian(long ptr);
    private static native void finishJacobianWrite(long ptr, int cmdCount);
    private static native void processJacobianBuffer(long ptr, int cmdCount);
    private static native void processRHSBuffer(long ptr);
    private static native void setPrecision(long ptr, double absoluteCriterion, double relativeCriterion, double minimumPrecision, double searchAlpha);
    private static native void setHookPresence(long ptr, boolean iterHooks, boolean residualHooks);
    private native ByteBuffer singleTick(long ptr, int maxIters, int jacobianCmdCount);
    private static native int verifySupport();
}
