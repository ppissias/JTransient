package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.MasterMapGenerator;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.core.SlowMoverAnalysis;
import io.github.ppissias.jtransient.core.SlowMoverAnalyzer;
import io.github.ppissias.jtransient.core.SlowMoverCandidateDiagnostics;
import io.github.ppissias.jtransient.core.SourceExtractor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Synthetic-stack and engine integration coverage for morphology-based slow-mover candidates. */
public class JTransientEngineSlowMoverTest {
    /** A short swept source survives while a round stationary source fails the axis-ratio gate. */
    @Test
    public void movingSourceSurvivesWhileStationaryStarIsRejected() {
        DetectionConfig config = testConfig();
        List<ImageFrame> frames = createFrames(6.0, 1.4, 1.4, false, true);
        short[][] maximumStack = MasterMapGenerator.createMaximumMasterStack(frames);
        short[][] medianStack = MasterMapGenerator.createMedianMasterStack(frames);

        SlowMoverAnalysis analysis = SlowMoverAnalyzer.analyze(maximumStack, medianStack, config);

        assertSame(maximumStack, analysis.maximumStackData);
        assertSame(maximumStack, analysis.slowMoverStackData);
        assertSame(analysis.medianMask, analysis.medianVetoMask);
        assertEquals(1, analysis.candidates.size());
        assertEquals(1, analysis.telemetry.rejectedBelowMinAxisRatio);
        assertEquals(1, analysis.telemetry.candidatesDetected);
        assertEquals(0, analysis.telemetry.rejectedHighMedianSupport);
        assertEquals(60.0, analysis.candidates.get(0).object.x, 1.0);

        SlowMoverCandidateDiagnostics diagnostics = analysis.candidates.get(0).diagnostics;
        assertEquals(analysis.candidates.get(0).object.rawPixels.size(), diagnostics.pixelCount);
        assertEquals(analysis.candidates.get(0).object.elongation, diagnostics.momentElongation, 0.0);
        assertTrue(diagnostics.axisRatio >= config.slowMoverMinAxisRatio);
        assertTrue(diagnostics.axisRatio <= config.slowMoverMaxAxisRatio);
        assertEquals(diagnostics.axisRatio - 1.0, diagnostics.estimatedMotionDiameters, 1.0e-9);
        assertEquals(1.0 - diagnostics.medianMaskOverlapFraction,
                diagnostics.outsideMedianMaskFraction, 1.0e-9);
        assertEquals(config.slowMoverMedianSupportMaxOverlapFraction,
                diagnostics.medianSupportMaxOverlapThreshold, 0.0);
        assertEquals(analysis.telemetry.avgCandidateAxisRatio, diagnostics.axisRatio, 1.0e-9);
    }

    /** An elongated but persistent PSF fails because its maximum pixels match the median mask. */
    @Test
    public void stationaryElongatedSourceIsRejectedByMedianOverlap() {
        DetectionConfig config = testConfig();
        List<ImageFrame> frames = createFrames(0.0, 3.0, 1.3, false, false);
        short[][] maximumStack = MasterMapGenerator.createMaximumMasterStack(frames);
        short[][] medianStack = MasterMapGenerator.createMedianMasterStack(frames);

        SlowMoverAnalysis analysis = SlowMoverAnalyzer.analyze(maximumStack, medianStack, config);

        assertEquals(1, analysis.telemetry.rawCandidatesExtracted);
        assertEquals(1, analysis.telemetry.evaluatedAgainstMedianMask);
        assertEquals(1, analysis.telemetry.rejectedHighMedianSupport);
        assertEquals(0, analysis.candidates.size());
        assertEquals(1.0, analysis.telemetry.candidateMedianMaskOverlaps.get(0), 0.0);
        assertMedianMaskMatchesExtractedFootprints(medianStack, analysis.medianMask, config);
    }

