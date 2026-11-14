/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.LiveSnapshot;
import io.github.ppissias.jtransient.engine.PipelineStageObserver;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;
import io.github.ppissias.jtransient.execution.CancellationScope;
import io.github.ppissias.jtransient.execution.OperationContext;
import io.github.ppissias.jtransient.execution.WorkerPool;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
import io.github.ppissias.jtransient.quality.FrameQualityAnalyzer;
import io.github.ppissias.jtransient.telemetry.FaintMoverSearchTelemetry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The faint-mover search (experimental, on by default): digital tracking of point-like movers too faint for the single
 * frames. The kept frames are shifted along trial motions and added, so a mover's light adds up while the noise
 * averages down.
 *
 * <p>Per tile of the frame, each kept frame becomes a residual: the frame minus the median stack (the static field),
 * minus the frame's own local background (the sky level changes during a session), divided by the frame's robust
 * noise and clipped, so that one exceptional pixel or frame cannot dominate. Star cores of the median stack,
 * registration fill and the run's streaks are set to zero, and each residual is filtered with a Gaussian of the stars'
 * size. Shifting the filtered residuals by whole pixels along a motion and adding them gives that motion's
 * PSF-filtered stack; its score is the stack over the robust noise of such stacks in the tile.</p>
 *
 * <p>Every position is searched over a coarse grid of motions, the local maxima are refined on a finer grid, and a
 * candidate must reach {@code faintMoverMinScore}, be supported by both halves of the session, score clearly above the
 * stationary stack, not follow a moving track of the run or meet a moving track or a much stronger peak in some frame
 * (a side lobe, which that frame's light makes), and score clearly above the stack at one camera position (a hot pixel
 * or defect, whose light adds up best where the registrations put one camera pixel).</p>
 *
 * <p>Tiles are searched in batches in parallel; the caller limits the workers with {@link Plan#bytesPerTile}. The search
 * stops after {@code faintMoverMaxSeconds} and then says so ({@link FaintMoverSearchTelemetry#truncated}).</p>
 */
public final class FaintMoverSearch {
    /** Star size when no kept frame has a measurement (Gaussian FWHM), in pixels. */
    static final double FALLBACK_FWHM = FaintStreakSearch.FALLBACK_SIGMA * 2.3548;
    /** Fewest searched frames; each half of the session needs half of them. */
    static final int MIN_FRAMES = 8;
    /**
     * A gap between consecutive kept frames longer than this, in minutes, separates sessions; only the session with the
     * most frames is searched, since one constant motion does not hold across nights.
     */
    static final double MAX_GAP_MINUTES = 120.0;
    /** Most coarse motions; a wider speed range for the session is not searched. */
    static final int MAX_MOTIONS = 100_000;
    /** Largest share of the maximum heap one tile's work may need. */
    static final double MAX_TILE_HEAP_SHARE = 0.25;
    /** Side of a tile's searched area, in pixels. */
    static final int TILE = 512;
    /** Tiles searched between two looks at the clock and the progress. */
    static final int TILES_PER_BATCH = 32;
    /** Rows of a tile added together for every motion, so the rows read stay in the processor's cache. */
    private static final int STRIP_ROWS = 16;
    /** Residuals are clipped to this many robust sigmas. */
    static final double CLIP_SIGMA = 5.0;
    /** Side of the running mean that estimates a residual's local background, in pixels. */
    static final int BACKGROUND_BOX = 41;
    /** A residual is clipped to this many sigmas about its centre before the local background is taken. */
    static final double BACKGROUND_CLIP_SIGMA = 3.0;
    /** Median-stack pixels this many sky sigmas above the tile's sky are star cores. */
    static final double STAR_CORE_SIGMA = 8.0;
    /** Star cores and fill are grown by these many pixels. */
    static final int STAR_CORE_GROW = 3;
    static final int FILL_GROW = 4;
    /** A streak is masked out to this many FWHM beyond its footprint. */
    static final double STREAK_MASK_FWHM = 1.0;
    /** Coarse local maxima at this share of the minimum score or more are refined. */
    static final double COARSE_KEEP_FRACTION = 0.7;
    /** A coarse local maximum is the largest value within this many pixels. */
    static final int PEAK_RADIUS = 6;
    /** Fine steps per coarse step; the refinement searches one coarse step to either side. */
    static final int FINE_STEPS = 4;
    /** The refined peak is the best position within this many pixels of the coarse one. */
    static final int REFINE_RADIUS = 2;
    /** {@code faintMoverSearchSpeed} values: every pixel and frame; cheaper where little is lost; cheaper still. */
    static final int SPEED_NORMAL = 0;
    static final int SPEED_FAST = 1;
    static final int SPEED_FASTER = 2;
    /**
     * By search speed: with stars at least this wide, in pixels, the coarse search adds {@link #COARSE_BIN} x
     * {@link #COARSE_BIN} pixels. At the fast speed binned stars stay at least 1.75 px wide; at the faster one, 1 px.
     * Refinement and every check use the full-resolution residuals.
     */
    static final double[] COARSE_BIN_MIN_FWHM = {Double.POSITIVE_INFINITY, 3.5, 2.0};
    static final int COARSE_BIN = 2;
    /**
     * By search speed: the coarse search adds consecutive frames together while the fastest motion moves at most this
     * many star sizes from the first to the last of them. At the fast speed no frame lies more than a third of the star
     * size from the group's path; at the faster one, two thirds.
     */
    static final double[] COARSE_GROUP_MAX_FWHM = {0.0, 0.65, 1.3};
    /**
     * Each half of the session must reach this score along the candidate's motion, counting only its frames whose path
     * position lies outside the masks (star cores, fill, streaks). A half with fewer such frames than
     * {@link #MIN_HALF_FRAMES} and {@link #MIN_HALF_COVERAGE} of its frames cannot be tested; at least one half must be.
     */
    static final double MIN_HALF_SCORE = 3.0;
    /**
     * With {@code enableFaintMoverFrameLimit}, the score must still pass when no frame adds more than the path's typical
     * frame plus this many frame noises. A faint mover is about equally faint in every frame; a few frames carrying the
     * same residual, such as faint star tails past the mask, are not.
     */
    static final double FRAME_LIMIT_SIGMA = 3.0;
    static final int MIN_HALF_FRAMES = 3;
    static final double MIN_HALF_COVERAGE = 1.0 / 3.0;
    /**
     * The score must exceed by this much the best stationary score within {@link #STATIONARY_RADIUS} pixels, and the best
     * camera-fixed score within as many pixels of the path's median camera position (a hot pixel or a defect, whose
     * light adds up best at one camera position).
     */
    static final double MIN_MOTION_MARGIN = 2.0;
    static final int STATIONARY_RADIUS = 4;
    /**
     * A peak whose camera-fixed score reaches this share of its score is taken for a hot pixel or defect: much of its
     * light adds up at one camera position. Real movers measured 0.16 (median) to 0.42 of their score on Klangwolke-2,
     * partly from the best noise peak among the positions tried.
     */
    static final double MAX_CAMERA_SHARE = 0.5;
    /** Peaks within this many FWHM and {@link #DUPLICATE_MOTION_STEPS} coarse steps of a stronger one repeat it. */
    static final double DUPLICATE_RADIUS_FWHM = 2.5;
    static final double DUPLICATE_MOTION_STEPS = 1.5;
    /** A peak follows a moving track when its path passes the track's detections within this many FWHM (median). */
    static final double TRACK_MATCH_FWHM = 2.0;
    /**
     * A peak whose path passes within this many FWHM of a moving track's detection, or of a peak
     * {@link #SIDE_LOBE_SCORE_RATIO} times stronger, in any kept frame, is its side lobe: that frame's light adds to it.
     */
    static final double SIDE_LOBE_RADIUS_FWHM = 2.0;
    static final double SIDE_LOBE_SCORE_RATIO = 2.0;
    /** Side of a candidate's stacked cutout, in pixels. */
    static final int CUTOUT = 31;
    /** Most candidates reported; further ones are counted as below the score. */
    static final int MAX_CANDIDATES = 1000;
    /** Copied review pixels retained per run; selection and the small candidate cutouts use separate storage. */
    static final long REVIEW_BUDGET_BYTES = 64L * 1024 * 1024;
    /** Fewest unmasked pixels of a tile for its noise; a tile with fewer is not searched. */
    static final int MIN_GOOD_PIXELS = 1000;
    /** Values sampled for a robust centre and spread. */
    private static final int SAMPLE_SIZE = 20000;

    private FaintMoverSearch() {
    }

    /** What the search reads; set by the engine. */
    public static final class Input {
        /** The kept frames, in sequence order; borrowed, not modified. */
        public List<ImageFrame> frames = List.of();
        /** Their quality metrics, in the same order (for the star size), or null. */
        public List<FrameQualityAnalyzer.FrameMetrics> frameMetrics;
        /** The median stack of the kept frames. */
        public short[][] masterStack;
        /** Where every kept frame has data, or null for the whole frame. */
        public DataArea dataArea;
        /** Frame registrations by sequence index, for the camera check; null or empty skips it. */
        public Map<Integer, FrameRegistration> registrations;
        /** Streak detections to mask, one list per kept frame in kept-frame order, or null. */
        public List<List<DetectedObject>> streaks;
        /** The run's tracks; peaks following a point track, and their side lobes, are not reported. */
        public List<Track> tracks = List.of();
        /** Phase-local estimated work from 0 to 100, or null; notices may come from joined tile workers and must be thread-safe. */
        public TransientEngineProgressListener progress;
        /** Optional requested scalar work previews; called synchronously on joined workers, with no borrowed pixels. */
        public PipelineStageObserver observer;
    }

    /** The search domain of one run, worked out before any pixel is read. */
    public static final class Plan {
        /** Why the search cannot run, or null. */
        public final String skippedReason;
        /** Tiles to search. */
        public final int tileCount;
        /** Memory one tile's work needs, in bytes. */
        public final long bytesPerTile;
        /**
         * Additions of the coarse search: coarse motions x coarse frames x pixels of the data area / bin², zero when the
         * search cannot run. The search's run time grows about in proportion: about 7e-11 seconds per unit on a 16-core
         * desktop at full resolution (measured 2026-11-03); binned additions cost more each.
         */
        public final double estimatedWork;
        final Input input;
        final DetectionConfig config;
        final FaintMoverSearchTelemetry telemetry;
        final double fwhm;
        /** The searched frames, in kept order, and their indices in the input's lists. */
        final List<ImageFrame> frames;
        final int[] frameIndex;
        final double[] minutes;
        final long referenceTimeMillis;
        final int[] timeOrder;
        final double[][] motions;
        /** Whole-pixel shifts of the searched frames along each coarse motion, [motion][frame]. */
        final int[][] shiftX;
        final int[][] shiftY;
        /** Pixels added in each direction for the coarse search: 1, or {@link #COARSE_BIN} for wide stars. */
        final int coarseBin;
        /**
         * The searched frames (indices in kept order) added into each coarse frame; one frame each, in kept order, when
         * no frames are close enough in time.
         */
        final int[][] coarseGroups;
        /** Whole binned-pixel shifts of the coarse frames along each coarse motion, [motion][coarse frame]. */
        final int[][] coarseShiftX;
        final int[][] coarseShiftY;
        final double coarseStep;
        final double fineStep;
        final double maxSpeed;
        final int margin;
        final float[] kernel;
        final List<int[]> tiles;
        final long startedNanos;

        private Plan(String skippedReason, Input input, DetectionConfig config, FaintMoverSearchTelemetry telemetry,
                     double fwhm, List<ImageFrame> frames, int[] frameIndex, double[] minutes, long referenceTimeMillis,
                     int[] timeOrder, double[][] motions, double coarseStep, double maxSpeed, int margin, float[] kernel,
                     int coarseBin, int[][] coarseGroups, List<int[]> tiles, long startedNanos) {
            this.skippedReason = skippedReason;
            this.input = input;
            this.config = config;
            this.telemetry = telemetry;
            this.fwhm = fwhm;
            this.frames = frames;
            this.frameIndex = frameIndex;
            this.minutes = minutes;
            this.referenceTimeMillis = referenceTimeMillis;
            this.timeOrder = timeOrder;
            this.motions = motions;
            this.shiftX = new int[motions.length][minutes.length];
            this.shiftY = new int[motions.length][minutes.length];
            for (int mi = 0; mi < motions.length; mi++) {
                for (int k = 0; k < minutes.length; k++) {
                    shiftX[mi][k] = (int) Math.round(motions[mi][0] * minutes[k]);
                    shiftY[mi][k] = (int) Math.round(motions[mi][1] * minutes[k]);
                }
            }
            this.coarseBin = coarseBin;
            this.coarseGroups = coarseGroups;
            this.coarseShiftX = new int[motions.length][coarseGroups.length];
            this.coarseShiftY = new int[motions.length][coarseGroups.length];
            for (int gi = 0; gi < coarseGroups.length; gi++) {
                double groupMinutes = 0;
                for (int k : coarseGroups[gi]) {
                    groupMinutes += minutes[k] / coarseGroups[gi].length;
                }
                for (int mi = 0; mi < motions.length; mi++) {
                    coarseShiftX[mi][gi] = (int) Math.round(motions[mi][0] * groupMinutes / coarseBin);
                    coarseShiftY[mi][gi] = (int) Math.round(motions[mi][1] * groupMinutes / coarseBin);
                }
            }
            this.coarseStep = coarseStep;
            this.fineStep = coarseStep / FINE_STEPS;
            this.maxSpeed = maxSpeed;
            this.margin = margin;
            this.kernel = kernel;
            this.tiles = tiles;
            this.startedNanos = startedNanos;
            this.tileCount = tiles.size();
            this.bytesPerTile = tileBytes(margin, frames.size(), coarseBin, coarseGroups.length);
            double pixels = input.dataArea != null ? input.dataArea.getPixelCount()
                    : input.masterStack != null ? (double) input.masterStack.length * input.masterStack[0].length : 0;
            this.estimatedWork = skippedReason == null
                    ? (double) motions.length * coarseGroups.length * pixels / (coarseBin * coarseBin) : 0.0;
            telemetry.coarseBin = coarseBin;
            telemetry.coarseFrames = coarseGroups.length;
        }
    }

    /** The candidates of one run, strongest first, with what the search did. */
    public static final class Result {
        public final List<FaintMoverCandidate> candidates;
        public final FaintMoverSearchTelemetry telemetry;

        Result(List<FaintMoverCandidate> candidates, FaintMoverSearchTelemetry telemetry) {
            this.candidates = Collections.unmodifiableList(candidates);
            this.telemetry = telemetry;
        }
    }

    /** A peak of one tile that passed the tile's checks. */
    private static final class Peak {
        final double x;
        final double y;
        final double vx;
        final double vy;
        final double score;
        final double firstHalf;
        final double secondHalf;
        final double stationary;
        final double camera;
        final float[][] cutout;
        final PeakLocation location;

        /** Owns a refined score cutout and the coordinates used to copy bounded review evidence. */
        Peak(double x, double y, double vx, double vy, double score, double firstHalf, double secondHalf,
             double stationary, double camera, float[][] cutout, PeakLocation location) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.score = score;
            this.firstHalf = firstHalf;
            this.secondHalf = secondHalf;
            this.stationary = stationary;
            this.camera = camera;
            this.cutout = cutout;
            this.location = location;
        }
    }

    /** Original tile geometry, scoring-grid centre and stack noise; contains no borrowed image buffers. */
    private record PeakLocation(int tx, int ty, int width, int height, int x, int y, double noise) { }

    /** Temporary preprocessed search pixels; only small review rectangles may outlive this tile. */
    private record PreparedTile(float[][] residuals, boolean[] bad, BitSet[] streakMasks) { }

    /** Owns at most 64 MiB of copied review evidence, shared by this search's workers, strongest peaks first. */
    private static final class EvidencePool {
        private final TreeMap<Peak, FaintMoverDiagnostics> reviews = new TreeMap<>(peakOrder()
                .thenComparingDouble(p -> p.vx).thenComparingDouble(p -> p.vy)
                .thenComparingInt(p -> p.location.tx).thenComparingInt(p -> p.location.ty)
                .thenComparingInt(p -> p.location.x).thenComparingInt(p -> p.location.y)
                .thenComparingInt(p -> p.location.width).thenComparingInt(p -> p.location.height)
                .thenComparingDouble(p -> p.location.noise));
        private long bytes;

        /** Creates one run-owned evidence budget; no full tile is retained. */
        EvidencePool() { }

        /** Copies a passing peak while its search tile is resident; eviction affects review evidence only. */
        synchronized void capture(Plan plan, Peak peak, PreparedTile tile) {
            var evidence = diagnostics(plan, peak, tile);
            if (evidence.unavailableReason != null) return;
            var previous = reviews.put(peak, evidence);
            if (previous != null) bytes -= diagnosticBytes(previous);
            bytes += diagnosticBytes(evidence);
            while (bytes > REVIEW_BUDGET_BYTES) bytes -= diagnosticBytes(reviews.pollLastEntry().getValue());
        }

        /** Transfers only a selected candidate's small arrays; missing evidence has an explicit reason. */
        synchronized FaintMoverDiagnostics take(Plan plan, Peak peak) {
            var evidence = reviews.remove(peak);
            if (evidence != null) bytes -= diagnosticBytes(evidence);
            return evidence == null ? diagnostics(plan, peak, null) : evidence;
        }
    }

    /** Matches the deterministic order used for candidate selection and the review-only memory budget. */
    private static Comparator<Peak> peakOrder() {
        return Comparator.comparingDouble((Peak p) -> p.score).reversed().thenComparingDouble(p -> p.y).thenComparingDouble(p -> p.x);
    }

    /** Accounts owned review pixels and per-frame metadata without counting any borrowed tile buffers. */
    private static long diagnosticBytes(FaintMoverDiagnostics d) {
        return 4L * d.frames.size() * d.width * d.height + 8L * CUTOUT * CUTOUT + 128L * d.frames.size();
    }

    /** The peaks of one tile and its counts. */
    private static final class TileResult {
        final List<Peak> peaks = new ArrayList<>();
        long searchedPixels;
        int refinedPeaks;
        int belowScore;
        int rejectedSplitHalf;
        int rejectedFewFrames;
        int rejectedStationary;
        /** Whether the time limit stopped the tile; its peaks are then left out. */
        boolean timedOut;
        int followingCamera;
    }

    /**
     * Works out the search domain: the frames' times, the speeds, the motion grid and the tiles.
     *
     * @param input the frames and the run's products; only read
     * @param config the run's configuration; only read
     * @return the plan, with a reason when the search cannot run
     */
    public static Plan plan(Input input, DetectionConfig config) {
        long started = System.nanoTime();
        FaintMoverSearchTelemetry telemetry = new FaintMoverSearchTelemetry();
        telemetry.searchSpeed = Math.max(SPEED_NORMAL, Math.min(SPEED_FASTER, config.faintMoverSearchSpeed));
        List<ImageFrame> kept = input.frames;
        telemetry.frames = kept.size();
        FaintStreakSearch.Width width = input.frameMetrics != null
                ? FaintStreakSearch.width(input.frameMetrics, config) : null;
        double fwhm = width != null && width.frames > 0 ? width.sigma * 2.3548 : FALLBACK_FWHM;
        telemetry.fwhmPixels = fwhm;
        telemetry.fwhmFromStars = width != null && width.frames > 0;
        if (kept.size() < MIN_FRAMES || input.masterStack == null) {
            return skipped(input, config, telemetry, "fewer than " + MIN_FRAMES + " kept frames", started);
        }
        double[] midMillis = new double[kept.size()];
        for (int k = 0; k < kept.size(); k++) {
            ImageFrame frame = kept.get(k);
            if (frame.timestamp <= 0) {
                return skipped(input, config, telemetry, "kept frames without a capture time", started);
            }
            midMillis[k] = frame.timestamp + Math.max(0L, frame.exposureDuration) / 2.0;
        }
        int[] frameIndex = largestSession(midMillis);
        int n = frameIndex.length;
        telemetry.frames = n;
        telemetry.framesOutsideSession = kept.size() - n;
        if (n < MIN_FRAMES) {
            return skipped(input, config, telemetry, String.format(java.util.Locale.US,
                    "fewer than %d kept frames without a gap of over %.0f minutes", MIN_FRAMES, MAX_GAP_MINUTES), started);
        }
        List<ImageFrame> frames = new ArrayList<>(n);
        double meanMillis = 0;
        for (int index : frameIndex) {
            frames.add(kept.get(index));
            meanMillis += midMillis[index] / n;
        }
        telemetry.searchedFrameIndices = frames.stream().map(frame -> frame.sequenceIndex).toList();
        long reference = Math.round(meanMillis);
        double[] minutes = new double[n];
        double first = Double.MAX_VALUE;
        double last = -Double.MAX_VALUE;
        double maxAbs = 0;
        for (int k = 0; k < n; k++) {
            minutes[k] = (midMillis[frameIndex[k]] - reference) / 60000.0;
            first = Math.min(first, minutes[k]);
            last = Math.max(last, minutes[k]);
            maxAbs = Math.max(maxAbs, Math.abs(minutes[k]));
        }
        double span = last - first;
        telemetry.sessionMinutes = span;
        if (!(span > 0)) {
            return skipped(input, config, telemetry, "the kept frames have one capture time", started);
        }
        double minSpeed = config.faintMoverMinDisplacementFwhm * fwhm / span;
        double maxSpeed = config.faintMoverMaxSpeedPixelsPerMinute;
        double coarse = fwhm / maxAbs;
        telemetry.minSpeedPixelsPerMinute = minSpeed;
        telemetry.maxSpeedPixelsPerMinute = maxSpeed;
        telemetry.coarseStepPixelsPerMinute = coarse;
        telemetry.fineStepPixelsPerMinute = coarse / FINE_STEPS;
        if (!(maxSpeed > minSpeed)) {
            return skipped(input, config, telemetry, String.format(java.util.Locale.US,
                    "the slowest motion (%.3f px/min) is not below the fastest", minSpeed), started);
        }
        double reach = maxSpeed / coarse;
        if (Math.PI * reach * reach > 2.0 * MAX_MOTIONS) {
            return skipped(input, config, telemetry, String.format(java.util.Locale.US,
                    "the speed range needs over %d trial motions for this session; lower the fastest motion",
                    MAX_MOTIONS), started);
        }
        double[][] motions = motionGrid(minSpeed, maxSpeed, coarse);
        telemetry.coarseMotions = motions.length;
        if (motions.length > MAX_MOTIONS) {
            return skipped(input, config, telemetry, String.format(java.util.Locale.US,
                    "the speed range needs over %d trial motions for this session; lower the fastest motion",
                    MAX_MOTIONS), started);
        }

        Integer[] order = new Integer[n];
        for (int k = 0; k < n; k++) {
            order[k] = k;
        }
        Arrays.sort(order, Comparator.comparingDouble(k -> minutes[k]));
        int[] timeOrder = new int[n];
        for (int k = 0; k < n; k++) {
            timeOrder[k] = order[k];
        }

        int speed = telemetry.searchSpeed;
        int coarseBin = fwhm >= COARSE_BIN_MIN_FWHM[speed] ? COARSE_BIN : 1;
        int[][] coarseGroups = speed == SPEED_NORMAL ? singleFrames(n)
                : coarseGroups(minutes, timeOrder, maxSpeed, COARSE_GROUP_MAX_FWHM[speed] * fwhm);
        float[] kernel = FaintStreakSearch.kernel(fwhm / 2.3548);
        int maxShift = (int) Math.ceil((maxSpeed + coarse) * maxAbs);
        int margin = maxShift + Math.max(kernel.length / 2 + 2, CUTOUT / 2 + REFINE_RADIUS + 1);
        long tileBytes = tileBytes(margin, n, coarseBin, coarseGroups.length);
        if (tileBytes > MAX_TILE_HEAP_SHARE * Runtime.getRuntime().maxMemory()) {
            return skipped(input, config, telemetry, String.format(java.util.Locale.US,
                    "one tile's work would need %d MB; lower the fastest motion or use fewer frames",
                    tileBytes >> 20), started);
        }
        int height = input.masterStack.length;
        int widthPx = input.masterStack[0].length;
        List<int[]> tiles = new ArrayList<>();
        for (int ty = 0; ty < height; ty += TILE) {
            for (int tx = 0; tx < widthPx; tx += TILE) {
                int w = Math.min(TILE, widthPx - tx);
                int h = Math.min(TILE, height - ty);
                if (overlapsData(input.dataArea, tx, ty, w, h)) {
                    tiles.add(new int[]{tx, ty, w, h});
                }
            }
        }
        telemetry.tiles = tiles.size();
        return new Plan(null, input, config, telemetry, fwhm, frames, frameIndex, minutes, reference, timeOrder,
                motions, coarse, maxSpeed, margin, kernel, coarseBin, coarseGroups, tiles, started);
    }

    /**
     * The searched frames added into each coarse frame: frames consecutive in time while the fastest motion moves at
     * most {@code limit} pixels from the first to the last of them. When no two frames are that close, each frame is its
     * own coarse frame, in kept order, and the coarse search is the full one.
     *
     * @param minutes the searched frames' mid-exposure times, in kept order
     * @param timeOrder kept-order indices sorted by time
     * @param maxSpeed the fastest searched motion, in pixels per minute
     * @param limit the largest motion within a group, in pixels
     * @return kept-order indices of each coarse frame's frames
     */
    static int[][] coarseGroups(double[] minutes, int[] timeOrder, double maxSpeed, double limit) {
        int n = minutes.length;
        List<int[]> groups = new ArrayList<>();
        int from = 0;
        while (from < n) {
            int to = from + 1;
            while (to < n && (minutes[timeOrder[to]] - minutes[timeOrder[from]]) * maxSpeed <= limit) {
                to++;
            }
            groups.add(Arrays.copyOfRange(timeOrder, from, to));
            from = to;
        }
        return groups.size() == n ? singleFrames(n) : groups.toArray(new int[0][]);
    }

    /**
     * The coarse search's reductions at a search speed, for run-time estimates made before a run: the pixels added in
     * each direction and the number of coarse frames. The search applies the same rules to its kept frames and the star
     * size it measures.
     *
     * @param speed {@code faintMoverSearchSpeed}; out-of-range values are treated as the nearest speed
     * @param fwhm star size (Gaussian FWHM), in pixels
     * @param midMinutes the frames' mid-exposure times, in minutes, in any order; not modified
     * @param maxSpeed the fastest searched motion, in pixels per minute
     * @return {bin, coarse frames}
     */
    public static int[] coarseSearchShape(int speed, double fwhm, double[] midMinutes, double maxSpeed) {
        int s = Math.max(SPEED_NORMAL, Math.min(SPEED_FASTER, speed));
        int bin = fwhm >= COARSE_BIN_MIN_FWHM[s] ? COARSE_BIN : 1;
        int n = midMinutes.length;
        if (s == SPEED_NORMAL) {
            return new int[]{bin, n};
        }
        double[] sorted = midMinutes.clone();
        Arrays.sort(sorted);
        int[] order = new int[n];
        for (int k = 0; k < n; k++) {
            order[k] = k;
        }
        return new int[]{bin, coarseGroups(sorted, order, maxSpeed, COARSE_GROUP_MAX_FWHM[s] * fwhm).length};
    }

    /** Each of n searched frames as its own coarse frame, in kept order. */
    private static int[][] singleFrames(int n) {
        int[][] single = new int[n][];
        for (int k = 0; k < n; k++) {
            single[k] = new int[]{k};
        }
        return single;
    }

    /**
     * The indices, in kept order, of the frames in the largest block whose consecutive mid-exposures are at most
     * {@link #MAX_GAP_MINUTES} apart; the earliest block on a tie.
     */
    static int[] largestSession(double[] midMillis) {
        int n = midMillis.length;
        Integer[] order = new Integer[n];
        for (int k = 0; k < n; k++) {
            order[k] = k;
        }
        Arrays.sort(order, Comparator.comparingDouble(k -> midMillis[k]));
        int bestFrom = 0;
        int bestTo = 0;
        int from = 0;
        for (int i = 1; i <= n; i++) {
            if (i == n || midMillis[order[i]] - midMillis[order[i - 1]] > MAX_GAP_MINUTES * 60000.0) {
                if (i - from > bestTo - bestFrom) {
                    bestFrom = from;
                    bestTo = i;
                }
                from = i;
            }
        }
        int[] block = new int[bestTo - bestFrom];
        for (int i = bestFrom; i < bestTo; i++) {
            block[i - bestFrom] = order[i];
        }
        Arrays.sort(block);
        return block;
    }

    /**
     * Memory one tile's work needs: every searched frame's residual of the tile with its margin, the coarse frames when
     * they are binned or grouped, and the maps.
     */
    static long tileBytes(int margin, int frames, int coarseBin, int coarseFrames) {
        long side = TILE + 2L * margin;
        long coarseSide = side / coarseBin + 1;
        long coarse = coarseBin == 1 && coarseFrames == frames ? 0L : coarseSide * coarseSide * 4L * coarseFrames;
        return side * side * (4L * frames + 12L) + coarse + (long) TILE * TILE * 20L;
    }

    /**
     * Runs a plan.
     *
     * @param plan from {@link #plan}
     * @return the candidates, strongest first, and the search's counts
     * @throws ExecutionException when a tile's work fails
     */
    public static Result search(Plan plan) throws ExecutionException {
        FaintMoverSearchTelemetry telemetry = plan.telemetry;
        if (plan.skippedReason != null) {
            if (plan.input.progress != null) OperationContext.notify(plan.input.progress::onProgressUpdate, 0,
                    "Faint-mover search skipped: " + plan.skippedReason);
            return new Result(List.of(), telemetry);
        }
        long deadline = plan.startedNanos + (long) (plan.config.faintMoverMaxSeconds * 1e9);
        List<Peak> peaks = new ArrayList<>();
        EvidencePool evidence = new EvidencePool();
        SearchProgress progress = new SearchProgress(plan, deadline);
        int searched = 0;
        for (int from = 0; from < plan.tiles.size(); from += TILES_PER_BATCH) {
            CancellationScope.check();
            if (System.nanoTime() > deadline) {
                telemetry.truncated = true;
                break;
            }
            List<Callable<TileResult>> tasks = new ArrayList<>();
            for (int[] tile : plan.tiles.subList(from, Math.min(plan.tiles.size(), from + TILES_PER_BATCH))) {
                TileProgress tileProgress = new TileProgress(progress, from + tasks.size(), tile);
                tasks.add(() -> searchTile(plan, tile[0], tile[1], tile[2], tile[3], deadline, evidence, tileProgress));
            }
            int finished = 0;
            for (TileResult tile : WorkerPool.invokeAll(tasks)) {
                if (tile.timedOut) {
                    telemetry.truncated = true;
                    continue;
                }
                finished++;
                peaks.addAll(tile.peaks);
                telemetry.searchedPixels += tile.searchedPixels;
                telemetry.refinedPeaks += tile.refinedPeaks;
                telemetry.belowScore += tile.belowScore;
                telemetry.rejectedSplitHalf += tile.rejectedSplitHalf;
                telemetry.rejectedFewFrames += tile.rejectedFewFrames;
                telemetry.rejectedStationary += tile.rejectedStationary;
                telemetry.followingCamera += tile.followingCamera;
            }
            searched += finished;
            if (telemetry.truncated) {
                break;
            }
        }
        telemetry.tilesSearched = searched;
        List<FaintMoverCandidate> candidates = select(plan, peaks, evidence);
        telemetry.candidates = candidates.size();
        if (plan.input.progress != null) OperationContext.notify(plan.input.progress::onProgressUpdate,
                telemetry.truncated ? progress.percentage() : 100,
                "Faint-mover search " + (telemetry.truncated ? "truncated at its time limit" : "completed")
                        + ": " + searched + " of " + plan.tiles.size() + " tiles; " + candidates.size() + " candidates retained.");
        telemetry.elapsedMs = (System.nanoTime() - plan.startedNanos) / 1_000_000L;
        return new Result(candidates, telemetry);
    }

    /** Run-owned work accounting and a globally throttled notice; no observer means no preview construction. */
    private static final class SearchProgress {
        private final Plan plan;
        private final long deadline;
        private final AtomicLong units = new AtomicLong();
        private final AtomicLong nextNotice = new AtomicLong();
        private final AtomicInteger finishedTiles = new AtomicInteger();

        /** Captures only this admitted search and its unchanged cooperative deadline. */
        private SearchProgress(Plan plan, long deadline) { this.plan = plan; this.deadline = deadline; }

        /** Returns monotonic phase-local estimated work, reserving 100 percent for completed selection. */
        private int percentage() { return (int) Math.min(99, 100 * units.get() / (1_000_000L * plan.tiles.size())); }

        /** Serializes at most four notices a second across tile workers and asks before allocating preview coordinates. */
        private void notice(TileProgress tile, String phase, int motion) {
            if (plan.input.progress == null && plan.input.observer == null) return;
            long now = System.nanoTime(), next = nextNotice.get();
            if (now < next || now >= deadline || !nextNotice.compareAndSet(next, now + 250_000_000L)) return;
            synchronized (this) {
                if (System.nanoTime() >= deadline) return;
                String caption = "Faint-mover search estimated work " + percentage() + "%: " + finishedTiles.get() + " of "
                        + plan.tiles.size() + " tiles completed; tile " + (tile.index + 1) + " — " + phase
                        + (motion >= 0 ? " " + (motion + 1) + " of " + plan.motions.length : "");
                if (plan.input.progress != null) OperationContext.notify(plan.input.progress::onProgressUpdate, percentage(), caption);
                var observer = plan.input.observer;
                if (observer == null) return;
                try {
                    if (!observer.wantsUpdate()) return;
                    double vx = motion < 0 ? 0 : plan.motions[motion][0], vy = motion < 0 ? 0 : plan.motions[motion][1];
                    double cx = tile.bounds[0] + tile.bounds[2] / 2.0, cy = tile.bounds[1] + tile.bounds[3] / 2.0;
                    int frame = plan.frames.get(plan.frames.size() / 2).sequenceIndex;
                    observer.liveUpdate(new LiveSnapshot("Faint-mover search", frame,
                            caption + "\nExample source frame " + (frame + 1) + "; "
                                    + (motion < 0 ? "search area" : "trial motion, not a retained candidate"),
                            new double[]{tile.bounds[0], tile.bounds[0] + tile.bounds[2], cx + vx * plan.minutes[0], cx + vx * plan.minutes[plan.minutes.length - 1]},
                            new double[]{tile.bounds[1], tile.bounds[1] + tile.bounds[3], cy + vy * plan.minutes[0], cy + vy * plan.minutes[plan.minutes.length - 1]}));
                } catch (RuntimeException ignored) {
                    // Observer failures cannot alter detection, cancellation or the scientific products.
                    if (io.github.ppissias.jtransient.execution.DiagnosticContext.enabled())
                        io.github.ppissias.jtransient.execution.DiagnosticContext.println("DEBUG: A faint-search work preview failed; the search continues: " + ignored);
                }
            }
        }
    }

    /** One worker's monotonically accumulated tile work, with shared notices rather than a rendering or image queue. */
    private static final class TileProgress {
        private final SearchProgress owner;
        private final int index;
        private final int[] bounds;
        private int previous;

        /** Borrows fixed tile geometry for the joined worker lifetime. */
        private TileProgress(SearchProgress owner, int index, int[] bounds) { this.owner = owner; this.index = index; this.bounds = bounds; }

        /** Advances only this tile; percentages estimate preparation/coarse/refinement work, not retained-candidate confidence. */
        private void update(int units, String phase, int motion) {
            if (owner.plan.input.progress == null && owner.plan.input.observer == null) return;
            owner.units.addAndGet(Math.max(0, units - previous)); previous = Math.max(previous, units);
            owner.notice(this, phase, motion);
        }

        /** Counts a completed tile after its existing search checks, without changing scientific tile telemetry. */
        private void finish() { owner.finishedTiles.incrementAndGet(); update(1_000_000, "tile finished", -1); }
    }

    private static Plan skipped(Input input, DetectionConfig config, FaintMoverSearchTelemetry telemetry,
                                String reason, long started) {
        telemetry.skippedReason = reason;
        telemetry.elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        return new Plan(reason, input, config, telemetry, telemetry.fwhmPixels, List.of(), new int[0], new double[0],
                0L, new int[0], new double[0][], 1.0, 0.0, 0, new float[]{1f}, 1, new int[0][], List.of(), started);
    }

    /** The coarse motions, {vx, vy} in pixels per minute, on a square grid with the speeds in the range. */
    static double[][] motionGrid(double minSpeed, double maxSpeed, double step) {
        int reach = (int) Math.floor(maxSpeed / step + 1e-9);
        List<double[]> motions = new ArrayList<>();
        for (int i = -reach; i <= reach; i++) {
            for (int j = -reach; j <= reach; j++) {
                double vx = i * step;
                double vy = j * step;
                double speed = Math.hypot(vx, vy);
                if (speed >= minSpeed - 1e-9 && speed <= maxSpeed + 1e-9) {
                    motions.add(new double[]{vx, vy});
                }
            }
        }
        return motions.toArray(new double[0][]);
    }

    private static boolean overlapsData(DataArea area, int tx, int ty, int w, int h) {
        if (area == null) {
            return true;
        }
        for (int y = ty; y < ty + h && y < area.getHeight(); y++) {
            if (area.getRowStart(y) <= area.getRowEnd(y) && area.getRowEnd(y) >= tx && area.getRowStart(y) < tx + w) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------------------
    // One tile
    // ------------------------------------------------------------------------------------------------------------

    /** Prepares the original tile residuals; the caller releases these full-tile buffers after searching. */
    private static PreparedTile prepareTile(Plan plan, int tx, int ty, int w, int h, TileProgress progress) {
        Input input = plan.input;
        int n = plan.frames.size();
        int m = plan.margin;
        int width = w + 2 * m;
        int height = h + 2 * m;
        int pixels = width * height;
        float[][] residuals = new float[n][];
        boolean[] bad = readResiduals(plan, tx - m, ty - m, width, height, residuals);
        float[] scratch = new float[pixels];
        float[] weights = new float[pixels];
        BitSet[] streakMasks = new BitSet[n];
        for (int k = 0; k < n; k++) {
            CancellationScope.check();
            progress.update(10_000 + 90_000 * k / n, "preparing residuals", -1);
            normalise(residuals[k], bad, width, height, scratch, weights);
            int index = plan.frameIndex[k];
            if (input.streaks != null && index < input.streaks.size() && input.streaks.get(index) != null
                    && !input.streaks.get(index).isEmpty()) {
                BitSet masked = new BitSet(pixels);
                maskStreaks(residuals[k], input.streaks.get(index), tx - m, ty - m, width, height,
                        STREAK_MASK_FWHM * plan.fwhm, masked);
                streakMasks[k] = masked.isEmpty() ? null : masked;
            }
            filter(residuals[k], width, height, plan.kernel, scratch);
        }
        return new PreparedTile(residuals, bad, streakMasks);
    }

    /** Searches a tile using temporary preprocessed residuals, without retaining those buffers in peaks. */
    private static TileResult searchTile(Plan plan, int tx, int ty, int w, int h, long deadline, EvidencePool evidence, TileProgress progress) {
        progress.update(0, "preparing residuals", -1);
        TileResult result = new TileResult();
        int m = plan.margin;
        int width = w + 2 * m;
        PreparedTile prepared = prepareTile(plan, tx, ty, w, h, progress);
        progress.update(100_000, "coarse trial motions", 0);
        float[][] residuals = prepared.residuals;
        boolean[] bad = prepared.bad;
        BitSet[] streakMasks = prepared.streakMasks;

        boolean[] innerBad = new boolean[w * h];
        int good = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean b = bad[(y + m) * width + x + m];
                innerBad[y * w + x] = b;
                if (!b) {
                    good++;
                }
            }
        }
        if (good < MIN_GOOD_PIXELS) {
            progress.finish();
            return result;
        }
        result.searchedPixels = good;
        double noise = stackNoise(plan, residuals, width, w, h, innerBad);
        if (!(noise > 0)) {
            progress.finish();
            return result;
        }
        // Coarse search on the coarse frames: the best score and its motion at every coarse position.
        CoarseTile coarse = coarseTile(plan, residuals, innerBad, w, h);
        double coarseNoise = coarse.residuals() == residuals ? noise : coarseNoise(plan, coarse);
        if (!(coarseNoise > 0)) {
            progress.finish();
            return result;
        }
        float[][] coarseFrames = coarse.residuals();
        int cw = coarse.width();
        int ch = coarse.height();
        float scale = (float) (1.0 / (coarseFrames.length * coarseNoise));
        double[][] motions = plan.motions;
        float[] best = new float[cw * ch];
        Arrays.fill(best, Float.NEGATIVE_INFINITY);
        int[] bestMotion = new int[cw * ch];
        float[] acc = new float[STRIP_ROWS * cw];
        for (int s0 = 0; s0 < ch; s0 += STRIP_ROWS) {
            int rows = Math.min(STRIP_ROWS, ch - s0);
            if (System.nanoTime() > deadline) {
                result.timedOut = true;
                return result;
            }
            for (int mi = 0; mi < motions.length; mi++) {
                CancellationScope.check();
                if ((mi & 63) == 0) progress.update(100_000 + (int) (800_000L * ((long) (s0 / STRIP_ROWS) * motions.length + mi)
                        / (((ch + STRIP_ROWS - 1L) / STRIP_ROWS) * motions.length)), "coarse trial motions", mi);
                Arrays.fill(acc, 0, rows * cw, 0f);
                addCoarseFrames(coarseFrames, plan.coarseShiftX[mi], plan.coarseShiftY[mi], coarse.stride(),
                        coarse.margin(), s0, rows, cw, acc);
                int base = s0 * cw;
                for (int i = 0; i < rows * cw; i++) {
                    float value = acc[i] * scale;
                    if (value > best[base + i]) {
                        best[base + i] = value;
                        bestMotion[base + i] = mi;
                    }
                }
            }
        }
        boolean[] coarseBad = coarse.bad();
        for (int i = 0; i < best.length; i++) {
            if (coarseBad[i]) {
                best[i] = Float.NEGATIVE_INFINITY;
            }
        }

        // Local maxima, refined and checked on the full-resolution residuals of every frame.
        progress.update(900_000, "checking provisional peaks", -1);
        int bin = plan.coarseBin;
        float[] localMax = maxFilter(best, cw, ch, Math.max(1, Math.round((float) PEAK_RADIUS / bin)));
        double keep = COARSE_KEEP_FRACTION * plan.config.faintMoverMinScore;
        for (int cy = 0; cy < ch; cy++) {
            progress.update(900_000 + (int) (100_000L * cy / ch), "checking provisional peaks", -1);
            for (int cx = 0; cx < cw; cx++) {
                int i = cy * cw + cx;
                if (best[i] < keep || best[i] != localMax[i]) {
                    continue;
                }
                CancellationScope.check();
                result.refinedPeaks++;
                int x = cx;
                int y = cy;
                if (bin > 1) {
                    int pixel = bestPixelOfBin(plan, residuals, width, w, h, cx, cy, bestMotion[i]);
                    x = pixel % w;
                    y = pixel / w;
                }
                Peak peak = refine(plan, residuals, bad, streakMasks, width, w, h, x, y, motions[bestMotion[i]], noise,
                        tx, ty, result);
                if (peak != null) {
                    result.peaks.add(peak);
                    evidence.capture(plan, peak, prepared);
                }
            }
        }
        progress.finish();
        return result;
    }

    /**
     * A tile's coarse frames, which the coarse search adds along each motion: the full-resolution residuals themselves,
     * or their sums over {@code bin x bin} pixels and over each group of frames. Coarse pixel (x, y) of the inner area
     * covers inner pixels {@code bin * x} to {@code bin * x + bin - 1}; {@code bad} marks coarse pixels whose pixels are
     * all left out. Rows are {@code stride} apart and start {@code margin} coarse pixels before the inner area.
     */
    private record CoarseTile(float[][] residuals, int stride, int margin, int width, int height, boolean[] bad) { }

    /** Builds a tile's coarse frames from its full-resolution residuals, which stay unchanged. */
    private static CoarseTile coarseTile(Plan plan, float[][] residuals, boolean[] innerBad, int w, int h) {
        int bin = plan.coarseBin;
        int[][] groups = plan.coarseGroups;
        int m = plan.margin;
        int width = w + 2 * m;
        if (bin == 1 && groups.length == residuals.length) {
            return new CoarseTile(residuals, width, m, w, h, innerBad);
        }
        int height = h + 2 * m;
        int coarseMargin = m / bin;
        int offset = m - coarseMargin * bin;
        int stride = (width - offset) / bin;
        int rows = (height - offset) / bin;
        float[][] coarse = new float[groups.length][];
        for (int gi = 0; gi < groups.length; gi++) {
            CancellationScope.check();
            float[] c = new float[stride * rows];
            for (int k : groups[gi]) {
                float[] r = residuals[k];
                for (int cy = 0; cy < rows; cy++) {
                    int dst = cy * stride;
                    for (int dy = 0; dy < bin; dy++) {
                        int src = (offset + cy * bin + dy) * width + offset;
                        for (int cx = 0; cx < stride; cx++) {
                            int s = src + cx * bin;
                            float sum = 0f;
                            for (int dx = 0; dx < bin; dx++) {
                                sum += r[s + dx];
                            }
                            c[dst + cx] += sum;
                        }
                    }
                }
            }
            coarse[gi] = c;
        }
        int cw = (w + bin - 1) / bin;
        int ch = (h + bin - 1) / bin;
        boolean[] bad = new boolean[cw * ch];
        for (int cy = 0; cy < ch; cy++) {
            for (int cx = 0; cx < cw; cx++) {
                boolean allBad = true;
                for (int y = cy * bin; y < Math.min(h, cy * bin + bin) && allBad; y++) {
                    for (int x = cx * bin; x < Math.min(w, cx * bin + bin); x++) {
                        if (!innerBad[y * w + x]) {
                            allBad = false;
                            break;
                        }
                    }
                }
                bad[cy * cw + cx] = allBad;
            }
        }
        return new CoarseTile(coarse, stride, coarseMargin, cw, ch, bad);
    }

    /**
     * Adds the coarse frames, shifted along one motion, into {@code acc} for a strip of coarse rows, four frames per
     * pass over {@code acc}. Each sum is formed frame by frame in order, exactly as when adding them one at a time.
     */
    private static void addCoarseFrames(float[][] frames, int[] sx, int[] sy, int stride, int margin, int s0, int rows,
                                        int w, float[] acc) {
        int n = frames.length;
        int k = 0;
        for (; k + 4 <= n; k += 4) {
            float[] r0 = frames[k];
            float[] r1 = frames[k + 1];
            float[] r2 = frames[k + 2];
            float[] r3 = frames[k + 3];
            for (int row = 0; row < rows; row++) {
                int line = (s0 + row + margin) * stride + margin;
                int a0 = line + sy[k] * stride + sx[k];
                int a1 = line + sy[k + 1] * stride + sx[k + 1];
                int a2 = line + sy[k + 2] * stride + sx[k + 2];
                int a3 = line + sy[k + 3] * stride + sx[k + 3];
                int dst = row * w;
                for (int x = 0; x < w; x++) {
                    float sum = acc[dst + x];
                    sum += r0[a0 + x];
                    sum += r1[a1 + x];
                    sum += r2[a2 + x];
                    sum += r3[a3 + x];
                    acc[dst + x] = sum;
                }
            }
        }
        for (; k < n; k++) {
            float[] r = frames[k];
            for (int row = 0; row < rows; row++) {
                int src = (s0 + row + margin + sy[k]) * stride + margin + sx[k];
                int dst = row * w;
                for (int x = 0; x < w; x++) {
                    acc[dst + x] += r[src + x];
                }
            }
        }
    }

    /** The robust spread of the coarse frames' stacks along the same reference motion as {@link #stackNoise}. */
    private static double coarseNoise(Plan plan, CoarseTile coarse) {
        int reference = referenceMotion(plan);
        int[] sx = plan.coarseShiftX[reference];
        int[] sy = plan.coarseShiftY[reference];
        float[][] frames = coarse.residuals();
        int g = frames.length;
        int cw = coarse.width();
        int ch = coarse.height();
        int cm = coarse.margin();
        int stride = coarse.stride();
        float[] sample = new float[Math.min(SAMPLE_SIZE, cw * ch)];
        int step = Math.max(1, cw * ch / sample.length);
        int count = 0;
        for (int i = 0; i < cw * ch && count < sample.length; i += step) {
            if (coarse.bad()[i]) {
                continue;
            }
            int x = i % cw;
            int y = i / cw;
            float sum = 0f;
            for (int k = 0; k < g; k++) {
                sum += frames[k][(y + cm + sy[k]) * stride + x + cm + sx[k]];
            }
            sample[count++] = sum / g;
        }
        return count > 0 ? centreAndSpread(sample, count)[1] : 0.0;
    }

    /**
     * The full-resolution inner pixel ({@code y * w + x}) with the largest stack along the coarse motion within a
     * coarse pixel's bin and a one-pixel border. Refinement starts there, as it does from a full-resolution coarse maximum.
     */
    private static int bestPixelOfBin(Plan plan, float[][] residuals, int width, int w, int h, int cx, int cy, int motion) {
        int bin = plan.coarseBin;
        int[] sx = plan.shiftX[motion];
        int[] sy = plan.shiftY[motion];
        int n = residuals.length;
        double top = Double.NEGATIVE_INFINITY;
        int best = Math.min(h - 1, cy * bin) * w + Math.min(w - 1, cx * bin);
        for (int y = Math.max(0, cy * bin - 1); y <= Math.min(h - 1, cy * bin + bin); y++) {
            for (int x = Math.max(0, cx * bin - 1); x <= Math.min(w - 1, cx * bin + bin); x++) {
                double value = stackAt(residuals, plan.margin, width, x, y, sx, sy, 0, n);
                if (value > top) {
                    top = value;
                    best = y * w + x;
                }
            }
        }
        return best;
    }

    /**
     * Fills {@code residuals} with frame minus median stack over the area (stored values; outside the image zero) and
     * returns the pixels to leave out: outside the image or the data area, registration fill in any frame, and star
     * cores of the median stack, each grown.
     */
    private static boolean[] readResiduals(Plan plan, int x0, int y0, int width, int height, float[][] residuals) {
        Input input = plan.input;
        short[][] master = input.masterStack;
        int imageHeight = master.length;
        int imageWidth = master[0].length;
        DataArea area = input.dataArea;
        boolean[] fill = new boolean[width * height];
        for (int yy = 0; yy < height; yy++) {
            int y = y0 + yy;
            int base = yy * width;
            if (y < 0 || y >= imageHeight) {
                Arrays.fill(fill, base, base + width, true);
                continue;
            }
            int from = area != null ? area.getRowStart(y) : 0;
            int to = area != null ? area.getRowEnd(y) : imageWidth - 1;
            for (int xx = 0; xx < width; xx++) {
                int x = x0 + xx;
                if (x < 0 || x >= imageWidth || x < from || x > to) {
                    fill[base + xx] = true;
                }
            }
        }
        for (int k = 0; k < residuals.length; k++) {
            CancellationScope.check();
            short[][] frame = plan.frames.get(k).pixelData;
            float[] r = new float[width * height];
            residuals[k] = r;
            for (int yy = 0; yy < height; yy++) {
                int y = y0 + yy;
                if (y < 0 || y >= imageHeight) {
                    continue;
                }
                short[] row = frame[y];
                short[] masterRow = master[y];
                int base = yy * width;
                for (int xx = 0; xx < width; xx++) {
                    int i = base + xx;
                    if (fill[i]) {
                        continue;
                    }
                    int x = x0 + xx;
                    short value = row[x];
                    if (value <= DataArea.FILL_THRESHOLD) {
                        fill[i] = true;
                        continue;
                    }
                    r[i] = value - masterRow[x];
                }
            }
        }

        // Star cores of the median stack, above the tile's sky.
        float[] sample = new float[Math.min(SAMPLE_SIZE, width * height)];
        int stride = Math.max(1, width * height / sample.length);
        int count = 0;
        for (int i = 0; i < width * height && count < sample.length; i += stride) {
            if (!fill[i]) {
                int y = y0 + i / width;
                int x = x0 + i % width;
                sample[count++] = master[y][x];
            }
        }
        boolean[] core = new boolean[width * height];
        if (count > 0) {
            double[] centreSpread = centreAndSpread(sample, count);
            double limit = centreSpread[0] + STAR_CORE_SIGMA * Math.max(centreSpread[1], 1.0);
            for (int yy = 0; yy < height; yy++) {
                int y = y0 + yy;
                if (y < 0 || y >= imageHeight) {
                    continue;
                }
                for (int xx = 0; xx < width; xx++) {
                    int x = x0 + xx;
                    int i = yy * width + xx;
                    if (!fill[i] && master[y][x] > limit) {
                        core[i] = true;
                    }
                }
            }
        }
        boolean[] bad = grow(fill, width, height, FILL_GROW);
        boolean[] cores = grow(core, width, height, STAR_CORE_GROW);
        for (int i = 0; i < bad.length; i++) {
            bad[i] |= cores[i];
        }
        return bad;
    }

    /**
     * Turns one frame's residual into noise units: minus its local background (a masked running mean of the residual
     * clipped about its centre), divided by its robust spread and clipped; left-out pixels become zero. A residual
     * without spread becomes zero.
     */
    private static void normalise(float[] r, boolean[] bad, int width, int height, float[] values, float[] weights) {
        float[] sample = new float[Math.min(SAMPLE_SIZE, r.length)];
        int stride = Math.max(1, r.length / sample.length);
        int count = 0;
        for (int i = 0; i < r.length && count < sample.length; i += stride) {
            if (!bad[i]) {
                sample[count++] = r[i];
            }
        }
        double[] centreSpread = count > 0 ? centreAndSpread(sample, count) : new double[]{0, 0};
        double centre = centreSpread[0];
        double sigma = centreSpread[1];
        if (!(sigma > 0)) {
            Arrays.fill(r, 0f);
            return;
        }
        float low = (float) (centre - BACKGROUND_CLIP_SIGMA * sigma);
        float high = (float) (centre + BACKGROUND_CLIP_SIGMA * sigma);
        for (int i = 0; i < r.length; i++) {
            if (bad[i]) {
                values[i] = 0f;
                weights[i] = 0f;
            } else {
                values[i] = Math.max(low, Math.min(high, r[i]));
                weights[i] = 1f;
            }
        }
        boxMean(values, weights, width, height, BACKGROUND_BOX / 2);
        float inverse = (float) (1.0 / sigma);
        float clip = (float) CLIP_SIGMA;
        for (int i = 0; i < r.length; i++) {
            if (bad[i]) {
                r[i] = 0f;
            } else {
                float background = weights[i] > 0 ? values[i] / weights[i] : (float) centre;
                r[i] = Math.max(-clip, Math.min(clip, (r[i] - background) * inverse));
            }
        }
    }

    /** Running sums of values and weights over a square of the radius, in place (separable). */
    private static void boxMean(float[] values, float[] weights, int width, int height, int radius) {
        float[] line = new float[Math.max(width, height)];
        float[] lineWeights = new float[line.length];
        for (int y = 0; y < height; y++) {
            int base = y * width;
            runningSum(values, weights, base, 1, width, radius, line, lineWeights);
        }
        for (int x = 0; x < width; x++) {
            runningSum(values, weights, x, width, height, radius, line, lineWeights);
        }
    }

    private static void runningSum(float[] values, float[] weights, int start, int step, int length, int radius,
                                   float[] line, float[] lineWeights) {
        for (int i = 0; i < length; i++) {
            line[i] = values[start + i * step];
            lineWeights[i] = weights[start + i * step];
        }
        double sum = 0;
        double weight = 0;
        for (int i = 0; i <= Math.min(radius, length - 1); i++) {
            sum += line[i];
            weight += lineWeights[i];
        }
        for (int i = 0; i < length; i++) {
            values[start + i * step] = (float) sum;
            weights[start + i * step] = (float) weight;
            int enter = i + radius + 1;
            int leave = i - radius;
            if (enter < length) {
                sum += line[enter];
                weight += lineWeights[enter];
            }
            if (leave >= 0) {
                sum -= line[leave];
                weight -= lineWeights[leave];
            }
        }
    }

    /** Sets the residual to zero within each streak's footprint, grown by the extra distance, and marks the pixels. */
    private static void maskStreaks(float[] r, List<DetectedObject> streaks, int x0, int y0, int width, int height,
                                    double extra, BitSet masked) {
        for (DetectedObject streak : streaks) {
            if (streak == null) {
                continue;
            }
            double halfLength = Math.max(streak.majorExtent, 1.0) / 2 + extra;
            double halfWidth = Math.max(streak.minorExtent, 1.0) / 2 + extra;
            double cos = Math.cos(streak.angle);
            double sin = Math.sin(streak.angle);
            double reach = Math.hypot(halfLength, halfWidth);
            int fromX = Math.max(0, (int) Math.floor(streak.x - reach) - x0);
            int toX = Math.min(width - 1, (int) Math.ceil(streak.x + reach) - x0);
            int fromY = Math.max(0, (int) Math.floor(streak.y - reach) - y0);
            int toY = Math.min(height - 1, (int) Math.ceil(streak.y + reach) - y0);
            for (int yy = fromY; yy <= toY; yy++) {
                double dy = yy + y0 - streak.y;
                for (int xx = fromX; xx <= toX; xx++) {
                    double dx = xx + x0 - streak.x;
                    if (Math.abs(dx * cos + dy * sin) <= halfLength && Math.abs(-dx * sin + dy * cos) <= halfWidth) {
                        r[yy * width + xx] = 0f;
                        masked.set(yy * width + xx);
                    }
                }
            }
        }
    }

    /** Separable convolution with the kernel, zero outside the area, in place. */
    private static void filter(float[] r, int width, int height, float[] kernel, float[] scratch) {
        int radius = kernel.length / 2;
        for (int y = 0; y < height; y++) {
            int base = y * width;
            for (int x = 0; x < width; x++) {
                float sum = 0f;
                int from = Math.max(-radius, -x);
                int to = Math.min(radius, width - 1 - x);
                for (int d = from; d <= to; d++) {
                    sum += kernel[d + radius] * r[base + x + d];
                }
                scratch[base + x] = sum;
            }
        }
        for (int y = 0; y < height; y++) {
            int from = Math.max(-radius, -y);
            int to = Math.min(radius, height - 1 - y);
            int base = y * width;
            Arrays.fill(r, base, base + width, 0f);
            for (int d = from; d <= to; d++) {
                float weight = kernel[d + radius];
                int source = (y + d) * width;
                for (int x = 0; x < width; x++) {
                    r[base + x] += weight * scratch[source + x];
                }
            }
        }
    }

    /** The index of the first coarse motion nearest to halfway through the speed range; its stacks set a tile's noise. */
    private static int referenceMotion(Plan plan) {
        int reference = 0;
        double target = (plan.telemetry.minSpeedPixelsPerMinute + plan.maxSpeed) / 2;
        for (int mi = 0; mi < plan.motions.length; mi++) {
            double[] motion = plan.motions[mi];
            double[] current = plan.motions[reference];
            if (Math.abs(Math.hypot(motion[0], motion[1]) - target) < Math.abs(Math.hypot(current[0], current[1]) - target)) {
                reference = mi;
            }
        }
        return reference;
    }

    /** Robust spread of the stack along a motion halfway through the speed range, over the unmasked positions. */
    private static double stackNoise(Plan plan, float[][] residuals, int width, int w, int h, boolean[] innerBad) {
        double[] reference = plan.motions[referenceMotion(plan)];
        int n = residuals.length;
        int m = plan.margin;
        int[] sx = new int[n];
        int[] sy = new int[n];
        for (int k = 0; k < n; k++) {
            sx[k] = (int) Math.round(reference[0] * plan.minutes[k]);
            sy[k] = (int) Math.round(reference[1] * plan.minutes[k]);
        }
        float[] sample = new float[Math.min(SAMPLE_SIZE, w * h)];
        int stride = Math.max(1, w * h / sample.length);
        int count = 0;
        for (int i = 0; i < w * h && count < sample.length; i += stride) {
            if (innerBad[i]) {
                continue;
            }
            int x = i % w;
            int y = i / w;
            float sum = 0f;
            for (int k = 0; k < n; k++) {
                sum += residuals[k][(y + m + sy[k]) * width + x + m + sx[k]];
            }
            sample[count++] = sum / n;
        }
        return count > 0 ? centreAndSpread(sample, count)[1] : 0.0;
    }

    /**
     * Refines a coarse peak on the fine motion grid within {@link #REFINE_RADIUS} pixels and applies the tile's checks;
     * null when a check fails (counted in the result).
     */
    private static Peak refine(Plan plan, float[][] residuals, boolean[] bad, BitSet[] streakMasks, int width, int w,
                               int h, int x, int y, double[] coarse, double noise, int tx, int ty, TileResult result) {
        int n = residuals.length;
        double scale = 1.0 / (n * noise);
        double top = Double.NEGATIVE_INFINITY;
        double topVx = coarse[0];
        double topVy = coarse[1];
        int topX = x;
        int topY = y;
        int[] sx = new int[n];
        int[] sy = new int[n];
        double limit = plan.maxSpeed + plan.coarseStep;
        for (int i = -FINE_STEPS; i <= FINE_STEPS; i++) {
            for (int j = -FINE_STEPS; j <= FINE_STEPS; j++) {
                double vx = coarse[0] + i * plan.fineStep;
                double vy = coarse[1] + j * plan.fineStep;
                if (Math.hypot(vx, vy) > limit) {
                    continue;
                }
                shifts(plan, vx, vy, sx, sy);
                for (int dy = -REFINE_RADIUS; dy <= REFINE_RADIUS; dy++) {
                    for (int dx = -REFINE_RADIUS; dx <= REFINE_RADIUS; dx++) {
                        int px = x + dx;
                        int py = y + dy;
                        if (px < 0 || py < 0 || px >= w || py >= h) {
                            continue;
                        }
                        double value = stackAt(residuals, plan.margin, width, px, py, sx, sy, 0, n) * scale;
                        if (value > top) {
                            top = value;
                            topVx = vx;
                            topVy = vy;
                            topX = px;
                            topY = py;
                        }
                    }
                }
            }
        }
        if (top < plan.config.faintMoverMinScore) {
            result.belowScore++;
            return null;
        }
        shifts(plan, topVx, topVy, sx, sy);
        int half = n / 2;
        double firstHalf = halfScore(plan, residuals, bad, streakMasks, width, topX, topY, sx, sy, 0, half, noise);
        double secondHalf = halfScore(plan, residuals, bad, streakMasks, width, topX, topY, sx, sy, half, n, noise);
        if (Double.isNaN(firstHalf) && Double.isNaN(secondHalf) || firstHalf < MIN_HALF_SCORE
                || secondHalf < MIN_HALF_SCORE) {
            result.rejectedSplitHalf++;
            return null;
        }
        if (plan.config.enableFaintMoverFrameLimit
                && frameLimitedScore(plan, residuals, bad, streakMasks, width, topX, topY, sx, sy, noise) < plan.config.faintMoverMinScore) {
            result.rejectedFewFrames++;
            return null;
        }
        double stationary = Double.NEGATIVE_INFINITY;
        int[] zero = new int[n];
        for (int dy = -STATIONARY_RADIUS; dy <= STATIONARY_RADIUS; dy++) {
            for (int dx = -STATIONARY_RADIUS; dx <= STATIONARY_RADIUS; dx++) {
                stationary = Math.max(stationary,
                        stackAt(residuals, plan.margin, width, topX + dx, topY + dy, zero, zero, 0, n) * scale);
            }
        }
        if (top - stationary < MIN_MOTION_MARGIN) {
            result.rejectedStationary++;
            return null;
        }
        double camera = cameraScore(plan, residuals, width, h + 2 * plan.margin, tx, ty, topX, topY, topVx, topVy,
                noise);
        if (top - camera < MIN_MOTION_MARGIN || camera >= MAX_CAMERA_SHARE * top) {
            result.followingCamera++;
            return null;
        }
        int r = CUTOUT / 2;
        float[][] cutout = new float[CUTOUT][CUTOUT];
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                cutout[dy + r][dx + r] = (float) (stackAt(residuals, plan.margin, width, topX + dx, topY + dy, sx, sy, 0, n) * scale);
            }
        }
        double subX = vertex(cutout[r][r - 1], cutout[r][r], cutout[r][r + 1]);
        double subY = vertex(cutout[r - 1][r], cutout[r][r], cutout[r + 1][r]);
        return new Peak(tx + topX + subX, ty + topY + subY, topVx, topVy, top, firstHalf, secondHalf, stationary,
                camera, cutout, new PeakLocation(tx, ty, w, h, topX, topY, noise));
    }

    /**
     * The path's score when no frame adds more than its typical unmasked frame plus {@link #FRAME_LIMIT_SIGMA} frame
     * noises. The stack's spread gives the frame noise, as the frames are independent.
     */
    private static double frameLimitedScore(Plan plan, float[][] residuals, boolean[] bad, BitSet[] streakMasks, int width,
                                             int x, int y, int[] sx, int[] sy, double noise) {
        int n = residuals.length;
        float[] values = new float[n];
        float[] unmasked = new float[n];
        int valid = 0;
        for (int k = 0; k < n; k++) {
            int i = (y + plan.margin + sy[k]) * width + x + plan.margin + sx[k];
            values[k] = residuals[k][i];
            if (!bad[i] && (streakMasks[k] == null || !streakMasks[k].get(i))) {
                unmasked[valid++] = values[k];
            }
        }
        double limit = centreAndSpread(unmasked, valid)[0] + FRAME_LIMIT_SIGMA * noise * Math.sqrt(n);
        double sum = 0;
        for (float value : values) {
            sum += Math.min(value, limit);
        }
        return sum / (n * noise);
    }

    /**
     * Best score of the stack at one camera position within {@link #STATIONARY_RADIUS} pixels of the median camera
     * position of the peak's path, on the full-stack scale over the frames whose position lies in the tile; each frame
     * is read where its registration puts that camera position. Negative infinity without usable registrations for
     * at least half of the frames.
     */
    private static double cameraScore(Plan plan, float[][] residuals, int width, int height, int tx, int ty, int x,
                                      int y, double vx, double vy, double noise) {
        Map<Integer, FrameRegistration> registrations = plan.input.registrations;
        int n = plan.frames.size();
        if (registrations == null || registrations.isEmpty()) {
            return Double.NEGATIVE_INFINITY;
        }
        FrameRegistration[] frameRegistration = new FrameRegistration[n];
        double[] us = new double[n];
        double[] vs = new double[n];
        int usable = 0;
        for (int k = 0; k < n; k++) {
            FrameRegistration registration = registrations.get(plan.frames.get(k).sequenceIndex);
            if (registration == null || !registration.usable) {
                continue;
            }
            frameRegistration[k] = registration;
            double t = plan.minutes[k];
            double[] camera = registration.toCamera(tx + x + vx * t, ty + y + vy * t);
            us[usable] = camera[0];
            vs[usable] = camera[1];
            usable++;
        }
        int needed = Math.max(MIN_FRAMES, (n + 1) / 2);
        if (usable < needed) {
            return Double.NEGATIVE_INFINITY;
        }
        double u0 = median(us, usable);
        double v0 = median(vs, usable);
        int x0 = tx - plan.margin;
        int y0 = ty - plan.margin;
        double best = Double.NEGATIVE_INFINITY;
        for (int dv = -STATIONARY_RADIUS; dv <= STATIONARY_RADIUS; dv++) {
            for (int du = -STATIONARY_RADIUS; du <= STATIONARY_RADIUS; du++) {
                double sum = 0;
                int count = 0;
                for (int k = 0; k < n; k++) {
                    if (frameRegistration[k] == null) {
                        continue;
                    }
                    double[] frame = frameRegistration[k].toFrame(u0 + du, v0 + dv);
                    int ax = (int) Math.round(frame[0]) - x0;
                    int ay = (int) Math.round(frame[1]) - y0;
                    if (ax >= 0 && ay >= 0 && ax < width && ay < height) {
                        sum += residuals[k][ay * width + ax];
                        count++;
                    }
                }
                if (count >= needed) {
                    best = Math.max(best, sum / (noise * Math.sqrt((double) n * count)));
                }
            }
        }
        return best;
    }

    private static double median(double[] values, int count) {
        double[] copy = Arrays.copyOf(values, count);
        Arrays.sort(copy);
        return count % 2 == 1 ? copy[count / 2] : 0.5 * (copy[count / 2 - 1] + copy[count / 2]);
    }

    /**
     * Best score within {@link #REFINE_RADIUS} pixels of the frames in time order [from, to), on the full-stack scale,
     * normalised by the frames whose path position lies outside the masks; NaN when too few do.
     */
    private static double halfScore(Plan plan, float[][] residuals, boolean[] bad, BitSet[] streakMasks, int width,
                                    int x, int y, int[] sx, int[] sy, int from, int to, double noise) {
        int valid = 0;
        for (int o = from; o < to; o++) {
            int k = plan.timeOrder[o];
            int i = (y + plan.margin + sy[k]) * width + x + plan.margin + sx[k];
            if (!bad[i] && (streakMasks[k] == null || !streakMasks[k].get(i))) {
                valid++;
            }
        }
        if (valid < Math.max(MIN_HALF_FRAMES, (int) Math.ceil((to - from) * MIN_HALF_COVERAGE))) {
            return Double.NaN;
        }
        double scale = 1.0 / (noise * Math.sqrt((double) residuals.length * valid));
        double best = Double.NEGATIVE_INFINITY;
        for (int dy = -REFINE_RADIUS; dy <= REFINE_RADIUS; dy++) {
            for (int dx = -REFINE_RADIUS; dx <= REFINE_RADIUS; dx++) {
                double sum = 0;
                for (int o = from; o < to; o++) {
                    int k = plan.timeOrder[o];
                    sum += residuals[k][(y + dy + plan.margin + sy[k]) * width + x + dx + plan.margin + sx[k]];
                }
                best = Math.max(best, sum * scale);
            }
        }
        return best;
    }

    private static void shifts(Plan plan, double vx, double vy, int[] sx, int[] sy) {
        for (int k = 0; k < sx.length; k++) {
            sx[k] = (int) Math.round(vx * plan.minutes[k]);
            sy[k] = (int) Math.round(vy * plan.minutes[k]);
        }
    }

    private static double stackAt(float[][] residuals, int margin, int width, int x, int y, int[] sx, int[] sy,
                                  int from, int to) {
        double sum = 0;
        for (int k = from; k < to; k++) {
            sum += residuals[k][(y + margin + sy[k]) * width + x + margin + sx[k]];
        }
        return sum;
    }

    /** Offset of a parabola's vertex through three equally spaced values, within half a pixel. */
    private static double vertex(double left, double centre, double right) {
        double curvature = left - 2 * centre + right;
        if (!(curvature < 0)) {
            return 0.0;
        }
        return Math.max(-0.5, Math.min(0.5, 0.5 * (left - right) / curvature));
    }

    // ------------------------------------------------------------------------------------------------------------
    // The whole frame
    // ------------------------------------------------------------------------------------------------------------

    /** The peaks that are not duplicates, tracked objects, side lobes or camera-fixed, strongest first. */
    private static List<FaintMoverCandidate> select(Plan plan, List<Peak> peaks, EvidencePool evidencePool) {
        FaintMoverSearchTelemetry telemetry = plan.telemetry;
        peaks.sort(peakOrder());
        double fwhm = plan.fwhm;
        List<Track> pointTracks = new ArrayList<>();
        for (Track track : plan.input.tracks) {
            if (track != null && !track.isStreakTrack && track.points.size() >= 2) {
                pointTracks.add(track);
            }
        }
        List<Peak> unique = new ArrayList<>();
        for (Peak peak : peaks) {
            CancellationScope.check();
            boolean duplicate = false;
            for (Peak kept : unique) {
                if (Math.hypot(peak.x - kept.x, peak.y - kept.y) <= DUPLICATE_RADIUS_FWHM * fwhm
                        && Math.hypot(peak.vx - kept.vx, peak.vy - kept.vy) <= DUPLICATE_MOTION_STEPS * plan.coarseStep) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                telemetry.duplicates++;
            } else {
                unique.add(peak);
            }
        }
        List<Peak> strong = new ArrayList<>();
        List<Peak> untracked = new ArrayList<>();
        for (Peak peak : unique) {
            if (followsTrack(plan, peak, pointTracks)) {
                telemetry.matchedTracks++;
                strong.add(peak);
            } else {
                untracked.add(peak);
            }
        }
        List<FaintMoverCandidate> candidates = new ArrayList<>();
        double radius = SIDE_LOBE_RADIUS_FWHM * fwhm;
        for (Peak peak : untracked) {
            CancellationScope.check();
            boolean sideLobe = false;
            for (Peak other : strong) {
                if (other.score >= SIDE_LOBE_SCORE_RATIO * peak.score && closestApproach(plan, peak, other) <= radius) {
                    sideLobe = true;
                    break;
                }
            }
            for (int t = 0; !sideLobe && t < pointTracks.size(); t++) {
                sideLobe = closestApproach(plan, peak, pointTracks.get(t)) <= radius;
            }
            if (sideLobe) {
                telemetry.nearStronger++;
                continue;
            }
            strong.add(peak);
            if (candidates.size() >= MAX_CANDIDATES) {
                telemetry.belowScore++;
                continue;
            }
            var evidence = evidencePool.take(plan, peak);
            if (evidence.unavailableReason == null) telemetry.diagnosticsAvailable++;
            if (evidence.unavailableReason == null) telemetry.diagnosticBytes += diagnosticBytes(evidence);
            candidates.add(new FaintMoverCandidate(peak.x, peak.y, peak.vx, peak.vy, plan.referenceTimeMillis,
                    peak.score, peak.firstHalf, peak.secondHalf, peak.stationary, peak.camera, peak.cutout,
                    evidence));
        }
        return candidates;
    }

    /**
     * Copies bounded evidence from the resident tile; a null tile reports why no review pixels were retained. A path
     * that fits {@link FaintMoverDiagnostics#fixedRectangleFits} shares one rectangle; a longer one gets a square per
     * frame that follows its scoring-path sample.
     */
    private static FaintMoverDiagnostics diagnostics(Plan plan, Peak peak, PreparedTile tile) {
        int n = plan.frames.size();
        var p = peak.location;
        int[] sx = new int[n], sy = new int[n];
        shifts(plan, peak.vx, peak.vy, sx, sy);
        int minX = Arrays.stream(sx).min().orElse(0), maxX = Arrays.stream(sx).max().orElse(0);
        int minY = Arrays.stream(sy).min().orElse(0), maxY = Arrays.stream(sy).max().orElse(0);
        int context = FaintMoverDiagnostics.CONTEXT;
        int imageWidth = plan.input.masterStack[0].length, imageHeight = plan.input.masterStack.length;
        int x = Math.max(Math.max(0, p.tx - plan.margin), p.tx + p.x + minX - context);
        int y = Math.max(Math.max(0, p.ty - plan.margin), p.ty + p.y + minY - context);
        int endX = Math.min(Math.min(imageWidth, p.tx + p.width + plan.margin), p.tx + p.x + maxX + context + 1);
        int endY = Math.min(Math.min(imageHeight, p.ty + p.height + plan.margin), p.ty + p.y + maxY + context + 1);
        int w = endX - x, h = endY - y;
        boolean follow = !FaintMoverDiagnostics.fixedRectangleFits(w, h, n);
        if (follow) {
            w = h = FaintMoverDiagnostics.FOLLOWING_SIDE;
            x = p.tx + p.x + sx[0] - context;
            y = p.ty + p.y + sy[0] - context;
        }
        if (4L * n * w * h > REVIEW_BUDGET_BYTES)
            return FaintMoverDiagnostics.unavailable("review pixels exceed the 64 MiB review-pixel budget");
        if (tile == null) return FaintMoverDiagnostics.unavailable("64 MiB review-pixel budget exceeded");
        int stride = p.width + 2 * plan.margin;
        List<FaintMoverDiagnostics.Frame> frames = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            CancellationScope.check();
            int ox = follow ? p.tx + p.x + sx[k] - context : x;
            int oy = follow ? p.ty + p.y + sy[k] - context : y;
            float[] pixels = follow ? followingCutout(plan, p, tile.residuals[k], ox, oy, imageWidth, imageHeight)
                    : new float[w * h];
            if (!follow) for (int row = 0; row < h; row++) {
                int start = (y - p.ty + plan.margin + row) * stride + x - p.tx + plan.margin;
                System.arraycopy(tile.residuals[k], start, pixels, row * w, w);
            }
            int sampleX = p.x + plan.margin + sx[k], sampleY = p.y + plan.margin + sy[k];
            int sample = sampleY * stride + sampleX;
            boolean unmasked = !tile.bad[sample] && (tile.streakMasks[k] == null || !tile.streakMasks[k].get(sample));
            ImageFrame frame = plan.frames.get(k);
            long mid = frame.timestamp + Math.max(0L, frame.exposureDuration) / 2;
            frames.add(new FaintMoverDiagnostics.Frame(frame.sequenceIndex, mid, p.tx + p.x + sx[k],
                    p.ty + p.y + sy[k], ox, oy, unmasked, tile.residuals[k][sample], pixels));
        }
        float[][] first = Double.isFinite(peak.firstHalf) ? diagnosticHalf(plan, tile, p, sx, sy, 0, n / 2) : null;
        float[][] second = Double.isFinite(peak.secondHalf) ? diagnosticHalf(plan, tile, p, sx, sy, n / 2, n) : null;
        return new FaintMoverDiagnostics(null, x, y, w, h, follow, p.tx + p.x, p.ty + p.y, frames, first, second);
    }

    /** Copies one path-following square from a tile's residuals; pixels outside the image or the tile are NaN. */
    private static float[] followingCutout(Plan plan, PeakLocation p, float[] residuals, int ox, int oy,
                                           int imageWidth, int imageHeight) {
        int side = FaintMoverDiagnostics.FOLLOWING_SIDE;
        int stride = p.width + 2 * plan.margin;
        float[] pixels = new float[side * side];
        Arrays.fill(pixels, Float.NaN);
        int fromX = Math.max(ox, Math.max(0, p.tx - plan.margin));
        int toX = Math.min(ox + side, Math.min(imageWidth, p.tx + p.width + plan.margin));
        int fromY = Math.max(oy, Math.max(0, p.ty - plan.margin));
        int toY = Math.min(oy + side, Math.min(imageHeight, p.ty + p.height + plan.margin));
        for (int gy = fromY; gy < toY && fromX < toX; gy++) {
            int start = (gy - p.ty + plan.margin) * stride + fromX - p.tx + plan.margin;
            System.arraycopy(residuals, start, pixels, (gy - oy) * side + fromX - ox, toX - fromX);
        }
        return pixels;
    }

    /** Copies the half-score map with its original mask count, time order and full-stack normalisation. */
    private static float[][] diagnosticHalf(Plan plan, PreparedTile tile, PeakLocation p, int[] sx, int[] sy,
                                           int from, int to) {
        int width = p.width + 2 * plan.margin;
        int valid = 0;
        for (int o = from; o < to; o++) {
            int k = plan.timeOrder[o];
            int i = (p.y + plan.margin + sy[k]) * width + p.x + plan.margin + sx[k];
            if (!tile.bad[i] && (tile.streakMasks[k] == null || !tile.streakMasks[k].get(i))) valid++;
        }
        double scale = 1.0 / (p.noise * Math.sqrt((double) plan.frames.size() * valid));
        float[][] image = new float[CUTOUT][CUTOUT];
        int r = CUTOUT / 2;
        for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) {
            double sum = 0;
            for (int o = from; o < to; o++) {
                int k = plan.timeOrder[o];
                sum += tile.residuals[k][(p.y + dy + plan.margin + sy[k]) * width + p.x + dx + plan.margin + sx[k]];
            }
            image[dy + r][dx + r] = (float) (sum * scale);
        }
        return image;
    }

    /** Whether the peak's path passes the track's timed detections within {@link #TRACK_MATCH_FWHM} (median). */
    private static boolean followsTrack(Plan plan, Peak peak, List<Track> tracks) {
        for (Track track : tracks) {
            List<Double> distances = new ArrayList<>();
            for (DetectedObject point : track.points) {
                if (point.timestamp <= 0) {
                    continue;
                }
                double mid = point.timestamp + Math.max(0L, point.exposureDuration) / 2.0;
                double minutes = (mid - plan.referenceTimeMillis) / 60000.0;
                distances.add(Math.hypot(peak.x + peak.vx * minutes - point.x, peak.y + peak.vy * minutes - point.y));
            }
            if (distances.size() >= 2) {
                Collections.sort(distances);
                if (distances.get(distances.size() / 2) <= TRACK_MATCH_FWHM * plan.fwhm) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Smallest distance between two peaks' positions at the same kept frame. */
    private static double closestApproach(Plan plan, Peak a, Peak b) {
        double best = Double.MAX_VALUE;
        for (double t : plan.minutes) {
            best = Math.min(best, Math.hypot(a.x - b.x + (a.vx - b.vx) * t, a.y - b.y + (a.vy - b.vy) * t));
        }
        return best;
    }

    /** Smallest distance between a peak's position and a track's timed detections, at the detections' times. */
    private static double closestApproach(Plan plan, Peak peak, Track track) {
        double best = Double.MAX_VALUE;
        for (DetectedObject point : track.points) {
            if (point.timestamp <= 0) {
                continue;
            }
            double t = (point.timestamp + Math.max(0L, point.exposureDuration) / 2.0 - plan.referenceTimeMillis) / 60000.0;
            best = Math.min(best, Math.hypot(peak.x + peak.vx * t - point.x, peak.y + peak.vy * t - point.y));
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------------------

    /** Median and robust spread (1.4826 x median absolute deviation) of the first {@code count} values, reordered. */
    static double[] centreAndSpread(float[] values, int count) {
        float[] copy = Arrays.copyOf(values, count);
        Arrays.sort(copy);
        double median = count % 2 == 1 ? copy[count / 2] : 0.5 * (copy[count / 2 - 1] + copy[count / 2]);
        for (int i = 0; i < count; i++) {
            copy[i] = (float) Math.abs(copy[i] - median);
        }
        Arrays.sort(copy);
        double mad = count % 2 == 1 ? copy[count / 2] : 0.5 * (copy[count / 2 - 1] + copy[count / 2]);
        return new double[]{median, 1.4826 * mad};
    }

    /** The mask grown by the steps through 4-connected neighbours (a diamond of that radius). */
    private static boolean[] grow(boolean[] mask, int width, int height, int steps) {
        boolean[] current = mask.clone();
        for (int s = 0; s < steps; s++) {
            boolean[] next = current.clone();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = y * width + x;
                    if (!current[i]) {
                        continue;
                    }
                    if (x > 0) next[i - 1] = true;
                    if (x + 1 < width) next[i + 1] = true;
                    if (y > 0) next[i - width] = true;
                    if (y + 1 < height) next[i + width] = true;
                }
            }
            current = next;
        }
        return current;
    }

    /** The largest value within the radius (a square), with out-of-area positions left out. */
    private static float[] maxFilter(float[] values, int width, int height, int radius) {
        float[] rows = new float[values.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float max = Float.NEGATIVE_INFINITY;
                for (int d = Math.max(0, x - radius); d <= Math.min(width - 1, x + radius); d++) {
                    max = Math.max(max, values[y * width + d]);
                }
                rows[y * width + x] = max;
            }
        }
        float[] result = new float[values.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float max = Float.NEGATIVE_INFINITY;
                for (int d = Math.max(0, y - radius); d <= Math.min(height - 1, y + radius); d++) {
                    max = Math.max(max, rows[d * width + x]);
                }
                result[y * width + x] = max;
            }
        }
        return result;
    }
}
