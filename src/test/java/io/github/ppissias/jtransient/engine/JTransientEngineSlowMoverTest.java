package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.core.SlowMoverAnalysis;
import io.github.ppissias.jtransient.core.SlowMoverAnalyzer;
import io.github.ppissias.jtransient.core.SlowMoverCandidateResult;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for slow-mover filtering.
 * Candidates are filtered by elongation, median-stack overlap, and optional footprint residual support.
 */
public class JTransientEngineSlowMoverTest {

    /**
     * Verifies the slow-mover branch honors the configured overlap band and keeps only
     * candidates whose median-stack support lands between the minimum and maximum limits.
     */
    @Test
    public void filterSlowMoverCandidatesRequiresMedianStackSupportWithinConfiguredBand() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.slowMoverMedianSupportOverlapFraction = 0.10;
        config.slowMoverMedianSupportMaxOverlapFraction = 0.65;
        PipelineTelemetry.SlowMoverTelemetry telemetry = new PipelineTelemetry.SlowMoverTelemetry();
        short[][] slowMoverImage = createBlankEncodedImage(80, 80);

        List<SourceExtractor.DetectedObject> rawSlowMovers = new ArrayList<>();
        addBaselineSlowMoverCandidates(rawSlowMovers, slowMoverImage);

        SourceExtractor.DetectedObject lowMedianSupportReject = createLinearCandidate(10, 48, 10, 4.6, (short) 120, slowMoverImage);
        SourceExtractor.DetectedObject medianSupportedKeep = createLinearCandidate(10, 56, 10, 4.9, (short) 120, slowMoverImage);
        SourceExtractor.DetectedObject highMedianSupportReject = createLinearCandidate(10, 64, 10, 5.1, (short) 120, slowMoverImage);
        rawSlowMovers.add(lowMedianSupportReject);
        rawSlowMovers.add(medianSupportedKeep);
        rawSlowMovers.add(highMedianSupportReject);

        boolean[][] medianMask = new boolean[80][80];
        markFirstPixels(medianMask, lowMedianSupportReject, 0);
        markFirstPixels(medianMask, medianSupportedKeep, 5);
        markFirstPixels(medianMask, highMedianSupportReject, 8);

        List<SlowMoverCandidateResult> filtered = filterSlowMoverCandidates(
                rawSlowMovers,
                slowMoverImage,
                medianMask,
                config,
                telemetry
        );

