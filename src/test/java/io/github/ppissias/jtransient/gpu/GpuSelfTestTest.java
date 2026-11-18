package io.github.ppissias.jtransient.gpu;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;

import static org.junit.Assert.assertTrue;

/** Exercises {@link GpuSelfTest} and {@link OpenClLaunchSizer} on available OpenCL hardware. */
public class GpuSelfTestTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void selfTestPassesOnEveryAvailableOpenClDevice() throws Exception {
        OpenClDeviceSelector.Probe probe = OpenClDeviceSelector.probe();
        Assume.assumeTrue("No OpenCL driver: " + probe.errorReason(), probe.errorReason() == null);
        boolean allowCpu = Boolean.getBoolean(OpenClDeviceSelector.ALLOW_CPU_PROPERTY);
        List<OpenClDevice> devices = OpenClDeviceSelector.rank(probe.devices(), allowCpu);
        Assume.assumeFalse("No OpenCL device found", devices.isEmpty());

        for (OpenClDevice device : devices) {
            try (var diag = io.github.ppissias.jtransient.execution.DiagnosticContext.open(
                    new io.github.ppissias.jtransient.execution.DiagnosticOptions(true, System.out::println));
                 OpenClContextManager ctx = new OpenClContextManager(device, temp.newFolder().toPath());
                 OpenClCoarseSearch gpu = new OpenClCoarseSearch(ctx, false)) {
                System.out.println("Testing OpenCL device: " + device.name() + " | driver=" + device.driverVersion()
                        + " | unified=" + device.hostUnifiedMemory() + " | simpleOnly=" + device.simpleOnly()
                        + " | hasBlocked=" + ctx.kernels(true).hasBlocked());
                GpuSelfTest.run(gpu);
                double speedup = GpuBenchmark.measureSpeedup(gpu, 2);
                System.out.println("  -> PASSED! Benchmark speedup vs 2 CPU workers: " + speedup + "x");
                assertTrue("Measured speedup must be finite and positive: " + speedup,
                        Double.isFinite(speedup) && speedup > 0.0);
            }
        }
    }

    @Test
    public void launchSizerKeepsLaunchesBelowTargetCap() {
        OpenClLaunchSizer sizer = new OpenClLaunchSizer(1, 1024);
        assertTrue(sizer.unitsPerLaunch() == 1);
        assertTrue(OpenClLaunchSizer.TARGET_MS <= OpenClLaunchSizer.MAX_TARGET_MS);
        assertTrue(OpenClLaunchSizer.MAX_TARGET_MS <= 100.0);
    }
}
