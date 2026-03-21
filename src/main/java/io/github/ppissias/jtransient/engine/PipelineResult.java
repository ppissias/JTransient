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

import java.util.List;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

public class PipelineResult {
    public final List<TrackLinker.Track> tracks;
    public final PipelineTelemetry telemetry;

    // --- NEW: Master Data Payloads for the Diagnostic UI ---
    public final short[][] masterStackData;
    public final List<SourceExtractor.DetectedObject> masterStars;

    // --- NEW: Slow Mover Data Payloads ---
    public final short[][] slowMoverStackData;
    public final List<SourceExtractor.DetectedObject> slowMoverCandidates;

    /** A chronological, frame-by-frame list of all surviving transients */
    public final List<List<SourceExtractor.DetectedObject>> allTransients;

    public PipelineResult(List<TrackLinker.Track> tracks,
                          PipelineTelemetry telemetry,
                          short[][] masterStackData,
                          List<SourceExtractor.DetectedObject> masterStars,
                          short[][] slowMoverStackData,
                          List<SourceExtractor.DetectedObject> slowMoverCandidates,
                          List<List<SourceExtractor.DetectedObject>> allTransients) {
        this.tracks = tracks;
        this.telemetry = telemetry;
        this.masterStackData = masterStackData;
        this.masterStars = masterStars;
        this.slowMoverStackData = slowMoverStackData;
        this.slowMoverCandidates = slowMoverCandidates;
        this.allTransients = allTransients;
    }
}