/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.MasterMapGenerator;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;
import io.github.ppissias.jtransient.quality.FrameQualityAnalyzer;
import io.github.ppissias.jtransient.quality.SessionEvaluator;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates the full JTransient processing pipeline from extraction through track linking.
 */
public class JTransientEngine {

    /** Global debug switch shared by the core library. */
    public static boolean DEBUG = false;
    /** Pixels at the signed-short floor are treated as synthetic alignment padding, not real image data. */
    private static final int DRIFT_VALID_PIXEL_THRESHOLD = Short.MIN_VALUE + 8;

    // Internal thread pool for the library
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * Internal concurrent work product for one frame.
     */
    private static class FrameExtractionResult {
        public int frameIndex;
        public SourceExtractor.ExtractionResult extractionResult;
        public FrameQualityAnalyzer.FrameMetrics metrics;
    }

    /**
     * Frame-edge padding measurement used to recover the true per-frame translation vector.
     */
    private static class FrameDriftMeasurement {
        public final int leftPadding;
        public final int rightPadding;
        public final int topPadding;
        public final int bottomPadding;
        public final int dx;
        public final int dy;

        private FrameDriftMeasurement(int leftPadding, int rightPadding, int topPadding, int bottomPadding) {
            this.leftPadding = leftPadding;
            this.rightPadding = rightPadding;
            this.topPadding = topPadding;
            this.bottomPadding = bottomPadding;
            this.dx = leftPadding - rightPadding;
            this.dy = topPadding - bottomPadding;
        }

        private int maxPadding() {
            return Math.max(Math.max(leftPadding, rightPadding), Math.max(topPadding, bottomPadding));
        }
    }

    /**
     * Shared context returned by the extraction and quality-filtering stages.
     */
    public static class FramesExtractedSources {
        /** Source-extraction results for frames that passed quality control. */
        public List<SourceExtractor.ExtractionResult> cleanFramesData;
        /** Raw image frames that survived session-level rejection. */
        public List<ImageFrame> cleanFrames;
        /** Telemetry accumulated during the early pipeline phases. */
        public PipelineTelemetry telemetry;
        /** Pipeline start timestamp used to calculate total runtime. */
        public long startTime;
        /** Relative per-frame drift diagnostics derived from the valid image footprint. */
        public List<SourceExtractor.Pixel> driftPoints;
    }

    /**
     * Pairing of one frame label with the post-veto transient detections exported for that frame.
     */
    public static class FrameTransients {
        /** Source frame label. */
        public final String filename;
        /** Post-veto transient objects for the frame. */
        public final List<SourceExtractor.DetectedObject> transients;
        /** Full extraction result for the same frame. */
        public final SourceExtractor.ExtractionResult extractionResult;

        /**
         * Creates a transient-export payload for one frame.
         */
        public FrameTransients(String filename, List<SourceExtractor.DetectedObject> transients, SourceExtractor.ExtractionResult extractionResult) {
            this.filename = filename;
            this.transients = transients;
            this.extractionResult = extractionResult;
        }
    }

    /**
     * Generates the Master Stack independently so it can be reused across iterative pipeline runs.
     * Highly optimized: Skips transient extraction and only runs quality analysis to drop outliers.
     *
     * @param inputFrames frames to evaluate and stack
     * @param config pipeline configuration
     * @param listener optional progress listener
     * @return median master stack built from the retained frames
     */
    public short[][] generateMasterStack(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener) throws Exception {
        if (listener != null) {
            listener.onProgressUpdate(0, "Evaluating frames for Master Stack...");
        }

        if (DEBUG) {
            System.out.println("\n--- JTRANSIENT: PRE-COMPUTING MASTER STACK ---");
        }

        List<Callable<FrameExtractionResult>> tasks = new ArrayList<>();
        inputFrames.sort(Comparator.comparingInt(f -> f.sequenceIndex));

        int totalFrames = inputFrames.size();
        AtomicInteger framesCompleted = new AtomicInteger(0);

        for (ImageFrame frame : inputFrames) {
            tasks.add(() -> {
                // We only need quality metrics to drop outliers. We skip SourceExtractor to save massive CPU time!
                FrameQualityAnalyzer.FrameMetrics metrics = FrameQualityAnalyzer.evaluateFrame(frame.pixelData, config);
                metrics.filename = frame.filename;

                FrameExtractionResult result = new FrameExtractionResult();
                result.frameIndex = frame.sequenceIndex;
                result.metrics = metrics;

                int completed = framesCompleted.incrementAndGet();
                if (listener != null) {
                    int progress = (int) ((completed / (double) totalFrames) * 50.0);
                    listener.onProgressUpdate(progress, "Evaluating frame " + completed + " of " + totalFrames);
                }

                return result;
            });
        }

        List<FrameExtractionResult> completedResults = new ArrayList<>();
        List<Future<FrameExtractionResult>> futures = executor.invokeAll(tasks);
        for (Future<FrameExtractionResult> future : futures) {
            completedResults.add(future.get());
        }
        completedResults.sort(Comparator.comparingInt(r -> r.frameIndex));

        List<FrameQualityAnalyzer.FrameMetrics> sessionMetrics = new ArrayList<>();
        for (FrameExtractionResult result : completedResults) {
            sessionMetrics.add(result.metrics);
        }

        if (listener != null) {
            listener.onProgressUpdate(55, "Filtering outlier frames...");
        }

        SessionEvaluator.rejectOutlierFrames(sessionMetrics, config);

        List<ImageFrame> cleanFrames = new ArrayList<>();
        for (int i = 0; i < completedResults.size(); i++) {
            if (!sessionMetrics.get(i).isRejected) {
                cleanFrames.add(inputFrames.get(i));
            }
        }

        if (listener != null) {
            listener.onProgressUpdate(60, "Stacking " + cleanFrames.size() + " clean frames...");
        }

        return MasterMapGenerator.createMedianMasterStack(cleanFrames);
    }

