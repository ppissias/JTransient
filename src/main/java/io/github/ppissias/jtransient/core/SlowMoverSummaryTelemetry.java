package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable snapshot of the slow-mover filter stages and candidate distributions. */
public final class SlowMoverSummaryTelemetry {
    /** Maximum-stack components returned by source extraction, before shape filtering. */
    public final int rawCandidatesExtracted;
    /** Components below the configured raw-pixel count. */
    public final int rejectedBelowMinPixels;
    /** Components rounder than the geometric axis-ratio window. */
    public final int rejectedBelowMinAxisRatio;
    /** Components longer than the geometric axis-ratio window. */
    public final int rejectedAboveMaxAxisRatio;
    /** Components vetoed by the optional oriented-box fill factor. */
    public final int rejectedLowFillFactor;
    /** Shape-qualified components whose raw pixels were compared with the median mask. */
    public final int evaluatedAgainstMedianMask;
    /** Components below the optional median-mask overlap floor. */
    public final int rejectedLowMedianSupport;
    /** Components above the stationary-source median-mask overlap ceiling. */
    public final int rejectedHighMedianSupport;
    /** Components with measurable frame evidence. */
    public final int evaluatedAgainstFrames;
    /** Components for which no usable frame evidence was available. */
    public final int frameEvidenceUnavailable;
    /** Components below the optional frame-support percentage floor. */
    public final int rejectedLowFrameSupport;
    /** Components above the optional stationary-likelihood percentage ceiling. */
    public final int rejectedHighStationaryLikelihood;
    /** Components accepted after all enabled slow-mover filters. */
    public final int candidatesDetected;
    /** Effective lower geometric axis-ratio threshold. */
    public final double minAxisRatioThreshold;
    /** Effective upper geometric axis-ratio threshold. */
    public final double maxAxisRatioThreshold;
    /** Effective minimum fill factor; zero disables that filter. */
    public final double minFillFactorThreshold;
    /** Effective lower median-mask overlap threshold. */
    public final double medianSupportOverlapThreshold;
    /** Effective upper median-mask overlap threshold. */
    public final double medianSupportMaxOverlapThreshold;
    /** Effective minimum frame-support percentage. */
    public final double minFrameSupportThreshold;
    /** Effective maximum stationary-likelihood percentage. */
    public final double maxStationaryLikelihoodThreshold;
    /** Mean geometric axis ratio across accepted candidates. */
    public final double avgCandidateAxisRatio;
    /** Lowest accepted geometric axis ratio, or zero when none were accepted. */
    public final double minCandidateAxisRatio;
    /** Highest accepted geometric axis ratio, or zero when none were accepted. */
    public final double maxCandidateAxisRatio;
    /** Mean overlap across all mask-evaluated components, including rejected ones. */
    public final double avgMedianMaskOverlap;
    /** Mean morphology-derived displacement in pixels across accepted candidates. */
    public final double avgEstimatedMotionPixels;
    /** Mean morphology-derived displacement in footprint diameters across accepted candidates. */
    public final double avgEstimatedMotionDiameters;
    /** Axis ratios of all size-qualified maximum-stack components. */
    public final List<Double> candidateAxisRatios;
    /** Moment elongations of those same size-qualified components. */
    public final List<Double> candidateMomentElongations;
    /** Fill factors of those same size-qualified components. */
    public final List<Double> candidateFillFactors;
    /** Overlaps of every component that reached the median-mask stage. */
    public final List<Double> candidateMedianMaskOverlaps;
    /** Overlaps of accepted candidates, in the same order as the candidate results. */
    public final List<Double> candidateMedianSupportOverlaps;
    /** Frame-support percentages of accepted candidates, in result order. */
    public final List<Double> candidateFrameSupportPercentages;
    /** Stationary-likelihood percentages of accepted candidates, in result order. */
    public final List<Double> candidateStationaryLikelihoodPercentages;
    /** Whether each accepted candidate has a frame-support measurement. */
    public final List<Boolean> candidateFrameSupportAvailable;
    /** Whether each accepted candidate has a stationary-likelihood measurement. */
    public final List<Boolean> candidateStationaryLikelihoodAvailable;

