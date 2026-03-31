package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.SourceExtractor;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * Regression tests for the slow-mover median-support filter.
 * Candidates now need their median-stack overlap to stay inside a configured support band.
 */
public class JTransientEngineSlowMoverTest {
    private static final List<String> REAL_SLOW_MOVER_MASKS = List.of(
            "real-slow-mover-01.png",
            "real-slow-mover-02.png",
            "real-slow-mover-03.png",
            "real-slow-mover-04.png",
            "real-slow-mover-05.png",
            "real-slow-mover-06.png",
            "real-slow-mover-07.png",
            "real-slow-mover-08.png",
            "real-slow-mover-09.png"
    );


    /**
     * Verifies the slow-mover branch honors the configured overlap band and keeps only
     * candidates whose median-stack support lands between the minimum and maximum limits.
     */
    @Test
    public void filterSlowMoverCandidatesRequiresMedianStackSupportWithinConfiguredBand() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.slowMoverMedianSupportOverlapFraction = 0.10;
        config.slowMoverMedianSupportMaxOverlapFraction = 0.65;
        PipelineResult.SlowMoverTelemetry telemetry = new PipelineResult.SlowMoverTelemetry();
        short[][] slowMoverImage = new short[80][80];

        List<SourceExtractor.DetectedObject> rawSlowMovers = new ArrayList<>();
        double[] baselineElongations = {1.0, 1.4, 1.6, 1.8, 2.0, 2.0, 2.2, 2.4};
        for (int i = 0; i < baselineElongations.length; i++) {
            rawSlowMovers.add(createLinearCandidate(10, 8 + (i * 4), 10, baselineElongations[i], (short) 100, slowMoverImage));
        }

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

        List<SourceExtractor.DetectedObject> filtered = filterSlowMoverCandidates(
                rawSlowMovers,
                slowMoverImage,
                medianMask,
                config,
                telemetry
        );

        assertEquals(1, filtered.size());
        assertSame(medianSupportedKeep, filtered.get(0));