    /** A long swept footprint lies beyond the slow-mover axis-ratio window. */
    @Test
    public void fastMoverIsRejectedByMaximumAxisRatio() {
        DetectionConfig config = testConfig();
        List<ImageFrame> frames = createFrames(22.0, 1.4, 1.4, false, false);
        SlowMoverAnalysis analysis = SlowMoverAnalyzer.analyze(
                MasterMapGenerator.createMaximumMasterStack(frames),
                MasterMapGenerator.createMedianMasterStack(frames),
                config
        );

        assertEquals(0, analysis.candidates.size());
        assertEquals(1, analysis.telemetry.rejectedAboveMaxAxisRatio);
    }

    /** Zero minimum overlap admits one-frame artifacts; an optional floor can veto them. */
    @Test
    public void minimumOverlapCanVetoOneFrameElongatedTransient() {
        DetectionConfig config = testConfig();
        List<ImageFrame> frames = createFrames(0.0, 3.0, 1.3, true, false);
        short[][] maximumStack = MasterMapGenerator.createMaximumMasterStack(frames);
        short[][] medianStack = MasterMapGenerator.createMedianMasterStack(frames);

        SlowMoverAnalysis permissive = SlowMoverAnalyzer.analyze(maximumStack, medianStack, config);
        assertEquals(1, permissive.candidates.size());
        assertEquals(0.0, permissive.candidates.get(0).diagnostics.medianMaskOverlapFraction, 0.0);

        config.slowMoverMedianSupportOverlapFraction = 0.1;
        SlowMoverAnalysis supported = SlowMoverAnalyzer.analyze(maximumStack, medianStack, config);
        assertEquals(0, supported.candidates.size());
        assertEquals(1, supported.telemetry.rejectedLowMedianSupport);
    }

    @Test
    public void frameSupportMeasuresOneFrameArtifactAndOptionalFloorRejectsIt() {
        DetectionConfig config = testConfig();
        disableFrameEvidenceGates(config);
        List<ImageFrame> frames = createFrames(0.0, 3.0, 1.3, true, false);
        short[][] maximumStack = MasterMapGenerator.createMaximumMasterStack(frames);
        short[][] medianStack = MasterMapGenerator.createMedianMasterStack(frames);

        SlowMoverAnalysis measured = SlowMoverAnalyzer.analyze(maximumStack, medianStack, frames, config);

        assertEquals(1, measured.candidates.size());
        SlowMoverCandidateDiagnostics diagnostics = measured.candidates.get(0).diagnostics;
        assertTrue(diagnostics.frameSupportAvailable);
        assertEquals(9, diagnostics.usableFrameCount);
        assertEquals(1, diagnostics.supportedFrameCount);
        assertEquals(100.0 / 9.0, diagnostics.frameSupportPercentage, 1.0e-9);
        assertEquals(config.slowMoverMinFrameSupport, diagnostics.minFrameSupportThreshold, 0.0);
        assertEquals(config.slowMoverMaxStationaryLikelihood, diagnostics.maxStationaryLikelihoodThreshold, 0.0);
        assertEquals(diagnostics.frameSupportPercentage,
                measured.telemetry.candidateFrameSupportPercentages.get(0), 0.0);
        assertEquals(1, measured.telemetry.evaluatedAgainstFrames);

        config.slowMoverMinFrameSupport = 25.0;
        SlowMoverAnalysis filtered = SlowMoverAnalyzer.analyze(maximumStack, medianStack, frames, config);
        assertEquals(0, filtered.candidates.size());
        assertEquals(1, filtered.telemetry.rejectedLowFrameSupport);
    }

