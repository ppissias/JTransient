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

import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.execution.DiagnosticContext;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.config.DetectionConfigRules;
import io.github.ppissias.jtransient.execution.CancellationScope;
import io.github.ppissias.jtransient.execution.FrameIdentity;
import io.github.ppissias.jtransient.execution.OperationContext;
import io.github.ppissias.jtransient.execution.StageId;
import io.github.ppissias.jtransient.execution.WorkerPool;
import io.github.ppissias.jtransient.core.CommonMotionRejector;
import io.github.ppissias.jtransient.core.DataArea;
import io.github.ppissias.jtransient.core.FrameCoverage;
import io.github.ppissias.jtransient.core.FrameDriftAnalyzer;
import io.github.ppissias.jtransient.core.FrameRegistration;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates the full JTransient processing pipeline from extraction through track linking.
 *
 * <p>An engine owns its worker threads and runs one operation at a time. Reuse the same instance sequentially when
 * practical and call {@link #shutdown()} when no longer needed. To cancel an operation, run it inside a
 * {@link io.github.ppissias.jtransient.execution.CancellationScope}.</p>
 *
 * <p>Every entry point checks its input first and throws {@link io.github.ppissias.jtransient.execution.OperationFailedException}, retaining validation causes and naming the frames, when
 * the list is empty, the frames differ in size or share a sequence index, some frames have a timestamp and others
 * don't ({@code -1}), some have an exposure time and others don't, the exposure times differ by more than 2 % (at
 * least 0.1 s), or a supplied master stack has another size. A timestamp of 0 is a real time.</p>
 *
 * <p>{@link #runPipeline} stops after quality control when it keeps fewer than {@link #MIN_KEPT_FRAMES} frames, and
 * returns a completed result marked {@link PipelineResult#tooFewFrames}, not a failure.</p>
 */
public class JTransientEngine implements AutoCloseable {

    /**
     * The fewest frames quality control must keep for {@link #runPipeline} to go on to the stacks, tracking, slow
     * movers, residual analysis and photometry; the tracker needs as many for a point track. With fewer, the run stops
     * after quality control ({@link PipelineResult#tooFewFrames}).
     */
    public static final int MIN_KEPT_FRAMES = 3;

    /**
     * A frame is rejected when leaving it out would enlarge the area where every frame has image data by at least
     * this share of the frame: it is shifted far from the others, and keeping it would cost every other frame that
     * much sky. The worst frame of the reference datasets costs 1.8 %.
     */
    static final double MAX_SINGLE_FRAME_AREA_LOSS = 0.05;

    private final WorkerPool workers;
    private boolean running;
    private boolean closed;

    /**
     * Runs one operation at a time on this engine's workers. Cancellation comes from the caller's
     * {@link CancellationScope}; diagnostics from the caller's {@link DiagnosticContext}.
     */
    private <Result> Result executeOperation(Callable<Result> operation) {
        synchronized (this) {
            ensureOpen();
            if (running) {
                throw new IllegalStateException("JTransientEngine already has an operation in progress.");
            }
            running = true;
        }
        try (WorkerPool.Scope workerScope = workers.open();
             DiagnosticContext.Scope diagnosticScope = DiagnosticContext.openDefaults()) {
            CancellationScope.check();
            Result result = OperationContext.execute(StageId.OPERATION, operation);
            CancellationScope.check();
            return result;
        } finally {
            synchronized (this) {
                running = false;
            }
        }
    }

    /**
     * Creates an engine with one worker thread per available processor; how many work at once also depends on the
     * frame size and the free heap.
     */
    public JTransientEngine() {
        this(Runtime.getRuntime().availableProcessors());
    }

    /** Creates an engine with at most {@code maxWorkers} worker threads; tests use one for a deterministic order. */
    JTransientEngine(int maxWorkers) {
        this.workers = new WorkerPool(maxWorkers);
    }


    /** Copies only the list structure; null is left for the existing named input-validation failure. */
    private static List<ImageFrame> copyFrameList(List<ImageFrame> inputFrames) {
        return inputFrames == null ? null : new ArrayList<>(inputFrames);
    }


    /** Rejects work after {@link #close()}. */
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("JTransientEngine has been closed.");
        }
    }

    /** Limits the operation's parallel extraction, stacking and photometry by the frame count and size. */
    private void limitWorkToFrames(List<ImageFrame> frames) {
        ensureOpen();
        long largestFramePixels = 1L;
        for (ImageFrame frame : frames) {
            largestFramePixels = Math.max(largestFramePixels,
                    (long) frame.pixelData.length * frame.pixelData[0].length);
        }
        workers.limitWork(frames.size(), largestFramePixels);
    }

    /**
     * Generates the Master Stack independently so it can be reused across iterative pipeline runs.
     * Highly optimized: Skips transient extraction and only runs quality analysis to drop outliers.
     *
     * <p>A private list copy is ordered by {@link ImageFrame#sequenceIndex}; caller order is preserved.
     * Frame descriptors and pixel buffers remain borrowed, not cloned.</p>
     *
     * @param inputFrames frames to evaluate and stack
     * @param config pipeline configuration
     * @param listener optional progress listener
     * @return median master stack built from the retained frames
     * @throws io.github.ppissias.jtransient.execution.OperationFailedException if frame evaluation or stack generation fails
     */
    public short[][] generateMasterStack(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener) {
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
     * not changed.
     */
    public MasterStackResult generateMasterStackWithDetails(List<ImageFrame> inputFrames, DetectionConfig config,
                                                            TransientEngineProgressListener listener) {
        List<ImageFrame> operationFrames = copyFrameList(inputFrames);
        return executeOperation(() -> generateMasterStackWithDetailsInternal(operationFrames,
                OperationContext.compute(StageId.INPUT_VALIDATION, null, () -> DetectionConfigRules.snapshot(config, DetectionConfigRules.Operation.MASTER_STACK)), listener));
    }

    private MasterStackResult generateMasterStackWithDetailsInternal(List<ImageFrame> inputFrames, DetectionConfig config,
                                                            TransientEngineProgressListener listener) throws Exception {
        ensureOpen();
        OperationContext.run(StageId.INPUT_VALIDATION, null, () -> InputRules.checkPipelineInput(inputFrames, null));
        limitWorkToFrames(inputFrames);
        if (listener != null) {
            OperationContext.notify(listener::onProgressUpdate, 0, "Evaluating frames for Master Stack...");
        }

        if (DiagnosticContext.enabled()) {
            DiagnosticContext.println("\n--- JTRANSIENT: PRE-COMPUTING MASTER STACK ---");
        }

        PipelineTelemetry telemetry = new PipelineTelemetry();
        inputFrames.sort(Comparator.comparingInt(f -> f.sequenceIndex));
        FrameDriftAnalyzer.DriftAnalysisResult driftAnalysis = analyzeDitherAndDrift(inputFrames, config, null, telemetry);
        QualityArea quality = qualityArea(driftAnalysis.frameCoverages, telemetry);
        Map<Integer, FrameRegistration> registrations = registrationsBySequence(driftAnalysis);

        List<Callable<PreparedFrame>> tasks = new ArrayList<>();

        int totalFrames = inputFrames.size();
        AtomicInteger framesCompleted = new AtomicInteger(0);

        for (ImageFrame frame : inputFrames) {
            tasks.add(() -> OperationContext.call(StageId.FRAME_QUALITY, new FrameIdentity(frame.sequenceIndex, frame.filename), () -> {
                // We only need quality metrics to drop outliers. We skip SourceExtractor to save massive CPU time!
                FrameQualityAnalyzer.FrameMetrics metrics = OperationContext.compute(StageId.FRAME_QUALITY, null, () -> FrameQualityAnalyzer.evaluateFrame(frame.pixelData, config, quality.area));
                metrics.filename = frame.filename;

                synchronized (framesCompleted) { // keeps the reported progress in order across threads
                    int completed = framesCompleted.incrementAndGet();
                    if (listener != null) {
                        int progress = (int) ((completed / (double) totalFrames) * 50.0);
                        OperationContext.notify(listener::onProgressUpdate, progress, "Evaluating frame " + completed + " of " + totalFrames);
                    }
                }

                return new PreparedFrame(frame, null, metrics, registrations.get(frame.sequenceIndex));
            }));
        }

        List<PreparedFrame> completedResults = new ArrayList<>();
        completedResults.addAll(WorkerPool.invokeAll(tasks));
        completedResults.sort(Comparator.comparingInt(prepared -> prepared.frame().sequenceIndex));

        if (listener != null) {
            OperationContext.notify(listener::onProgressUpdate, 55, "Filtering outlier frames...");
        }

        OperationContext.compute(StageId.FRAME_SELECTION, null,
                () -> rejectUnusableFrames(completedResults, telemetry.driftExcludedFrames, quality.coverageOutliers, config));

        List<ImageFrame> cleanFrames = PreparedFrame.retained(completedResults).stream().map(PreparedFrame::frame).toList();
        List<Integer> keptFrames = new ArrayList<>();
        List<PipelineTelemetry.FrameRejectionStat> rejectedFrames = new ArrayList<>();
        for (int i = 0; i < completedResults.size(); i++) {
            PreparedFrame prepared = completedResults.get(i);
            FrameQualityAnalyzer.FrameMetrics metrics = prepared.metrics();
            if (!metrics.isRejected) {
                keptFrames.add(prepared.frame().sequenceIndex);
            } else {
                PipelineTelemetry.FrameRejectionStat rejected = new PipelineTelemetry.FrameRejectionStat();
                rejected.frameIndex = prepared.frame().sequenceIndex;
                rejected.filename = metrics.filename;
                rejected.reason = metrics.rejectionReason;
                rejected.medianEccentricity = metrics.medianEccentricity;
                rejected.brightStarMedianEccentricity = metrics.brightStarMedianEccentricity;
                rejected.brightStarShapeStarCount = metrics.brightStarShapeStarCount;
                rejectedFrames.add(rejected);
            }
        }

        if (listener != null) {
            OperationContext.notify(listener::onProgressUpdate, 60, "Stacking " + cleanFrames.size() + " clean frames...");
        }

        return new MasterStackResult(OperationContext.compute(StageId.MEDIAN_STACK, null, () -> MasterMapGenerator.createMedianMasterStack(cleanFrames)), keptFrames, rejectedFrames);
    }

    /**
     * Entry point for the JTransient library.
     * Convenience wrapper that calculates the master stack automatically.
     *
     * <p>A private list copy is ordered by {@link ImageFrame#sequenceIndex}; caller order is preserved.
     * Frame descriptors and pixel buffers remain borrowed, not cloned.</p>
     *
     * @param inputFrames frames to process
     * @param config pipeline configuration
     * @param listener optional progress listener
     * @return full pipeline output bundle
     * @throws io.github.ppissias.jtransient.execution.OperationFailedException if extraction, stacking, tracking, or residual analysis fails
     */
    public PipelineResult runPipeline(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener) {
        return runPipeline(inputFrames, config, listener, null);
    }

    /**
     * Runs the pipeline up to detecting the transients (extracted objects) for all frames and does no further processing.
     * Generates a median master stack automatically to apply the Veto Mask.
     *
     * <p>A private list copy is ordered by {@link ImageFrame#sequenceIndex}; caller order is preserved.
     * Frame descriptors and pixel buffers remain borrowed, not cloned.</p>
     *
     * @param inputFrames frames to process
     * @param config pipeline configuration
     * @param listener optional progress listener
     * @return per-frame transient exports after the stationary-star veto
     * @throws io.github.ppissias.jtransient.execution.OperationFailedException if extraction, master-stack generation, or vetoing fails
     */
    public List<FrameTransients> detectTransients(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener) {
        return detectTransients(inputFrames, config, listener, null);
    }

    /**
     * Runs the pipeline up to detecting the transients for all frames and does no further processing.
     * Uses the provided master stack to successfully apply the Veto Mask.
     *
     * <p>A private list copy is ordered by {@link ImageFrame#sequenceIndex}; caller order is preserved.
     * Frame descriptors and pixel buffers remain borrowed, not cloned.</p>
     *
     * @param inputFrames frames to process
     * @param config pipeline configuration
     * @param listener optional progress listener
     * @param providedMasterStack optional precomputed median master stack
     * @return per-frame transient exports after the stationary-star veto
     * @throws io.github.ppissias.jtransient.execution.OperationFailedException if extraction or vetoing fails
     */
    public List<FrameTransients> detectTransients(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener, short[][] providedMasterStack) {
        List<ImageFrame> operationFrames = copyFrameList(inputFrames);
        return executeOperation(() -> detectTransientsInternal(operationFrames,
                OperationContext.compute(StageId.INPUT_VALIDATION, null, () -> DetectionConfigRules.snapshot(config, DetectionConfigRules.Operation.TRANSIENTS)), listener, providedMasterStack)).frames();
    }

    /**
     * Runs the same transient detection and also keeps the frame selection, master stars and star mask, so one frame
     * can be re-detected later without building the reference again.
     */
    public TransientInspection inspectTransients(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener) {
        List<ImageFrame> operationFrames = copyFrameList(inputFrames);
        return executeOperation(() -> detectTransientsInternal(operationFrames,
                OperationContext.compute(StageId.INPUT_VALIDATION, null, () -> DetectionConfigRules.snapshot(config, DetectionConfigRules.Operation.TRANSIENTS)), listener, null));
    }

    /**
     * Re-detects one kept frame with new per-frame settings against a reference from {@link #inspectTransients}.
     * The master stack and star mask are not rebuilt.
     */
    public FrameTransients redetectFrame(TransientReference reference, int keptIndex, DetectionConfig config) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(config, "config");
        ImageFrame frame = reference.keptFrames().get(keptIndex);
        return executeOperation(() -> redetectFrameInternal(reference, frame,
                OperationContext.compute(StageId.INPUT_VALIDATION, null, () -> DetectionConfigRules.snapshot(config, DetectionConfigRules.Operation.TRANSIENTS))));
    }

    private FrameTransients redetectFrameInternal(TransientReference reference, ImageFrame frame, DetectionConfig config) {
        ensureOpen();
        SourceExtractor.ExtractionResult extracted = OperationContext.compute(StageId.FRAME_EXTRACTION, null, () -> SourceExtractor.extractSources(
                frame.pixelData, config.detectionSigmaMultiplier, config.minDetectionPixels, config,
                reference.backgroundArea(), reference.searchArea()));
        for (DetectedObject object : extracted.objects) {
            object.sourceFrameIndex = frame.sequenceIndex;
            object.sourceFilename = frame.filename;
            object.timestamp = frame.timestamp;
            object.exposureDuration = frame.exposureDuration;
        }
        if (reference.sessionArea() != null) {
            extracted.objects.removeIf(object -> !SourceExtractor.isInside(object, reference.sessionArea()));
        }
        int height = frame.pixelData.length;
        int width = frame.pixelData[0].length;
        TrackLinker.TransientsFilterResult filtered = OperationContext.compute(StageId.TRANSIENT_VETO, null, () -> TrackLinker.filterTransients(
                List.of(extracted.objects), reference.masterStars(), config, null, width, height, reference.starMask()));
        return new FrameTransients(frame.filename, filtered.allTransients.get(0), extracted);
    }

    private TransientInspection detectTransientsInternal(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener, short[][] providedMasterStack) throws Exception {
        ensureOpen();
        OperationContext.run(StageId.INPUT_VALIDATION, null, () -> InputRules.checkPipelineInput(inputFrames, providedMasterStack));
        ExtractedFramesContext context = extractSourcesFromFrames(inputFrames, config, listener, null);

        if (providedMasterStack != null) {
            if (listener != null) OperationContext.notify(listener::onProgressUpdate, 45, "Using pre-computed Master Stack for Veto Mask...");
        } else {
            if (listener != null) OperationContext.notify(listener::onProgressUpdate, 45, "Generating Median Master Stack for Veto Mask...");
        }

        if (listener != null) OperationContext.notify(listener::onProgressUpdate, 48, "Extracting Master Star Map...");

        MasterReferenceAnalyzer.MasterReferenceAnalysis masterReference = OperationContext.compute(StageId.MASTER_REFERENCE, null, () -> MasterReferenceAnalyzer.analyze(
                context.cleanFrames,
                providedMasterStack,
                config
        ));
        short[][] masterStackData = masterReference.masterStackData;
        List<DetectedObject> masterStars = masterReference.masterStars;
        int sensorHeight = masterReference.sensorHeight;
        int sensorWidth = masterReference.sensorWidth;

        TransientEngineProgressListener proxyListener;
        if (listener != null) {
            proxyListener = (percentage, message) -> OperationContext.notify(listener::onProgressUpdate, 60 + (int) (percentage * 0.40), message); // Scale 0-100 to 60-100
        } else {
            proxyListener = null;
        }

        List<List<DetectedObject>> cleanFramesObjects = new ArrayList<>();
        for (SourceExtractor.ExtractionResult extRes : context.cleanFramesData) {
            cleanFramesObjects.add(extRes.objects);
        }

        TrackLinker.TransientsFilterResult filterResult = OperationContext.compute(StageId.TRANSIENT_VETO, null, () -> TrackLinker.filterTransients(
                cleanFramesObjects, masterStars, config, proxyListener, sensorWidth, sensorHeight));
        context.labelTrackerFrames(filterResult.telemetry);
        
        if (listener != null) OperationContext.notify(listener::onProgressUpdate, 100, "Transient Extraction Complete!");

        List<FrameTransients> finalResult = new ArrayList<>();
        for (int i = 0; i < context.cleanFrames.size(); i++) {
            finalResult.add(new FrameTransients(
                    context.cleanFrames.get(i).filename,
                    filterResult.allTransients.get(i),
                    context.cleanFramesData.get(i)
            ));
        }
        TransientReference reference = new TransientReference(
                context.cleanFrames,
                context.backgroundArea,
                context.searchArea,
                context.dataArea,
                masterStars,
                filterResult.masterVetoMask,
                config.masterSigmaMultiplier,
                config.masterGrowSigmaMultiplier,
                config.masterMinDetectionPixels,
                config.maxStarJitter);
        return new TransientInspection(reference, finalResult);
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
    private ExtractedFramesContext extractSourcesFromFrames(List<ImageFrame> inputFrames, DetectionConfig config,
                                                             TransientEngineProgressListener listener,
                                                             PipelineStageObserver observer) throws Exception {
        long startNanos = System.nanoTime();
        limitWorkToFrames(inputFrames);
        PipelineTelemetry telemetry = new PipelineTelemetry();
        telemetry.totalFramesLoaded = inputFrames.size();

        if (listener != null) {
            OperationContext.notify(listener::onProgressUpdate, 0, "Initializing pipeline...");
        }

        if (DiagnosticContext.enabled()) {
            DiagnosticContext.println("\n--- JTRANSIENT: PHASE 1 (Extraction) ---");
        }
        notifyStarted(observer, "Source extraction");

        // =================================================================
        // --- NEW: DITHER & DRIFT DIAGNOSTICS ---
        // =================================================================
        long stageStartedNanos = System.nanoTime();
        FrameDriftAnalyzer.DriftAnalysisResult driftAnalysis = analyzeDitherAndDrift(inputFrames, config, listener, telemetry);
        List<Pixel> driftPoints = new ArrayList<>(driftAnalysis.driftPoints);
        QualityArea quality = qualityArea(driftAnalysis.frameCoverages, telemetry);
        Map<Integer, FrameRegistration> registrations = registrationsBySequence(driftAnalysis);
        telemetry.recordStage(PipelineTelemetry.Stage.DRIFT_ANALYSIS, stageStartedNanos);
        stageStartedNanos = System.nanoTime();

        List<Callable<PreparedFrame>> tasks = new ArrayList<>();

        // Ensure input frames are sorted chronologically before processing
        inputFrames.sort(Comparator.comparingInt(f -> f.sequenceIndex));
        // Objects are kept up to the image edge here; after quality control only those inside the area where every
        // kept frame has image data remain.
        DataArea wholeFrame = DataArea.fullFrame(inputFrames.get(0).pixelData[0].length, inputFrames.get(0).pixelData.length);

        // --- SAFE CONCURRENT PROGRESS TRACKING ---
        int totalFrames = inputFrames.size();
        AtomicInteger framesCompleted = new AtomicInteger(0);

        for (ImageFrame frame : inputFrames) {
            tasks.add(() -> OperationContext.call(StageId.FRAME_EXTRACTION, new FrameIdentity(frame.sequenceIndex, frame.filename), () -> {
                // 1. Extract Sources (Passing config as the 4th argument)
                SourceExtractor.ExtractionResult extResult = OperationContext.compute(StageId.FRAME_EXTRACTION, null, () -> SourceExtractor.extractSources(
                        frame.pixelData,
                        config.detectionSigmaMultiplier,
                        config.minDetectionPixels,
                        config,
                        quality.area,
                        wholeFrame
                ));
                List<DetectedObject> objectsInFrame = extResult.objects;

                for (DetectedObject obj : objectsInFrame) {
                    obj.sourceFrameIndex = frame.sequenceIndex;
                    obj.sourceFilename = frame.filename;
                    obj.timestamp = frame.timestamp;
                    obj.exposureDuration = frame.exposureDuration;
                }
                if (wantsLiveUpdate(observer)) {
                    int shown = Math.min(32, objectsInFrame.size());
                    double[] xs = new double[shown];
                    double[] ys = new double[shown];
                    for (int i = 0; i < shown; i++) {
                        xs[i] = objectsInFrame.get(i).x;
                        ys[i] = objectsInFrame.get(i).y;
                    }
                    notifyLive(observer, new LiveSnapshot("Source extraction", frame.sequenceIndex,
                            "Source extraction · frame " + (frame.sequenceIndex + 1) + " · "
                                    + objectsInFrame.size() + " sources · provisional", xs, ys));
                }

                // 2. Quality Metrics (Passing config down)
                FrameQualityAnalyzer.FrameMetrics metrics = OperationContext.compute(StageId.FRAME_QUALITY, null, () -> FrameQualityAnalyzer.evaluateFrame(frame.pixelData, config, quality.area));
                metrics.filename = frame.filename;

                // Safely update progress from multiple threads (Mapping Phase 1 to 0-40% of the total bar)
                synchronized (framesCompleted) { // keeps the reported progress in order across threads
                    int completed = framesCompleted.incrementAndGet();
                    if (listener != null) {
                        int progress = (int) ((completed / (double) totalFrames) * 40.0);
                        OperationContext.notify(listener::onProgressUpdate, progress, "Extracting features from frame " + completed + " of " + totalFrames);
                    }
                }

                return new PreparedFrame(frame, extResult, metrics, registrations.get(frame.sequenceIndex));
            }));
        }

        // Execute parallel extraction
        List<PreparedFrame> completedResults = new ArrayList<>();
        completedResults.addAll(WorkerPool.invokeAll(tasks));
        completedResults.sort(Comparator.comparingInt(prepared -> prepared.frame().sequenceIndex));

        // Unpack results & update Telemetry
        for (PreparedFrame result : completedResults) {
            telemetry.totalRawObjectsExtracted += result.extractionResult().objects.size();
            PipelineTelemetry.FrameExtractionStat stat = new PipelineTelemetry.FrameExtractionStat();
            stat.frameIndex = result.frame().sequenceIndex;
            stat.filename = result.metrics().filename;
            stat.objectCount = result.extractionResult().objects.size();
            if (result.extractionResult().backgroundMetrics != null) {
                stat.bgMedian = result.extractionResult().backgroundMetrics.median;
                stat.bgSigma = result.extractionResult().backgroundMetrics.sigma;
            }
            stat.seedThreshold = result.extractionResult().seedThreshold;
            stat.growThreshold = result.extractionResult().growThreshold;
            telemetry.frameExtractionStats.add(stat);
        }

        telemetry.recordStage(PipelineTelemetry.Stage.SOURCE_EXTRACTION, stageStartedNanos);
        stageStartedNanos = System.nanoTime();
        if (DiagnosticContext.enabled()) {
            DiagnosticContext.println("\n--- JTRANSIENT: PHASE 2 & 3 (Quality Filter) ---");
        }

        if (listener != null) {
            OperationContext.notify(listener::onProgressUpdate, 42, "Filtering outlier frames...");
        }

        // Pass the config down to the evaluator
        SessionEvaluator.SessionThresholds sessionThresholds =
                OperationContext.compute(StageId.FRAME_SELECTION, null,
                        () -> rejectUnusableFrames(completedResults, telemetry.driftExcludedFrames, quality.coverageOutliers, config));
        telemetry.qualityThresholds.available = sessionThresholds.available;
        telemetry.qualityThresholds.minAllowedStarCount = sessionThresholds.minAllowedStarCount;
        telemetry.qualityThresholds.maxAllowedFwhm = sessionThresholds.maxAllowedFwhm;
        telemetry.qualityThresholds.maxAllowedEccentricity = sessionThresholds.maxAllowedEccentricity;
        telemetry.qualityThresholds.maxAllowedBrightStarEccentricity = sessionThresholds.maxAllowedBrightStarEccentricity;
        telemetry.qualityThresholds.backgroundMedianBaseline = sessionThresholds.backgroundMedianBaseline;
        telemetry.qualityThresholds.maxAllowedBackgroundDeviation = sessionThresholds.maxAllowedBackgroundDeviation;
        telemetry.qualityThresholds.minAllowedBackgroundMedian = sessionThresholds.minAllowedBackgroundMedian;
        telemetry.qualityThresholds.maxAllowedBackgroundMedian = sessionThresholds.maxAllowedBackgroundMedian;

        List<PreparedFrame> retainedFrames = PreparedFrame.retained(completedResults);
        List<ImageFrame> cleanFrames = retainedFrames.stream().map(PreparedFrame::frame).toList();
        List<SourceExtractor.ExtractionResult> cleanFramesData = retainedFrames.stream()
                .map(PreparedFrame::extractionResult).toList();

        for (int i = 0; i < completedResults.size(); i++) {
            PreparedFrame prepared = completedResults.get(i);
            FrameQualityAnalyzer.FrameMetrics metrics = prepared.metrics();
            PipelineTelemetry.FrameQualityStat qualityStat = new PipelineTelemetry.FrameQualityStat();
            qualityStat.frameIndex = prepared.frame().sequenceIndex;
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
                rejStat.frameIndex = prepared.frame().sequenceIndex;
                rejStat.filename = metrics.filename;
                rejStat.reason = metrics.rejectionReason;
                rejStat.medianEccentricity = metrics.medianEccentricity;
                rejStat.brightStarMedianEccentricity = metrics.brightStarMedianEccentricity;
                rejStat.brightStarShapeStarCount = metrics.brightStarShapeStarCount;
                telemetry.rejectedFrames.add(rejStat);
            } else {
                telemetry.totalFramesKept++;
            }
        }

        DataArea dataArea = sessionDataArea(driftAnalysis.frameCoverages, cleanFrames, telemetry);
        if (dataArea != null) {
            // Outside this area some kept frame has no data, so the median stack and the star mask are wrong there.
            for (SourceExtractor.ExtractionResult frameData : cleanFramesData) {
                frameData.objects.removeIf(object -> !SourceExtractor.isInside(object, dataArea));
            }
        }
        telemetry.recordStage(PipelineTelemetry.Stage.QUALITY_FILTERING, stageStartedNanos);
        return new ExtractedFramesContext(
                retainedFrames,
                telemetry,
                startNanos,
                driftPoints,
                dataArea,
                quality.area,
                wholeFrame,
                registrations
        );
    }

    private static Map<Integer, FrameRegistration> registrationsBySequence(FrameDriftAnalyzer.DriftAnalysisResult drift) {
        Map<Integer, FrameRegistration> bySequence = new HashMap<>();
        for (FrameRegistration registration : drift.frameRegistrations) {
            bySequence.put(registration.sequenceIndex, registration);
        }
        return bySequence;
    }

    /**
     * The frame selection of the quality stage, shared by the full pipeline and {@link #generateMasterStack}: marks
     * the session outliers (quality settings), the blank or failed-registration frames found by the drift analysis,
     * and the frames that alone shrink the common data area a lot, as rejected. The last two reasons take precedence:
     * they are the cause when such a frame also fails the session statistics.
     *
     * @param preparedFrames associated preparation products, sorted by original sequence index; metrics updated in place
     * @param driftExcludedFrames sequence indices of blank or failed-registration frames
     * @param coverageOutliers rejection reasons of the frames that alone shrink the common data area, by sequence index
     * @return the session thresholds used for the outlier rejection
     */
    private static SessionEvaluator.SessionThresholds rejectUnusableFrames(List<PreparedFrame> preparedFrames,
                                                                          Collection<Integer> driftExcludedFrames,
                                                                          Map<Integer, String> coverageOutliers,
                                                                          DetectionConfig config) {
        List<FrameQualityAnalyzer.FrameMetrics> sessionMetrics = preparedFrames.stream().map(PreparedFrame::metrics).toList();
        SessionEvaluator.SessionThresholds thresholds = SessionEvaluator.rejectOutlierFrames(sessionMetrics, config);
        for (PreparedFrame prepared : preparedFrames) {
            FrameQualityAnalyzer.FrameMetrics metrics = prepared.metrics();
            int sequenceIndex = prepared.frame().sequenceIndex;
            if (driftExcludedFrames.contains(sequenceIndex)) {
                // A partly blank frame can keep enough stars to pass the session statistics, but its sky does
                // not match the other frames.
                metrics.isRejected = true;
                metrics.rejectionReason = "Blank or failed registration (less than half of the pixels hold image data)";
            } else if (coverageOutliers.containsKey(sequenceIndex)) {
                metrics.isRejected = true;
                metrics.rejectionReason = coverageOutliers.get(sequenceIndex);
            }
        }
        return thresholds;
    }

    /** The area where every registered frame has image data, and the frames rejected for shrinking it a lot. */
    private static final class QualityArea {
        /** Where the sky and the quality metrics are measured; null when no frame passed the drift analysis. */
        final DataArea area;
        /** Rejection reasons by sequence index. */
        final Map<Integer, String> coverageOutliers;

        private QualityArea(DataArea area, Map<Integer, String> coverageOutliers) {
            this.area = area;
            this.coverageOutliers = coverageOutliers;
        }
    }

    /**
     * The area where every registered frame has image data. A frame that alone shrinks it by at least
     * {@link #MAX_SINGLE_FRAME_AREA_LOSS} of the frame is left out and rejected, one at a time and at most a tenth of
     * the frames; a group of frames shifted together is the session's real coverage and stays.
     *
     * @param coverages coverage of every frame that passed the drift analysis
     */
    private static QualityArea qualityArea(List<FrameCoverage> coverages, PipelineTelemetry telemetry) {
        List<FrameCoverage> frames = new ArrayList<>(coverages);
        Map<Integer, String> outliers = new LinkedHashMap<>();
        int maxOutliers = Math.max(1, frames.size() / 10);
        while (frames.size() > 2 && outliers.size() < maxOutliers) {
            List<DataArea.FrameLimit> limits = DataArea.limitingFrames(frames);
            if (limits.isEmpty() || limits.get(0).gainedFraction < MAX_SINGLE_FRAME_AREA_LOSS) {
                break;
            }
            DataArea.FrameLimit worst = limits.get(0);
            outliers.put(worst.sequenceIndex, String.format(Locale.US,
                    "Shifted away from the other frames (without it, every frame has image data on %.1f %% more of the frame)",
                    100 * worst.gainedFraction));
            frames.removeIf(frame -> frame.getSequenceIndex() == worst.sequenceIndex);
        }
        DataArea area = frames.isEmpty() ? null : DataArea.of(frames);
        telemetry.qualityAreaFraction = area == null ? Double.NaN : area.getFraction();
        return new QualityArea(area, outliers);
    }

    /**
     * The area where every kept frame holds image data, and the kept frames that limit it, recorded in the telemetry.
     *
     * @param coverages coverage of every frame that passed the drift analysis
     * @param keptFrames the frames kept by quality control
     * @return the area, or null when no frame was kept
     */
    private static DataArea sessionDataArea(List<FrameCoverage> coverages, List<ImageFrame> keptFrames,
                                            PipelineTelemetry telemetry) {
        Map<Integer, FrameCoverage> bySequence = new HashMap<>();
        for (FrameCoverage coverage : coverages) {
            bySequence.put(coverage.getSequenceIndex(), coverage);
        }
        Map<Integer, String> names = new HashMap<>();
        List<FrameCoverage> kept = new ArrayList<>();
        for (ImageFrame frame : keptFrames) {
            FrameCoverage coverage = bySequence.get(frame.sequenceIndex);
            if (coverage != null) {
                kept.add(coverage);
                names.put(frame.sequenceIndex, frame.filename);
            }
        }
        if (kept.isEmpty()) {
            return null;
        }
        DataArea area = DataArea.of(kept);
        telemetry.dataAreaFraction = area.getFraction();
        for (DataArea.FrameLimit limit : DataArea.limitingFrames(kept)) {
            PipelineTelemetry.DataAreaFrameStat stat = new PipelineTelemetry.DataAreaFrameStat();
            stat.frameIndex = limit.sequenceIndex;
            stat.filename = names.get(limit.sequenceIndex);
            stat.gainedFraction = limit.gainedFraction;
            telemetry.dataAreaLimitingFrames.add(stat);
        }
        return area;
    }

    /**
     * Analyzes sequence dither and corner drift by measuring the valid-image bounds per frame, and records where each
     * frame holds image data. The configuration is not changed.
     *
     * @param inputFrames frames to inspect
     * @param config pipeline configuration
     * @param listener optional progress listener
     * @param telemetry telemetry that records the frames left out of the drift analysis
     * @return the drift analysis: translation vectors (dx, dy) per frame, failed registrations and frame coverages
     */
    private FrameDriftAnalyzer.DriftAnalysisResult analyzeDitherAndDrift(List<ImageFrame> inputFrames, DetectionConfig config,
                                                              TransientEngineProgressListener listener,
                                                              PipelineTelemetry telemetry) {
        if (listener != null) {
            OperationContext.notify(listener::onProgressUpdate, 0, "Analyzing sequence dither and corner drift...");
        }

        FrameDriftAnalyzer.DriftAnalysisResult driftAnalysis = OperationContext.compute(StageId.REGISTRATION, null, () -> FrameDriftAnalyzer.analyze(inputFrames));

        telemetry.driftExcludedFrames.addAll(driftAnalysis.failedRegistrationFrames);
        if (DiagnosticContext.enabled() && !driftAnalysis.failedRegistrationFrames.isEmpty()) {
            DiagnosticContext.println("DEBUG: Dither Diagnostics left out blank or failed-registration frames "
                    + "(less than half the pixels valid): " + driftAnalysis.failedRegistrationFrames);
        }
        if (DiagnosticContext.enabled() && driftAnalysis.maxPaddingPixels > 0) {
            DiagnosticContext.println("DEBUG: Dither Diagnostics found registration padding up to "
                    + driftAnalysis.maxPaddingPixels + " px.");
        }
        return driftAnalysis;
    }

    /**
     * Entry point for the JTransient library.
     * Allows passing a pre-computed master stack to bypass the heavy stacking phase during iterative runs.
     *
     * <p>A private list copy is ordered by {@link ImageFrame#sequenceIndex}; caller order is preserved.
     * Frame descriptors and pixel buffers remain borrowed, not cloned.</p>
     *
     * @param inputFrames frames to process
     * @param config pipeline configuration
     * @param listener optional progress listener
     * @param providedMasterStack optional precomputed median master stack
     * @return full pipeline output bundle
     * @throws io.github.ppissias.jtransient.execution.OperationFailedException if extraction, tracking, or residual analysis fails
     */
    public PipelineResult runPipeline(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener, short[][] providedMasterStack) {
        return runPipeline(inputFrames, config, listener, providedMasterStack, null);
    }

    /**
     * {@link #runPipeline(List, DetectionConfig, TransientEngineProgressListener, short[][])} that also hands each
     * finished stage to {@code observer}, for example to show findings while the run continues. The observer cannot
     * change the result: it gets the run's products to read, and its exceptions are reported and ignored.
     *
     * @param observer called on this thread once per finished stage, in order; null for none
     */
    public PipelineResult runPipeline(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener,
                                      short[][] providedMasterStack, PipelineStageObserver observer) {
        List<ImageFrame> operationFrames = copyFrameList(inputFrames);
        return executeOperation(() -> runPipelineInternal(operationFrames,
                OperationContext.compute(StageId.INPUT_VALIDATION, null, () -> DetectionConfigRules.snapshot(config, DetectionConfigRules.Operation.PIPELINE,
                        operationFrames != null && operationFrames.stream().anyMatch(frame -> frame != null && frame.timestamp != -1L)
                                ? DetectionConfigRules.Timing.AVAILABLE : DetectionConfigRules.Timing.MISSING)),
                listener, providedMasterStack, observer));
    }

    /** Hands a finished stage to the observer; its failure is reported through the diagnostics and ignored. */
    private static void notifyObserver(PipelineStageObserver observer, PipelineStage stage) {
        try {
            observer.stageFinished(stage);
        } catch (RuntimeException failure) {
            if (DiagnosticContext.enabled()) {
                DiagnosticContext.println("DEBUG: The pipeline stage observer failed; the run continues: " + failure);
            }
        }
    }

    /** Tells the observer a long stage is starting. A failure does not change the run. */
    private static void notifyStarted(PipelineStageObserver observer, String stage) {
        if (observer == null) {
            return;
        }
        try {
            observer.stageStarted(stage);
        } catch (RuntimeException failure) {
            if (DiagnosticContext.enabled()) {
                DiagnosticContext.println("DEBUG: The pipeline stage observer failed; the run continues: " + failure);
            }
        }
    }

    /** Asks whether one in-stage snapshot is wanted. A failure means no snapshot. */
    private static boolean wantsLiveUpdate(PipelineStageObserver observer) {
        if (observer == null) {
            return false;
        }
        try {
            return observer.wantsUpdate();
        } catch (RuntimeException failure) {
            if (DiagnosticContext.enabled()) {
                DiagnosticContext.println("DEBUG: The pipeline stage observer failed; the run continues: " + failure);
            }
            return false;
        }
    }

    /** Hands over a snapshot that was built only because {@link #wantsLiveUpdate} returned true. */
    private static void notifyLive(PipelineStageObserver observer, LiveSnapshot snapshot) {
        if (observer == null || snapshot == null) {
            return;
        }
        try {
            observer.liveUpdate(snapshot);
        } catch (RuntimeException failure) {
            if (DiagnosticContext.enabled()) {
                DiagnosticContext.println("DEBUG: The pipeline stage observer failed; the run continues: " + failure);
            }
        }
    }

    private PipelineResult runPipelineInternal(List<ImageFrame> inputFrames, DetectionConfig config, TransientEngineProgressListener listener,
                                               short[][] providedMasterStack, PipelineStageObserver observer) throws Exception {
        ensureOpen();
        OperationContext.run(StageId.INPUT_VALIDATION, null, () -> InputRules.checkPipelineInput(inputFrames, providedMasterStack));
        ExtractedFramesContext context = extractSourcesFromFrames(inputFrames, config, listener, observer);
        if (context.cleanFrames.size() < MIN_KEPT_FRAMES) {
            return tooFewFramesResult(context, listener);
        }
        long stageStartedNanos = System.nanoTime();
        PipelineTelemetry telemetry = context.telemetry;
        List<SourceExtractor.ExtractionResult> cleanFramesData = context.cleanFramesData;
        List<ImageFrame> cleanFrames = context.cleanFrames;

        // =================================================================
        // PHASE 0 (Generate Deep Master Star Map)
        // =================================================================
        if (providedMasterStack != null) {
            if (DiagnosticContext.enabled()) {
                DiagnosticContext.println("\n--- JTRANSIENT: PHASE 0 (Using Pre-Computed Master Stack) ---");
            }
            if (listener != null) {
                OperationContext.notify(listener::onProgressUpdate, 45, "Using pre-computed Master Stack...");
            }
        } else {
            if (DiagnosticContext.enabled()) {
                DiagnosticContext.println("\n--- JTRANSIENT: PHASE 0 (Master Map Generation) ---");
            }
            if (listener != null) {
                OperationContext.notify(listener::onProgressUpdate, 45, "Generating Median Master Stack...");
            }
        }

        if (DiagnosticContext.enabled()) {
            DiagnosticContext.printf("DEBUG: Master Map Config -> Master Sigma: %.2f | Master Grow: %.2f | Master MinPix: %d%n",
                    config.masterSigmaMultiplier, MasterReferenceAnalyzer.effectiveMasterGrowSigma(config), config.masterMinDetectionPixels);
        }

        notifyStarted(observer, "Frame selection and reference");
        if (listener != null) {
            OperationContext.notify(listener::onProgressUpdate, 48, "Extracting Master Star Map...");
        }

        MasterReferenceAnalyzer.MasterReferenceAnalysis masterReference = OperationContext.compute(StageId.MASTER_REFERENCE, null, () -> MasterReferenceAnalyzer.analyze(
                cleanFrames,
                providedMasterStack,
                config
        ));
        short[][] masterStackData = masterReference.masterStackData;
        List<DetectedObject> masterStars = masterReference.masterStars;
        int sensorHeight = masterReference.sensorHeight;
        int sensorWidth = masterReference.sensorWidth;

        if (DiagnosticContext.enabled()) {
            DiagnosticContext.println("DEBUG: Master Stack generated. Found " + masterStars.size() + " deep stationary objects.");
        }

        // =================================================================
        // PHASE 0.5 (Maximum-Stack Slow-Mover Candidate Detection)
        // =================================================================

        telemetry.recordStage(PipelineTelemetry.Stage.MASTER_REFERENCE, stageStartedNanos);
        if (observer != null) {
            notifyObserver(observer, new PipelineStage.MasterReference(masterStackData, masterStars));
        }
        // Build once for both the detector and PipelineResult.maximumStackData.
        stageStartedNanos = System.nanoTime();
        short[][] maximumStackData = OperationContext.compute(StageId.MAXIMUM_STACK, null, () -> MasterMapGenerator.createMaximumMasterStack(cleanFrames));
        telemetry.recordStage(PipelineTelemetry.Stage.MAXIMUM_STACK, stageStartedNanos);
        SlowMoverAnalysis slowMoverAnalysis = SlowMoverAnalysis.empty();
        short[][] slowMoverStackData = null;
        boolean[][] slowMoverMedianVetoMask = null;
        List<DetectedObject> slowMoverCandidates = new ArrayList<>();
        PipelineTelemetry.SlowMoverTelemetry smTelemetry = null;

        if (config.enableSlowMoverDetection) {
            notifyStarted(observer, "Slow movers");
            stageStartedNanos = System.nanoTime();
            if (DiagnosticContext.enabled()) {
                DiagnosticContext.println("\n--- JTRANSIENT: PHASE 0.5 (Slow Mover Detection) ---");
            }
            if (listener != null) {
                OperationContext.notify(listener::onProgressUpdate, 49, "Analyzing Maximum Stack for Slow Movers...");
            }

            slowMoverAnalysis = OperationContext.compute(StageId.SLOW_MOVERS, null, () -> SlowMoverAnalyzer.analyze(
                    maximumStackData,
                    masterStackData,
                    cleanFrames,
                    config,
                    context.dataArea
            ));
            slowMoverStackData = slowMoverAnalysis.slowMoverStackData;
            slowMoverMedianVetoMask = slowMoverAnalysis.medianVetoMask;
            slowMoverCandidates = new ArrayList<>(slowMoverAnalysis.candidates.size());
            for (SlowMoverCandidateResult candidate : slowMoverAnalysis.candidates) {
                slowMoverCandidates.add(candidate.object);
            }
            smTelemetry = slowMoverAnalysis.telemetry.toLegacyTelemetry(slowMoverAnalysis.candidates);

            if (DiagnosticContext.enabled()) {
                DiagnosticContext.printf(
                        "DEBUG: Slow Mover Shape -> Axis Ratio: %.2f-%.2f | Min Fill: %.2f | Avg Accepted Axis Ratio: %.2f%n",
                        smTelemetry.minAxisRatioThreshold,
                        smTelemetry.maxAxisRatioThreshold,
                        smTelemetry.minFillFactorThreshold,
                        smTelemetry.avgCandidateAxisRatio
                );
                DiagnosticContext.printf(
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
                DiagnosticContext.printf(
                        "DEBUG: Slow Mover Mask -> MinOverlap: %.3f | MaxOverlap: %.3f | AvgOverlap: %.3f | AvgEstimatedMotion: %.2f px%n",
                        smTelemetry.medianSupportOverlapThreshold,
                        smTelemetry.medianSupportMaxOverlapThreshold,
                        smTelemetry.avgMedianMaskOverlap,
                        smTelemetry.avgEstimatedMotionPixels
                );
                if (!smTelemetry.candidateMedianSupportOverlaps.isEmpty()) {
                    DiagnosticContext.printf(
                            "DEBUG: Accepted Slow Mover Overlaps -> %s%n",
                            formatOverlapPercentages(smTelemetry.candidateMedianSupportOverlaps)
                    );
                }
            }
        }

        if (config.enableSlowMoverDetection) {
            telemetry.recordStage(PipelineTelemetry.Stage.SLOW_MOVERS, stageStartedNanos);
        }
        if (observer != null) {
            notifyObserver(observer, new PipelineStage.SlowMovers(config.enableSlowMoverDetection, maximumStackData,
                    slowMoverAnalysis.candidates));
        }

        // =================================================================
        // PHASE 4 (Track Linking)
        // =================================================================
        if (DiagnosticContext.enabled()) {
            DiagnosticContext.println("\n--- JTRANSIENT: PHASE 4 (Track Linking) ---");
        }

        // --- THE PROXY LISTENER ---
        // We pass a synthetic listener to the Linker. It maps the Linker's 0-100% to the Engine's 50-90% range;
        // residual analysis and photometry follow, so the progress never goes back.
        TransientEngineProgressListener trackingProxyListener;
        if (listener != null) {
            trackingProxyListener = (percentage, message) -> {
                int scaledProgress = 50 + (int) (percentage * 0.4);
                OperationContext.notify(listener::onProgressUpdate, scaledProgress, message);
            };
        } else {
            trackingProxyListener = null;
        }

        List<List<DetectedObject>> cleanFramesObjects = new ArrayList<>();
        for (SourceExtractor.ExtractionResult extRes : cleanFramesData) {
            cleanFramesObjects.add(extRes.objects);
        }

        if (DiagnosticContext.enabled()) {
            DiagnosticContext.println("DEBUG: TrackLinker input frame timing summary:");
            for (int i = 0; i < cleanFrames.size(); i++) {
                ImageFrame frame = cleanFrames.get(i);
                int objectCount = cleanFramesData.get(i).objects.size();
                DiagnosticContext.printf(
                        "   Frame %d [%s] -> timestamp=%d exposure=%d detectedObjects=%d%n",
                        frame.sequenceIndex,
                        frame.filename,
                        frame.timestamp,
                        frame.exposureDuration,
                        objectCount
                );
            }
        }

        stageStartedNanos = System.nanoTime();
        notifyStarted(observer, "Tracking");
        TrackLinker.TrackingResult trackResult = OperationContext.compute(StageId.TRACKING, null, () -> TrackLinker.findMovingObjects(
                cleanFramesObjects,
                masterStars,
                config,
                trackingProxyListener,
                sensorWidth,
                sensorHeight,
                context.frameRegistrations,
                observer
        ));
        context.labelTrackerFrames(trackResult.telemetry);

        telemetry.recordStage(PipelineTelemetry.Stage.TRACKING, stageStartedNanos);
        // Map the track results back to our main telemetry object
        telemetry.totalMasterStarsIdentified = masterStars.size();
        telemetry.totalTracksFound = trackResult.tracks.size();
        telemetry.totalAnomaliesFound = trackResult.anomalies.size();
        telemetry.trackerTelemetry = trackResult.telemetry;
        telemetry.slowMoverTelemetry = smTelemetry;
        if (observer != null) {
            notifyObserver(observer, new PipelineStage.Tracking(trackResult.tracks, trackResult.anomalies, trackResult.masterVetoMask));
        }

        List<ResidualTransientAnalysis.CommonMotionGroup> commonMotionGroups = List.of();
        if (config.enableCommonMotionRejection) {
            stageStartedNanos = System.nanoTime();
            if (listener != null) {
                OperationContext.notify(listener::onProgressUpdate, 92, "Filtering common-motion transients...");
            }
            commonMotionGroups = OperationContext.compute(StageId.COMMON_MOTION, null,
                    () -> CommonMotionRejector.reject(trackResult.unclassifiedTransients, config));
            telemetry.recordStage(PipelineTelemetry.Stage.COMMON_MOTION, stageStartedNanos);
        }
        if (observer != null) {
            notifyObserver(observer, new PipelineStage.CommonMotion(config.enableCommonMotionRejection, commonMotionGroups));
        }
        ResidualTransientAnalysis residual = ResidualTransientAnalysis.empty();
        if (config.enableResidualTransientAnalysis) {
            notifyStarted(observer, "Residual analysis");
            stageStartedNanos = System.nanoTime();
            if (listener != null) {
                OperationContext.notify(listener::onProgressUpdate, 92, "Analyzing residual transients...");
            }
            residual = OperationContext.compute(StageId.RESIDUAL_ANALYSIS, null, () -> ResidualTransientAnalyzer.analyze(trackResult.unclassifiedTransients, config));
            telemetry.recordStage(PipelineTelemetry.Stage.RESIDUAL_ANALYSIS, stageStartedNanos);
        }
        if (observer != null) {
            notifyObserver(observer, new PipelineStage.ResidualAnalysis(config.enableResidualTransientAnalysis, residual));
        }
        ResidualTransientAnalysis residualTransientAnalysis = new ResidualTransientAnalysis(
                residual.localRescueCandidates, residual.localActivityClusters, commonMotionGroups);

        // =================================================================
        // PHASE 5 (Variable-Star Photometry)
        // =================================================================
        VariableStarAnalysis variableStarAnalysis = VariableStarAnalysis.empty();
        if (config.enableVariableStarDetection) {
            notifyStarted(observer, "Photometry");
            stageStartedNanos = System.nanoTime();
            if (DiagnosticContext.enabled()) {
                DiagnosticContext.println("\n--- JTRANSIENT: PHASE 5 (Variable-Star Photometry) ---");
            }
            if (listener != null) {
                OperationContext.notify(listener::onProgressUpdate, 93, "Measuring stationary-star photometry...");
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
            photometryInput.dataArea = context.dataArea;
            if (listener != null) {
                // Photometry 0-100% maps to 93-99%.
                photometryInput.progress = (percentage, message) -> OperationContext.notify(listener::onProgressUpdate, 93 + (int) (percentage * 0.06), message);
            }
            photometryInput.liveView = observer;
            variableStarAnalysis = OperationContext.call(StageId.PHOTOMETRY, null, () -> VariableStarAnalyzer.analyze(photometryInput, config));
            telemetry.photometryTelemetry = variableStarAnalysis.telemetry;
            telemetry.recordStage(PipelineTelemetry.Stage.PHOTOMETRY, stageStartedNanos);
        }
        if (observer != null) {
            notifyObserver(observer, new PipelineStage.Photometry(config.enableVariableStarDetection, variableStarAnalysis));
        }

        if (listener != null) {
            OperationContext.notify(listener::onProgressUpdate, 100, "Processing Complete!");
        }

        // --- FINAL POST-PROCESSING ---
        if (listener != null) {
            OperationContext.notify(listener::onProgressUpdate, 100, "Maximum Stack generation complete.");
        }

        telemetry.processingTimeMs = (System.nanoTime() - context.startNanos) / 1_000_000L;
        return PipelineResult.builder()
                .tracks(trackResult.tracks)
                .telemetry(telemetry)
                .masterStackData(masterStackData)
                .masterStars(masterStars)
                .slowMoverAnalysis(slowMoverAnalysis)
                .slowMoverStackData(slowMoverStackData)
                .slowMoverMedianVetoMask(slowMoverMedianVetoMask)
                .slowMoverCandidates(slowMoverCandidates)
                .anomalies(trackResult.anomalies)
                .allTransients(trackResult.allTransients)
                .unclassifiedTransients(trackResult.unclassifiedTransients)
                .residualTransientAnalysis(residualTransientAnalysis)
                .masterVetoMask(trackResult.masterVetoMask)
                .driftPoints(context.driftPoints)
                .maximumStackData(maximumStackData)
                .variableStarAnalysis(variableStarAnalysis)
                .dataArea(context.dataArea)
                .cameraFixedGroups(trackResult.cameraFixedGroups)
                .build();
    }

    /**
     * The result of a run that stops after quality control because fewer than {@link #MIN_KEPT_FRAMES} frames were
     * kept: the extraction and quality-control telemetry, the drift points and the data area of the kept frames. No
     * later stage runs, so the lists are empty, the stacks and masks are null and the observer hears no stage.
     */
    private static PipelineResult tooFewFramesResult(ExtractedFramesContext context, TransientEngineProgressListener listener) {
        PipelineTelemetry telemetry = context.telemetry;
        if (DiagnosticContext.enabled()) {
            DiagnosticContext.println("DEBUG: Quality control kept " + telemetry.totalFramesKept + " of " + telemetry.totalFramesLoaded
                    + " frames; at least " + MIN_KEPT_FRAMES + " are needed. The run stops here.");
        }
        if (listener != null) {
            OperationContext.notify(listener::onProgressUpdate, 100, "Only " + telemetry.totalFramesKept + " of the "
                    + MIN_KEPT_FRAMES + " frames needed passed quality control; stopping.");
        }
        telemetry.processingTimeMs = (System.nanoTime() - context.startNanos) / 1_000_000L;
        return PipelineResult.builder()
                .tooFewFrames(true)
                .tracks(List.of())
                .telemetry(telemetry)
                .masterStars(List.of())
                .slowMoverCandidates(List.of())
                .anomalies(List.of())
                .allTransients(List.of())
                .unclassifiedTransients(List.of())
                .residualTransientAnalysis(ResidualTransientAnalysis.empty())
                .driftPoints(context.driftPoints)
                .dataArea(context.dataArea)
                .build();
    }

    /**
     * Closes an idle engine and waits for its worker threads to end. An active operation must first be cancelled and
     * awaited; closing during an operation is rejected.
     */
    public void shutdown() {
        synchronized (this) {
            if (running) {
                throw new IllegalStateException("Cancel and await the active operation before closing JTransientEngine.");
            }
            closed = true;
        }
        workers.close();
    }

    @Override
    public void close() {
        shutdown();
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