    /**
     * Entry point for the JTransient library.
     * Convenience wrapper that calculates the master stack automatically.
     *
     * @param inputFrames frames to process
     * @param config pipeline configuration
     * @param listener optional progress listener
     * @return full pipeline output bundle
     */
    public PipelineResult runPipeline(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener) throws Exception {
        return runPipeline(inputFrames, config, listener, null);
    }

    /**
     * Runs the pipeline up to detecting the transients (extracted objects) for all frames and does no further processing.
     * Generates a median master stack automatically to apply the Veto Mask.
     *
     * @param inputFrames frames to process
     * @param config pipeline configuration
     * @param listener optional progress listener
     * @return A list of objects containing the filename and its actual transients that survived the Master Veto Mask.
     */
    public List<FrameTransients> detectTransients(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener) throws Exception {
        return detectTransients(inputFrames, config, listener, null);
    }

    /**
     * Runs the pipeline up to detecting the transients for all frames and does no further processing.
     * Uses the provided master stack to successfully apply the Veto Mask.
     *
     * @param inputFrames frames to process
     * @param config pipeline configuration
     * @param listener optional progress listener
     * @param providedMasterStack optional precomputed median master stack
     * @return A list of objects containing the filename and its actual transients that survived the Master Veto Mask.
     */
    public List<FrameTransients> detectTransients(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener, short[][] providedMasterStack) throws Exception {
        FramesExtractedSources context = extractSourcesFromFrames(inputFrames, config, listener);
        
        short[][] masterStackData;
        if (providedMasterStack != null) {
            masterStackData = providedMasterStack;
        } else {
            if (listener != null) listener.onProgressUpdate(45, "Generating Median Master Stack for Veto Mask...");
            masterStackData = MasterMapGenerator.createMedianMasterStack(context.cleanFrames);
        }

        if (listener != null) listener.onProgressUpdate(48, "Extracting Master Star Map...");

        double originalGrowSigma = config.growSigmaMultiplier;
        config.growSigmaMultiplier = config.masterSigmaMultiplier;
        int originalEdgeMargin = config.edgeMarginPixels;
        int originalVoidProximity = config.voidProximityRadius;

        config.edgeMarginPixels = 5;
        config.voidProximityRadius = 5;

        List<SourceExtractor.DetectedObject> masterStars = SourceExtractor.extractSources(
                masterStackData, config.masterSigmaMultiplier, config.masterMinDetectionPixels, config).objects;

        config.edgeMarginPixels = originalEdgeMargin;
        config.voidProximityRadius = originalVoidProximity;
        config.growSigmaMultiplier = originalGrowSigma;

        int sensorHeight = context.cleanFrames.get(0).pixelData.length;
        int sensorWidth = context.cleanFrames.get(0).pixelData[0].length;

        TransientEngineProgressListener proxyListener = null;
        if (listener != null) {
            proxyListener = (percentage, message) -> listener.onProgressUpdate(60 + (int) (percentage * 0.40), message); // Scale 0-100 to 60-100
        }

        List<List<SourceExtractor.DetectedObject>> cleanFramesObjects = new ArrayList<>();
        for (SourceExtractor.ExtractionResult extRes : context.cleanFramesData) {
            cleanFramesObjects.add(extRes.objects);
        }

        TrackLinker.TransientsFilterResult filterResult = TrackLinker.filterTransients(
                cleanFramesObjects, masterStars, config, proxyListener, sensorWidth, sensorHeight);
        
        if (listener != null) listener.onProgressUpdate(100, "Transient Extraction Complete!");

        List<FrameTransients> finalResult = new ArrayList<>();
        for (int i = 0; i < context.cleanFrames.size(); i++) {
            finalResult.add(new FrameTransients(
                    context.cleanFrames.get(i).filename,
                    filterResult.allTransients.get(i),
                    context.cleanFramesData.get(i)
            ));
        }
        return finalResult;
    }