        assertEquals(11, telemetry.rawCandidatesExtracted);
        assertEquals(3, telemetry.candidatesAboveElongationThreshold);
        assertEquals(3, telemetry.candidatesEvaluatedAgainstMasks);
        assertEquals(0, telemetry.rejectedIrregularShape);
        assertEquals(0, telemetry.rejectedBinaryAnomaly);
        assertEquals(0, telemetry.rejectedSlowMoverShape);
        assertEquals(0, totalDetailedSlowMoverShapeRejects(telemetry));
        assertEquals(1, telemetry.rejectedLowMedianSupport);
        assertEquals(1, telemetry.rejectedHighMedianSupport);
        assertEquals(1, telemetry.candidatesDetected);
        assertEquals(0.10, telemetry.medianSupportOverlapThreshold, 0.0001);
        assertEquals(0.65, telemetry.medianSupportMaxOverlapThreshold, 0.0001);
        assertEquals((0.0 + 0.5 + 0.8) / 3.0, telemetry.avgMedianSupportOverlap, 0.0001);
        assertEquals(List.of(0.5), telemetry.candidateMedianSupportOverlaps);
    }

    /**
     * Verifies the additional slow-mover-only shape veto rejects short hooked residuals
     * that are too compact and kinked to be trustworthy movers.
     */
    @Test
    public void filterSlowMoverCandidatesRejectsShortHookedResiduals() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.slowMoverMedianSupportOverlapFraction = 0.0;
        config.slowMoverMedianSupportMaxOverlapFraction = 1.0;
        PipelineResult.SlowMoverTelemetry telemetry = new PipelineResult.SlowMoverTelemetry();
        short[][] slowMoverImage = new short[80][80];

        List<SourceExtractor.DetectedObject> rawSlowMovers = new ArrayList<>();
        double[] baselineElongations = {1.0, 1.4, 1.6, 1.8, 2.0, 2.0, 2.2, 2.4};
        for (int i = 0; i < baselineElongations.length; i++) {
            rawSlowMovers.add(createLinearCandidate(10, 8 + (i * 4), 10, baselineElongations[i], (short) 100, slowMoverImage));
        }

        SourceExtractor.DetectedObject hookedReject = createHookedCandidate(10, 48, (short) 120, slowMoverImage);
        SourceExtractor.DetectedObject straightKeep = createLinearCandidate(10, 56, 8, 4.8, (short) 120, slowMoverImage);
        rawSlowMovers.add(hookedReject);
        rawSlowMovers.add(straightKeep);

        boolean[][] medianMask = new boolean[80][80];
        markFirstPixels(medianMask, hookedReject, hookedReject.rawPixels.size());
        markFirstPixels(medianMask, straightKeep, straightKeep.rawPixels.size());

        List<SourceExtractor.DetectedObject> filtered = filterSlowMoverCandidates(
                rawSlowMovers,
                slowMoverImage,
                medianMask,
                config,
                telemetry
        );

        assertEquals(1, filtered.size());
        assertSame(straightKeep, filtered.get(0));
        assertEquals(2, telemetry.candidatesAboveElongationThreshold);
        assertEquals(1, telemetry.candidatesEvaluatedAgainstMasks);
        assertEquals(1, telemetry.rejectedSlowMoverShape);
        assertEquals(telemetry.rejectedSlowMoverShape, totalDetailedSlowMoverShapeRejects(telemetry));
        assertEquals(0, telemetry.rejectedIrregularShape);
        assertEquals(0, telemetry.rejectedBinaryAnomaly);
        assertEquals(List.of(1.0), telemetry.candidateMedianSupportOverlaps);
    }

    /**
     * Verifies the slow-mover-only veto also rejects lopsided bulged shapes that are
     * straight-ish but do not resemble one compact elongated star.
     */
    @Test
    public void filterSlowMoverCandidatesRejectsBulgedResidualsThatAreNotStarLike() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.slowMoverMedianSupportOverlapFraction = 0.0;
        config.slowMoverMedianSupportMaxOverlapFraction = 1.0;
        PipelineResult.SlowMoverTelemetry telemetry = new PipelineResult.SlowMoverTelemetry();
        short[][] slowMoverImage = new short[80][80];

        List<SourceExtractor.DetectedObject> rawSlowMovers = new ArrayList<>();
        double[] baselineElongations = {1.0, 1.4, 1.6, 1.8, 2.0, 2.0, 2.2, 2.4};
        for (int i = 0; i < baselineElongations.length; i++) {
            rawSlowMovers.add(createLinearCandidate(10, 8 + (i * 4), 10, baselineElongations[i], (short) 100, slowMoverImage));
        }

        SourceExtractor.DetectedObject bulgedReject = createBulgedCandidate(10, 48, (short) 120, slowMoverImage);
        SourceExtractor.DetectedObject straightKeep = createLinearCandidate(10, 56, 8, 4.8, (short) 120, slowMoverImage);
        rawSlowMovers.add(bulgedReject);
        rawSlowMovers.add(straightKeep);

        boolean[][] medianMask = new boolean[80][80];
        markFirstPixels(medianMask, bulgedReject, bulgedReject.rawPixels.size());
        markFirstPixels(medianMask, straightKeep, straightKeep.rawPixels.size());

        List<SourceExtractor.DetectedObject> filtered = filterSlowMoverCandidates(
                rawSlowMovers,
                slowMoverImage,
                medianMask,
                config,
                telemetry
        );

        assertEquals(1, filtered.size());
        assertSame(straightKeep, filtered.get(0));
        assertEquals(2, telemetry.candidatesAboveElongationThreshold);
        assertEquals(1, telemetry.candidatesEvaluatedAgainstMasks);
        assertEquals(1, telemetry.rejectedSlowMoverShape);
        assertEquals(telemetry.rejectedSlowMoverShape, totalDetailedSlowMoverShapeRejects(telemetry));
        assertEquals(List.of(1.0), telemetry.candidateMedianSupportOverlaps);
    }

    /**
     * Verifies the slow-mover shape vetoes can be disabled from config so only elongation
     * and median-support overlap remain active.
     */
    @Test
    public void filterSlowMoverCandidatesCanDisableShapeChecks() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.enableSlowMoverShapeFiltering = false;
        config.enableSlowMoverSpecificShapeFiltering = false;
        config.slowMoverMedianSupportOverlapFraction = 0.0;
        config.slowMoverMedianSupportMaxOverlapFraction = 1.0;
        PipelineResult.SlowMoverTelemetry telemetry = new PipelineResult.SlowMoverTelemetry();
        short[][] slowMoverImage = new short[80][80];

        List<SourceExtractor.DetectedObject> rawSlowMovers = new ArrayList<>();
        double[] baselineElongations = {1.0, 1.4, 1.6, 1.8, 2.0, 2.0, 2.2, 2.4};
        for (int i = 0; i < baselineElongations.length; i++) {
            rawSlowMovers.add(createLinearCandidate(10, 8 + (i * 4), 10, baselineElongations[i], (short) 100, slowMoverImage));
        }

        SourceExtractor.DetectedObject hookedKeep = createHookedCandidate(10, 48, (short) 120, slowMoverImage);
        SourceExtractor.DetectedObject straightKeep = createLinearCandidate(10, 56, 8, 4.8, (short) 120, slowMoverImage);
        rawSlowMovers.add(hookedKeep);
        rawSlowMovers.add(straightKeep);

        boolean[][] medianMask = new boolean[80][80];
        markFirstPixels(medianMask, hookedKeep, hookedKeep.rawPixels.size());
        markFirstPixels(medianMask, straightKeep, straightKeep.rawPixels.size());

        List<SourceExtractor.DetectedObject> filtered = filterSlowMoverCandidates(
                rawSlowMovers,
                slowMoverImage,
                medianMask,
                config,
                telemetry
        );

        assertEquals(2, filtered.size());
        assertSame(hookedKeep, filtered.get(0));
        assertSame(straightKeep, filtered.get(1));
        assertEquals(2, telemetry.candidatesAboveElongationThreshold);
        assertEquals(2, telemetry.candidatesEvaluatedAgainstMasks);
        assertEquals(0, telemetry.rejectedIrregularShape);
        assertEquals(0, telemetry.rejectedBinaryAnomaly);
        assertEquals(0, telemetry.rejectedSlowMoverShape);
        assertEquals(0, totalDetailedSlowMoverShapeRejects(telemetry));
        assertEquals(List.of(1.0, 1.0), telemetry.candidateMedianSupportOverlaps);
    }

    /**
     * Verifies the targeted slow-mover-only shape filter can be disabled independently while
     * keeping the shared irregular/binary checks active.
     */
    @Test
    public void filterSlowMoverCandidatesCanDisableOnlySpecificShapeFilter() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.enableSlowMoverShapeFiltering = true;
        config.enableSlowMoverSpecificShapeFiltering = false;
        config.slowMoverMedianSupportOverlapFraction = 0.0;
        config.slowMoverMedianSupportMaxOverlapFraction = 1.0;
        PipelineResult.SlowMoverTelemetry telemetry = new PipelineResult.SlowMoverTelemetry();
        short[][] slowMoverImage = new short[80][80];

        List<SourceExtractor.DetectedObject> rawSlowMovers = new ArrayList<>();
        double[] baselineElongations = {1.0, 1.4, 1.6, 1.8, 2.0, 2.0, 2.2, 2.4};
        for (int i = 0; i < baselineElongations.length; i++) {
            rawSlowMovers.add(createLinearCandidate(10, 8 + (i * 4), 10, baselineElongations[i], (short) 100, slowMoverImage));
        }

        SourceExtractor.DetectedObject hookedKeep = createHookedCandidate(10, 48, (short) 120, slowMoverImage);
        SourceExtractor.DetectedObject straightKeep = createLinearCandidate(10, 56, 8, 4.8, (short) 120, slowMoverImage);
        rawSlowMovers.add(hookedKeep);
        rawSlowMovers.add(straightKeep);

        boolean[][] medianMask = new boolean[80][80];
        markFirstPixels(medianMask, hookedKeep, hookedKeep.rawPixels.size());
        markFirstPixels(medianMask, straightKeep, straightKeep.rawPixels.size());

        List<SourceExtractor.DetectedObject> filtered = filterSlowMoverCandidates(
                rawSlowMovers,
                slowMoverImage,
                medianMask,
                config,
                telemetry
        );

        assertEquals(2, filtered.size());
        assertSame(hookedKeep, filtered.get(0));
        assertSame(straightKeep, filtered.get(1));
        assertEquals(2, telemetry.candidatesEvaluatedAgainstMasks);
        assertEquals(0, telemetry.rejectedIrregularShape);
        assertEquals(0, telemetry.rejectedBinaryAnomaly);
        assertEquals(0, telemetry.rejectedSlowMoverShape);
        assertEquals(0, totalDetailedSlowMoverShapeRejects(telemetry));
        assertEquals(List.of(1.0, 1.0), telemetry.candidateMedianSupportOverlaps);
    }

    /**
     * Verifies the slow-mover-only shape veto uses the binary footprint itself rather than
     * trusting the stored object angle from the extractor moments.
     */
    @Test
    public void filterSlowMoverCandidatesUsesBinaryFootprintInsteadOfStoredAngle() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.slowMoverMedianSupportOverlapFraction = 0.0;
        config.slowMoverMedianSupportMaxOverlapFraction = 1.0;
        PipelineResult.SlowMoverTelemetry telemetry = new PipelineResult.SlowMoverTelemetry();
        short[][] slowMoverImage = new short[80][80];

        List<SourceExtractor.DetectedObject> rawSlowMovers = new ArrayList<>();
        double[] baselineElongations = {1.0, 1.4, 1.6, 1.8, 2.0, 2.0, 2.2, 2.4};
        for (int i = 0; i < baselineElongations.length; i++) {
            rawSlowMovers.add(createLinearCandidate(10, 8 + (i * 4), 10, baselineElongations[i], (short) 100, slowMoverImage));
        }

        SourceExtractor.DetectedObject wrongAngleKeep = createLinearCandidate(10, 48, 8, 4.8, (short) 120, slowMoverImage);
        wrongAngleKeep.angle = Math.PI / 2.0;
        rawSlowMovers.add(wrongAngleKeep);

        boolean[][] medianMask = new boolean[80][80];
        markFirstPixels(medianMask, wrongAngleKeep, wrongAngleKeep.rawPixels.size());

        List<SourceExtractor.DetectedObject> filtered = filterSlowMoverCandidates(
                rawSlowMovers,
                slowMoverImage,
                medianMask,
                config,
                telemetry
        );

        assertEquals(1, filtered.size());
        assertSame(wrongAngleKeep, filtered.get(0));
        assertEquals(1, telemetry.candidatesEvaluatedAgainstMasks);
        assertEquals(0, telemetry.rejectedSlowMoverShape);
        assertEquals(0, totalDetailedSlowMoverShapeRejects(telemetry));
        assertEquals(List.of(1.0), telemetry.candidateMedianSupportOverlaps);
    }

    /**
     * Verifies the last slow-mover-only shape filter still accepts compact high-fill elongated
     * blobs, which are common for real faint slow movers in the deep stack.
     */
    @Test
    public void filterSlowMoverCandidatesKeepsCompactElongatedBlobs() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.slowMoverMedianSupportOverlapFraction = 0.0;
        config.slowMoverMedianSupportMaxOverlapFraction = 1.0;
        PipelineResult.SlowMoverTelemetry telemetry = new PipelineResult.SlowMoverTelemetry();
        short[][] slowMoverImage = new short[80][80];

        List<SourceExtractor.DetectedObject> rawSlowMovers = new ArrayList<>();
        double[] baselineElongations = {1.0, 1.4, 1.6, 1.8, 2.0, 2.0, 2.2, 2.4};
        for (int i = 0; i < baselineElongations.length; i++) {
            rawSlowMovers.add(createLinearCandidate(10, 8 + (i * 4), 10, baselineElongations[i], (short) 100, slowMoverImage));
        }

        SourceExtractor.DetectedObject compactKeep = createLinearCandidate(10, 48, 4, 4.5, (short) 120, slowMoverImage);
        rawSlowMovers.add(compactKeep);

        boolean[][] medianMask = new boolean[80][80];
        markFirstPixels(medianMask, compactKeep, compactKeep.rawPixels.size());

        List<SourceExtractor.DetectedObject> filtered = filterSlowMoverCandidates(
                rawSlowMovers,
                slowMoverImage,
                medianMask,
                config,
                telemetry
        );

        assertEquals(1, filtered.size());
        assertSame(compactKeep, filtered.get(0));
        assertEquals(1, telemetry.candidatesEvaluatedAgainstMasks);
        assertEquals(0, telemetry.rejectedSlowMoverShape);
        assertEquals(0, totalDetailedSlowMoverShapeRejects(telemetry));
        assertEquals(List.of(1.0), telemetry.candidateMedianSupportOverlaps);
    }

    /**
     * Verifies the targeted slow-mover-only shape filter accepts the real compact elongated
     * raw masks captured from injected slow movers.
     */
    @Test
    public void evaluateSlowMoverSpecificShapeFilterAcceptsProvidedRealSlowMoverMasks() throws Exception {
        Method method = slowMoverSpecificShapeFilterMethod();

        for (String resourceName : REAL_SLOW_MOVER_MASKS) {
            SourceExtractor.DetectedObject obj = loadObjectFromMaskResource(resourceName);
            Object result = method.invoke(null, obj);
            assertEquals(resourceName, "NONE", result.toString());
        }
    }

    /**
     * Verifies the targeted slow-mover-only shape filter also accepts the exact same real masks
     * after they are scaled up to a larger pixel footprint.
     */
    @Test
    public void evaluateSlowMoverSpecificShapeFilterAcceptsScaledRealSlowMoverMasks() throws Exception {
        Method method = slowMoverSpecificShapeFilterMethod();

        for (String resourceName : REAL_SLOW_MOVER_MASKS) {
            SourceExtractor.DetectedObject scaledObj = scaleObject(loadObjectFromMaskResource(resourceName), 2);
            Object result = method.invoke(null, scaledObj);
            assertEquals(resourceName + " @2x", "NONE", result.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<SourceExtractor.DetectedObject> filterSlowMoverCandidates(
            List<SourceExtractor.DetectedObject> rawSlowMovers,
            short[][] slowMoverImage,
            boolean[][] medianMask,
            DetectionConfig config,
            PipelineResult.SlowMoverTelemetry telemetry
    ) throws Exception {
        Method method = JTransientEngine.class.getDeclaredMethod(
                "filterSlowMoverCandidates",
                List.class,
                short[][].class,
                boolean[][].class,
                DetectionConfig.class,
                PipelineResult.SlowMoverTelemetry.class
        );
        method.setAccessible(true);
        return (List<SourceExtractor.DetectedObject>) method.invoke(
                null,
                rawSlowMovers,
                slowMoverImage,
                medianMask,
                config,
                telemetry
        );
    }

    private static int totalDetailedSlowMoverShapeRejects(PipelineResult.SlowMoverTelemetry telemetry) {
        return telemetry.rejectedSlowMoverShapeTooShort
                + telemetry.rejectedSlowMoverShapeLowFill
                + telemetry.rejectedSlowMoverShapeSparseBins
                + telemetry.rejectedSlowMoverShapeGappedBins
                + telemetry.rejectedSlowMoverShapeCurvedCenterline
                + telemetry.rejectedSlowMoverShapeBulgedWidth;
    }

    private static Method slowMoverSpecificShapeFilterMethod() throws Exception {
        Method method = JTransientEngine.class.getDeclaredMethod(
                "evaluateSlowMoverSpecificShapeFilter",
                SourceExtractor.DetectedObject.class
        );
        method.setAccessible(true);
        return method;
    }

    private static SourceExtractor.DetectedObject loadObjectFromMaskResource(String resourceName) throws Exception {
        try (InputStream input = JTransientEngineSlowMoverTest.class.getClassLoader()
                .getResourceAsStream("slow-mover-shapes/" + resourceName)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing test resource: " + resourceName);
            }
            BufferedImage image = ImageIO.read(input);
            List<SourceExtractor.Pixel> pixels = new ArrayList<>();
            double sumX = 0.0;
            double sumY = 0.0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int rgb = image.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    if (r == 0 && g == 0 && b == 0) {
                        continue;
                    }
                    pixels.add(new SourceExtractor.Pixel(x, y, (short) Math.max(r, Math.max(g, b))));
                    sumX += x;
                    sumY += y;
                }
            }

            if (pixels.isEmpty()) {
                throw new IllegalArgumentException("No object pixels found in test resource: " + resourceName);
            }

            SourceExtractor.DetectedObject obj = new SourceExtractor.DetectedObject(
                    sumX / pixels.size(),
                    sumY / pixels.size(),
                    pixels.size(),
                    pixels.size()
            );
            obj.rawPixels = pixels;
            obj.pixelArea = pixels.size();
            obj.elongation = 4.5;
            return obj;
        }
    }

    private static SourceExtractor.DetectedObject scaleObject(SourceExtractor.DetectedObject source, int scaleFactor) {
        if (scaleFactor <= 1) {
            return source;
        }

        List<SourceExtractor.Pixel> scaledPixels = new ArrayList<>(source.rawPixels.size() * scaleFactor * scaleFactor);
        double sumX = 0.0;
        double sumY = 0.0;

        for (SourceExtractor.Pixel pixel : source.rawPixels) {
            for (int dy = 0; dy < scaleFactor; dy++) {
                for (int dx = 0; dx < scaleFactor; dx++) {
                    int scaledX = pixel.x * scaleFactor + dx;
                    int scaledY = pixel.y * scaleFactor + dy;
                    scaledPixels.add(new SourceExtractor.Pixel(scaledX, scaledY, pixel.value));
                    sumX += scaledX;
                    sumY += scaledY;
                }
            }
        }

        SourceExtractor.DetectedObject scaled = new SourceExtractor.DetectedObject(
                sumX / scaledPixels.size(),
                sumY / scaledPixels.size(),
                source.totalFlux * scaleFactor * scaleFactor,
                scaledPixels.size()
        );
        scaled.rawPixels = scaledPixels;
        scaled.pixelArea = scaledPixels.size();
        scaled.elongation = source.elongation;
        scaled.angle = source.angle;
        return scaled;
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
            image[y][x] = signal;
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
            image[point[1]][point[0]] = signal;
        }
        return obj;
    }

    private static SourceExtractor.DetectedObject createBulgedCandidate(
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
                {xStart + 5, yStart},
                {xStart + 6, yStart},
                {xStart + 7, yStart},
                {xStart + 4, yStart + 1},
                {xStart + 5, yStart + 1},
                {xStart + 5, yStart + 2}
        };

        SourceExtractor.DetectedObject obj = new SourceExtractor.DetectedObject(
                xStart + 3.8,
                yStart + 0.6,
                signal * points.length,
                points.length
        );
        obj.elongation = 4.8;
        obj.angle = 0.0;
        obj.pixelArea = points.length;
        obj.rawPixels = new ArrayList<>(points.length);

        for (int[] point : points) {
            obj.rawPixels.add(new SourceExtractor.Pixel(point[0], point[1], signal));
            image[point[1]][point[0]] = signal;
        }
        return obj;
    }

    private static void markFirstPixels(boolean[][] mask, SourceExtractor.DetectedObject obj, int count) {
        for (int i = 0; i < count; i++) {
            SourceExtractor.Pixel p = obj.rawPixels.get(i);
            mask[p.y][p.x] = true;
        }
    }
}
