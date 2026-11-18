package io.github.ppissias.jtransient.gpu;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Verifies OpenCL device filtering and ranking rules using synthetic device descriptors. */
public class OpenClDeviceSelectorTest {

    @Test
    public void ranksDiscreteGpuAheadOfIntegratedGpuAndIgnoresCpuUnlessAllowed() {
        OpenClDevice cpu = fake("PoCL CPU", false, true, true, 32L << 30, true, true, true, "cpu-1");
        OpenClDevice igpu = fake("AMD Radeon(TM) Graphics", true, false, true, 4L << 30, true, true, true, "igpu-1");
        OpenClDevice dgpuSmall = fake("NVIDIA RTX 3050", true, false, false, 4L << 30, true, true, true, "dgpu-4");
        OpenClDevice dgpuBig = fake("NVIDIA RTX 5060", true, false, false, 8L << 30, true, true, true, "dgpu-8");
        OpenClDevice nonIeee = fake("Broken GPU", true, false, false, 16L << 30, true, true, false, "bad-fp");
        OpenClDevice unavailable = fake("Offline GPU", true, false, false, 16L << 30, false, true, true, "off");

        List<OpenClDevice> rankedNoCpu = OpenClDeviceSelector.rank(
                List.of(cpu, igpu, dgpuSmall, dgpuBig, nonIeee, unavailable), false);
        assertEquals(List.of(dgpuBig, dgpuSmall, igpu), rankedNoCpu);

        List<OpenClDevice> rankedWithCpu = OpenClDeviceSelector.rank(
                List.of(cpu, igpu, dgpuBig), true);
        assertEquals(List.of(dgpuBig, igpu, cpu), rankedWithCpu);
    }

    @Test
    public void savedDeviceWinsWhenPresentAndFallsBackWithNoteWhenMissing() {
        OpenClDevice igpu = fake("Integrated", true, false, true, 4L << 30, true, true, true, "igpu-1");
        OpenClDevice dgpu = fake("Discrete", true, false, false, 8L << 30, true, true, true, "dgpu-8");
        List<OpenClDevice> ranked = List.of(dgpu, igpu);

        var chosenSaved = OpenClDeviceSelector.choose(ranked, "igpu-1");
        assertEquals(igpu, chosenSaved.device());
        assertNull(chosenSaved.note());

        var chosenMissing = OpenClDeviceSelector.choose(ranked, "missing-id");
        assertEquals(dgpu, chosenMissing.device());
        assertTrue(chosenMissing.note().contains("Discrete"));
    }

    private static OpenClDevice fake(String name, boolean isGpu, boolean isCpu, boolean unified, long globalMem,
                                     boolean available, boolean compiler, boolean ieee, String id) {
        return new OpenClDevice(0, 0, "TestPlatform", "OpenCL 1.2", "OpenCL 1.2", name, "Vendor", 1, "1.0",
                isGpu, isCpu, unified, globalMem, globalMem / 4, 32768, 256, 0L, true, ieee, available, compiler, id);
    }
}
