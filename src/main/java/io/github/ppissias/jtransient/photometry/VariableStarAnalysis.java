/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.photometry;

import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Result of the variable-star photometry stage: readiness verdict, light curves of every
 * measured star, the scored candidates, and the session and per-frame telemetry.
 */
public final class VariableStarAnalysis {
    /** Names of the candidate gates, in evaluation order, as they appear in {@link StarLightCurve#failedGates}. */
    public static final List<String> GATE_NAMES = Collections.unmodifiableList(Arrays.asList(
            VariabilityScorer.GATE_DATA,
            VariabilityScorer.GATE_AMPLITUDE,
            VariabilityScorer.GATE_PERSISTENCE,
            VariabilityScorer.GATE_SPLIT_HALF,
            VariabilityScorer.GATE_APERTURE,
            VariabilityScorer.GATE_SYSTEMATICS,
            VariabilityScorer.GATE_LOCAL,
            VariabilityScorer.GATE_LINEARITY));

    /** Readiness verdict and the measurements behind it. */
    public final PhotometricReadiness readiness;
    /** Per-frame diagnostics; every light-curve array is aligned with this list. */
    public final List<PipelineTelemetry.PhotometryFrameStat> frames;
    /** All measured stars with their light curves. */
    public final List<StarLightCurve> stars;
    /** Stars that passed scoring (HIGH_CONFIDENCE, POSSIBLE, REJECTED), best tier first. */
    public final List<StarLightCurve> candidates;
    /** Session-level diagnostics (also attached to the pipeline telemetry). */
    public final PipelineTelemetry.PhotometryTelemetry telemetry;

    public VariableStarAnalysis(PhotometricReadiness readiness,
                                List<StarLightCurve> stars,
                                PipelineTelemetry.PhotometryTelemetry telemetry) {
        this.readiness = readiness;
        this.telemetry = telemetry;
        this.frames = Collections.unmodifiableList(telemetry.frames);
        this.stars = Collections.unmodifiableList(new ArrayList<>(stars));
        List<StarLightCurve> scored = new ArrayList<>();
        for (StarLightCurve star : stars) {
            if (star.tier == VariabilityTier.HIGH_CONFIDENCE
                    || star.tier == VariabilityTier.POSSIBLE
                    || star.tier == VariabilityTier.REJECTED) {
                scored.add(star);
            }
        }
        scored.sort((a, b) -> {
            int byTier = Integer.compare(b.tier.ordinal(), a.tier.ordinal());
            return byTier != 0 ? byTier : Double.compare(b.scatterZ, a.scatterZ);
        });
        this.candidates = Collections.unmodifiableList(scored);
    }

    /** Returns only the high-confidence candidates. */
    public List<StarLightCurve> highConfidenceCandidates() {
        List<StarLightCurve> out = new ArrayList<>();
        for (StarLightCurve star : candidates) {
            if (star.tier == VariabilityTier.HIGH_CONFIDENCE) {
                out.add(star);
            }
        }
        return out;
    }

    /**
     * Returns an empty result for when photometry is disabled.
     */
    public static VariableStarAnalysis empty() {
        return new VariableStarAnalysis(new PhotometricReadiness(), Collections.emptyList(),
                new PipelineTelemetry.PhotometryTelemetry());
    }
}
