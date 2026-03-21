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

public class JTransientEngine {

    // --- NEW: Centralized Debug Flag for the entire JTransient library ---
    public static boolean DEBUG = false;

    // Internal thread pool for the library
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private static class FrameExtractionResult {
        public int frameIndex;
        public List<SourceExtractor.DetectedObject> extractedObjects;
        public FrameQualityAnalyzer.FrameMetrics metrics;
    }

    public static class TransientExtractionContext {
        public List<List<SourceExtractor.DetectedObject>> cleanFramesData;
        public List<ImageFrame> cleanFrames;
        public PipelineTelemetry telemetry;
        public long startTime;
    }

    public static class FrameTransients {
        public final String filename;
        public final List<SourceExtractor.DetectedObject> transients;

        public FrameTransients(String filename, List<SourceExtractor.DetectedObject> transients) {
            this.filename = filename;
            this.transients = transients;
        }
    }

    /**
     * Generates the Master Stack independently so it can be reused across iterative pipeline runs.
     * Highly optimized: Skips transient extraction and only runs quality analysis to drop outliers.
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
                metrics.filename = frame.identifier;

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
     * The single entry point for the JTransient library.
     * Convenience wrapper that calculates the master stack automatically.
     */
    public PipelineResult runPipeline(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener) throws Exception {
        return runPipeline(inputFrames, config, listener, null);
    }

    /**
     * Runs the pipeline up to detecting the transients (extracted objects) for all frames and does no further processing.
     * Generates a median master stack automatically to apply the Veto Mask.
     * @return A list of objects containing the filename and its actual transients that survived the Master Veto Mask.
     */
    public List<FrameTransients> detectTransients(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener) throws Exception {
        return detectTransients(inputFrames, config, listener, null);
    }

    /**
     * Runs the pipeline up to detecting the transients for all frames and does no further processing.
     * Uses the provided master stack to successfully apply the Veto Mask.
     * @return A list of objects containing the filename and its actual transients that survived the Master Veto Mask.
     */
    public List<FrameTransients> detectTransients(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener, short[][] providedMasterStack) throws Exception {
        TransientExtractionContext context = extractFrameTransientsContext(inputFrames, config, listener);
        
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
        double originalStreakElongation = config.streakMinElongation;

        config.edgeMarginPixels = 5;
        config.voidProximityRadius = 5;
        config.streakMinElongation = 999.0;

        List<SourceExtractor.DetectedObject> masterStars = SourceExtractor.extractSources(
                masterStackData, config.masterSigmaMultiplier, config.masterMinDetectionPixels, config);

        config.edgeMarginPixels = originalEdgeMargin;
        config.voidProximityRadius = originalVoidProximity;
        config.growSigmaMultiplier = originalGrowSigma;
        config.streakMinElongation = originalStreakElongation;

        TransientEngineProgressListener proxyListener = null;
        if (listener != null) {
            proxyListener = (percentage, message) -> listener.onProgressUpdate(60 + (int) (percentage * 0.40), message); // Scale 0-100 to 60-100
        }

        TrackLinker.TransientsFilterResult filterResult = TrackLinker.filterTransients(context.cleanFramesData, masterStars, config, proxyListener);
        
        if (listener != null) listener.onProgressUpdate(100, "Transient Extraction Complete!");

        List<FrameTransients> finalResult = new ArrayList<>();
        for (int i = 0; i < context.cleanFrames.size(); i++) {
            finalResult.add(new FrameTransients(
                    context.cleanFrames.get(i).identifier,
                    filterResult.mergedTransients.get(i)
            ));
        }
        return finalResult;
    }

