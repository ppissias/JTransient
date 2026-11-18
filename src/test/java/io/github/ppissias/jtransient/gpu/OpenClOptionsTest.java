package io.github.ppissias.jtransient.gpu;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Verifies OpenCL build options forbid relaxed floating-point flags and enforce FP_CONTRACT OFF in kernel source. */
public class OpenClOptionsTest {

    private static final List<String> FORBIDDEN = List.of(
            "-cl-fast-relaxed-math",
            "-cl-mad-enable",
            "-cl-unsafe-math-optimizations",
            "-cl-finite-math-only",
            "-cl-no-signed-zeros",
            "-cl-denorms-are-zero");

    @Test
    public void buildOptionsAreStrictOpenCl12AndContainNoRelaxedMathFlags() {
        for (String opts : List.of(
                OpenClContextManager.BLOCKED_OPTIONS_SHORT,
                OpenClContextManager.SIMPLE_OPTIONS_SHORT,
                OpenClContextManager.blockedBuildOptions(false),
                OpenClContextManager.simpleBuildOptions(false))) {
            assertTrue(opts.contains("-cl-std=CL1.2"));
            for (String flag : FORBIDDEN) {
                assertFalse("Forbidden OpenCL flag " + flag + " in " + opts, opts.contains(flag));
            }
        }
    }

    @Test
    public void kernelSourceDisablesFpContraction() {
        assertTrue(OpenClContextManager.SOURCE.contains("#pragma OPENCL FP_CONTRACT OFF"));
    }
}