    /**
     * Does exactly the same processing as the first phases of runPipeline up to detecting the transients,
     * returning the full context so it can be reused by runPipeline.
     *
     * @param inputFrames frames to process
     * @param config pipeline configuration
     * @param listener optional progress listener
     * @return extraction context reused by the full pipeline and transient-only path
     */
    private FramesExtractedSources extractSourcesFromFrames(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener) throws Exception {
        long startTime = System.currentTimeMillis();
        PipelineTelemetry telemetry = new PipelineTelemetry();
        telemetry.totalFramesLoaded = inputFrames.size();

        if (listener != null) {
            listener.onProgressUpdate(0, "Initializing pipeline...");
        }

        if (DEBUG) {
            System.out.println("\n--- JTRANSIENT: PHASE 1 (Extraction) ---");
        }

        // =================================================================
        // --- NEW: DITHER & DRIFT DIAGNOSTICS ---
        // =================================================================
        List<SourceExtractor.Pixel> driftPoints = analyzeDitherAndDrift(inputFrames, config, listener);

        List<Callable<FrameExtractionResult>> tasks = new ArrayList<>();

        // Ensure input frames are sorted chronologically before processing
        inputFrames.sort(Comparator.comparingInt(f -> f.sequenceIndex));

        // --- SAFE CONCURRENT PROGRESS TRACKING ---
        int totalFrames = inputFrames.size();
        AtomicInteger framesCompleted = new AtomicInteger(0);

        for (ImageFrame frame : inputFrames) {
            tasks.add(() -> {
                // 1. Extract Sources (Passing config as the 4th argument)
                SourceExtractor.ExtractionResult extResult = SourceExtractor.extractSources(
                        frame.pixelData,
                        config.detectionSigmaMultiplier,
                        config.minDetectionPixels,
                        config
                );
                List<SourceExtractor.DetectedObject> objectsInFrame = extResult.objects;

                for (SourceExtractor.DetectedObject obj : objectsInFrame) {
                    obj.sourceFrameIndex = frame.sequenceIndex;
                    obj.sourceFilename = frame.filename;
                    obj.timestamp = frame.timestamp;
                    obj.exposureDuration = frame.exposureDuration;
                }

                // 2. Quality Metrics (Passing config down)
                FrameQualityAnalyzer.FrameMetrics metrics = FrameQualityAnalyzer.evaluateFrame(frame.pixelData, config);
                metrics.filename = frame.filename;

                FrameExtractionResult result = new FrameExtractionResult();
                result.frameIndex = frame.sequenceIndex;
                result.extractionResult = extResult;
                result.metrics = metrics;

                // Safely update progress from multiple threads (Mapping Phase 1 to 0-40% of the total bar)
                int completed = framesCompleted.incrementAndGet();
                if (listener != null) {
                    int progress = (int) ((completed / (double) totalFrames) * 40.0);
                    listener.onProgressUpdate(progress, "Extracting features from frame " + completed + " of " + totalFrames);
                }

                return result;
            });
        }

        // Execute parallel extraction
        List<FrameExtractionResult> completedResults = new ArrayList<>();
        List<Future<FrameExtractionResult>> futures = executor.invokeAll(tasks);
        for (Future<FrameExtractionResult> future : futures) {
            completedResults.add(future.get());
        }
        completedResults.sort(Comparator.comparingInt(r -> r.frameIndex));

        // Unpack results & update Telemetry
        List<SourceExtractor.ExtractionResult> rawExtractedFrames = new ArrayList<>();
        List<FrameQualityAnalyzer.FrameMetrics> sessionMetrics = new ArrayList<>();

        for (FrameExtractionResult result : completedResults) {
            rawExtractedFrames.add(result.extractionResult);
            sessionMetrics.add(result.metrics);

            telemetry.totalRawObjectsExtracted += result.extractionResult.objects.size();
            PipelineTelemetry.FrameExtractionStat stat = new PipelineTelemetry.FrameExtractionStat();
            stat.frameIndex = result.frameIndex;
            stat.filename = result.metrics.filename;
            stat.objectCount = result.extractionResult.objects.size();
            if (result.extractionResult.backgroundMetrics != null) {
                stat.bgMedian = result.extractionResult.backgroundMetrics.median;
                stat.bgSigma = result.extractionResult.backgroundMetrics.sigma;
            }
            stat.seedThreshold = result.extractionResult.seedThreshold;
            stat.growThreshold = result.extractionResult.growThreshold;
            telemetry.frameExtractionStats.add(stat);
        }

        if (DEBUG) {
            System.out.println("\n--- JTRANSIENT: PHASE 2 & 3 (Quality Filter) ---");
        }

        if (listener != null) {
            listener.onProgressUpdate(42, "Filtering outlier frames...");
        }

        // Pass the config down to the evaluator
        SessionEvaluator.rejectOutlierFrames(sessionMetrics, config);

        List<SourceExtractor.ExtractionResult> cleanFramesData = new ArrayList<>();
        List<ImageFrame> cleanFrames = new ArrayList<>(); // Track the raw images that passed the quality check

        for (int i = 0; i < rawExtractedFrames.size(); i++) {
            FrameQualityAnalyzer.FrameMetrics metrics = sessionMetrics.get(i);
            if (metrics.isRejected) {
                telemetry.totalFramesRejected++;
                PipelineTelemetry.FrameRejectionStat rejStat = new PipelineTelemetry.FrameRejectionStat();
                rejStat.frameIndex = i;
                rejStat.filename = metrics.filename;
                rejStat.reason = metrics.rejectionReason;
                telemetry.rejectedFrames.add(rejStat);
            } else {
                telemetry.totalFramesKept++;
                cleanFramesData.add(rawExtractedFrames.get(i));
                cleanFrames.add(inputFrames.get(i)); // Keep the actual frame for the Master Stack
            }
        }

        FramesExtractedSources context = new FramesExtractedSources();
        context.cleanFramesData = cleanFramesData;
        context.cleanFrames = cleanFrames;
        context.telemetry = telemetry;
        context.startTime = startTime;
        context.driftPoints = driftPoints;

        return context;
    }

    /**
     * Measures the true valid-image footprint for one aligned frame.
     * This is more robust than sampling only the central cross-section because dust lanes, dead rows,
     * or bright structures near the center cannot hide real edge padding.
     */
    private static FrameDriftMeasurement measureFrameDrift(short[][] frame) {
        int height = frame.length;
        int width = frame[0].length;
        int minX = 0;
        int maxX = width - 1;
        int minY = 0;
        int maxY = height - 1;

        // Require at least 5% of the line to contain valid pixels before treating it as real image content.
        int minValidPixelsPerColumn = height / 20;
        int minValidPixelsPerRow = width / 20;

        for (int y = 0; y < height; y++) {
            int validCount = 0;
            for (int x = 0; x < width; x++) {
                if (frame[y][x] > DRIFT_VALID_PIXEL_THRESHOLD) {
                    validCount++;
                }
            }
            if (validCount > minValidPixelsPerRow) {
                minY = y;
                break;
            }
        }

        for (int y = height - 1; y >= 0; y--) {
            int validCount = 0;
            for (int x = 0; x < width; x++) {
                if (frame[y][x] > DRIFT_VALID_PIXEL_THRESHOLD) {
                    validCount++;
                }
            }
            if (validCount > minValidPixelsPerRow) {
                maxY = y;
                break;
            }
        }

        for (int x = 0; x < width; x++) {
            int validCount = 0;
            for (int y = 0; y < height; y++) {
                if (frame[y][x] > DRIFT_VALID_PIXEL_THRESHOLD) {
                    validCount++;
                }
            }
            if (validCount > minValidPixelsPerColumn) {
                minX = x;
                break;
            }
        }

        for (int x = width - 1; x >= 0; x--) {
            int validCount = 0;
            for (int y = 0; y < height; y++) {
                if (frame[y][x] > DRIFT_VALID_PIXEL_THRESHOLD) {
                    validCount++;
                }
            }
            if (validCount > minValidPixelsPerColumn) {
                maxX = x;
                break;
            }
        }

        int leftPadding = minX;
        int rightPadding = (width - 1) - maxX;
        int topPadding = minY;
        int bottomPadding = (height - 1) - maxY;
        return new FrameDriftMeasurement(leftPadding, rightPadding, topPadding, bottomPadding);
    }

