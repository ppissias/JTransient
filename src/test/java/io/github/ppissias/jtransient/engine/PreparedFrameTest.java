package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.core.FrameRegistration;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.quality.FrameQualityAnalyzer;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import io.github.ppissias.jtransient.telemetry.TrackerTelemetry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Verifies preparation association, retained positions and borrowed buffer/product ownership. */
public class PreparedFrameTest {

    /** Middle rejection must not shift metrics, registration or empty detections onto another frame. */
    @Test
    public void rejectionPreservesAssociationsAndEmptyFrameSlots() {
        PreparedFrame first = prepared(10, false);
        PreparedFrame rejected = prepared(35, true);
        PreparedFrame last = prepared(90, false);
        List<PreparedFrame> retained = PreparedFrame.retained(List.of(first, rejected, last));
        ExtractedFramesContext context = new ExtractedFramesContext(retained, new PipelineTelemetry(),
                0L, List.of(), null, null, null, Map.of(10, first.registration(), 35, rejected.registration(), 90, last.registration()));

        assertEquals(List.of(10, 90), context.cleanFrames.stream().map(frame -> frame.sequenceIndex).toList());
        assertEquals(2, context.cleanFramesData.size());
        assertTrue(context.cleanFramesData.get(1).objects.isEmpty());
        assertSame(last.frame(), context.cleanFrames.get(1));
        assertSame(last.metrics(), context.cleanFrameMetrics.get(1));
        assertSame(last.extractionResult(), context.cleanFramesData.get(1));
        assertSame(last.registration(), context.frameRegistrations.get(context.cleanFrames.get(1).sequenceIndex));
        assertSame(last.frame().pixelData, context.retainedFrames.get(1).frame().pixelData);
        TrackerTelemetry tracker = new TrackerTelemetry();
        TrackerTelemetry.FrameStarMapStat stat = new TrackerTelemetry.FrameStarMapStat();
        stat.frameIndex = 1;
        tracker.frameStarMapStats.add(stat);
        context.labelTrackerFrames(tracker);
        assertEquals(1, stat.frameIndex);
        assertEquals(90, stat.originalFrame.sequenceIndex());
        assertEquals(last.frame().filename, stat.originalFrame.filename());
    }

    /** Stable container structure must not imply cloning mutable images or extraction products. */
    @Test
    public void contextSnapshotsContainersButPreservesScienceAliases() {
        PreparedFrame prepared = prepared(70, false);
        List<PreparedFrame> supplied = new ArrayList<>(List.of(prepared));
        ExtractedFramesContext context = new ExtractedFramesContext(supplied, new PipelineTelemetry(),
                0L, List.of(), null, null, null, Map.of());
        supplied.clear();
        assertEquals(1, context.retainedFrames.size());
        assertThrows(UnsupportedOperationException.class, () -> context.cleanFrames.clear());
        prepared.frame().pixelData[0][0] = 123;
        assertEquals(123, context.cleanFrames.getFirst().pixelData[0][0]);
        DetectedObject object = new DetectedObject(0, 0, 1, 1);
        prepared.extractionResult().objects.add(object);
        assertSame(object, context.cleanFramesData.getFirst().objects.getFirst());
    }

    /** Quality-only master preparation requires no full extraction payload. */
    @Test
    public void qualityOnlyPreparationDoesNotRequireSourceExtraction() {
        PreparedFrame withExtraction = prepared(40, false);
        PreparedFrame qualityOnly = new PreparedFrame(withExtraction.frame(), null,
                withExtraction.metrics(), withExtraction.registration());
        assertEquals(List.of(qualityOnly), PreparedFrame.retained(List.of(qualityOnly)));
        assertNull(qualityOnly.extractionResult());
        assertThrows(NullPointerException.class, () -> new ExtractedFramesContext(List.of(qualityOnly),
                new PipelineTelemetry(), 0L, List.of(), null, null, null, Map.of()));
    }

    /** An original sequence identity cannot silently receive another frame's registration. */
    @Test
    public void rejectsMismatchedRegistrationIdentity() {
        PreparedFrame prepared = prepared(80, false);
        assertThrows(IllegalArgumentException.class, () -> new PreparedFrame(prepared.frame(),
                prepared.extractionResult(), prepared.metrics(), FrameRegistration.of(1, 0, 0, 0)));
        assertThrows(NullPointerException.class, () -> new PreparedFrame(null,
                prepared.extractionResult(), prepared.metrics(), null));
        assertThrows(NullPointerException.class, () -> new PreparedFrame(prepared.frame(),
                prepared.extractionResult(), null, null));
    }

    /** Creates owned small products around a borrowed one-pixel image with an original sequence identity. */
    private static PreparedFrame prepared(int sequenceIndex, boolean rejected) {
        ImageFrame frame = new ImageFrame(sequenceIndex, "frame-" + sequenceIndex, new short[1][1], -1, -1);
        FrameQualityAnalyzer.FrameMetrics metrics = new FrameQualityAnalyzer.FrameMetrics();
        metrics.filename = frame.filename;
        metrics.isRejected = rejected;
        SourceExtractor.ExtractionResult extraction = new SourceExtractor.ExtractionResult(new ArrayList<>(), null, 1, 1);
        return new PreparedFrame(frame, extraction, metrics, FrameRegistration.of(sequenceIndex, sequenceIndex, 0, 0));
    }
}
