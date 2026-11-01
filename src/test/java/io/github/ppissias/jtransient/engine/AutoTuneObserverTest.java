package io.github.ppissias.jtransient.engine;

import com.google.gson.GsonBuilder;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.execution.CancellationScope;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Live calibration is lazy, scientifically neutral, failure-isolated and cooperatively cancellable. */
public class AutoTuneObserverTest {
    /** Every measurement, candidate and profile choice remains identical with live observations enabled. */
    @Test public void snapshotsDoNotChangeCalibrationOrBorrowedPixels() {
        var frames = frames();
        int hash = java.util.Arrays.deepHashCode(frames.getFirst().pixelData);
        var baseline = calibrate(frames, null);
        var kinds = Collections.synchronizedSet(EnumSet.noneOf(AutoTuneSnapshot.Kind.class));
        AtomicInteger recovered = new AtomicInteger();
        AtomicInteger missed = new AtomicInteger();
        var observed = calibrate(frames, new AutoTuneObserver() {
            @Override public boolean wantsUpdate() { return true; }
            @Override public void update(AutoTuneSnapshot snapshot) {
                kinds.add(snapshot.kind());
                assertNotNull(snapshot.image());
                if (snapshot.kind() == AutoTuneSnapshot.Kind.RECOVERY) {
                    assertNotNull(snapshot.mask());
                    for (var marker : snapshot.markers()) {
                        if (marker.mark() == AutoTuneSnapshot.Mark.RECOVERED) recovered.incrementAndGet();
                        if (marker.mark() == AutoTuneSnapshot.Mark.MISSED) missed.incrementAndGet();
                    }
                }
            }
        });
        assertTrue(observed.failureReason, observed.success);
        assertTrue(kinds.toString(), kinds.containsAll(EnumSet.allOf(AutoTuneSnapshot.Kind.class)));
        assertTrue(recovered.get() > 0);
        assertTrue(missed.get() > 0);
        assertCalibration(baseline, observed);
        assertEquals(hash, java.util.Arrays.deepHashCode(frames.getFirst().pixelData));
    }

    /** Declined pictures build no snapshots; failures in each callback leave the full result intact. */
    @Test public void hiddenAndFailingObserversLeaveResultsIntact() {
        var frames = frames();
        var baseline = calibrate(frames, null);
        AtomicInteger updates = new AtomicInteger();
        assertCalibration(baseline, calibrate(frames, new AutoTuneObserver() {
            @Override public boolean wantsUpdate() { return false; }
            @Override public void update(AutoTuneSnapshot snapshot) { updates.incrementAndGet(); }
        }));
        assertEquals(0, updates.get());
        for (boolean failReservation : new boolean[]{true, false}) {
            assertCalibration(baseline, calibrate(frames, new AutoTuneObserver() {
                @Override public void phaseStarted(AutoTuneSnapshot.Kind kind) { throw new IllegalStateException("phase"); }
                @Override public boolean wantsUpdate() {
                    if (failReservation) throw new IllegalStateException("reservation");
                    return true;
                }
                @Override public void update(AutoTuneSnapshot snapshot) { throw new IllegalStateException("render"); }
            }));
        }
    }

    /** A cancellation requested while viewing a picture stops the next scientific loop, with no late callbacks. */
    @Test public void cancellationStillStopsCalibration() {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger updates = new AtomicInteger();
        try (var scope = CancellationScope.open(cancelled::get)) {
            assertThrows(CancellationException.class, () -> calibrate(frames(), new AutoTuneObserver() {
                @Override public boolean wantsUpdate() { return true; }
                @Override public void update(AutoTuneSnapshot snapshot) { updates.incrementAndGet(); cancelled.set(true); }
            }));
        }
        assertEquals(1, updates.get());
    }