    /**
     * Analyzes sequence dither and corner drift by measuring the valid-image bounds per frame.
     * Dynamically overrides the void proximity radius if the drift exceeds the configured value.
     *
     * @param inputFrames frames to inspect
     * @param config pipeline configuration that may be updated with a safer void radius
     * @param listener optional progress listener
     * @return A list of translation vectors (dx, dy) representing the relative movement per frame.
     */
    private List<SourceExtractor.Pixel> analyzeDitherAndDrift(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener) {
        if (listener != null) {
            listener.onProgressUpdate(0, "Analyzing sequence dither and corner drift...");
        }

        List<SourceExtractor.Pixel> driftPoints = new ArrayList<>();
        if (inputFrames.isEmpty()) {
            return driftPoints;
        }

        int maxDrift = 0;
        List<ImageFrame> sortedFrames = new ArrayList<>(inputFrames);
        sortedFrames.sort(Comparator.comparingInt(f -> f.sequenceIndex));

        for (ImageFrame frame : sortedFrames) {
            FrameDriftMeasurement measurement = measureFrameDrift(frame.pixelData);
            driftPoints.add(new SourceExtractor.Pixel(measurement.dx, measurement.dy, frame.sequenceIndex));
            if (measurement.maxPadding() > maxDrift) {
                maxDrift = measurement.maxPadding();
            }
        }

        // Derive the optimal Void Proximity Radius (Max inward drift + 10px safety envelope)
        if (maxDrift > 0) {
            int derivedVoidRadius = maxDrift + 10;
            if (derivedVoidRadius > config.voidProximityRadius) {
                if (DEBUG) System.out.println("DEBUG: Dither Diagnostics found corner drift of " + maxDrift + "px. Overriding config.voidProximityRadius to " + derivedVoidRadius);
                config.voidProximityRadius = derivedVoidRadius;
            }
        }
        return driftPoints;
    }

