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

    /**
     * The single entry point for the JTransient library.
     */
    public PipelineResult runPipeline(List<ImageFrame> inputFrames, DetectionConfig config) throws Exception {
        long startTime = System.currentTimeMillis();
        PipelineTelemetry telemetry = new PipelineTelemetry();
        telemetry.totalFramesLoaded = inputFrames.size();

        if (DEBUG) {
            System.out.println("\n--- JTRANSIENT: PHASE 1 (Extraction) ---");
        }

        List<Callable<FrameExtractionResult>> tasks = new ArrayList<>();

        // Ensure input frames are sorted chronologically before processing
        inputFrames.sort(Comparator.comparingInt(f -> f.sequenceIndex));

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
                }

                // 2. Quality Metrics (Passing config down)
                FrameQualityAnalyzer.FrameMetrics metrics = FrameQualityAnalyzer.evaluateFrame(frame.pixelData, config);
                metrics.filename = frame.identifier;

                FrameExtractionResult result = new FrameExtractionResult();
                result.frameIndex = frame.sequenceIndex;
                result.extractedObjects = objectsInFrame;
                result.metrics = metrics;

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

        // =================================================================
        // PHASE 0 (Generate Deep Master Star Map)
        // =================================================================
        if (DEBUG) {
            System.out.println("\n--- JTRANSIENT: PHASE 0 (Master Map Generation) ---");
        }

        // 1. Mathematically stack the surviving frames to erase transients
        // (Note: Retained SourceExtractor call to match your current architecture)
        short[][] masterStackData = MasterMapGenerator.createMedianMasterStack(cleanFrames);

        // --- DYNAMIC MASTER MAP PARAMETERS ---
        // Scale sensitivity based on user config, but enforce absolute safety floors
        double masterSigma = Math.max(1.5, config.detectionSigmaMultiplier / 2.0);
        int masterMinPix = Math.max(2, config.minDetectionPixels / 3);

        // Note: config.growSigmaMultiplier is deliberately left exactly as the user configured it.

        if (DEBUG) {
            System.out.printf("DEBUG: Master Map Config -> Master Sigma: %.2f | Master Grow: %.2f | Master MinPix: %d%n",
                    masterSigma, config.growSigmaMultiplier, masterMinPix);
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
        );

        // --- RESTORE ORIGINAL CONFIGURATION ---
        config.edgeMarginPixels = originalEdgeMargin;
        config.voidProximityRadius = originalVoidProximity;

        if (DEBUG) {
            System.out.println("DEBUG: Master Stack generated. Found " + masterStars.size() + " deep stationary objects.");
        }

        // =================================================================
        // PHASE 4 (Track Linking)
        // =================================================================
        if (DEBUG) {
            System.out.println("\n--- JTRANSIENT: PHASE 4 (Track Linking) ---");
        }

        int sensorHeight = masterStackData.length;
        int sensorWidth = masterStackData[0].length;

        // Pass BOTH the clean frame extractions and the master stars into the Linker
        TrackLinker.TrackingResult trackResult = TrackLinker.findMovingObjects(
                cleanFramesData,
                masterStars,
                config
        );

        // Map the track results back to our main telemetry object
        telemetry.totalMovingTargetsFound = trackResult.tracks.size();
        telemetry.trackerTelemetry = trackResult.telemetry;

        telemetry.processingTimeMs = System.currentTimeMillis() - startTime;

        // Return the unified result with the new Master Data payloads!
        return new PipelineResult(trackResult.tracks, telemetry, masterStackData, masterStars);
    }

    /**
     * Gracefully shuts down the internal thread pool.
     * Call this when your application is closing to prevent memory leaks.
     */
    public void shutdown() {
        executor.shutdown();
    }
}