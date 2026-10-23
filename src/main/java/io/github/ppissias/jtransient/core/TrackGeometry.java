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

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.execution.CancellationScope;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry and kinematics shared by the tracking stages: distances, angles, lines and the profile and rhythm checks
 * a point track's steps must pass.
 */
final class TrackGeometry {

    private TrackGeometry() {
    }

    /**
     * Returns the time delta when timestamps are available, otherwise the frame-index delta.
     */
    static double getTrackDelta(DetectedObject from, DetectedObject to) {
        if (from.timestamp != -1 && to.timestamp != -1) {
            double delta = to.timestamp - from.timestamp;
            if (delta > 0) {
                return delta;
            }
        }
        return Math.max(1, to.sourceFrameIndex - from.sourceFrameIndex);
    }

    /**
     * Applies morphology consistency checks between two candidate points.
     */
    static boolean isProfileConsistent(DetectedObject obj1, DetectedObject obj2, DetectionConfig config) {
        // 1. FWHM Consistency (Optics fingerprint)
        if (config.maxFwhmRatio > 0.0) {
            double fwhm1 = Math.max(obj1.fwhm, 0.1);
            double fwhm2 = Math.max(obj2.fwhm, 0.1);
            double fwhmRatio = Math.max(fwhm1, fwhm2) / Math.min(fwhm1, fwhm2);
            if (fwhmRatio > config.maxFwhmRatio) return false;
        }

        // 2. Surface Brightness Consistency (Density of the light)
        if (config.maxSurfaceBrightnessRatio > 0.0) {
            double sb1 = obj1.totalFlux / Math.max(obj1.pixelArea, 1.0);
            double sb2 = obj2.totalFlux / Math.max(obj2.pixelArea, 1.0);
            double sbRatio = Math.max(sb1, sb2) / Math.min(sb1, sb2);
            if (sbRatio > config.maxSurfaceBrightnessRatio) return false;
        }
        return true;
    }

    /**
     * Returns Euclidean distance between two points.
     */
    static double distance(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Compares two orientation angles while treating 180-degree flips as equivalent streak directions.
     */
    static boolean anglesMatch(double a1, double a2, double tolerance) {
        double diff = Math.abs(a1 - a2) % Math.PI;
        return diff <= tolerance || Math.PI - diff <= tolerance;
    }

    /**
     * Returns whether an observed motion vector stays within the expected directional cone.
     */
    static boolean isDirectionConsistent(double expectedAngle, double actualAngle, double tolerance) {
        double diff = Math.abs(expectedAngle - actualAngle);
        if (diff > Math.PI) diff = (2.0 * Math.PI) - diff;
        return diff <= tolerance;
    }

    /**
     * Computes the smallest unsigned angular distance between two directions.
     */
    static double angularDifference(double a1, double a2) {
        double diff = Math.abs(a1 - a2);
        if (diff > Math.PI) {
            diff = (2.0 * Math.PI) - diff;
        }
        return diff;
    }

    /**
     * Projects one object onto a candidate same-frame line for stable ordering.
     */
    static double projectionAlongLine(DetectedObject object,
                                              double originX,
                                              double originY,
                                              double dx,
                                              double dy) {
        return projectionAlongLine(object.x, object.y, originX, originY, dx, dy);
    }

    /**
     * Projects one coordinate pair onto a candidate line for frame-level streak motion checks.
     */
    static double projectionAlongLine(double x,
                                              double y,
                                              double originX,
                                              double originY,
                                              double dx,
                                              double dy) {
        return ((x - originX) * dx) + ((y - originY) * dy);
    }

    /**
     * Computes perpendicular distance from {@code p3} to the line defined by {@code p1 -> p2}.
     */
    static double distanceToLineOptimized(DetectedObject p1,
                                                  DetectedObject p2,
                                                  DetectedObject p3,
                                                  double precalculatedDenominator) {

        if (precalculatedDenominator == 0) return Double.MAX_VALUE;
        double numerator = Math.abs((p2.x - p1.x) * (p1.y - p3.y) - (p1.x - p3.x) * (p2.y - p1.y));
        return numerator / precalculatedDenominator;
    }

    /**
     * Validates that the step sizes in a track follow a mostly steady rhythm after allowing for skipped frames.
     */
    static boolean hasSteadyRhythm(Track track, double rhythmAllowedVariance, double rhythmStationaryThreshold, double rhythmMinConsistencyRatio) {
        if (track.points.size() < 3) return true;

        List<Double> jumps = new ArrayList<>();
        for (int i = 0; i < track.points.size() - 1; i++) {
            CancellationScope.check();
            DetectedObject p1 = track.points.get(i);
            DetectedObject p2 = track.points.get(i + 1);
            jumps.add(distance(p1.x, p1.y, p2.x, p2.y));
        }

        List<Double> sortedJumps = new ArrayList<>(jumps);
        sortedJumps.sort(Double::compareTo);
        double medianJump = sortedJumps.get(sortedJumps.size() / 2);

        if (medianJump < rhythmStationaryThreshold) return false;

        int consistentJumps = 0;

        for (double jump : jumps) {
            CancellationScope.check();
            long multiplier = Math.round(jump / medianJump);
            if (multiplier == 0) continue;

            double expectedJump = multiplier * medianJump;

            if (Math.abs(jump - expectedJump) <= (rhythmAllowedVariance * multiplier)) {
                consistentJumps++;
            }
        }

        double consistencyRatio = (double) consistentJumps / jumps.size();
        return consistencyRatio >= rhythmMinConsistencyRatio;
    }
}