    /**
     * Entry point for the JTransient library.
     * Allows passing a pre-computed master stack to bypass the heavy stacking phase during iterative runs.
     *
     * @param inputFrames frames to process
     * @param config pipeline configuration
     * @param listener optional progress listener
     * @param providedMasterStack optional precomputed median master stack
     * @return full pipeline output bundle
     */
    public PipelineResult runPipeline(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener, short[][] providedMasterStack) throws Exception {
        FramesExtractedSources context = extractSourcesFromFrames(inputFrames, config, listener);
        long startTime = context.startTime;
        PipelineTelemetry telemetry = context.telemetry;
        List<SourceExtractor.ExtractionResult> cleanFramesData = context.cleanFramesData;
        List<ImageFrame> cleanFrames = context.cleanFrames;

        // =================================================================
        // PHASE 0 (Generate Deep Master Star Map)
        // =================================================================
        short[][] masterStackData;

        if (providedMasterStack != null) {
            if (DEBUG) {
                System.out.println("\n--- JTRANSIENT: PHASE 0 (Using Pre-Computed Master Stack) ---");
            }
            if (listener != null) {
                listener.onProgressUpdate(45, "Using pre-computed Master Stack...");
            }
            masterStackData = providedMasterStack;
        } else {
            if (DEBUG) {
                System.out.println("\n--- JTRANSIENT: PHASE 0 (Master Map Generation) ---");
            }
            if (listener != null) {
                listener.onProgressUpdate(45, "Generating Median Master Stack...");
            }
            // 1. Mathematically stack the surviving frames to erase transients
            masterStackData = MasterMapGenerator.createMedianMasterStack(cleanFrames);
        }

        int sensorHeight = masterStackData.length;
        int sensorWidth = masterStackData[0].length;

        // --- MASTER MAP PARAMETERS ---
        double masterSigma = config.masterSigmaMultiplier;
        int masterMinPix = config.masterMinDetectionPixels;

        // --- SAFE CORE-ONLY EXTRACTION ---
        // Explicitly tie the grow multiplier to the master sigma. 
        // This disables fuzzy halo expansion, guaranteeing consistently tight and relaxed Veto Masks.
        double originalGrowSigma = config.growSigmaMultiplier;
        config.growSigmaMultiplier = masterSigma;

        if (DEBUG) {
            System.out.printf("DEBUG: Master Map Config -> Master Sigma: %.2f | Master Grow: %.2f | Master MinPix: %d%n",
                    masterSigma, config.growSigmaMultiplier, masterMinPix);
        }

        if (listener != null) {
            listener.onProgressUpdate(48, "Extracting Master Star Map...");
        }

        // --- NARROW MARGIN TRICK FOR MASTER MAP ---
        // Force extraction to the very edge of the sensor to map edge artifacts
        int originalEdgeMargin = config.edgeMarginPixels;
        int originalVoidProximity = config.voidProximityRadius;

        config.edgeMarginPixels = 5;
        config.voidProximityRadius = 5;

        // 2. Extract every stable star and galaxy from the deep stack
        List<SourceExtractor.DetectedObject> masterStars = SourceExtractor.extractSources(
                masterStackData,
                masterSigma,
                masterMinPix,
                config
        ).objects;

        // --- RESTORE ORIGINAL CONFIGURATION ---
        config.edgeMarginPixels = originalEdgeMargin;
        config.voidProximityRadius = originalVoidProximity;
        config.growSigmaMultiplier = originalGrowSigma;

        if (DEBUG) {
            System.out.println("DEBUG: Master Stack generated. Found " + masterStars.size() + " deep stationary objects.");
        }

        // =================================================================
        // PHASE 0.5 (Slow Mover Detection
        // =================================================================

        short[][] slowMoverStackData = null;
        boolean[][] slowMoverMedianVetoMask = null;
        List<SourceExtractor.DetectedObject> slowMoverCandidates = new ArrayList<>();
        PipelineTelemetry.SlowMoverTelemetry smTelemetry = null;

        if (config.enableSlowMoverDetection) {
            if (DEBUG) {
                System.out.println("\n--- JTRANSIENT: PHASE 0.5 (Slow Mover Detection) ---");
            }
            if (listener != null) {
                listener.onProgressUpdate(49, "Generating Slow Mover Master Stack...");
            }

            slowMoverStackData = MasterMapGenerator.createSlowMoverMasterStack(cleanFrames, config.slowMoverStackMiddleFraction);

            // Temporarily override config parameters for slow mover extraction
            double origGrow = config.growSigmaMultiplier;
            config.growSigmaMultiplier = config.masterSlowMoverGrowSigmaMultiplier;

            List<SourceExtractor.DetectedObject> rawSlowMovers = SourceExtractor.extractSources(
                    slowMoverStackData,
                    config.masterSlowMoverSigmaMultiplier,
                    config.masterSlowMoverMinPixels,
                    config
            ).objects;

            // Extract baseline static artifacts from the median stack using IDENTICAL parameters
            List<SourceExtractor.DetectedObject> medianArtifacts = SourceExtractor.extractSources(
                    masterStackData,
                    config.masterSlowMoverSigmaMultiplier,
                    config.masterSlowMoverMinPixels,
                    config
            ).objects;

            // Restore original config
            config.growSigmaMultiplier = origGrow;

            smTelemetry = new PipelineTelemetry.SlowMoverTelemetry();
            boolean[][] medianMask = buildObjectMask(medianArtifacts, sensorWidth, sensorHeight, 0);
            slowMoverMedianVetoMask = medianMask;

            slowMoverCandidates = filterSlowMoverCandidates(
                    rawSlowMovers,
                    slowMoverStackData,
                    medianMask,
                    config,
                    smTelemetry
            );

            if (DEBUG) {
                System.out.printf(
                        "DEBUG: Slow Mover Stats -> Median Elong: %.2f | MAD: %.2f | Dynamic Threshold: %.2f%n",
                        smTelemetry.medianElongation,
                        smTelemetry.madElongation,
                        smTelemetry.dynamicElongationThreshold
                );
                System.out.printf(
                        "DEBUG: Slow Mover Filters -> Raw: %d | AboveElong: %d | MaskStage: %d | Irregular: %d | Binary: %d | SlowMoverShape: %d | LowMedianSupport: %d | HighMedianSupport: %d | Final: %d%n",
                        smTelemetry.rawCandidatesExtracted,
                        smTelemetry.candidatesAboveElongationThreshold,
                        smTelemetry.candidatesEvaluatedAgainstMasks,
                        smTelemetry.rejectedIrregularShape,
                        smTelemetry.rejectedBinaryAnomaly,
                        smTelemetry.rejectedSlowMoverShape,
                        smTelemetry.rejectedLowMedianSupport,
                        smTelemetry.rejectedHighMedianSupport,
                        smTelemetry.candidatesDetected
                );
                if (smTelemetry.rejectedSlowMoverShape > 0) {
                    System.out.printf(
                            "DEBUG: Slow Mover Shape Breakdown -> TooShort: %d | LowFill: %d | SparseBins: %d | GappedBins: %d | CurvedCenterline: %d | BulgedWidth: %d%n",
                            smTelemetry.rejectedSlowMoverShapeTooShort,
                            smTelemetry.rejectedSlowMoverShapeLowFill,
                            smTelemetry.rejectedSlowMoverShapeSparseBins,
                            smTelemetry.rejectedSlowMoverShapeGappedBins,
                            smTelemetry.rejectedSlowMoverShapeCurvedCenterline,
                            smTelemetry.rejectedSlowMoverShapeBulgedWidth
                    );
                }
                System.out.printf(
                        "DEBUG: Slow Mover Signals -> MinSupport: %.3f | MaxSupport: %.3f | AvgMedianSupportOverlap: %.3f%n",
                        smTelemetry.medianSupportOverlapThreshold,
                        smTelemetry.medianSupportMaxOverlapThreshold,
                        smTelemetry.avgMedianSupportOverlap
                );
                if (!smTelemetry.candidateMedianSupportOverlaps.isEmpty()) {
                    System.out.printf(
                            "DEBUG: Accepted Slow Mover Overlaps -> %s%n",
                            formatOverlapPercentages(smTelemetry.candidateMedianSupportOverlaps)
                    );
                }
            }
        }

        // =================================================================
        // PHASE 4 (Track Linking)
        // =================================================================
        if (DEBUG) {
            System.out.println("\n--- JTRANSIENT: PHASE 4 (Track Linking) ---");
        }

        // --- THE PROXY LISTENER ---
        // We pass a synthetic listener to the Linker. It maps the Linker's 0-100% to the Engine's 50-100% range!
        TransientEngineProgressListener trackingProxyListener = null;
        if (listener != null) {
            trackingProxyListener = (percentage, message) -> {
                int scaledProgress = 50 + (percentage / 2);
                listener.onProgressUpdate(scaledProgress, message);
            };
        }

        List<List<SourceExtractor.DetectedObject>> cleanFramesObjects = new ArrayList<>();
        for (SourceExtractor.ExtractionResult extRes : cleanFramesData) {
            cleanFramesObjects.add(extRes.objects);
        }

        if (DEBUG) {
            System.out.println("DEBUG: TrackLinker input frame timing summary:");
            for (int i = 0; i < cleanFrames.size(); i++) {
                ImageFrame frame = cleanFrames.get(i);
                int objectCount = cleanFramesData.get(i).objects.size();
                System.out.printf(
                        "   Frame %d [%s] -> timestamp=%d exposure=%d detectedObjects=%d%n",
                        frame.sequenceIndex,
                        frame.filename,
                        frame.timestamp,
                        frame.exposureDuration,
                        objectCount
                );
            }
        }

        TrackLinker.TrackingResult trackResult = TrackLinker.findMovingObjects(
                cleanFramesObjects,
                masterStars,
                config,
                trackingProxyListener,
                sensorWidth,
                sensorHeight
        );

        // Map the track results back to our main telemetry object
        telemetry.totalMasterStarsIdentified = masterStars.size();
        telemetry.totalTracksFound = trackResult.tracks.size();
        telemetry.totalAnomaliesFound = trackResult.anomalies.size();
        telemetry.totalSuspectedStreakTracksFound = trackResult.telemetry.suspectedStreakTracksFound;
        telemetry.trackerTelemetry = trackResult.telemetry;
        telemetry.slowMoverTelemetry = smTelemetry;

        telemetry.processingTimeMs = System.currentTimeMillis() - startTime;

        if (listener != null) {
            listener.onProgressUpdate(96, "Analyzing residual transients...");
        }

        ResidualTransientAnalysis residualTransientAnalysis = ResidualTransientAnalyzer.analyze(
                trackResult.unclassifiedTransients,
                config
        );

        if (listener != null) {
            listener.onProgressUpdate(100, "Processing Complete!");
        }

        // --- FINAL POST-PROCESSING ---
        short[][] maximumStackData = MasterMapGenerator.createMaximumMasterStack(cleanFrames);

        // --- MASTER MAXIMUM STACK EXPORT ONLY ---
        if (listener != null) {
            listener.onProgressUpdate(100, "Maximum Stack generation complete.");
        }

        return new PipelineResult(trackResult.tracks, telemetry, masterStackData, masterStars,
                slowMoverStackData, slowMoverMedianVetoMask, slowMoverCandidates, trackResult.anomalies,
                trackResult.allTransients, trackResult.unclassifiedTransients,
                residualTransientAnalysis, trackResult.masterVetoMask, context.driftPoints,
                maximumStackData);
    }