        assertEquals(1, filtered.size());
        assertSame(medianSupportedKeep, filtered.get(0).object);
        assertEquals(11, telemetry.rawCandidatesExtracted);
        assertEquals(3, telemetry.candidatesAboveElongationThreshold);
        assertEquals(3, telemetry.candidatesEvaluatedAgainstMasks);
        assertEquals(1, telemetry.rejectedLowMedianSupport);
        assertEquals(1, telemetry.rejectedHighMedianSupport);
        assertEquals(1, telemetry.candidatesDetected);
        assertEquals(0.10, telemetry.medianSupportOverlapThreshold, 0.0001);
        assertEquals(0.65, telemetry.medianSupportMaxOverlapThreshold, 0.0001);
        assertEquals((0.0 + 0.5 + 0.8) / 3.0, telemetry.avgMedianSupportOverlap, 0.0001);
        assertEquals(List.of(0.5), telemetry.candidateMedianSupportOverlaps);
    }

    /**
     * Verifies the slow-mover branch rejects candidates whose own detected footprint is already almost entirely
     * explained by the ordinary median stack.
     */
    @Test
    public void filterSlowMoverCandidatesRejectsCandidatesWithLowResidualFootprintFlux() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.slowMoverMedianSupportOverlapFraction = 0.0;
        config.slowMoverMedianSupportMaxOverlapFraction = 1.0;
        config.slowMoverResidualFootprintMinFluxFraction = 0.5;
        PipelineTelemetry.SlowMoverTelemetry telemetry = new PipelineTelemetry.SlowMoverTelemetry();
        short[][] slowMoverImage = createBlankEncodedImage(80, 80);
        short[][] medianImage = createBlankEncodedImage(80, 80);

        List<SourceExtractor.DetectedObject> rawSlowMovers = new ArrayList<>();
        addBaselineSlowMoverCandidates(rawSlowMovers, slowMoverImage);

        SourceExtractor.DetectedObject mostlyExplainedReject = createLinearCandidate(10, 48, 8, 4.8, (short) 120, slowMoverImage);
        SourceExtractor.DetectedObject genuinelyResidualKeep = createLinearCandidate(10, 56, 8, 4.9, (short) 120, slowMoverImage);
        rawSlowMovers.add(mostlyExplainedReject);
        rawSlowMovers.add(genuinelyResidualKeep);

        copyPixelValues(medianImage, mostlyExplainedReject, 0, 7);
        copyPixelValues(medianImage, genuinelyResidualKeep, 0, 4);

        List<SlowMoverCandidateResult> filtered = filterSlowMoverCandidates(
                rawSlowMovers,
                slowMoverImage,
                medianImage,
                new boolean[80][80],
                config,
                telemetry
        );

        assertEquals(1, filtered.size());
        assertSame(genuinelyResidualKeep, filtered.get(0).object);
        assertEquals(2, telemetry.candidatesAboveElongationThreshold);
        assertEquals(2, telemetry.candidatesEvaluatedAgainstMasks);
        assertEquals(1, telemetry.rejectedLowResidualFootprintSupport);
        assertEquals(0, telemetry.rejectedLowMedianSupport);
        assertEquals(0, telemetry.rejectedHighMedianSupport);
        assertEquals(1, telemetry.candidatesDetected);
        assertEquals(0.5, telemetry.residualFootprintMinFluxFractionThreshold, 0.0001);
        assertEquals(0.5, telemetry.avgResidualFootprintFluxFraction, 0.0001);
    }

    /**
     * Verifies the residual-footprint veto can be disabled independently when diagnosing edge cases.
     */
    @Test
    public void filterSlowMoverCandidatesCanDisableResidualFootprintFilter() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.enableSlowMoverResidualFootprintFiltering = false;
        config.slowMoverMedianSupportOverlapFraction = 0.0;
        config.slowMoverMedianSupportMaxOverlapFraction = 1.0;
        PipelineTelemetry.SlowMoverTelemetry telemetry = new PipelineTelemetry.SlowMoverTelemetry();
        short[][] slowMoverImage = createBlankEncodedImage(80, 80);
        short[][] medianImage = createBlankEncodedImage(80, 80);

        List<SourceExtractor.DetectedObject> rawSlowMovers = new ArrayList<>();
        addBaselineSlowMoverCandidates(rawSlowMovers, slowMoverImage);

        SourceExtractor.DetectedObject fullyExplainedKeep = createLinearCandidate(10, 48, 8, 4.8, (short) 120, slowMoverImage);
        rawSlowMovers.add(fullyExplainedKeep);
        copyPixelValues(medianImage, fullyExplainedKeep, 0, 8);

        List<SlowMoverCandidateResult> filtered = filterSlowMoverCandidates(
                rawSlowMovers,
                slowMoverImage,
                medianImage,
                new boolean[80][80],
                config,
                telemetry
        );

        assertEquals(1, filtered.size());
        assertSame(fullyExplainedKeep, filtered.get(0).object);
        assertEquals(0, telemetry.rejectedLowResidualFootprintSupport);
        assertEquals(0.0, telemetry.residualFootprintMinFluxFractionThreshold, 0.0001);
        assertEquals(0.0, telemetry.avgResidualFootprintFluxFraction, 0.0001);
    }

    /**
     * Verifies the legacy slow-mover shape vetoes are no longer applied.
     */
    @Test
    public void filterSlowMoverCandidatesDoesNotApplyLegacyShapeVetoes() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.slowMoverMedianSupportOverlapFraction = 0.0;
        config.slowMoverMedianSupportMaxOverlapFraction = 1.0;
        config.slowMoverResidualFootprintMinFluxFraction = 0.0;
        PipelineTelemetry.SlowMoverTelemetry telemetry = new PipelineTelemetry.SlowMoverTelemetry();
        short[][] slowMoverImage = createBlankEncodedImage(80, 80);

        List<SourceExtractor.DetectedObject> rawSlowMovers = new ArrayList<>();
        addBaselineSlowMoverCandidates(rawSlowMovers, slowMoverImage);

        SourceExtractor.DetectedObject hookedKeep = createHookedCandidate(10, 48, (short) 120, slowMoverImage);
        SourceExtractor.DetectedObject straightKeep = createLinearCandidate(10, 56, 8, 4.8, (short) 120, slowMoverImage);
        rawSlowMovers.add(hookedKeep);
        rawSlowMovers.add(straightKeep);

        boolean[][] medianMask = new boolean[80][80];
        markFirstPixels(medianMask, hookedKeep, hookedKeep.rawPixels.size());
        markFirstPixels(medianMask, straightKeep, straightKeep.rawPixels.size());

        List<SlowMoverCandidateResult> filtered = filterSlowMoverCandidates(
                rawSlowMovers,
                slowMoverImage,
                medianMask,
                config,
                telemetry
        );

        assertEquals(2, filtered.size());
        assertSame(hookedKeep, filtered.get(0).object);
        assertSame(straightKeep, filtered.get(1).object);
        assertEquals(2, telemetry.candidatesEvaluatedAgainstMasks);
        assertEquals(List.of(1.0, 1.0), telemetry.candidateMedianSupportOverlaps);
    }

    /**
     * Verifies accepted slow movers export their overlap and residual-footprint diagnostics together.
     */
    @Test
    public void filterSlowMoverCandidatesExportsAcceptedCandidateDiagnostics() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.slowMoverMedianSupportOverlapFraction = 0.0;
        config.slowMoverMedianSupportMaxOverlapFraction = 1.0;
        config.slowMoverResidualFootprintMinFluxFraction = 0.5;
        PipelineTelemetry.SlowMoverTelemetry telemetry = new PipelineTelemetry.SlowMoverTelemetry();
        short[][] slowMoverImage = createBlankEncodedImage(80, 80);
        short[][] medianImage = createBlankEncodedImage(80, 80);

        List<SourceExtractor.DetectedObject> rawSlowMovers = new ArrayList<>();
        addBaselineSlowMoverCandidates(rawSlowMovers, slowMoverImage);

        SourceExtractor.DetectedObject accepted = createLinearCandidate(10, 48, 8, 4.9, (short) 120, slowMoverImage);
        rawSlowMovers.add(accepted);

        boolean[][] medianMask = new boolean[80][80];
        markFirstPixels(medianMask, accepted, 4);
        copyPixelValues(medianImage, accepted, 0, 4);

        List<SlowMoverCandidateResult> filtered = filterSlowMoverCandidates(
                rawSlowMovers,
                slowMoverImage,
                medianImage,
                medianMask,
                config,
                telemetry
        );

        assertEquals(1, filtered.size());
        SlowMoverCandidateResult result = filtered.get(0);
        assertSame(accepted, result.object);
        assertEquals(0.5, result.diagnostics.medianSupportOverlap, 0.0001);
        assertEquals(0.5, result.diagnostics.residualFootprintFluxFraction, 0.0001);
        assertEquals(480.0, result.diagnostics.residualFootprintFlux, 0.0001);
        assertEquals(960.0, result.diagnostics.slowMoverFootprintFlux, 0.0001);
        assertEquals(480.0, result.diagnostics.medianFootprintFlux, 0.0001);
        assertEquals(8, result.diagnostics.footprintPixelCount);
        assertTrue(result.diagnostics.residualFootprintFilteringEnabled);
    }

    /**
     * Verifies residual-footprint diagnostics are still exported even when the veto is disabled.
     */
    @Test
    public void filterSlowMoverCandidatesExportsResidualFootprintDiagnosticsWhenFilterDisabled() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.enableSlowMoverResidualFootprintFiltering = false;
        config.slowMoverMedianSupportOverlapFraction = 0.0;
        config.slowMoverMedianSupportMaxOverlapFraction = 1.0;
        PipelineTelemetry.SlowMoverTelemetry telemetry = new PipelineTelemetry.SlowMoverTelemetry();
        short[][] slowMoverImage = createBlankEncodedImage(80, 80);
        short[][] medianImage = createBlankEncodedImage(80, 80);

        List<SourceExtractor.DetectedObject> rawSlowMovers = new ArrayList<>();
        addBaselineSlowMoverCandidates(rawSlowMovers, slowMoverImage);

        SourceExtractor.DetectedObject accepted = createLinearCandidate(10, 48, 8, 4.9, (short) 120, slowMoverImage);
        rawSlowMovers.add(accepted);
        copyPixelValues(medianImage, accepted, 0, 8);

        List<SlowMoverCandidateResult> filtered = filterSlowMoverCandidates(
                rawSlowMovers,
                slowMoverImage,
                medianImage,
                new boolean[80][80],
                config,
                telemetry
        );

        assertEquals(1, filtered.size());
        SlowMoverCandidateResult result = filtered.get(0);
        assertSame(accepted, result.object);
        assertEquals(0.0, result.diagnostics.residualFootprintFluxFraction, 0.0001);
        assertEquals(0.0, result.diagnostics.residualFootprintFlux, 0.0001);
        assertEquals(960.0, result.diagnostics.slowMoverFootprintFlux, 0.0001);
        assertEquals(960.0, result.diagnostics.medianFootprintFlux, 0.0001);
        assertEquals(8, result.diagnostics.footprintPixelCount);
        assertFalse(result.diagnostics.residualFootprintFilteringEnabled);
    }

    /**
     * Verifies the exported residual-footprint fraction matches the exported flux totals.
     */
    @Test
    public void filterSlowMoverCandidatesExportsResidualFootprintFluxConsistentWithFraction() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.slowMoverMedianSupportOverlapFraction = 0.0;
        config.slowMoverMedianSupportMaxOverlapFraction = 1.0;
        config.slowMoverResidualFootprintMinFluxFraction = 0.0;
        PipelineTelemetry.SlowMoverTelemetry telemetry = new PipelineTelemetry.SlowMoverTelemetry();
        short[][] slowMoverImage = createBlankEncodedImage(80, 80);
        short[][] medianImage = createBlankEncodedImage(80, 80);

        List<SourceExtractor.DetectedObject> rawSlowMovers = new ArrayList<>();
        addBaselineSlowMoverCandidates(rawSlowMovers, slowMoverImage);

        SourceExtractor.DetectedObject accepted = createLinearCandidate(10, 48, 8, 4.9, (short) 120, slowMoverImage);
        rawSlowMovers.add(accepted);
        copyPixelValues(medianImage, accepted, 0, 2);

        List<SlowMoverCandidateResult> filtered = filterSlowMoverCandidates(
                rawSlowMovers,
                slowMoverImage,
                medianImage,
                new boolean[80][80],
                config,
                telemetry
        );

        assertEquals(1, filtered.size());
        SlowMoverCandidateResult result = filtered.get(0);
        assertEquals(720.0, result.diagnostics.residualFootprintFlux, 0.0001);
        assertEquals(960.0, result.diagnostics.slowMoverFootprintFlux, 0.0001);
        assertEquals(0.75, result.diagnostics.residualFootprintFluxFraction, 0.0001);
    }

    /**
     * Verifies accepted candidates keep the correct diagnostics instead of drifting into a parallel-ordering bug.
     */
    @Test
    public void filterSlowMoverCandidatesKeepsDiagnosticsAttachedToCorrectAcceptedCandidate() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.slowMoverMedianSupportOverlapFraction = 0.0;
        config.slowMoverMedianSupportMaxOverlapFraction = 1.0;
        config.slowMoverResidualFootprintMinFluxFraction = 0.0;
        PipelineTelemetry.SlowMoverTelemetry telemetry = new PipelineTelemetry.SlowMoverTelemetry();
        short[][] slowMoverImage = createBlankEncodedImage(80, 80);
        short[][] medianImage = createBlankEncodedImage(80, 80);

        List<SourceExtractor.DetectedObject> rawSlowMovers = new ArrayList<>();
        addBaselineSlowMoverCandidates(rawSlowMovers, slowMoverImage);

        SourceExtractor.DetectedObject firstAccepted = createLinearCandidate(10, 48, 8, 4.9, (short) 120, slowMoverImage);
        SourceExtractor.DetectedObject secondAccepted = createLinearCandidate(10, 56, 8, 5.0, (short) 120, slowMoverImage);
        rawSlowMovers.add(firstAccepted);
        rawSlowMovers.add(secondAccepted);

        boolean[][] medianMask = new boolean[80][80];
        markFirstPixels(medianMask, firstAccepted, 2);
        markFirstPixels(medianMask, secondAccepted, 6);
        copyPixelValues(medianImage, firstAccepted, 0, 2);

        List<SlowMoverCandidateResult> filtered = filterSlowMoverCandidates(
                rawSlowMovers,
                slowMoverImage,
                medianImage,
                medianMask,
                config,
                telemetry
        );

        assertEquals(2, filtered.size());
        assertSame(firstAccepted, filtered.get(0).object);
        assertEquals(0.25, filtered.get(0).diagnostics.medianSupportOverlap, 0.0001);
        assertEquals(0.75, filtered.get(0).diagnostics.residualFootprintFluxFraction, 0.0001);
        assertSame(secondAccepted, filtered.get(1).object);
        assertEquals(0.75, filtered.get(1).diagnostics.medianSupportOverlap, 0.0001);
        assertEquals(1.0, filtered.get(1).diagnostics.residualFootprintFluxFraction, 0.0001);
    }

    /**
     * Verifies the slow-mover analysis uses a stage-local config when applying extraction overrides.
     */
    @Test
    public void analyzeDoesNotMutateCallerConfigDuringSlowMoverExtraction() {
        DetectionConfig config = new DetectionConfig();
        config.growSigmaMultiplier = 6.5;
        config.masterSlowMoverGrowSigmaMultiplier = 2.5;
        config.masterSlowMoverSigmaMultiplier = 2.0;
        config.masterSlowMoverMinPixels = 3;

        List<ImageFrame> frames = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            short[][] image = createBlankEncodedImage(16, 16);
            image[8][8] = PixelEncoding.fromShiftedPositiveInt(1000);
            image[8][9] = PixelEncoding.fromShiftedPositiveInt(900);
            image[9][8] = PixelEncoding.fromShiftedPositiveInt(900);
            frames.add(new ImageFrame(i, "frame_" + i + ".fit", image, -1L, -1L));
        }

        short[][] masterStack = createBlankEncodedImage(16, 16);
        masterStack[8][8] = PixelEncoding.fromShiftedPositiveInt(1000);
        masterStack[8][9] = PixelEncoding.fromShiftedPositiveInt(900);
        masterStack[9][8] = PixelEncoding.fromShiftedPositiveInt(900);

        SlowMoverAnalysis analysis = SlowMoverAnalyzer.analyze(frames, masterStack, config);

        assertEquals(6.5, config.growSigmaMultiplier, 0.0);
        assertNotNull(analysis.telemetry);
    }

    @SuppressWarnings("unchecked")
    private static List<SlowMoverCandidateResult> filterSlowMoverCandidates(
            List<SourceExtractor.DetectedObject> rawSlowMovers,
            short[][] slowMoverImage,
            boolean[][] medianMask,
            DetectionConfig config,
            PipelineTelemetry.SlowMoverTelemetry telemetry
    ) throws Exception {
        return filterSlowMoverCandidates(
                rawSlowMovers,
                slowMoverImage,
                createBlankEncodedImage(slowMoverImage[0].length, slowMoverImage.length),
                medianMask,
                config,
                telemetry
        );
    }

    @SuppressWarnings("unchecked")
    private static List<SlowMoverCandidateResult> filterSlowMoverCandidates(
            List<SourceExtractor.DetectedObject> rawSlowMovers,
            short[][] slowMoverImage,
            short[][] medianImage,
            boolean[][] medianMask,
            DetectionConfig config,
            PipelineTelemetry.SlowMoverTelemetry telemetry
    ) throws Exception {
        Method method = SlowMoverAnalyzer.class.getDeclaredMethod(
                "filterSlowMoverCandidates",
                List.class,
                short[][].class,
                short[][].class,
                boolean[][].class,
                DetectionConfig.class,
                PipelineTelemetry.SlowMoverTelemetry.class
        );
        method.setAccessible(true);
        return (List<SlowMoverCandidateResult>) method.invoke(
                null,
                rawSlowMovers,
                slowMoverImage,
                medianImage,
                medianMask,
                config,
                telemetry
        );
    }

    private static void addBaselineSlowMoverCandidates(List<SourceExtractor.DetectedObject> rawSlowMovers,
                                                       short[][] slowMoverImage) {
        double[] baselineElongations = {1.0, 1.4, 1.6, 1.8, 2.0, 2.0, 2.2, 2.4};
        for (int i = 0; i < baselineElongations.length; i++) {
            rawSlowMovers.add(createLinearCandidate(10, 8 + (i * 4), 10, baselineElongations[i], (short) 100, slowMoverImage));
        }
    }

    private static SourceExtractor.DetectedObject createLinearCandidate(
            int xStart,
            int y,
            int length,
            double elongation,
            short signal,
            short[][] image
    ) {
        SourceExtractor.DetectedObject obj = new SourceExtractor.DetectedObject(
                xStart + ((length - 1) / 2.0),
                y,
                signal * length,
                length
        );
        obj.elongation = elongation;
        obj.angle = 0.0;
        obj.pixelArea = length;
        obj.rawPixels = new ArrayList<>(length);

        for (int dx = 0; dx < length; dx++) {
            int x = xStart + dx;
            obj.rawPixels.add(new SourceExtractor.Pixel(x, y, signal));
            image[y][x] = PixelEncoding.fromShiftedPositiveInt(signal);
        }
        return obj;
    }

    private static SourceExtractor.DetectedObject createHookedCandidate(
            int xStart,
            int yStart,
            short signal,
            short[][] image
    ) {
        int[][] points = {
                {xStart, yStart},
                {xStart + 1, yStart},
                {xStart + 2, yStart},
                {xStart + 3, yStart},
                {xStart + 4, yStart},
                {xStart + 5, yStart + 1},
                {xStart + 6, yStart + 1},
                {xStart + 5, yStart + 2},
                {xStart + 6, yStart + 2}
        };

        SourceExtractor.DetectedObject obj = new SourceExtractor.DetectedObject(
                xStart + 3.0,
                yStart + 1.0,
                signal * points.length,
                points.length
        );
        obj.elongation = 4.7;
        obj.angle = 0.0;
        obj.pixelArea = points.length;
        obj.rawPixels = new ArrayList<>(points.length);

        for (int[] point : points) {
            obj.rawPixels.add(new SourceExtractor.Pixel(point[0], point[1], signal));
            image[point[1]][point[0]] = PixelEncoding.fromShiftedPositiveInt(signal);
        }
        return obj;
    }

    private static void markFirstPixels(boolean[][] mask, SourceExtractor.DetectedObject obj, int count) {
        for (int i = 0; i < count; i++) {
            SourceExtractor.Pixel p = obj.rawPixels.get(i);
            mask[p.y][p.x] = true;
        }
    }

    private static void copyPixelValues(short[][] image, SourceExtractor.DetectedObject obj, int startInclusive, int endExclusive) {
        for (int i = startInclusive; i < endExclusive; i++) {
            SourceExtractor.Pixel p = obj.rawPixels.get(i);
            image[p.y][p.x] = PixelEncoding.fromShiftedPositiveInt(p.value);
        }
    }

    private static short[][] createBlankEncodedImage(int width, int height) {
        short[][] image = new short[height][width];
        short encodedZero = PixelEncoding.fromShiftedPositiveInt(0);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image[y][x] = encodedZero;
            }
        }
        return image;
    }
}
