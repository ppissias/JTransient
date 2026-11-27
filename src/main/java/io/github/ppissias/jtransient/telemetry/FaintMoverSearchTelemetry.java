/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.telemetry;

/**
 * What the faint-mover search did in one run: the search domain, how much of it was searched, and how many peaks each
 * check kept.
 *
 * <p>The counts follow one population in the order the checks see it: {@link #refinedPeaks} = {@link #belowScore} +
 * {@link #rejectedSplitHalf} + {@link #rejectedFewFrames} + {@link #rejectedStationary} + {@link #followingCamera} + {@link #duplicates} +
 * {@link #matchedTracks} + {@link #nearStronger} + {@link #candidates}.</p>
 */
public class FaintMoverSearchTelemetry {
    /** Original sequence indices of the selected search session, in kept order; empty when it was not selected. */
    public java.util.List<Integer> searchedFrameIndices = java.util.List.of();
    /** Why the search did not run, or null when it ran. */
    public String skippedReason;
    /** Kept frames searched: those of the longest session, without a gap of over two hours. */
    public int frames;
    /** Kept frames left out because a gap of over two hours separates them from the searched session. */
    public int framesOutsideSession;
    /** Time from the first to the last kept frame's mid-exposure, in minutes. */
    public double sessionMinutes;
    /** Star size the stacks were filtered with (Gaussian FWHM), in pixels. */
    public double fwhmPixels;
    /** Whether the star size was measured on the kept frames or set; false when a default size was used. */
    public boolean fwhmFromStars;
    /** Where the star size came from: {@code SETTING}, {@code MEASURED} or {@code ASSUMED}. */
    public String fwhmSource = "";
    /** Slowest and fastest motion searched, in pixels per minute. */
    public double minSpeedPixelsPerMinute;
    public double maxSpeedPixelsPerMinute;
    /** Spacing of the coarse motion grid and of its refinement, in pixels per minute. */
    public double coarseStepPixelsPerMinute;
    public double fineStepPixelsPerMinute;
    /** Motions in the coarse grid. */
    public int coarseMotions;
    /** The search speed used: 0 normal, 1 fast, 2 faster ({@code DetectionConfig.faintMoverSearchSpeed}). */
    public int searchSpeed = 1;
    /**
     * Pixels added in each direction for the coarse search: 1 at full resolution, 2 for wide stars. Refinement and every
     * check use the full-resolution residuals.
     */
    public int coarseBin = 1;
    /**
     * Frames the coarse search adds along each motion: the searched frames, or fewer when consecutive frames close
     * enough in time are added together first. Refinement and every check use every searched frame.
     */
    public int coarseFrames;
    /** Tiles the search area was divided into, and those searched before the time limit. */
    public int tiles;
    public int tilesSearched;
    /** Whether the time limit stopped the search before every tile was searched. */
    public boolean truncated;
    /** Pixels whose motions were searched, outside masked star cores, fill and trails. */
    public long searchedPixels;
    /** Coarse local maxima that were refined. */
    public int refinedPeaks;
    /** Refined peaks below the minimum score. */
    public int belowScore;
    /** Peaks one half of the session does not support. */
    public int rejectedSplitHalf;
    /** Peaks whose score comes from a few frames: below the minimum once no frame may stand far above the path's typical frame. */
    public int rejectedFewFrames;
    /** Peaks that do not score clearly above the stationary stack. */
    public int rejectedStationary;
    /** Peaks that repeat a stronger peak at a nearby position and motion (neighbouring tiles). */
    public int duplicates;
    /** Peaks that follow a moving track of the run. */
    public int matchedTracks;
    /** Peaks whose path meets a moving track or a much stronger peak in some kept frame (their side lobes). */
    public int nearStronger;
    /** Peaks that do not score clearly above the stack at one camera position (hot pixels, defects). */
    public int followingCamera;
    /** Candidates reported. */
    public int candidates;
    /** Candidates with bounded replay evidence; the rest carry an explicit resource-limit reason. */
    public int diagnosticsAvailable;
    /** Estimated owned bytes of the selected candidates' review pixels and per-frame metadata. */
    public long diagnosticBytes;
    /** Wall-clock time of the search, in milliseconds. */
    public long elapsedMs;
    /** Sum of per-tile preparation durations across workers, in milliseconds. */
    public long prepMs;
    /** Sum of per-tile coarse-search durations across workers, in milliseconds. */
    public long coarseMs;
    /** Sum of per-tile refinement and check durations across workers, in milliseconds. */
    public long refineMs;
    /** Median exposure duration of the searched frames, in minutes; NaN when unavailable. */
    public double exposureMinutes = Double.NaN;
    /** Speed above which a single exposure trails beyond {@code POINT_TRAIL_FWHM} star widths, in px/min; NaN when unavailable. */
    public double pointLikeMaxSpeedPixelsPerMinute = Double.NaN;
    /** Per-exposure trail length at {@link #maxSpeedPixelsPerMinute}, in FWHM; NaN when unavailable. */
    public double trailFwhmAtMaxSpeed = Double.NaN;
    /** Coarse-search backend path used: "Java" or "OpenCL". */
    public String coarsePath = "Java";
    /** Selected OpenCL device name, or empty when Java ran. */
    public String coarseDevice = "";
    /** Selected OpenCL driver version, or empty when Java ran. */
    public String coarseDriver = "";
    /** Selected OpenCL version string, or empty when Java ran. */
    public String openclVersion = "";
    /** Explanation for why the selected coarse-search path ran or fell back. */
    public String coarseReason = "Default CPU backend";
    /** GPU self-test outcome: "passed", "failed", or "not run". */
    public String gpuSelfTest = "not run";
    /** Measured GPU-over-CPU speedup from the benchmark, or NaN when not measured. */
    public double gpuBenchmarkSpeedup = Double.NaN;
    /** Tiles whose coarse pass fell back from GPU to Java during this run. */
    public int cpuFallbackTiles;
    /** Trial-motion cap applied to this run's plan. */
    public int maxMotionsApplied;
    /** Time limit applied to this run, in seconds. */
    public double timeLimitSecondsApplied;
    /** Whether the fastest motion was chosen during the run ({@code faintMoverAutoMaxSpeed}). */
    public boolean autoMaxSpeed;
    /**
     * What set the fastest motion: {@code SETTING} (the configured value, automatic speed off),
     * {@code NO_EXPOSURE_TIMES} (the configured value, because the frames have no exposure times),
     * {@code POINT_LIKE} (2 star widths per exposure), {@code MOTION_LIMIT} (90 % of {@link #maxMotionsApplied}),
     * {@code MEMORY} (one tile within a quarter of the heap) or {@code TIME_LIMIT} (the estimated search time).
     */
    public String maxSpeedLimit = "SETTING";
    /** The fastest motion before the time limit lowered it, in px/min; NaN when the time limit did not. */
    public double speedBeforeTimeLimitPixelsPerMinute = Double.NaN;
    /** The search time estimated from the timed sample at the searched fastest motion, in seconds; NaN without one. */
    public double estimatedSeconds = Double.NaN;
    /** Time spent on the timed sample, in milliseconds; included in {@link #elapsedMs}. */
    public long sampleMs;
}