    /** Copies the mutable pipeline telemetry into a read-only analysis summary. */
    public SlowMoverSummaryTelemetry(PipelineTelemetry.SlowMoverTelemetry telemetry) {
        this.rawCandidatesExtracted = telemetry.rawCandidatesExtracted;
        this.rejectedBelowMinPixels = telemetry.rejectedBelowMinPixels;
        this.rejectedBelowMinAxisRatio = telemetry.rejectedBelowMinAxisRatio;
        this.rejectedAboveMaxAxisRatio = telemetry.rejectedAboveMaxAxisRatio;
        this.rejectedLowFillFactor = telemetry.rejectedLowFillFactor;
        this.evaluatedAgainstMedianMask = telemetry.evaluatedAgainstMedianMask;
        this.rejectedLowMedianSupport = telemetry.rejectedLowMedianSupport;
        this.rejectedHighMedianSupport = telemetry.rejectedHighMedianSupport;
        this.evaluatedAgainstFrames = telemetry.evaluatedAgainstFrames;
        this.frameEvidenceUnavailable = telemetry.frameEvidenceUnavailable;
        this.rejectedLowFrameSupport = telemetry.rejectedLowFrameSupport;
        this.rejectedHighStationaryLikelihood = telemetry.rejectedHighStationaryLikelihood;
        this.candidatesDetected = telemetry.candidatesDetected;
        this.minAxisRatioThreshold = telemetry.minAxisRatioThreshold;
        this.maxAxisRatioThreshold = telemetry.maxAxisRatioThreshold;
        this.minFillFactorThreshold = telemetry.minFillFactorThreshold;
        this.medianSupportOverlapThreshold = telemetry.medianSupportOverlapThreshold;
        this.medianSupportMaxOverlapThreshold = telemetry.medianSupportMaxOverlapThreshold;
        this.minFrameSupportThreshold = telemetry.minFrameSupportThreshold;
        this.maxStationaryLikelihoodThreshold = telemetry.maxStationaryLikelihoodThreshold;
        this.avgCandidateAxisRatio = telemetry.avgCandidateAxisRatio;
        this.minCandidateAxisRatio = telemetry.minCandidateAxisRatio;
        this.maxCandidateAxisRatio = telemetry.maxCandidateAxisRatio;
        this.avgMedianMaskOverlap = telemetry.avgMedianMaskOverlap;
        this.avgEstimatedMotionPixels = telemetry.avgEstimatedMotionPixels;
        this.avgEstimatedMotionDiameters = telemetry.avgEstimatedMotionDiameters;
        this.candidateAxisRatios = Collections.unmodifiableList(new ArrayList<>(telemetry.candidateAxisRatios));
        this.candidateMomentElongations = Collections.unmodifiableList(new ArrayList<>(telemetry.candidateMomentElongations));
        this.candidateFillFactors = Collections.unmodifiableList(new ArrayList<>(telemetry.candidateFillFactors));
        this.candidateMedianMaskOverlaps = Collections.unmodifiableList(new ArrayList<>(telemetry.candidateMedianMaskOverlaps));
        this.candidateMedianSupportOverlaps = Collections.unmodifiableList(new ArrayList<>(telemetry.candidateMedianSupportOverlaps));
        this.candidateFrameSupportPercentages = Collections.unmodifiableList(new ArrayList<>(telemetry.candidateFrameSupportPercentages));
        this.candidateStationaryLikelihoodPercentages = Collections.unmodifiableList(new ArrayList<>(telemetry.candidateStationaryLikelihoodPercentages));
        this.candidateFrameSupportAvailable = Collections.unmodifiableList(new ArrayList<>(telemetry.candidateFrameSupportAvailable));
        this.candidateStationaryLikelihoodAvailable = Collections.unmodifiableList(new ArrayList<>(telemetry.candidateStationaryLikelihoodAvailable));
    }