    /**
     * Does exactly the same processing as the first phases of runPipeline up to detecting the transients,
     * returning the full context so it can be reused by runPipeline.
     */
    private TransientExtractionContext extractFrameTransientsContext(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener) throws Exception {
        long startTime = System.currentTimeMillis();
        PipelineTelemetry telemetry = new PipelineTelemetry();
        telemetry.totalFramesLoaded = inputFrames.size();

        if (listener != null) {
            listener.onProgressUpdate(0, "Initializing pipeline...");
        }

        if (DEBUG) {
            System.out.println("\n--- JTRANSIENT: PHASE 1 (Extraction) ---");
        }

        List<Callable<FrameExtractionResult>> tasks = new ArrayList<>();

        // Ensure input frames are sorted chronologically before processing
        inputFrames.sort(Comparator.comparingInt(f -> f.sequenceIndex));

        // --- SAFE CONCURRENT PROGRESS TRACKING ---
        int totalFrames = inputFrames.size();
        AtomicInteger framesCompleted = new AtomicInteger(0);

        for (ImageFrame frame : inputFrames) {
            tasks.add(() -> {
                // 1. Extract Sources (Passing config as the 4th argument)
                List<SourceExtractor.DetectedObject> objectsInFrame = SourceExtractor.extractSources(
                        frame.pixelData,
                        config.detectionSigmaMultiplier,
                        config.minDetectionPixels,
                        config
                );

                for (SourceExtractor.DetectedObject obj : objectsInFrame) {
                    obj.sourceFrameIndex = frame.sequenceIndex;
                    obj.sourceFilename = frame.identifier;
                    obj.timestamp = frame.timestamp;
                }

                // 2. Quality Metrics (Passing config down)
                FrameQualityAnalyzer.FrameMetrics metrics = FrameQualityAnalyzer.evaluateFrame(frame.pixelData, config);
                metrics.filename = frame.identifier;

                FrameExtractionResult result = new FrameExtractionResult();
                result.frameIndex = frame.sequenceIndex;
                result.extractedObjects = objectsInFrame;
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
        List<List<SourceExtractor.DetectedObject>> rawExtractedFrames = new ArrayList<>();
        List<FrameQualityAnalyzer.FrameMetrics> sessionMetrics = new ArrayList<>();

        for (FrameExtractionResult result : completedResults) {
            rawExtractedFrames.add(result.extractedObjects);
            sessionMetrics.add(result.metrics);

            telemetry.totalRawObjectsExtracted += result.extractedObjects.size();
            PipelineTelemetry.FrameExtractionStat stat = new PipelineTelemetry.FrameExtractionStat();
            stat.frameIndex = result.frameIndex;
            stat.filename = result.metrics.filename;
            stat.objectCount = result.extractedObjects.size();
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

        List<List<SourceExtractor.DetectedObject>> cleanFramesData = new ArrayList<>();
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

        TransientExtractionContext context = new TransientExtractionContext();
        context.cleanFramesData = cleanFramesData;
        context.cleanFrames = cleanFrames;
        context.telemetry = telemetry;
        context.startTime = startTime;

        return context;
    }

    /**
     * Iterative entry point for the JTransient library.
     * Allows passing a pre-computed master stack to bypass the heavy stacking phase during iterative runs.
     */
    public PipelineResult runPipeline(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener, short[][] providedMasterStack) throws Exception {
        TransientExtractionContext context = extractFrameTransientsContext(inputFrames, config, listener);
        long startTime = context.startTime;
        PipelineTelemetry telemetry = context.telemetry;
        List<List<SourceExtractor.DetectedObject>> cleanFramesData = context.cleanFramesData;
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
        double originalStreakElongation = config.streakMinElongation;

        config.edgeMarginPixels = 5;
        config.voidProximityRadius = 5;
        // Prevent optically distorted corner stars from being classified as "elongated noise" and rejected
        config.streakMinElongation = 999.0;

        // 2. Extract every stable star and galaxy from the deep stack
        List<SourceExtractor.DetectedObject> masterStars = SourceExtractor.extractSources(
                masterStackData,
                masterSigma,
                masterMinPix,
                config
        );

        // --- RESTORE ORIGINAL CONFIGURATION ---
        config.edgeMarginPixels = originalEdgeMargin;
        config.voidProximityRadius = originalVoidProximity;
        config.growSigmaMultiplier = originalGrowSigma;
        config.streakMinElongation = originalStreakElongation;

        if (DEBUG) {
            System.out.println("DEBUG: Master Stack generated. Found " + masterStars.size() + " deep stationary objects.");
        }

        // =================================================================
        // PHASE 0.5 (Slow Mover Detection)
        // =================================================================
        short[][] slowMoverStackData = null;
        List<SourceExtractor.DetectedObject> slowMoverCandidates = new ArrayList<>();

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
            );

            // Restore original config
            config.growSigmaMultiplier = origGrow;

            // Filter for true slow movers (must be elongated to prove they moved over time)
            for (SourceExtractor.DetectedObject obj : rawSlowMovers) {
                if (config.masterSlowMoverMinElongation <= 0 || obj.elongation >= config.masterSlowMoverMinElongation) {
                    
                    // Apply strict morphological filters to reject merged binary stars and stacking artifacts
                    boolean isIrregular = SourceExtractor.isIrregularStreakShape(obj);
                    boolean isBinary = SourceExtractor.isBinaryStarAnomaly(slowMoverStackData, obj);
                    
                    if (!isIrregular && !isBinary) {
                        slowMoverCandidates.add(obj);
                    }
                }
            }

            if (DEBUG) {
                System.out.println("DEBUG: Slow Mover analysis complete. Found " + slowMoverCandidates.size() + " highly elongated candidate(s).");
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

        // NOTE: Make sure your TrackLinker.findMovingObjects method signature is updated to accept the new listener!
        // (You might also need to pass sensorWidth and sensorHeight here if you kept the Phase 5 boundary check)
        TrackLinker.TrackingResult trackResult = TrackLinker.findMovingObjects(
                cleanFramesData,
                masterStars,
                config,
                trackingProxyListener
        );

        // Map the track results back to our main telemetry object
        telemetry.totalMovingTargetsFound = trackResult.tracks.size();
        telemetry.trackerTelemetry = trackResult.telemetry;

        telemetry.processingTimeMs = System.currentTimeMillis() - startTime;

        if (listener != null) {
            listener.onProgressUpdate(100, "Processing Complete!");
        }

        // Return the unified result with the new Master Data payloads!
        return new PipelineResult(trackResult.tracks, telemetry, masterStackData, masterStars, 
                                  slowMoverStackData, slowMoverCandidates, trackResult.allTransients);
    }

    /**
     * Gracefully shuts down the internal thread pool.
     * Call this when your application is closing to prevent memory leaks.
     */
    public void shutdown() {
        executor.shutdown();
    }
}