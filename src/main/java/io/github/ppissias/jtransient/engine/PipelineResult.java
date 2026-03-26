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

import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.util.List;

public class PipelineResult {
    public final List<TrackLinker.Track> tracks;
    public final PipelineTelemetry telemetry;
    public final short[][] masterStackData;
    public final List<SourceExtractor.DetectedObject> masterStars;

    // --- Slow Mover Data Payloads ---
    public final short[][] slowMoverStackData;
    public final List<SourceExtractor.DetectedObject> slowMoverCandidates;

    /** A chronological, frame-by-frame list of all surviving transients */
    public final List<List<SourceExtractor.DetectedObject>> allTransients;

    /** The pixel-perfect Boolean Veto Mask generated from the Master Star Map */
    public final boolean[][] masterMask;

    /** The diagnostic points representing the relative translation vector of the dither/drift */
    public final List<SourceExtractor.Pixel> driftPoints;

    /** Statistical baseline metrics calculated during the slow mover detection phase */
    public final SlowMoverTelemetry slowMoverTelemetry;

    /** A stack containing the maximum pixel values across the sequence */
    public final short[][] masterMaximumStackData;

    /** Streaks detected in the master maximum stack that were not found in single frames. */
    public final List<SourceExtractor.DetectedObject> masterMaximumStackTransientStreaks;


    public static class SlowMoverTelemetry {
        public int candidatesDetected;
        public double medianElongation;
        public double dynamicElongationThreshold;
    }

    public PipelineResult(List<TrackLinker.Track> tracks,
                          PipelineTelemetry telemetry,
                          short[][] masterStackData,
                          List<SourceExtractor.DetectedObject> masterStars,
                          short[][] slowMoverStackData,
                          List<SourceExtractor.DetectedObject> slowMoverCandidates,
                          List<List<SourceExtractor.DetectedObject>> allTransients,
                          boolean[][] masterMask,
                          List<SourceExtractor.Pixel> driftPoints,
                          SlowMoverTelemetry slowMoverTelemetry,
                          short[][] masterMaximumStackData,
                          List<SourceExtractor.DetectedObject> masterMaximumStackTransientStreaks) {
        this.tracks = tracks;
        this.telemetry = telemetry;
        this.masterStackData = masterStackData;
        this.masterStars = masterStars;
        this.slowMoverStackData = slowMoverStackData;
        this.slowMoverCandidates = slowMoverCandidates;
        this.allTransients = allTransients;
        this.masterMask = masterMask;
        this.driftPoints = driftPoints;
        this.slowMoverTelemetry = slowMoverTelemetry;
        this.masterMaximumStackData = masterMaximumStackData;
        this.masterMaximumStackTransientStreaks = masterMaximumStackTransientStreaks;
    }
}