    /** Returns a summary with zero counts and empty distributions. */
    public static SlowMoverSummaryTelemetry empty() {
        return new SlowMoverSummaryTelemetry(new PipelineTelemetry.SlowMoverTelemetry());
    }

    /**
     * Copies the summary back into the mutable pipeline telemetry type.
     * The candidates parameter is retained for compatibility; overlaps are already stored here.
     */
    public PipelineTelemetry.SlowMoverTelemetry toLegacyTelemetry(List<SlowMoverCandidateResult> candidates) {
        PipelineTelemetry.SlowMoverTelemetry telemetry = new PipelineTelemetry.SlowMoverTelemetry();
        telemetry.rawCandidatesExtracted = rawCandidatesExtracted;
        telemetry.rejectedBelowMinPixels = rejectedBelowMinPixels;
        telemetry.rejectedBelowMinAxisRatio = rejectedBelowMinAxisRatio;
        telemetry.rejectedAboveMaxAxisRatio = rejectedAboveMaxAxisRatio;
        telemetry.rejectedLowFillFactor = rejectedLowFillFactor;
        telemetry.evaluatedAgainstMedianMask = evaluatedAgainstMedianMask;
        telemetry.rejectedLowMedianSupport = rejectedLowMedianSupport;
        telemetry.rejectedHighMedianSupport = rejectedHighMedianSupport;
        telemetry.evaluatedAgainstFrames = evaluatedAgainstFrames;
        telemetry.frameEvidenceUnavailable = frameEvidenceUnavailable;
        telemetry.rejectedLowFrameSupport = rejectedLowFrameSupport;
        telemetry.rejectedHighStationaryLikelihood = rejectedHighStationaryLikelihood;
        telemetry.candidatesDetected = candidatesDetected;
        telemetry.minAxisRatioThreshold = minAxisRatioThreshold;
        telemetry.maxAxisRatioThreshold = maxAxisRatioThreshold;
        telemetry.minFillFactorThreshold = minFillFactorThreshold;
        telemetry.medianSupportOverlapThreshold = medianSupportOverlapThreshold;
        telemetry.medianSupportMaxOverlapThreshold = medianSupportMaxOverlapThreshold;
        telemetry.minFrameSupportThreshold = minFrameSupportThreshold;
        telemetry.maxStationaryLikelihoodThreshold = maxStationaryLikelihoodThreshold;
        telemetry.avgCandidateAxisRatio = avgCandidateAxisRatio;
        telemetry.minCandidateAxisRatio = minCandidateAxisRatio;
        telemetry.maxCandidateAxisRatio = maxCandidateAxisRatio;
        telemetry.avgMedianMaskOverlap = avgMedianMaskOverlap;
        telemetry.avgEstimatedMotionPixels = avgEstimatedMotionPixels;
        telemetry.avgEstimatedMotionDiameters = avgEstimatedMotionDiameters;
        telemetry.candidateAxisRatios.addAll(candidateAxisRatios);
        telemetry.candidateMomentElongations.addAll(candidateMomentElongations);
        telemetry.candidateFillFactors.addAll(candidateFillFactors);
        telemetry.candidateMedianMaskOverlaps.addAll(candidateMedianMaskOverlaps);
        telemetry.candidateMedianSupportOverlaps.addAll(candidateMedianSupportOverlaps);
        telemetry.candidateFrameSupportPercentages.addAll(candidateFrameSupportPercentages);
        telemetry.candidateStationaryLikelihoodPercentages.addAll(candidateStationaryLikelihoodPercentages);
        telemetry.candidateFrameSupportAvailable.addAll(candidateFrameSupportAvailable);
        telemetry.candidateStationaryLikelihoodAvailable.addAll(candidateStationaryLikelihoodAvailable);
        return telemetry;
    }
}