    @Test
    public void stationaryLikelihoodMeasuresFixedSourceAndOptionalCeilingRejectsIt() {
        DetectionConfig config = testConfig();
        disableFrameEvidenceGates(config);
        config.slowMoverMedianSupportMaxOverlapFraction = 1.0;
        List<ImageFrame> frames = createFrames(0.0, 3.0, 1.3, false, false);
        short[][] maximumStack = MasterMapGenerator.createMaximumMasterStack(frames);
        short[][] medianStack = MasterMapGenerator.createMedianMasterStack(frames);

        SlowMoverAnalysis measured = SlowMoverAnalyzer.analyze(maximumStack, medianStack, frames, config);

        assertEquals(1, measured.candidates.size());
        SlowMoverCandidateDiagnostics diagnostics = measured.candidates.get(0).diagnostics;
        assertEquals(100.0, diagnostics.frameSupportPercentage, 0.0);
        assertTrue(diagnostics.stationaryLikelihoodAvailable);
        assertEquals(100.0, diagnostics.stationaryLikelihoodPercentage, 0.0);
        assertEquals(diagnostics.stationaryLikelihoodPercentage,
                measured.telemetry.candidateStationaryLikelihoodPercentages.get(0), 0.0);

        config.slowMoverMaxStationaryLikelihood = 80.0;
        SlowMoverAnalysis filtered = SlowMoverAnalyzer.analyze(maximumStack, medianStack, frames, config);
        assertEquals(0, filtered.candidates.size());
        assertEquals(1, filtered.telemetry.rejectedHighStationaryLikelihood);
    }

    @Test
    public void stationarySourceRemainsRecognizableWithOneFrameExtension() {
        DetectionConfig config = testConfig();
        disableFrameEvidenceGates(config);
        config.slowMoverMinAxisRatio = 1.0;
        config.slowMoverMedianSupportMaxOverlapFraction = 1.0;
        List<ImageFrame> frames = new ArrayList<>();
        for (int frameIndex = 0; frameIndex < 9; frameIndex++) {
            short[][] image = createBackgroundImage();
            drawGaussian(image, 60.0, 48.0, 1.4, 1.4);
            if (frameIndex == 4) {
                drawGaussian(image, 63.0, 48.0, 1.4, 1.4);
            }
            frames.add(new ImageFrame(frameIndex, "frame_" + frameIndex + ".fit", image, -1L, -1L));
        }

        SlowMoverAnalysis analysis = SlowMoverAnalyzer.analyze(
                MasterMapGenerator.createMaximumMasterStack(frames),
                MasterMapGenerator.createMedianMasterStack(frames), frames, config
        );

        assertEquals(1, analysis.candidates.size());
        SlowMoverCandidateDiagnostics diagnostics = analysis.candidates.get(0).diagnostics;
        assertEquals(9, diagnostics.supportedFrameCount);
        assertTrue(diagnostics.stationaryLikelihoodPercentage >= 80.0);
    }

    @Test
    public void movingSourceHasFrameSupportWithoutAStationaryPosition() {
        DetectionConfig config = testConfig();
        List<ImageFrame> frames = createFrames(6.0, 1.4, 1.4, false, false);

        SlowMoverAnalysis analysis = SlowMoverAnalyzer.analyze(
                MasterMapGenerator.createMaximumMasterStack(frames),
                MasterMapGenerator.createMedianMasterStack(frames), frames, config
        );

        assertEquals(1, analysis.candidates.size());
        SlowMoverCandidateDiagnostics diagnostics = analysis.candidates.get(0).diagnostics;
        assertEquals(9, diagnostics.supportedFrameCount);
        assertEquals(100.0, diagnostics.frameSupportPercentage, 0.0);
        assertTrue(diagnostics.stationaryLikelihoodAvailable);
        assertTrue(diagnostics.stationaryLikelihoodPercentage < 100.0);
    }

    @Test
    public void stackOnlyCallLeavesFrameEvidenceUnavailableWithoutVetoing() {
        DetectionConfig config = testConfig();
        config.slowMoverMinFrameSupport = 100.0;
        config.slowMoverMaxStationaryLikelihood = 0.0;
        List<ImageFrame> frames = createFrames(6.0, 1.4, 1.4, false, false);

        SlowMoverAnalysis analysis = SlowMoverAnalyzer.analyze(
                MasterMapGenerator.createMaximumMasterStack(frames),
                MasterMapGenerator.createMedianMasterStack(frames), config
        );

        assertEquals(1, analysis.candidates.size());
        assertEquals(1, analysis.telemetry.frameEvidenceUnavailable);
        assertEquals(0, analysis.telemetry.rejectedLowFrameSupport);
        assertEquals(0, analysis.telemetry.rejectedHighStationaryLikelihood);
        assertTrue(!analysis.candidates.get(0).diagnostics.frameSupportAvailable);
        assertTrue(!analysis.candidates.get(0).diagnostics.stationaryLikelihoodAvailable);
    }

