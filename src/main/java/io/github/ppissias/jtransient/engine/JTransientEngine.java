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
import io.github.ppissias.jtransient.core.FrameDriftAnalyzer;
import io.github.ppissias.jtransient.core.MasterReferenceAnalyzer;
import io.github.ppissias.jtransient.core.MasterMapGenerator;
import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.core.ResidualTransientAnalyzer;
import io.github.ppissias.jtransient.core.SlowMoverAnalysis;
import io.github.ppissias.jtransient.core.SlowMoverAnalyzer;
import io.github.ppissias.jtransient.core.SlowMoverCandidateResult;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;
import io.github.ppissias.jtransient.photometry.VariableStarAnalysis;
import io.github.ppissias.jtransient.photometry.VariableStarAnalyzer;
import io.github.ppissias.jtransient.quality.FrameQualityAnalyzer;
import io.github.ppissias.jtransient.quality.SessionEvaluator;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates the full JTransient processing pipeline from extraction through track linking.
 *
 * <p>The engine owns an internal executor for concurrent frame processing. Reuse the same
 * instance across runs when practical, and call {@link #shutdown()} when the application no
 * longer needs it.</p>
 */
public class JTransientEngine {

    /** Global debug switch shared by the core library. */
    public static boolean DEBUG = false;

    // Internal thread pool for the library
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * Creates a new engine instance backed by its own worker pool.
     */
    public JTransientEngine() {
    }

    /**
     * Internal concurrent work product for one frame.
     */
    private static final class FrameExtractionResult {
        public final int frameIndex;
        public final SourceExtractor.ExtractionResult extractionResult;
        public final FrameQualityAnalyzer.FrameMetrics metrics;

        private FrameExtractionResult(int frameIndex,
                                      SourceExtractor.ExtractionResult extractionResult,
                                      FrameQualityAnalyzer.FrameMetrics metrics) {
            this.frameIndex = frameIndex;
            this.extractionResult = extractionResult;
            this.metrics = metrics;
        }
    }

    /**
     * Generates the Master Stack independently so it can be reused across iterative pipeline runs.
     * Highly optimized: Skips transient extraction and only runs quality analysis to drop outliers.
     *
     * <p>The supplied {@code inputFrames} list is sorted in place by {@link ImageFrame#sequenceIndex}
     * before processing.</p>
     *
     * @param inputFrames frames to evaluate and stack
     * @param config pipeline configuration
     * @param listener optional progress listener
     * @return median master stack built from the retained frames
     * @throws Exception if frame evaluation or stack generation fails
     */
    public short[][] generateMasterStack(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener) throws Exception {
        return generateMasterStackWithDetails(inputFrames, config, listener).masterStack;
    }

    /** A master stack and the frames it was built from. */
    public static final class MasterStackResult {
        /** Median master stack of the kept frames. */
        public final short[][] masterStack;
        /** Sequence indices of the frames in the stack. */
        public final List<Integer> keptFrames;
        /** Frames left out, with the reason, as in the pipeline telemetry. */
        public final List<PipelineTelemetry.FrameRejectionStat> rejectedFrames;

        MasterStackResult(short[][] masterStack, List<Integer> keptFrames, List<PipelineTelemetry.FrameRejectionStat> rejectedFrames) {
            this.masterStack = masterStack;
            this.keptFrames = keptFrames;
            this.rejectedFrames = rejectedFrames;
        }
    }

    /**
     * Builds the master stack exactly as the pipeline does: the same drift analysis, quality metrics and frame
     * selection ({@link #rejectUnusableFrames}), then the median of the kept frames. The caller's configuration is
     * not changed (the pipeline may raise the void radius for its own run).
     */
    public MasterStackResult generateMasterStackWithDetails(List<ImageFrame> inputFrames, DetectionConfig config,
                                                            TransientEngineProgressListener listener) throws Exception {
        if (listener != null) {
            listener.onProgressUpdate(0, "Evaluating frames for Master Stack...");
        }

        if (DEBUG) {
            System.out.println("\n--- JTRANSIENT: PRE-COMPUTING MASTER STACK ---");
        }

        // The drift analysis may raise the void radius, which the quality metrics then use, as in the pipeline.
        DetectionConfig stageConfig = config.clone();
        PipelineTelemetry telemetry = new PipelineTelemetry();
        inputFrames.sort(Comparator.comparingInt(f -> f.sequenceIndex));
        analyzeDitherAndDrift(inputFrames, stageConfig, null, telemetry);

        List<Callable<FrameExtractionResult>> tasks = new ArrayList<>();

        int totalFrames = inputFrames.size();
        AtomicInteger framesCompleted = new AtomicInteger(0);

        for (ImageFrame frame : inputFrames) {
            tasks.add(() -> {
                // We only need quality metrics to drop outliers. We skip SourceExtractor to save massive CPU time!
                FrameQualityAnalyzer.FrameMetrics metrics = FrameQualityAnalyzer.evaluateFrame(frame.pixelData, stageConfig);
                metrics.filename = frame.filename;

                synchronized (framesCompleted) { // keeps the reported progress in order across threads
                    int completed = framesCompleted.incrementAndGet();
                    if (listener != null) {
                        int progress = (int) ((completed / (double) totalFrames) * 50.0);
                        listener.onProgressUpdate(progress, "Evaluating frame " + completed + " of " + totalFrames);
                    }
                }

                return new FrameExtractionResult(frame.sequenceIndex, null, metrics);
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

        rejectUnusableFrames(sessionMetrics, inputFrames, telemetry.driftExcludedFrames, stageConfig);

        List<ImageFrame> cleanFrames = new ArrayList<>();
        List<Integer> keptFrames = new ArrayList<>();
        List<PipelineTelemetry.FrameRejectionStat> rejectedFrames = new ArrayList<>();
        for (int i = 0; i < completedResults.size(); i++) {
            FrameQualityAnalyzer.FrameMetrics metrics = sessionMetrics.get(i);
            if (!metrics.isRejected) {
                cleanFrames.add(inputFrames.get(i));
                keptFrames.add(inputFrames.get(i).sequenceIndex);
            } else {
                PipelineTelemetry.FrameRejectionStat rejected = new PipelineTelemetry.FrameRejectionStat();
                rejected.frameIndex = inputFrames.get(i).sequenceIndex;
                rejected.filename = metrics.filename;
                rejected.reason = metrics.rejectionReason;
                rejected.medianEccentricity = metrics.medianEccentricity;
                rejected.brightStarMedianEccentricity = metrics.brightStarMedianEccentricity;
                rejected.brightStarShapeStarCount = metrics.brightStarShapeStarCount;
                rejectedFrames.add(rejected);
            }
        }

        if (listener != null) {
            listener.onProgressUpdate(60, "Stacking " + cleanFrames.size() + " clean frames...");
        }

        return new MasterStackResult(MasterMapGenerator.createMedianMasterStack(cleanFrames), keptFrames, rejectedFrames);
    }

    /**
     * Entry point for the JTransient library.
     * Convenience wrapper that calculates the master stack automatically.
     *
     * <p>The supplied {@code inputFrames} list is sorted in place by {@link ImageFrame#sequenceIndex}
     * before processing.</p>
     *
     * @param inputFrames frames to process
     * @param config pipeline configuration
     * @param listener optional progress listener
     * @return full pipeline output bundle
     * @throws Exception if extraction, stacking, tracking, or residual analysis fails
     */
    public PipelineResult runPipeline(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener) throws Exception {
        return runPipeline(inputFrames, config, listener, null);
    }

    /**
     * Runs the pipeline up to detecting the transients (extracted objects) for all frames and does no further processing.
     * Generates a median master stack automatically to apply the Veto Mask.
     *
     * <p>The supplied {@code inputFrames} list is sorted in place by {@link ImageFrame#sequenceIndex}
     * before processing.</p>
     *
     * @param inputFrames frames to process
     * @param config pipeline configuration
     * @param listener optional progress listener
     * @return per-frame transient exports after the stationary-star veto
     * @throws Exception if extraction, master-stack generation, or vetoing fails
     */
    public List<FrameTransients> detectTransients(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener) throws Exception {
        return detectTransients(inputFrames, config, listener, null);
    }

    /**
     * Runs the pipeline up to detecting the transients for all frames and does no further processing.
     * Uses the provided master stack to successfully apply the Veto Mask.
     *
     * <p>The supplied {@code inputFrames} list is sorted in place by {@link ImageFrame#sequenceIndex}
     * before processing.</p>
     *
     * @param inputFrames frames to process
     * @param config pipeline configuration
     * @param listener optional progress listener
     * @param providedMasterStack optional precomputed median master stack
     * @return per-frame transient exports after the stationary-star veto
     * @throws Exception if extraction or vetoing fails
     */
    public List<FrameTransients> detectTransients(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener, short[][] providedMasterStack) throws Exception {
        ExtractedFramesContext context = extractSourcesFromFrames(inputFrames, config, listener);

        if (providedMasterStack != null) {
            if (listener != null) listener.onProgressUpdate(45, "Using pre-computed Master Stack for Veto Mask...");
        } else {
            if (listener != null) listener.onProgressUpdate(45, "Generating Median Master Stack for Veto Mask...");
        }

        if (listener != null) listener.onProgressUpdate(48, "Extracting Master Star Map...");

        MasterReferenceAnalyzer.MasterReferenceAnalysis masterReference = MasterReferenceAnalyzer.analyze(
                context.cleanFrames,
                providedMasterStack,
                config
        );
        short[][] masterStackData = masterReference.masterStackData;
        List<SourceExtractor.DetectedObject> masterStars = masterReference.masterStars;
        int sensorHeight = masterReference.sensorHeight;
        int sensorWidth = masterReference.sensorWidth;

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
    private ExtractedFramesContext extractSourcesFromFrames(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener) throws Exception {
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
        List<SourceExtractor.Pixel> driftPoints = analyzeDitherAndDrift(inputFrames, config, listener, telemetry);

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

                // Safely update progress from multiple threads (Mapping Phase 1 to 0-40% of the total bar)
                synchronized (framesCompleted) { // keeps the reported progress in order across threads
                    int completed = framesCompleted.incrementAndGet();
                    if (listener != null) {
                        int progress = (int) ((completed / (double) totalFrames) * 40.0);
                        listener.onProgressUpdate(progress, "Extracting features from frame " + completed + " of " + totalFrames);
                    }
                }

                return new FrameExtractionResult(frame.sequenceIndex, extResult, metrics);
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
        SessionEvaluator.SessionThresholds sessionThresholds =
                rejectUnusableFrames(sessionMetrics, inputFrames, telemetry.driftExcludedFrames, config);
        telemetry.qualityThresholds.available = sessionThresholds.available;
        telemetry.qualityThresholds.minAllowedStarCount = sessionThresholds.minAllowedStarCount;
        telemetry.qualityThresholds.maxAllowedFwhm = sessionThresholds.maxAllowedFwhm;
        telemetry.qualityThresholds.maxAllowedEccentricity = sessionThresholds.maxAllowedEccentricity;
        telemetry.qualityThresholds.maxAllowedBrightStarEccentricity = sessionThresholds.maxAllowedBrightStarEccentricity;
        telemetry.qualityThresholds.backgroundMedianBaseline = sessionThresholds.backgroundMedianBaseline;
        telemetry.qualityThresholds.maxAllowedBackgroundDeviation = sessionThresholds.maxAllowedBackgroundDeviation;
        telemetry.qualityThresholds.minAllowedBackgroundMedian = sessionThresholds.minAllowedBackgroundMedian;
        telemetry.qualityThresholds.maxAllowedBackgroundMedian = sessionThresholds.maxAllowedBackgroundMedian;

        List<SourceExtractor.ExtractionResult> cleanFramesData = new ArrayList<>();
        List<ImageFrame> cleanFrames = new ArrayList<>(); // Track the raw images that passed the quality check
        List<FrameQualityAnalyzer.FrameMetrics> cleanFrameMetrics = new ArrayList<>();

        for (int i = 0; i < rawExtractedFrames.size(); i++) {
            FrameQualityAnalyzer.FrameMetrics metrics = sessionMetrics.get(i);
            PipelineTelemetry.FrameQualityStat qualityStat = new PipelineTelemetry.FrameQualityStat();
            qualityStat.frameIndex = inputFrames.get(i).sequenceIndex;
            qualityStat.filename = metrics.filename;
            qualityStat.backgroundMedian = metrics.backgroundMedian;
            qualityStat.backgroundNoise = metrics.backgroundNoise;
            qualityStat.medianFWHM = metrics.medianFWHM;
            qualityStat.medianEccentricity = metrics.medianEccentricity;
            qualityStat.brightStarMedianEccentricity = metrics.brightStarMedianEccentricity;
            qualityStat.starCount = metrics.starCount;
            qualityStat.usableShapeStarCount = metrics.usableShapeStarCount;
            qualityStat.brightStarShapeStarCount = metrics.brightStarShapeStarCount;
            qualityStat.fwhmStarCount = metrics.fwhmStarCount;
            qualityStat.rejected = metrics.isRejected;
            qualityStat.rejectionReason = metrics.rejectionReason;
            telemetry.frameQualityStats.add(qualityStat);

            if (metrics.isRejected) {
                telemetry.totalFramesRejected++;
                PipelineTelemetry.FrameRejectionStat rejStat = new PipelineTelemetry.FrameRejectionStat();
                rejStat.frameIndex = inputFrames.get(i).sequenceIndex;
                rejStat.filename = metrics.filename;
                rejStat.reason = metrics.rejectionReason;
                rejStat.medianEccentricity = metrics.medianEccentricity;
                rejStat.brightStarMedianEccentricity = metrics.brightStarMedianEccentricity;
                rejStat.brightStarShapeStarCount = metrics.brightStarShapeStarCount;
                telemetry.rejectedFrames.add(rejStat);
            } else {
                telemetry.totalFramesKept++;
                cleanFramesData.add(rawExtractedFrames.get(i));
                cleanFrames.add(inputFrames.get(i)); // Keep the actual frame for the Master Stack
                cleanFrameMetrics.add(metrics);
            }
        }

        return new ExtractedFramesContext(
                cleanFramesData,
                cleanFrames,
                cleanFrameMetrics,
                telemetry,
                startTime,
                driftPoints
        );
    }

    /**
     * The frame selection of the quality stage, shared by the full pipeline and {@link #generateMasterStack}: marks
     * the session outliers (quality settings) and the blank or failed-registration frames found by the drift
     * analysis as rejected.
     *
     * @param sessionMetrics per-frame quality metrics, in the order of {@code inputFrames}; updated in place
     * @param inputFrames the frames, sorted by sequence index
     * @param driftExcludedFrames sequence indices of blank or failed-registration frames
     * @return the session thresholds used for the outlier rejection
     */
    private static SessionEvaluator.SessionThresholds rejectUnusableFrames(List<FrameQualityAnalyzer.FrameMetrics> sessionMetrics,
                                                                          List<ImageFrame> inputFrames,
                                                                          Collection<Integer> driftExcludedFrames,
                                                                          DetectionConfig config) {
        SessionEvaluator.SessionThresholds thresholds = SessionEvaluator.rejectOutlierFrames(sessionMetrics, config);
        for (int i = 0; i < sessionMetrics.size(); i++) {
            FrameQualityAnalyzer.FrameMetrics metrics = sessionMetrics.get(i);
            if (!metrics.isRejected && driftExcludedFrames.contains(inputFrames.get(i).sequenceIndex)) {
                // A partly blank frame can keep enough stars to pass the session statistics, but its sky does
                // not match the other frames.
                metrics.isRejected = true;
                metrics.rejectionReason = "Blank or failed registration (less than half of the pixels hold image data)";
            }
        }
        return thresholds;
    }

    /**
     * Analyzes sequence dither and corner drift by measuring the valid-image bounds per frame.
     * Applies any required void-radius increase at the orchestration layer after core drift analysis runs.
     *
     * @param inputFrames frames to inspect
     * @param config pipeline configuration that may be updated with a safer void radius
     * @param listener optional progress listener
     * @param telemetry telemetry that records the frames left out of the drift analysis
     * @return A list of translation vectors (dx, dy) representing the relative movement per frame.
     */
    private List<SourceExtractor.Pixel> analyzeDitherAndDrift(List<ImageFrame> inputFrames, DetectionConfig config,
                                                              TransientEngineProgressListener listener,
                                                              PipelineTelemetry telemetry) {
        if (listener != null) {
            listener.onProgressUpdate(0, "Analyzing sequence dither and corner drift...");
        }

        FrameDriftAnalyzer.DriftAnalysisResult driftAnalysis = FrameDriftAnalyzer.analyze(
                inputFrames,
                config.voidProximityRadius
        );

        telemetry.driftExcludedFrames.addAll(driftAnalysis.failedRegistrationFrames);
        if (DEBUG && !driftAnalysis.failedRegistrationFrames.isEmpty()) {
            System.out.println("DEBUG: Dither Diagnostics left out blank or failed-registration frames "
                    + "(less than half the pixels valid): " + driftAnalysis.failedRegistrationFrames);
        }
        if (driftAnalysis.recommendedVoidProximityRadius > config.voidProximityRadius) {
            if (DEBUG) {
                System.out.println(
                        "DEBUG: Dither Diagnostics found corner drift of "
                                + driftAnalysis.maxPaddingPixels
                                + "px. Overriding config.voidProximityRadius to "
                                + driftAnalysis.recommendedVoidProximityRadius
                );
            }
            config.voidProximityRadius = driftAnalysis.recommendedVoidProximityRadius;
        }
        return new ArrayList<>(driftAnalysis.driftPoints);
    }

    /**
     * Entry point for the JTransient library.
     * Allows passing a pre-computed master stack to bypass the heavy stacking phase during iterative runs.
     *
     * <p>The supplied {@code inputFrames} list is sorted in place by {@link ImageFrame#sequenceIndex}
     * before processing.</p>
     *
     * @param inputFrames frames to process
     * @param config pipeline configuration
     * @param listener optional progress listener
     * @param providedMasterStack optional precomputed median master stack
     * @return full pipeline output bundle
     * @throws Exception if extraction, tracking, or residual analysis fails
     */
    public PipelineResult runPipeline(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener, short[][] providedMasterStack) throws Exception {
        ExtractedFramesContext context = extractSourcesFromFrames(inputFrames, config, listener);
        long startTime = context.startTime;
        PipelineTelemetry telemetry = context.telemetry;
        List<SourceExtractor.ExtractionResult> cleanFramesData = context.cleanFramesData;
        List<ImageFrame> cleanFrames = context.cleanFrames;

        // =================================================================
        // PHASE 0 (Generate Deep Master Star Map)
        // =================================================================
        if (providedMasterStack != null) {
            if (DEBUG) {
                System.out.println("\n--- JTRANSIENT: PHASE 0 (Using Pre-Computed Master Stack) ---");
            }
            if (listener != null) {
                listener.onProgressUpdate(45, "Using pre-computed Master Stack...");
            }
        } else {
            if (DEBUG) {
                System.out.println("\n--- JTRANSIENT: PHASE 0 (Master Map Generation) ---");
            }
            if (listener != null) {
                listener.onProgressUpdate(45, "Generating Median Master Stack...");
            }
        }

        if (DEBUG) {
            System.out.printf("DEBUG: Master Map Config -> Master Sigma: %.2f | Master Grow: %.2f | Master MinPix: %d%n",
                    config.masterSigmaMultiplier, MasterReferenceAnalyzer.effectiveMasterGrowSigma(config), config.masterMinDetectionPixels);
        }

        if (listener != null) {
            listener.onProgressUpdate(48, "Extracting Master Star Map...");
        }

        MasterReferenceAnalyzer.MasterReferenceAnalysis masterReference = MasterReferenceAnalyzer.analyze(
                cleanFrames,
                providedMasterStack,
                config
        );
        short[][] masterStackData = masterReference.masterStackData;
        List<SourceExtractor.DetectedObject> masterStars = masterReference.masterStars;
        int sensorHeight = masterReference.sensorHeight;
        int sensorWidth = masterReference.sensorWidth;

        if (DEBUG) {
            System.out.println("DEBUG: Master Stack generated. Found " + masterStars.size() + " deep stationary objects.");
        }

        // =================================================================
        // PHASE 0.5 (Maximum-Stack Slow-Mover Candidate Detection)
        // =================================================================

        // Build once for both the detector and PipelineResult.maximumStackData.
        short[][] maximumStackData = MasterMapGenerator.createMaximumMasterStack(cleanFrames);
        SlowMoverAnalysis slowMoverAnalysis = SlowMoverAnalysis.empty();
        short[][] slowMoverStackData = null;
        boolean[][] slowMoverMedianVetoMask = null;
        List<SourceExtractor.DetectedObject> slowMoverCandidates = new ArrayList<>();
        PipelineTelemetry.SlowMoverTelemetry smTelemetry = null;

        if (config.enableSlowMoverDetection) {
            if (DEBUG) {
                System.out.println("\n--- JTRANSIENT: PHASE 0.5 (Slow Mover Detection) ---");
            }
            if (listener != null) {
                listener.onProgressUpdate(49, "Analyzing Maximum Stack for Slow Movers...");
            }

            slowMoverAnalysis = SlowMoverAnalyzer.analyze(
                    maximumStackData,
                    masterStackData,
                    cleanFrames,
                    config
            );
            slowMoverStackData = slowMoverAnalysis.slowMoverStackData;
            slowMoverMedianVetoMask = slowMoverAnalysis.medianVetoMask;
            slowMoverCandidates = new ArrayList<>(slowMoverAnalysis.candidates.size());
            for (SlowMoverCandidateResult candidate : slowMoverAnalysis.candidates) {
                slowMoverCandidates.add(candidate.object);
            }
            smTelemetry = slowMoverAnalysis.telemetry.toLegacyTelemetry(slowMoverAnalysis.candidates);

            if (DEBUG) {
                System.out.printf(
                        "DEBUG: Slow Mover Shape -> Axis Ratio: %.2f-%.2f | Min Fill: %.2f | Avg Accepted Axis Ratio: %.2f%n",
                        smTelemetry.minAxisRatioThreshold,
                        smTelemetry.maxAxisRatioThreshold,
                        smTelemetry.minFillFactorThreshold,
                        smTelemetry.avgCandidateAxisRatio
                );
                System.out.printf(
                        "DEBUG: Slow Mover Filters -> Raw: %d | LowPixels: %d | LowAxis: %d | HighAxis: %d | LowFill: %d | MaskStage: %d | LowMedianOverlap: %d | HighMedianOverlap: %d | Final: %d%n",
                        smTelemetry.rawCandidatesExtracted,
                        smTelemetry.rejectedBelowMinPixels,
                        smTelemetry.rejectedBelowMinAxisRatio,
                        smTelemetry.rejectedAboveMaxAxisRatio,
                        smTelemetry.rejectedLowFillFactor,
                        smTelemetry.evaluatedAgainstMedianMask,
                        smTelemetry.rejectedLowMedianSupport,
                        smTelemetry.rejectedHighMedianSupport,
                        smTelemetry.candidatesDetected
                );
                System.out.printf(
                        "DEBUG: Slow Mover Mask -> MinOverlap: %.3f | MaxOverlap: %.3f | AvgOverlap: %.3f | AvgEstimatedMotion: %.2f px%n",
                        smTelemetry.medianSupportOverlapThreshold,
                        smTelemetry.medianSupportMaxOverlapThreshold,
                        smTelemetry.avgMedianMaskOverlap,
                        smTelemetry.avgEstimatedMotionPixels
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
        // We pass a synthetic listener to the Linker. It maps the Linker's 0-100% to the Engine's 50-90% range;
        // residual analysis and photometry follow, so the progress never goes back.
        TransientEngineProgressListener trackingProxyListener = null;
        if (listener != null) {
            trackingProxyListener = (percentage, message) -> {
                int scaledProgress = 50 + (int) (percentage * 0.4);
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
            listener.onProgressUpdate(92, "Analyzing residual transients...");
        }

        ResidualTransientAnalysis residualTransientAnalysis = ResidualTransientAnalyzer.analyze(
                trackResult.unclassifiedTransients,
                config
        );

        // =================================================================
        // PHASE 5 (Variable-Star Photometry)
        // =================================================================
        VariableStarAnalysis variableStarAnalysis = VariableStarAnalysis.empty();
        if (config.enableVariableStarDetection) {
            if (DEBUG) {
                System.out.println("\n--- JTRANSIENT: PHASE 5 (Variable-Star Photometry) ---");
            }
            if (listener != null) {
                listener.onProgressUpdate(93, "Measuring stationary-star photometry...");
            }
            VariableStarAnalyzer.Input photometryInput = new VariableStarAnalyzer.Input();
            photometryInput.frames = cleanFrames;
            photometryInput.frameMetrics = context.cleanFrameMetrics;
            photometryInput.frameDetections = cleanFramesObjects;
            photometryInput.masterStars = masterStars;
            photometryInput.masterStack = masterStackData;
            photometryInput.masterStarMask = trackResult.masterVetoMask;
            photometryInput.tracks = trackResult.tracks;
            photometryInput.slowMoverCandidates = slowMoverCandidates;
            if (listener != null) {
                // Photometry 0-100% maps to 93-99%.
                photometryInput.progress = (percentage, message) -> listener.onProgressUpdate(93 + (int) (percentage * 0.06), message);
            }
            variableStarAnalysis = VariableStarAnalyzer.analyze(photometryInput, config, executor);
            telemetry.photometryTelemetry = variableStarAnalysis.telemetry;
        }

        if (listener != null) {
            listener.onProgressUpdate(100, "Processing Complete!");
        }

        // --- FINAL POST-PROCESSING ---
        if (listener != null) {
            listener.onProgressUpdate(100, "Maximum Stack generation complete.");
        }

        return new PipelineResult(trackResult.tracks, telemetry, masterStackData, masterStars,
                slowMoverAnalysis, slowMoverStackData, slowMoverMedianVetoMask, slowMoverCandidates, trackResult.anomalies,
                trackResult.allTransients, trackResult.unclassifiedTransients,
                residualTransientAnalysis, trackResult.masterVetoMask, context.driftPoints,
                maximumStackData, variableStarAnalysis);
    }

    /**
     * Gracefully shuts down the internal thread pool.
     * Call this when your application is closing to prevent memory leaks.
     */
    public void shutdown() {
        executor.shutdown();
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
