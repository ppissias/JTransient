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

        System.out.println("\n--- JTRANSIENT: PHASE 1 (Extraction) ---");
        List<Callable<FrameExtractionResult>> tasks = new ArrayList<>();

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

        System.out.println("\n--- JTRANSIENT: PHASE 2 & 3 (Quality Filter) ---");
        // Pass the config down to the evaluator
        SessionEvaluator.rejectOutlierFrames(sessionMetrics, config);

        List<List<SourceExtractor.DetectedObject>> cleanFramesData = new ArrayList<>();
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
            }
        }

        System.out.println("\n--- JTRANSIENT: PHASE 4 (Track Linking) ---");
        TrackLinker.TrackingResult trackResult = TrackLinker.findMovingObjects(
                cleanFramesData,
                config
        );

        // Map the track results back to our main telemetry object
        telemetry.totalMovingTargetsFound = trackResult.tracks.size();
        telemetry.trackerTelemetry = trackResult.telemetry; // Assumes you added this field to PipelineTelemetry!

        telemetry.processingTimeMs = System.currentTimeMillis() - startTime;

        // Return the unified result!
        return new PipelineResult(trackResult.tracks, telemetry);
    }

    /**
     * Gracefully shuts down the internal thread pool.
     * Call this when your application is closing to prevent memory leaks.
     */
    public void shutdown() {
        executor.shutdown();
    }
}