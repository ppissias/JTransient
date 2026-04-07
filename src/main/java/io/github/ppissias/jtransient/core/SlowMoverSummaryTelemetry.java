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
    public final int rejectedIrregularShape;
    public final int rejectedBinaryAnomaly;
    public final int rejectedSlowMoverShape;
    public final int rejectedSlowMoverShapeTooShort;
    public final int rejectedSlowMoverShapeLowFill;
    public final int rejectedSlowMoverShapeSparseBins;
    public final int rejectedSlowMoverShapeGappedBins;
    public final int rejectedSlowMoverShapeCurvedCenterline;
    public final int rejectedSlowMoverShapeBulgedWidth;
    public final int rejectedLowMedianSupport;
    public final int rejectedHighMedianSupport;
    public final int rejectedLowResidualCoreSupport;
    public final int candidatesDetected;
    public final double medianElongation;
    public final double madElongation;
    public final double dynamicElongationThreshold;
    public final double medianSupportOverlapThreshold;
    public final double medianSupportMaxOverlapThreshold;
    public final double avgMedianSupportOverlap;
    public final double residualCoreMinPositiveFractionThreshold;
    public final double avgResidualCorePositiveFraction;

    public SlowMoverSummaryTelemetry(PipelineTelemetry.SlowMoverTelemetry telemetry) {
        this.rawCandidatesExtracted = telemetry.rawCandidatesExtracted;
        this.candidatesAboveElongationThreshold = telemetry.candidatesAboveElongationThreshold;
        this.candidatesEvaluatedAgainstMasks = telemetry.candidatesEvaluatedAgainstMasks;
        this.rejectedIrregularShape = telemetry.rejectedIrregularShape;
        this.rejectedBinaryAnomaly = telemetry.rejectedBinaryAnomaly;
        this.rejectedSlowMoverShape = telemetry.rejectedSlowMoverShape;
        this.rejectedSlowMoverShapeTooShort = telemetry.rejectedSlowMoverShapeTooShort;
        this.rejectedSlowMoverShapeLowFill = telemetry.rejectedSlowMoverShapeLowFill;
        this.rejectedSlowMoverShapeSparseBins = telemetry.rejectedSlowMoverShapeSparseBins;
        this.rejectedSlowMoverShapeGappedBins = telemetry.rejectedSlowMoverShapeGappedBins;
        this.rejectedSlowMoverShapeCurvedCenterline = telemetry.rejectedSlowMoverShapeCurvedCenterline;
        this.rejectedSlowMoverShapeBulgedWidth = telemetry.rejectedSlowMoverShapeBulgedWidth;
        this.rejectedLowMedianSupport = telemetry.rejectedLowMedianSupport;
        this.rejectedHighMedianSupport = telemetry.rejectedHighMedianSupport;
        this.rejectedLowResidualCoreSupport = telemetry.rejectedLowResidualCoreSupport;
        this.candidatesDetected = telemetry.candidatesDetected;
        this.medianElongation = telemetry.medianElongation;
        this.madElongation = telemetry.madElongation;
        this.dynamicElongationThreshold = telemetry.dynamicElongationThreshold;
        this.medianSupportOverlapThreshold = telemetry.medianSupportOverlapThreshold;
        this.medianSupportMaxOverlapThreshold = telemetry.medianSupportMaxOverlapThreshold;
        this.avgMedianSupportOverlap = telemetry.avgMedianSupportOverlap;
        this.residualCoreMinPositiveFractionThreshold = telemetry.residualCoreMinPositiveFractionThreshold;
        this.avgResidualCorePositiveFraction = telemetry.avgResidualCorePositiveFraction;
    }

    public static SlowMoverSummaryTelemetry empty() {
        return new SlowMoverSummaryTelemetry(new PipelineTelemetry.SlowMoverTelemetry());
    }

    public PipelineTelemetry.SlowMoverTelemetry toLegacyTelemetry(List<SlowMoverCandidateResult> candidates) {
        PipelineTelemetry.SlowMoverTelemetry telemetry = new PipelineTelemetry.SlowMoverTelemetry();
        telemetry.rawCandidatesExtracted = rawCandidatesExtracted;
        telemetry.candidatesAboveElongationThreshold = candidatesAboveElongationThreshold;
        telemetry.candidatesEvaluatedAgainstMasks = candidatesEvaluatedAgainstMasks;
        telemetry.rejectedIrregularShape = rejectedIrregularShape;
        telemetry.rejectedBinaryAnomaly = rejectedBinaryAnomaly;
        telemetry.rejectedSlowMoverShape = rejectedSlowMoverShape;
        telemetry.rejectedSlowMoverShapeTooShort = rejectedSlowMoverShapeTooShort;
        telemetry.rejectedSlowMoverShapeLowFill = rejectedSlowMoverShapeLowFill;
        telemetry.rejectedSlowMoverShapeSparseBins = rejectedSlowMoverShapeSparseBins;
        telemetry.rejectedSlowMoverShapeGappedBins = rejectedSlowMoverShapeGappedBins;
        telemetry.rejectedSlowMoverShapeCurvedCenterline = rejectedSlowMoverShapeCurvedCenterline;
        telemetry.rejectedSlowMoverShapeBulgedWidth = rejectedSlowMoverShapeBulgedWidth;
        telemetry.rejectedLowMedianSupport = rejectedLowMedianSupport;
        telemetry.rejectedHighMedianSupport = rejectedHighMedianSupport;
        telemetry.rejectedLowResidualCoreSupport = rejectedLowResidualCoreSupport;
        telemetry.candidatesDetected = candidatesDetected;
        telemetry.medianElongation = medianElongation;
        telemetry.madElongation = madElongation;
        telemetry.dynamicElongationThreshold = dynamicElongationThreshold;
        telemetry.medianSupportOverlapThreshold = medianSupportOverlapThreshold;
        telemetry.medianSupportMaxOverlapThreshold = medianSupportMaxOverlapThreshold;
        telemetry.avgMedianSupportOverlap = avgMedianSupportOverlap;
        telemetry.residualCoreMinPositiveFractionThreshold = residualCoreMinPositiveFractionThreshold;
        telemetry.avgResidualCorePositiveFraction = avgResidualCorePositiveFraction;
        for (SlowMoverCandidateResult candidate : candidates) {
            telemetry.candidateMedianSupportOverlaps.add(candidate.diagnostics.medianSupportOverlap);
        }
        return telemetry;
    }
}