    /**
     * Gracefully shuts down the internal thread pool.
     * Call this when your application is closing to prevent memory leaks.
     */
    public void shutdown() {
        executor.shutdown();
    }

    /**
     * Applies the slow-mover morphology and mask filters while recording stage-by-stage telemetry.
     */
    private static List<SourceExtractor.DetectedObject> filterSlowMoverCandidates(
            List<SourceExtractor.DetectedObject> rawSlowMovers,
            short[][] slowMoverStackData,
            boolean[][] medianMask,
            DetectionConfig config,
            PipelineTelemetry.SlowMoverTelemetry telemetry
    ) {
        List<SourceExtractor.DetectedObject> filteredCandidates = new ArrayList<>();
        telemetry.rawCandidatesExtracted = rawSlowMovers.size();

        List<Double> elongations = new ArrayList<>(rawSlowMovers.size());
        for (SourceExtractor.DetectedObject obj : rawSlowMovers) {
            elongations.add(obj.elongation);
        }

        double medianElong = 0.0;
        double madElong = 0.1;
        if (!elongations.isEmpty()) {
            elongations.sort(Double::compareTo);
            medianElong = elongations.get(elongations.size() / 2);

            List<Double> deviations = new ArrayList<>(elongations.size());
            for (double e : elongations) {
                deviations.add(Math.abs(e - medianElong));
            }
            deviations.sort(Double::compareTo);
            madElong = Math.max(0.1, deviations.get(deviations.size() / 2));
        }

        double dynamicElongationThreshold = 3.0;
        if (elongations.size() >= 10) {
            dynamicElongationThreshold = medianElong + (madElong * config.slowMoverBaselineMadMultiplier);
        }
        double minMedianSupportOverlap = Math.max(0.0, Math.min(1.0, config.slowMoverMedianSupportOverlapFraction));
        double maxMedianSupportOverlap = Math.max(minMedianSupportOverlap, Math.min(1.0, config.slowMoverMedianSupportMaxOverlapFraction));

        double medianSupportOverlapSum = 0.0;

        for (SourceExtractor.DetectedObject obj : rawSlowMovers) {
            if (obj.elongation < dynamicElongationThreshold) {
                continue;
            }
            telemetry.candidatesAboveElongationThreshold++;

            if (config.enableSlowMoverShapeFiltering) {
                if (SourceExtractor.isIrregularStreakShape(obj)) {
                    telemetry.rejectedIrregularShape++;
                    continue;
                }

                if (SourceExtractor.isBinaryStarAnomaly(slowMoverStackData, obj)) {
                    telemetry.rejectedBinaryAnomaly++;
                    continue;
                }
            }

            if (config.enableSlowMoverSpecificShapeFiltering) {
                SlowMoverShapeRejectReason shapeRejectReason = evaluateSlowMoverSpecificShapeFilter(obj);
                if (shapeRejectReason != SlowMoverShapeRejectReason.NONE) {
                    telemetry.rejectedSlowMoverShape++;
                    incrementSlowMoverShapeRejectTelemetry(telemetry, shapeRejectReason);
                    continue;
                }
            }

            double medianSupportOverlap = computeMaskOverlapFraction(obj, medianMask);

            telemetry.candidatesEvaluatedAgainstMasks++;
            medianSupportOverlapSum += medianSupportOverlap;

            if (medianSupportOverlap < minMedianSupportOverlap) {
                telemetry.rejectedLowMedianSupport++;
                continue;
            }
            if (medianSupportOverlap > maxMedianSupportOverlap) {
                telemetry.rejectedHighMedianSupport++;
                continue;
            }

            filteredCandidates.add(obj);
            telemetry.candidateMedianSupportOverlaps.add(medianSupportOverlap);
        }

        telemetry.candidatesDetected = filteredCandidates.size();
        telemetry.medianElongation = medianElong;
        telemetry.madElongation = madElong;
        telemetry.dynamicElongationThreshold = dynamicElongationThreshold;
        telemetry.medianSupportOverlapThreshold = minMedianSupportOverlap;
        telemetry.medianSupportMaxOverlapThreshold = maxMedianSupportOverlap;
        telemetry.avgMedianSupportOverlap = computeAverage(medianSupportOverlapSum, telemetry.candidatesEvaluatedAgainstMasks);

        return filteredCandidates;
    }