    /** The fill-factor threshold remains optional and is applied before median-mask overlap. */
    @Test
    public void optionalFillFactorGateCanRejectLooseFootprints() {
        DetectionConfig config = testConfig();
        List<ImageFrame> frames = createFrames(6.0, 1.4, 1.4, false, false);
        short[][] maximumStack = MasterMapGenerator.createMaximumMasterStack(frames);
        short[][] medianStack = MasterMapGenerator.createMedianMasterStack(frames);

        SlowMoverAnalysis permissive = SlowMoverAnalyzer.analyze(maximumStack, medianStack, config);
        assertEquals(1, permissive.candidates.size());
        double measuredFillFactor = permissive.candidates.get(0).diagnostics.fillFactor;

        config.slowMoverMinFillFactor = measuredFillFactor + 0.01;
        SlowMoverAnalysis filtered = SlowMoverAnalyzer.analyze(maximumStack, medianStack, config);
        assertEquals(0, filtered.candidates.size());
        assertEquals(1, filtered.telemetry.rejectedLowFillFactor);
    }

    /** A stage-local extraction config must not change later pipeline thresholds. */
    @Test
    public void extractionKeepsCallerConfigurationUnchanged() {
        DetectionConfig config = testConfig();
        config.growSigmaMultiplier = 6.5;
        config.masterSlowMoverGrowSigmaMultiplier = 2.5;
        List<ImageFrame> frames = createFrames(6.0, 1.4, 1.4, false, false);

        SlowMoverAnalysis analysis = SlowMoverAnalyzer.analyze(
                MasterMapGenerator.createMaximumMasterStack(frames),
                MasterMapGenerator.createMedianMasterStack(frames),
                config
        );

        assertEquals(6.5, config.growSigmaMultiplier, 0.0);
        assertNotNull(analysis.telemetry);
    }

    /** The pipeline detector and exported stack must share the same array instance. */
    @Test
    public void engineReusesExportedMaximumStackForSlowMoverAnalysis() throws Exception {
        JTransientEngine engine = new JTransientEngine();
        try {
            PipelineResult result = engine.runPipeline(
                    createFrames(6.0, 1.4, 1.4, false, true), testConfig(), null
            );

            assertSame(result.maximumStackData, result.slowMoverAnalysis.maximumStackData);
            assertSame(result.maximumStackData, result.slowMoverStackData);
            assertSame(result.slowMoverAnalysis.medianMask, result.slowMoverMedianVetoMask);
            assertEquals(result.slowMoverAnalysis.candidates.size(), result.slowMoverCandidates.size());
            assertEquals(result.slowMoverAnalysis.candidates.size(),
                    result.telemetry.slowMoverTelemetry.candidateFrameSupportPercentages.size());
            assertEquals(result.slowMoverAnalysis.candidates.size(),
                    result.telemetry.slowMoverTelemetry.candidateStationaryLikelihoodPercentages.size());
            assertTrue(result.slowMoverAnalysis.candidates.get(0).diagnostics.frameSupportAvailable);
            assertEquals(result.slowMoverAnalysis.candidates.get(0).diagnostics.frameSupportPercentage,
                    result.telemetry.slowMoverTelemetry.candidateFrameSupportPercentages.get(0), 0.0);
            assertEquals(result.slowMoverAnalysis.candidates.get(0).diagnostics.stationaryLikelihoodPercentage,
                    result.telemetry.slowMoverTelemetry.candidateStationaryLikelihoodPercentages.get(0), 0.0);
        } finally {
            engine.shutdown();
        }
    }

    /** Disables the frame-support floor and stationary-likelihood ceiling so raw diagnostics can be measured. */
    private static void disableFrameEvidenceGates(DetectionConfig config) {
        config.slowMoverMinFrameSupport = 0.0;
        config.slowMoverMaxStationaryLikelihood = 100.0;
    }

