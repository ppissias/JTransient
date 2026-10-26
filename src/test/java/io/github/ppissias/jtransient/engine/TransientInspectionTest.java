package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.model.DetectedObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A kept reference re-detects one frame to the same transients as a fresh run. */
public class TransientInspectionTest {

    /** Master-setting edits cannot replace the mask of a kept reference during a per-frame trial. */
    @Test
    public void trialRedetectionKeepsTheOriginalMaskWhenMasterJitterChanges() {
        DetectionConfig config = new DetectionConfig();
        config.maxJumpPixels = 1000;
        config.starCountSigmaDeviation = config.fwhmSigmaDeviation = config.backgroundSigmaDeviation = 100;
        try (var engine = new JTransientEngine(1)) {
            TransientInspection inspection = engine.inspectTransients(frames(), config, null);
            DetectionConfig trial = config.clone();
            trial.detectionSigmaMultiplier += 0.05;
            FrameTransients before = engine.redetectFrame(inspection.reference(), 1, trial);
            assertFalse("the moving source survives the kept mask", before.transients.isEmpty());

            trial.maxStarJitter = 30;
            FrameTransients after = engine.redetectFrame(inspection.reference(), 1, trial);
            assertEquals(positions(before), positions(after));

            // A reference newly built with that large jitter really does veto this source.
            TransientInspection reopened = engine.inspectTransients(frames(), trial, null);
            assertTrue(reopened.frames().get(1).transients.isEmpty());
        }
    }

    @Test
    public void startingSettingsMatchTheFirstPassAndOtherSettingsMatchAFreshRun() {
        List<ImageFrame> frames = frames();
        DetectionConfig config = new DetectionConfig();
        DetectionConfig changed = config.clone();
        changed.detectionSigmaMultiplier = config.detectionSigmaMultiplier + 1.0;
        try (var engine = new JTransientEngine(1)) {
            TransientInspection first = engine.inspectTransients(frames, config, null);
            FrameTransients again = engine.redetectFrame(first.reference(), 0, config);
            assertEquals(positions(first.frames().get(0)), positions(again));

            List<FrameTransients> fresh = engine.detectTransients(frames, changed, null);
            FrameTransients one = engine.redetectFrame(first.reference(), 0, changed);
            assertEquals(positions(fresh.get(0)), positions(one));
            assertEquals(first.reference().masterStars().size(), engine.inspectTransients(frames, changed, null).reference().masterStars().size());
        }
    }

    /** Stable source positions used to compare independent extractions. */
    private static List<String> positions(FrameTransients frame) {
        List<String> positions = new ArrayList<>();
        for (DetectedObject object : frame.transients) {
            positions.add(Math.round(object.x) + "," + Math.round(object.y));
        }
        return positions;
    }

    /** Five noisy frames with stars and one moving source. */
    private static List<ImageFrame> frames() {
        Random random = new Random(7);
        List<ImageFrame> frames = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            short[][] pixels = new short[96][96];
            for (int row = 0; row < 96; row++) {
                for (int column = 0; column < 96; column++) {
                    double value = 1000 + 8 * random.nextGaussian();
                    value += 900 * Math.exp(-(Math.pow(column - 30, 2) + Math.pow(row - 40, 2)) / 4.0);
                    value += 600 * Math.exp(-(Math.pow(column - 70, 2) + Math.pow(row - 60, 2)) / 4.0);
                    value += 500 * Math.exp(-(Math.pow(column - 12 - 14 * index, 2) + Math.pow(row - 20 - 10 * index, 2)) / 3.0);
                    pixels[row][column] = PixelEncoding.fromShiftedPositiveInt((int) Math.round(value));
                }
            }
            frames.add(new ImageFrame(index, "frame-" + index, pixels, index * 60_000L, 30_000));
        }
        return frames;
    }
}
