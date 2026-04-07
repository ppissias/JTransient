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

import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.util.List;

/**
 * Aggregate telemetry exported for the slow-mover analysis stage.
 */
public final class SlowMoverSummaryTelemetry {
    public final int rawCandidatesExtracted;
    public final int candidatesAboveElongationThreshold;
    public final int candidatesEvaluatedAgainstMasks;
    public final int rejectedLowMedianSupport;
    public final int rejectedHighMedianSupport;
    public final int rejectedLowResidualFootprintSupport;
    public final int candidatesDetected;
    public final double medianElongation;
    public final double madElongation;
    public final double dynamicElongationThreshold;
    public final double medianSupportOverlapThreshold;
    public final double medianSupportMaxOverlapThreshold;
    public final double avgMedianSupportOverlap;
    public final double residualFootprintMinFluxFractionThreshold;
    public final double avgResidualFootprintFluxFraction;

    public SlowMoverSummaryTelemetry(PipelineTelemetry.SlowMoverTelemetry telemetry) {
        this.rawCandidatesExtracted = telemetry.rawCandidatesExtracted;
        this.candidatesAboveElongationThreshold = telemetry.candidatesAboveElongationThreshold;
        this.candidatesEvaluatedAgainstMasks = telemetry.candidatesEvaluatedAgainstMasks;
        this.rejectedLowMedianSupport = telemetry.rejectedLowMedianSupport;
        this.rejectedHighMedianSupport = telemetry.rejectedHighMedianSupport;
        this.rejectedLowResidualFootprintSupport = telemetry.rejectedLowResidualFootprintSupport;
        this.candidatesDetected = telemetry.candidatesDetected;
        this.medianElongation = telemetry.medianElongation;
        this.madElongation = telemetry.madElongation;
        this.dynamicElongationThreshold = telemetry.dynamicElongationThreshold;
        this.medianSupportOverlapThreshold = telemetry.medianSupportOverlapThreshold;
        this.medianSupportMaxOverlapThreshold = telemetry.medianSupportMaxOverlapThreshold;
        this.avgMedianSupportOverlap = telemetry.avgMedianSupportOverlap;
        this.residualFootprintMinFluxFractionThreshold = telemetry.residualFootprintMinFluxFractionThreshold;
        this.avgResidualFootprintFluxFraction = telemetry.avgResidualFootprintFluxFraction;
    }

    public static SlowMoverSummaryTelemetry empty() {
        return new SlowMoverSummaryTelemetry(new PipelineTelemetry.SlowMoverTelemetry());
    }

    public PipelineTelemetry.SlowMoverTelemetry toLegacyTelemetry(List<SlowMoverCandidateResult> candidates) {
        PipelineTelemetry.SlowMoverTelemetry telemetry = new PipelineTelemetry.SlowMoverTelemetry();
        telemetry.rawCandidatesExtracted = rawCandidatesExtracted;
        telemetry.candidatesAboveElongationThreshold = candidatesAboveElongationThreshold;
        telemetry.candidatesEvaluatedAgainstMasks = candidatesEvaluatedAgainstMasks;
        telemetry.rejectedLowMedianSupport = rejectedLowMedianSupport;
        telemetry.rejectedHighMedianSupport = rejectedHighMedianSupport;
        telemetry.rejectedLowResidualFootprintSupport = rejectedLowResidualFootprintSupport;
        telemetry.candidatesDetected = candidatesDetected;
        telemetry.medianElongation = medianElongation;
        telemetry.madElongation = madElongation;
        telemetry.dynamicElongationThreshold = dynamicElongationThreshold;
        telemetry.medianSupportOverlapThreshold = medianSupportOverlapThreshold;
        telemetry.medianSupportMaxOverlapThreshold = medianSupportMaxOverlapThreshold;
        telemetry.avgMedianSupportOverlap = avgMedianSupportOverlap;
        telemetry.residualFootprintMinFluxFractionThreshold = residualFootprintMinFluxFractionThreshold;
        telemetry.avgResidualFootprintFluxFraction = avgResidualFootprintFluxFraction;
        for (SlowMoverCandidateResult candidate : candidates) {
            telemetry.candidateMedianSupportOverlaps.add(candidate.diagnostics.medianSupportOverlap);
        }
        return telemetry;
    }
}