    /** Relaxes edge checks for a small synthetic field without changing morphology defaults. */
    private static DetectionConfig testConfig() {
        DetectionConfig config = new DetectionConfig();
        config.masterSlowMoverMinPixels = 10;
        config.masterSlowMoverSigmaMultiplier = 4.0;
        config.masterSlowMoverGrowSigmaMultiplier = 3.5;
        config.edgeMarginPixels = 4;
        config.voidProximityRadius = 4;
        return config;
    }

    /**
     * Creates nine aligned frames with a Gaussian source displaced by totalMotion pixels
     * from the first to last frame. The one-frame case places it only in the middle frame.
     */
    private static List<ImageFrame> createFrames(double totalMotion,
                                                  double sigmaX,
                                                  double sigmaY,
                                                  boolean oneFrameOnly,
                                                  boolean includeStationaryStar) {
        List<ImageFrame> frames = new ArrayList<>();
        for (int frameIndex = 0; frameIndex < 9; frameIndex++) {
            short[][] image = createBackgroundImage();
            if (!oneFrameOnly || frameIndex == 4) {
                double centerX = 60.0 + (frameIndex - 4) * totalMotion / 8.0;
                drawGaussian(image, centerX, 48.0, sigmaX, sigmaY);
            }
            if (includeStationaryStar) {
                drawGaussian(image, 32.0, 48.0, 1.4, 1.4);
            }
            frames.add(new ImageFrame(frameIndex, "frame_" + frameIndex + ".fit", image, -1L, -1L));
        }
        return frames;
    }

    /** Deterministic low-amplitude background texture keeps extraction noise estimates nonzero. */
    private static short[][] createBackgroundImage() {
        short[][] image = new short[96][96];
        for (int y = 0; y < image.length; y++) {
            for (int x = 0; x < image[y].length; x++) {
                int value = 1000 + ((x * 17 + y * 13) % 7) - 3;
                image[y][x] = PixelEncoding.fromShiftedPositiveInt(value);
            }
        }
        return image;
    }

    /** Adds an elliptical Gaussian signal in shifted-positive pixel space. */
    private static void drawGaussian(short[][] image,
                                     double centerX,
                                     double centerY,
                                     double sigmaX,
                                     double sigmaY) {
        for (int y = (int) centerY - 9; y <= (int) centerY + 9; y++) {
            for (int x = (int) centerX - 20; x <= (int) centerX + 20; x++) {
                double offsetX = (x - centerX) / sigmaX;
                double offsetY = (y - centerY) / sigmaY;
                int signal = (int) Math.round(900.0 * Math.exp(-0.5 * (offsetX * offsetX + offsetY * offsetY)));
                int value = PixelEncoding.toShiftedPositiveInt(image[y][x]) + signal;
                image[y][x] = PixelEncoding.fromShiftedPositiveInt(value);
            }
        }
    }

    /** Verifies the returned mask contains exactly the median objects' raw pixels, without dilation. */
    private static void assertMedianMaskMatchesExtractedFootprints(short[][] medianStack,
                                                                    boolean[][] mask,
                                                                    DetectionConfig config) {
        DetectionConfig extractionConfig = config.clone();
        extractionConfig.growSigmaMultiplier = config.masterSlowMoverGrowSigmaMultiplier;
        List<SourceExtractor.DetectedObject> medianObjects = SourceExtractor.extractSources(
                medianStack,
                config.masterSlowMoverSigmaMultiplier,
                config.masterSlowMoverMinPixels,
                extractionConfig
        ).objects;
        boolean[][] expected = new boolean[mask.length][mask[0].length];
        for (SourceExtractor.DetectedObject object : medianObjects) {
            for (SourceExtractor.Pixel pixel : object.rawPixels) {
                expected[pixel.y][pixel.x] = true;
            }
        }
        for (int y = 0; y < mask.length; y++) {
            assertArrayEquals(expected[y], mask[y]);
        }
    }
}
