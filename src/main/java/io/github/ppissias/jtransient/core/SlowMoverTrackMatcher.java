package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.execution.CancellationScope;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.model.Track;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Measures possible track/maximum-stack correspondences after tracking, without merging or rejecting findings.
 * Spatial overlap alone never establishes identity. Common-frame distances compare the actual supported
 * aperture centroids rather than matching a track to an untimed smear or to an extrapolated fit.
 */
public final class SlowMoverTrackMatcher {
    /** Spatial screening and common-frame agreement in pixels; zero distances require frameComparisonAvailable. */
    public record Match(int overlappingTrackPoints, double closestDistancePixels, double spatialTolerancePixels,
                        int comparedFrames, int ambiguousFrames, int timestampMismatches,
                        boolean frameComparisonAvailable, double framePositionRmsPixels,
                        double maxFramePositionDistancePixels) {}

    /** Static measurement utility; no state or image buffers are owned. */
    private SlowMoverTrackMatcher() {}

    /**
     * Returns a possible spatial correspondence, or null without a nearby track point. The screening tolerance
     * preserves the report's former footprint rule, bounded to 3–10 px; it is not a duplicate-acceptance threshold.
     * Inputs are borrowed read-only. Repeated original frame IDs and conflicting known times are excluded from
     * frame comparisons and counted explicitly. Missing times do not prevent a match on unambiguous frame IDs.
     */
    public static Match match(DetectedObject detection, SlowMoverMotionEvidence evidence, Track track) {
        if (track == null || track.points == null || track.points.isEmpty()) return null;
        double area = detection.pixelArea > 0 ? detection.pixelArea
                : detection.rawPixels == null ? 1 : Math.max(1, detection.rawPixels.size());
        double tolerance = Math.max(3.0, Math.min(10.0, Math.sqrt(area / Math.PI) * 0.55 + 2.0));
        int overlaps = 0;
        double closest = Double.POSITIVE_INFINITY;
        Map<Integer, DetectedObject> trackByFrame = new HashMap<>();
        Set<Integer> ambiguous = new HashSet<>();
        for (DetectedObject point : track.points) {
            CancellationScope.check();
            if (point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y)) continue;
            double distance = footprintDistance(point, detection);
            closest = Math.min(closest, distance);
            if (distance <= tolerance) overlaps++;
            if (trackByFrame.put(point.sourceFrameIndex, point) != null) ambiguous.add(point.sourceFrameIndex);
        }
        if (overlaps == 0 || !Double.isFinite(closest)) return null;
        int compared = 0, ambiguousCount = 0, timestampMismatches = 0;
        double squareSum = 0, maximum = 0;
        if (evidence != null) {
            Map<Integer, SlowMoverMotionEvidence.Position> positions = new HashMap<>();
            Set<Integer> repeatedPositions = new HashSet<>();
            for (var p : evidence.positions) {
                if (positions.put(p.sourceFrameIndex(), p) != null) repeatedPositions.add(p.sourceFrameIndex());
            }
            for (var entry : positions.entrySet()) {
                CancellationScope.check();
                int index = entry.getKey();
                DetectedObject point = trackByFrame.get(index);
                if (point == null) continue;
                if (ambiguous.contains(index) || repeatedPositions.contains(index)) { ambiguousCount++; continue; }
                var p = entry.getValue();
                if (!Double.isFinite(p.x()) || !Double.isFinite(p.y())) continue;
                if (p.timestamp() >= 0 && point.timestamp >= 0 && p.timestamp() != point.timestamp) {
                    timestampMismatches++;
                    continue;
                }
                double distance = Math.hypot(p.x() - point.x, p.y() - point.y);
                compared++;
                squareSum += distance * distance;
                maximum = Math.max(maximum, distance);
            }
        }
        return new Match(overlaps, closest, tolerance, compared, ambiguousCount, timestampMismatches,
                compared > 0, compared > 0 ? Math.sqrt(squareSum / compared) : 0, maximum);
    }

    /** Uses the exact raw footprint when present, otherwise the centroid, preserving the original display screen. */
    private static double footprintDistance(DetectedObject point, DetectedObject detection) {
        if (detection.rawPixels != null && !detection.rawPixels.isEmpty()) {
            double closest = Double.POSITIVE_INFINITY;
            for (Pixel pixel : detection.rawPixels) {
                CancellationScope.check();
                closest = Math.min(closest, Math.hypot(point.x - pixel.x, point.y - pixel.y));
            }
            return closest;
        }
        return Math.hypot(point.x - detection.x, point.y - detection.y);
    }
}