    /** Dense noise annotations stay bounded and cover the crop rather than only its first extraction rows. */
    @Test public void denseNoiseMarkersSampleTheWholeCrop() {
        List<ImageFrame> frames = new ArrayList<>();
        Random random = new Random(77);
        for (int i = 0; i < 5; i++) {
            short[][] pixels = new short[600][600];
            for (short[] row : pixels) for (int x = 0; x < row.length; x++)
                row[x] = PixelEncoding.fromShiftedPositiveInt((int) Math.round(1000 + random.nextGaussian() * 20));
            frames.add(new ImageFrame(i, "noise-" + i, pixels, i * 1000L, 1000));
        }
        var builder = CalibratedAutoTuner.Options.builder();
        builder.sigmasToTest = new double[]{2.0};
        builder.growDeltasToTest = new double[]{0.75};
        builder.minPixelAreaFactors = new double[]{0.3};
        builder.masterSigmasToTest = new double[]{5};
        builder.masterGrowOffsetsToTest = new double[]{1};
        builder.masterMinPixelAreaFactors = new double[]{0.5};
        builder.maskOverlapsToTest = new double[]{0.9};
        builder.falsePositiveBudgetPerMpixFrame = new double[]{1e6, 1e6, 1e6, 1e6};
        builder.cropCount = builder.maxCropsPerSide = 1;
        builder.preferredCropSize = 512;
        builder.minCropBorderMargin = builder.cropPaddingClearance = 20;
        AtomicInteger samples = new AtomicInteger();
        var result = CalibratedAutoTuner.calibrate(frames, new DetectionConfig(), builder.build(), null, new AutoTuneObserver() {
            @Override public boolean wantsUpdate() { return true; }
            @Override public void update(AutoTuneSnapshot snapshot) {
                if (snapshot.kind() != AutoTuneSnapshot.Kind.NOISE) return;
                samples.incrementAndGet();
                assertTrue(snapshot.caption(), snapshot.caption().contains("sampled markers"));
                assertTrue(snapshot.markers().size() <= 200);
                assertTrue(snapshot.markers().stream().anyMatch(m -> m.y() < snapshot.image().length * 0.25));
                assertTrue(snapshot.markers().stream().anyMatch(m -> m.y() > snapshot.image().length * 0.75));
                assertTrue(snapshot.markers().stream().anyMatch(m -> m.x() > 64 && m.x() < 448 && m.y() > 64 && m.y() < 448));
            }
        });
        assertTrue(result.failureReason, result.success);
        assertTrue(samples.get() > 0);
    }

    /** Compares the entire scientific result, excluding only elapsed wall time. */
    private static void assertCalibration(CalibratedAutoTuner.Calibration expected, CalibratedAutoTuner.Calibration actual) {
        expected.elapsedSeconds = actual.elapsedSeconds = 0;
        var gson = new GsonBuilder().serializeSpecialFloatingPointValues().create();
        assertEquals(gson.toJson(expected), gson.toJson(actual));
    }

    /** Measures a bounded real search grid, retaining normal worker and measurement behavior. */
    private static CalibratedAutoTuner.Calibration calibrate(List<ImageFrame> frames, AutoTuneObserver observer) {
        var builder = CalibratedAutoTuner.Options.builder();
        builder.sigmasToTest = new double[]{3.0, 4.0};
        builder.growDeltasToTest = new double[]{1.25};
        builder.minPixelAreaFactors = new double[]{0.5};
        builder.masterSigmasToTest = new double[]{3};
        builder.masterGrowOffsetsToTest = new double[]{0.5};
        builder.masterMinPixelAreaFactors = new double[]{0.5};
        builder.maskOverlapsToTest = new double[]{0.7};
        builder.falsePositiveBudgetPerMpixFrame = new double[]{100, 100, 100, 100};
        builder.cropCount = builder.maxCropsPerSide = 1;
        builder.preferredCropSize = 112;
        builder.minCropSize = 64;
        builder.minCropBorderMargin = builder.cropPaddingClearance = 20;
        return CalibratedAutoTuner.calibrate(frames, new DetectionConfig(), builder.build(), null, observer);
    }

    /** Deterministic sky and stars in five registered unsigned-16-bit images. */
    private static List<ImageFrame> frames() {
        List<ImageFrame> frames = new ArrayList<>();
        Random random = new Random(42);
        for (int i = 0; i < 5; i++) {
            short[][] pixels = new short[160][160];
            for (int y = 0; y < 160; y++) for (int x = 0; x < 160; x++) {
                double value = 1000 + random.nextGaussian() * 6;
                for (int sy = 30; sy < 150; sy += 40) for (int sx = 30; sx < 150; sx += 40)
                    value += 600 * Math.exp(-((x - sx) * (x - sx) + (y - sy) * (y - sy)) / (2 * 1.3 * 1.3));
                pixels[y][x] = PixelEncoding.fromShiftedPositiveInt((int) Math.round(value));
            }
            frames.add(new ImageFrame(i, "synthetic-" + i, pixels, i * 1000L, 1000));
        }
        return frames;
    }
}
