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

    // Internal thread pool for the library
    private final ExecutorService executor = Executors.newCachedThreadPool();

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

                int completed = framesCompleted.incrementAndGet();
                if (listener != null) {
                    int progress = (int) ((completed / (double) totalFrames) * 50.0);
                    listener.onProgressUpdate(progress, "Evaluating frame " + completed + " of " + totalFrames);
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

                // Safely update progress from multiple threads (Mapping Phase 1 to 0-40% of the total bar)
                int completed = framesCompleted.incrementAndGet();
                if (listener != null) {
                    int progress = (int) ((completed / (double) totalFrames) * 40.0);
                    listener.onProgressUpdate(progress, "Extracting features from frame " + completed + " of " + totalFrames);
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

        return new ExtractedFramesContext(
                cleanFramesData,
                cleanFrames,
                telemetry,
                startTime,
                driftPoints
        );
    }

    /**
     * Analyzes sequence dither and corner drift by measuring the valid-image bounds per frame.
     * Applies any required void-radius increase at the orchestration layer after core drift analysis runs.
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

        FrameDriftAnalyzer.DriftAnalysisResult driftAnalysis = FrameDriftAnalyzer.analyze(
                inputFrames,
                config.voidProximityRadius
        );

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
     * @param inputFrames frames to process
     * @param config pipeline configuration
     * @param listener optional progress listener
     * @param providedMasterStack optional precomputed median master stack
     * @return full pipeline output bundle
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
                    config.masterSigmaMultiplier, config.masterSigmaMultiplier, config.masterMinDetectionPixels);
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
        // PHASE 0.5 (Slow Mover Detection
        // =================================================================

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
                listener.onProgressUpdate(49, "Generating Slow Mover Master Stack...");
            }

            slowMoverAnalysis = SlowMoverAnalyzer.analyze(
                    cleanFrames,
                    masterStackData,
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
                        "DEBUG: Slow Mover Stats -> Median Elong: %.2f | MAD: %.2f | Dynamic Threshold: %.2f%n",
                        smTelemetry.medianElongation,
                        smTelemetry.madElongation,
                        smTelemetry.dynamicElongationThreshold
                );
                System.out.printf(
                        "DEBUG: Slow Mover Filters -> Raw: %d | AboveElong: %d | MaskStage: %d | Irregular: %d | Binary: %d | SlowMoverShape: %d | LowMedianSupport: %d | HighMedianSupport: %d | LowResidualFootprint: %d | Final: %d%n",
                        smTelemetry.rawCandidatesExtracted,
                        smTelemetry.candidatesAboveElongationThreshold,
                        smTelemetry.candidatesEvaluatedAgainstMasks,
                        smTelemetry.rejectedIrregularShape,
                        smTelemetry.rejectedBinaryAnomaly,
                        smTelemetry.rejectedSlowMoverShape,
                        smTelemetry.rejectedLowMedianSupport,
                        smTelemetry.rejectedHighMedianSupport,
                        smTelemetry.rejectedLowResidualFootprintSupport,
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
                        "DEBUG: Slow Mover Signals -> MinSupport: %.3f | MaxSupport: %.3f | AvgMedianSupportOverlap: %.3f | MinResidualFootprint: %.3f | AvgResidualFootprint: %.3f%n",
                        smTelemetry.medianSupportOverlapThreshold,
                        smTelemetry.medianSupportMaxOverlapThreshold,
                        smTelemetry.avgMedianSupportOverlap,
                        smTelemetry.residualFootprintMinFluxFractionThreshold,
                        smTelemetry.avgResidualFootprintFluxFraction
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
                slowMoverAnalysis, slowMoverStackData, slowMoverMedianVetoMask, slowMoverCandidates, trackResult.anomalies,
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
