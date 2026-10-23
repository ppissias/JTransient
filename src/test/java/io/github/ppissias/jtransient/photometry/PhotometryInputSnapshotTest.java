package io.github.ppissias.jtransient.photometry;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.quality.FrameQualityAnalyzer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Checks explicit public photometry alignment capture without freezing legacy field-bag callers. */
public class PhotometryInputSnapshotTest {

    /** Captured positions retain empty detection slots and small container ownership, not deep image copies. */
    @Test
    public void snapshotRetainsOriginalIdentitiesAndBorrowedProducts() throws Exception {
        VariableStarAnalyzer.Input input = input();
        VariableStarAnalyzer.Input snapshot = input.snapshot();
        input.frames.clear();
        input.frameDetections.getFirst().clear();
        assertEquals(List.of(10, 90), snapshot.frames.stream().map(frame -> frame.sequenceIndex).toList());
        assertEquals(1, snapshot.frameDetections.getFirst().size());
        assertTrue(snapshot.frameDetections.get(1).isEmpty());
        assertSame(input.frameMetrics.getFirst(), snapshot.frameMetrics.getFirst());
        assertSame(input.masterStack, snapshot.masterStack);
        snapshot.frames.getFirst().pixelData[0][0] = 123;
        assertEquals(123, snapshot.frames.getFirst().pixelData[0][0]);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.frames.clear());
        VariableStarAnalysis result = VariableStarAnalyzer.analyze(snapshot, new DetectionConfig(), null);
        assertEquals(List.of(10, 90), result.frames.stream().map(stat -> stat.frameIndex).toList());
        assertEquals(PhotometricReadiness.Verdict.NOT_READY, result.readiness.verdict);
    }

    /** Missing, shifted and duplicate frame slots fail at the explicit boundary rather than inside numeric work. */
    @Test
    public void snapshotRejectsMisalignedContainersAndFrameOrder() {
        VariableStarAnalyzer.Input input = input();
        input.frameMetrics = List.of(input.frameMetrics.getFirst());
        assertThrows(IllegalArgumentException.class, input::snapshot);
        input = input();
        input.frameDetections = List.of(input.frameDetections.getFirst());
        assertThrows(IllegalArgumentException.class, input::snapshot);
        input = input();
        input.frames.set(1, input.frames.getFirst());
        assertThrows(IllegalArgumentException.class, input::snapshot);
        input = input();
        java.util.Collections.reverse(input.frames);
        assertThrows(IllegalArgumentException.class, input::snapshot);
    }

    /** Known metric labels and original detection IDs must refer to the same descriptor at each position. */
    @Test
    public void snapshotRejectsForeignMetricsAndDetections() {
        VariableStarAnalyzer.Input input = input();
        input.frameMetrics.getFirst().filename = "another frame";
        assertThrows(IllegalArgumentException.class, input::snapshot);
        input = input();
        input.frameDetections.getFirst().getFirst().sourceFrameIndex = 90;
        assertThrows(IllegalArgumentException.class, input::snapshot);
        input = input();
        input.frameDetections.getFirst().getFirst().sourceFilename = "another frame";
        assertThrows(IllegalArgumentException.class, input::snapshot);
        input = input();
        input.frameDetections.set(1, null);
        assertThrows(IllegalArgumentException.class, input::snapshot);
    }

    /** Canonical photometry validates required metrics even for readiness-only runs; detection lists remain optional. */
    @Test
    public void readinessRequiresAlignedMetricsAndOptionalDetections() throws Exception {
        VariableStarAnalyzer.Input input = input();
        input.frameDetections = null;
        assertNull(input.snapshot().frameDetections);
        input.frameMetrics = null;
        assertThrows(IllegalArgumentException.class,
                () -> VariableStarAnalyzer.analyze(input, new DetectionConfig(), null));
        assertThrows(IllegalArgumentException.class,
                () -> VariableStarAnalyzer.analyze(null, new DetectionConfig(), null));
    }

    /** Creates two aligned non-contiguous frames, one with no detections, using tiny caller-owned pixels. */
    private static VariableStarAnalyzer.Input input() {
        VariableStarAnalyzer.Input input = new VariableStarAnalyzer.Input();
        input.frames = new ArrayList<>();
        input.frameMetrics = new ArrayList<>();
        input.frameDetections = new ArrayList<>();
        for (int sequenceIndex : List.of(10, 90)) {
            ImageFrame frame = new ImageFrame(sequenceIndex, "frame-" + sequenceIndex, new short[2][2], -1, -1);
            input.frames.add(frame);
            FrameQualityAnalyzer.FrameMetrics metrics = new FrameQualityAnalyzer.FrameMetrics();
            metrics.filename = frame.filename;
            input.frameMetrics.add(metrics);
            input.frameDetections.add(new ArrayList<>());
        }
        SourceExtractor.DetectedObject object = new SourceExtractor.DetectedObject(0, 0, 1, 1);
        object.sourceFrameIndex = 10;
        object.sourceFilename = "frame-10";
        input.frameDetections.getFirst().add(object);
        input.masterStack = new short[2][2];
        return input;
    }
}