    /**
     * Paints detected-object footprints into a boolean mask, with optional circular dilation.
     */
    private static boolean[][] buildObjectMask(
            List<SourceExtractor.DetectedObject> objects,
            int sensorWidth,
            int sensorHeight,
            int dilationRadius
    ) {
        boolean[][] mask = new boolean[sensorHeight][sensorWidth];
        int effectiveRadius = Math.max(0, dilationRadius);

        for (SourceExtractor.DetectedObject obj : objects) {
            if (obj.rawPixels == null) {
                continue;
            }
            for (SourceExtractor.Pixel p : obj.rawPixels) {
                if (effectiveRadius == 0) {
                    if (p.x >= 0 && p.x < sensorWidth && p.y >= 0 && p.y < sensorHeight) {
                        mask[p.y][p.x] = true;
                    }
                    continue;
                }

                for (int dx = -effectiveRadius; dx <= effectiveRadius; dx++) {
                    for (int dy = -effectiveRadius; dy <= effectiveRadius; dy++) {
                        if (dx * dx + dy * dy > effectiveRadius * effectiveRadius) {
                            continue;
                        }
                        int mx = p.x + dx;
                        int my = p.y + dy;
                        if (mx >= 0 && mx < sensorWidth && my >= 0 && my < sensorHeight) {
                            mask[my][mx] = true;
                        }
                    }
                }
            }
        }

        return mask;
    }

    /**
     * Measures what fraction of an object's footprint overlaps a precomputed boolean mask.
     */
    private static double computeMaskOverlapFraction(SourceExtractor.DetectedObject obj, boolean[][] mask) {
        if (obj.rawPixels == null || obj.rawPixels.isEmpty()) {
            return 0.0;
        }

        int overlapCount = 0;
        int sensorHeight = mask.length;
        int sensorWidth = sensorHeight > 0 ? mask[0].length : 0;
        for (SourceExtractor.Pixel p : obj.rawPixels) {
            if (p.x >= 0 && p.x < sensorWidth && p.y >= 0 && p.y < sensorHeight && mask[p.y][p.x]) {
                overlapCount++;
            }
        }
        return (double) overlapCount / obj.rawPixels.size();
    }

    /**
     * Returns zero instead of NaN when a telemetry bucket had no contributing candidates.
     */
    private static double computeAverage(double sum, int count) {
        return count > 0 ? sum / count : 0.0;
    }

    /**
     * Additional slow-mover-only veto for tiny hooked residuals that still slip past the shared shape filters.
     * A trustworthy deep-stack slow mover should look like one compact elongated PSF:
     * straight centerline, decent fill, and no strong side bulges along the major axis.
     */
    private enum SlowMoverShapeRejectReason {
        NONE,
        TOO_SHORT,
        LOW_FILL,
        SPARSE_BINS,
        GAPPED_BINS,
        CURVED_CENTERLINE,
        BULGED_WIDTH
    }

    private static void incrementSlowMoverShapeRejectTelemetry(
            PipelineTelemetry.SlowMoverTelemetry telemetry,
            SlowMoverShapeRejectReason reason
    ) {
        switch (reason) {
            case TOO_SHORT -> telemetry.rejectedSlowMoverShapeTooShort++;
            case LOW_FILL -> telemetry.rejectedSlowMoverShapeLowFill++;
            case SPARSE_BINS -> telemetry.rejectedSlowMoverShapeSparseBins++;
            case GAPPED_BINS -> telemetry.rejectedSlowMoverShapeGappedBins++;
            case CURVED_CENTERLINE -> telemetry.rejectedSlowMoverShapeCurvedCenterline++;
            case BULGED_WIDTH -> telemetry.rejectedSlowMoverShapeBulgedWidth++;
            case NONE -> {
                // No telemetry increment needed when the shape is accepted.
            }
        }
    }

