package io.github.ppissias.jtransient.engine;

import java.util.List;
import io.github.ppissias.jtransient.core.TrackLinker;     // Your existing linker
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

public class PipelineResult {
    public final List<TrackLinker.Track> tracks;
    public final PipelineTelemetry telemetry;

    public PipelineResult(List<TrackLinker.Track> tracks, PipelineTelemetry telemetry) {
        this.tracks = tracks;
        this.telemetry = telemetry;
    }
}