    private static SlowMoverShapeRejectReason evaluateSlowMoverSpecificShapeFilter(SourceExtractor.DetectedObject obj) {
        if (obj.rawPixels == null || obj.rawPixels.isEmpty()) {
            return SlowMoverShapeRejectReason.TOO_SHORT;
        }

        double centroidX = 0.0;
        double centroidY = 0.0;
        for (SourceExtractor.Pixel p : obj.rawPixels) {
            centroidX += p.x;
            centroidY += p.y;
        }
        centroidX /= obj.rawPixels.size();
        centroidY /= obj.rawPixels.size();

        double covXX = 0.0;
        double covYY = 0.0;
        double covXY = 0.0;
        for (SourceExtractor.Pixel p : obj.rawPixels) {
            double vx = p.x - centroidX;
            double vy = p.y - centroidY;
            covXX += vx * vx;
            covYY += vy * vy;
            covXY += vx * vy;
        }

        double axisAngle = 0.5 * Math.atan2(2.0 * covXY, covXX - covYY);
        double dx = Math.cos(axisAngle);
        double dy = Math.sin(axisAngle);

        double minPar = Double.MAX_VALUE;
        double maxPar = -Double.MAX_VALUE;
        double minPerp = Double.MAX_VALUE;
        double maxPerp = -Double.MAX_VALUE;

        for (SourceExtractor.Pixel p : obj.rawPixels) {
            double vx = p.x - centroidX;
            double vy = p.y - centroidY;
            double par = vx * dx + vy * dy;
            double perp = -vx * dy + vy * dx;

            if (par < minPar) minPar = par;
            if (par > maxPar) maxPar = par;
            if (perp < minPerp) minPerp = perp;
            if (perp > maxPerp) maxPerp = perp;
        }

        double length = maxPar - minPar + 1.0;
        double width = maxPerp - minPerp + 1.0;
        double fillFactor = obj.rawPixels.size() / Math.max(1.0, length * width);

        // Extremely tiny footprints still do not carry enough geometry to be trustworthy.
        if (length < 3.5) {
            return SlowMoverShapeRejectReason.TOO_SHORT;
        }

        // Compact slow movers can still be real if they are dense and elongated, even when they are only a
        // few pixels long. Reserve the stricter longitudinal checks for longer candidates.
        if (length < 5.0) {
            if (fillFactor < 0.42) {
                return SlowMoverShapeRejectReason.LOW_FILL;
            }
            double compactAspect = length / Math.max(1.0, width);
            return compactAspect >= 1.20 ? SlowMoverShapeRejectReason.NONE : SlowMoverShapeRejectReason.TOO_SHORT;
        }

        // Longer slow movers should still fill their oriented bounding box reasonably well.
        if (fillFactor < 0.48) {
            return SlowMoverShapeRejectReason.LOW_FILL;
        }

        double compactAspect = length / Math.max(1.0, width);
        // Keep dense PSF-like masks scale-tolerant: upsampling the same footprint can preserve
        // the fill while stretching the measured major/minor-axis ratio slightly.
        if (fillFactor >= 0.60 && compactAspect >= 1.35 && compactAspect <= 3.20) {
            return SlowMoverShapeRejectReason.NONE;
        }

        int bins = Math.max(4, Math.min(8, (int) Math.round(length)));
        double binSpan = Math.max(1.0, length / bins);
        int[] binCounts = new int[bins];
        double[] perpSums = new double[bins];
        double[] binMinPerp = new double[bins];
        double[] binMaxPerp = new double[bins];
        for (int i = 0; i < bins; i++) {
            binMinPerp[i] = Double.MAX_VALUE;
            binMaxPerp[i] = -Double.MAX_VALUE;
        }

        for (SourceExtractor.Pixel p : obj.rawPixels) {
            double vx = p.x - centroidX;
            double vy = p.y - centroidY;
            double par = vx * dx + vy * dy;
            double perp = -vx * dy + vy * dx;

            int bin = (int) ((par - minPar) / binSpan);
            if (bin < 0) bin = 0;
            if (bin >= bins) bin = bins - 1;

            binCounts[bin]++;
            perpSums[bin] += perp;
            if (perp < binMinPerp[bin]) binMinPerp[bin] = perp;
            if (perp > binMaxPerp[bin]) binMaxPerp[bin] = perp;
        }

        int firstOccupied = -1;
        int lastOccupied = -1;
        int occupiedBins = 0;
        for (int i = 0; i < bins; i++) {
            if (binCounts[i] > 0) {
                if (firstOccupied == -1) {
                    firstOccupied = i;
                }
                lastOccupied = i;
                occupiedBins++;
            }
        }

        if (occupiedBins < Math.max(3, bins - 3)) {
            return SlowMoverShapeRejectReason.SPARSE_BINS;
        }

        for (int i = firstOccupied + 1; i < lastOccupied; i++) {
            if (binCounts[i] == 0) {
                return SlowMoverShapeRejectReason.GAPPED_BINS;
            }
        }

        double minPerpMean = Double.MAX_VALUE;
        double maxPerpMean = -Double.MAX_VALUE;
        double maxPerpJump = 0.0;
        double minBinWidth = Double.MAX_VALUE;
        double maxBinWidth = -Double.MAX_VALUE;
        double maxBinWidthJump = 0.0;
        int widestBin = -1;
        double previousPerpMean = 0.0;
        double previousBinWidth = 0.0;
        boolean hasPrevious = false;

        for (int i = 0; i < bins; i++) {
            if (binCounts[i] == 0) {
                continue;
            }
            double perpMean = perpSums[i] / binCounts[i];
            double binWidth = binMaxPerp[i] - binMinPerp[i] + 1.0;
            if (perpMean < minPerpMean) minPerpMean = perpMean;
            if (perpMean > maxPerpMean) maxPerpMean = perpMean;
            if (binWidth < minBinWidth) minBinWidth = binWidth;
            if (binWidth > maxBinWidth) {
                maxBinWidth = binWidth;
                widestBin = i;
            }
            if (hasPrevious) {
                double jump = Math.abs(perpMean - previousPerpMean);
                if (jump > maxPerpJump) {
                    maxPerpJump = jump;
                }
                double widthJump = Math.abs(binWidth - previousBinWidth);
                if (widthJump > maxBinWidthJump) {
                    maxBinWidthJump = widthJump;
                }
            }
            previousPerpMean = perpMean;
            previousBinWidth = binWidth;
            hasPrevious = true;
        }

        double centerlineExcursion = maxPerpMean - minPerpMean;
        double allowedExcursion = Math.max(1.20, width * 0.80);
        double allowedJump = Math.max(1.00, width * 0.65);
        double allowedMaxBinWidth = Math.max(3.0, minBinWidth * 2.4);
        double allowedBinWidthJump = Math.max(1.45, width * 0.60);
        boolean endHeavyBulge = widestBin != -1
                && (widestBin - firstOccupied <= 1 || lastOccupied - widestBin <= 1)
                && maxBinWidth > Math.max(2.5, minBinWidth * 1.8);

        if (centerlineExcursion > allowedExcursion || maxPerpJump > allowedJump) {
            return SlowMoverShapeRejectReason.CURVED_CENTERLINE;
        }
        if (maxBinWidth > allowedMaxBinWidth
                || maxBinWidthJump > allowedBinWidthJump
                || endHeavyBulge) {
            return SlowMoverShapeRejectReason.BULGED_WIDTH;
        }
        return SlowMoverShapeRejectReason.NONE;
    }

    /**
     * Formats overlap fractions as percentages for the debug report.
     */
    private static String formatOverlapPercentages(List<Double> overlaps) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < overlaps.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(String.format("%.1f%%", overlaps.get(i) * 100.0));
        }
        builder.append(']');
        return builder.toString();
    }
}
