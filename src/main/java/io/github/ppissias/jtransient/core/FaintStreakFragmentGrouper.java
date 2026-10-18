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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Joins faint same-frame streak pieces that never reach the normal streak gates.
 *
 * <p>An existing streak absorbs pieces that sit on its segment, and a connected chain may extend
 * the segment. Each added piece has to stay within {@link #EXTENSION_GAP_PX} of the part of
 * the streak already accepted, so a collinear point with a large empty gap is left alone.
 * A round piece has no reliable angle, so only an elongated piece is checked against the
 * streak's direction. A new single-frame streak needs at least three elongated pieces whose
 * angles agree, over a long span with no large gap. Rounder pieces may fill the gaps between
 * those anchors. A line of round pieces alone is not a streak. The normal streak elongation,
 * size and peak gates are left unchanged.</p>
 */
public final class FaintStreakFragmentGrouper {

    static final int MIN_NEW_LINE_PIECES = 4;
    static final double MAX_GAP_FRACTION = 0.40;
    static final double MIN_SPAN_PX = 80.0;
    static final double ANGLE_TOLERANCE_RAD = Math.toRadians(25.0);
    /** How far past the current segment a piece may sit and still join, in pixels. */
    static final double END_MARGIN_PX = 20.0;
    /**
     * Largest empty gap, in pixels, across which a same-frame streak may grow.
     * The growth repeats, so a chain of pieces spaced within this gap is absorbed,
     * and an isolated point beyond it is not.
     */
    static final double EXTENSION_GAP_PX = 80.0;
    /** Perpendicular distance at which an anomaly still counts as lying on a streak. */
    static final double ANOMALY_PATH_TOLERANCE_PX = 6.0;
    /** How far past a streak an anomaly may sit and still count as on its path. */
    static final double ANOMALY_PATH_MARGIN_PX = 80.0;
    static final double SEED_MIN_ELONGATION = 2.0;
    static final int SEED_MIN_PIXELS = 4;
    static final int STABLE_ANGLE_MIN_PIXELS = 6;
    private FaintStreakFragmentGrouper() {
    }

    /**
     * Extends confirmed same-frame streaks and adds new ones built from faint pieces.
     *
     * @param streakTracks confirmed streak tracks; new single-frame streaks are appended
     * @param pointTransients non-streak transients that survived the star veto, one list per frame
     * @param looseStreaks streak-shaped detections that were not placed on a streak track
     * @param usedPoints detections already consumed by a track; absorbed pieces are added
     * @param config pipeline configuration
     */
    public static void assemble(List<TrackLinker.Track> streakTracks,
                                List<List<SourceExtractor.DetectedObject>> pointTransients,
                                List<SourceExtractor.DetectedObject> looseStreaks,
                                Set<SourceExtractor.DetectedObject> usedPoints,
                                DetectionConfig config) {
        if (streakTracks == null || config == null || !config.enableFaintStreakFragments) {
            return;
        }

        double tolerance = Math.max(0.0, config.faintStreakLineTolerance);
        Set<SourceExtractor.DetectedObject> onStreak = new HashSet<>();
        for (TrackLinker.Track track : streakTracks) {
            if (track != null && track.isStreakTrack && track.points != null) {
                onStreak.addAll(track.points);
            }
        }

        Map<Integer, List<SourceExtractor.DetectedObject>> byFrame = new HashMap<>();
        collectCandidates(pointTransients, byFrame, usedPoints, onStreak);
        if (looseStreaks != null) {
            for (SourceExtractor.DetectedObject streak : looseStreaks) {
                addCandidate(byFrame, streak, usedPoints, onStreak);
            }
        }

        absorbExisting(streakTracks, byFrame, tolerance, usedPoints, onStreak);

        List<Integer> frames = new ArrayList<>(byFrame.keySet());
        frames.sort(Comparator.naturalOrder());
        for (int frame : frames) {
            List<SourceExtractor.DetectedObject> pool = byFrame.get(frame);
            if (pool == null) {
                continue;
            }
            while (true) {
                List<SourceExtractor.DetectedObject> line = bestNewLine(pool, tolerance);
                if (line == null) {
                    break;
                }
                TrackLinker.Track track = new TrackLinker.Track();
                track.isStreakTrack = true;
                for (SourceExtractor.DetectedObject piece : line) {
                    track.addPoint(piece);
                    usedPoints.add(piece);
                    onStreak.add(piece);
                }
                streakTracks.add(track);
                pool.removeAll(line);
            }
        }

        // A new line only keeps the window that qualified. Pieces left on that same line,
        // including a short chain past its ends, join it here.
        absorbExisting(streakTracks, byFrame, tolerance, usedPoints, onStreak);
    }

    private static void absorbExisting(List<TrackLinker.Track> streakTracks,
                                       Map<Integer, List<SourceExtractor.DetectedObject>> byFrame,
                                       double tolerance,
                                       Set<SourceExtractor.DetectedObject> usedPoints,
                                       Set<SourceExtractor.DetectedObject> onStreak) {
        for (TrackLinker.Track track : streakTracks) {
            if (track == null || !track.isStreakTrack || track.points == null || track.points.isEmpty()) {
                continue;
            }
            Map<Integer, List<SourceExtractor.DetectedObject>> partsByFrame = new HashMap<>();
            for (SourceExtractor.DetectedObject part : track.points) {
                if (part == null) {
                    continue;
                }
                partsByFrame.computeIfAbsent(part.sourceFrameIndex, key -> new ArrayList<>()).add(part);
            }
            for (Map.Entry<Integer, List<SourceExtractor.DetectedObject>> entry : partsByFrame.entrySet()) {
                absorbOntoSegment(track, entry.getValue(), byFrame.get(entry.getKey()), tolerance, usedPoints, onStreak);
            }
        }
    }

    /**
     * Whether {@code object} lies on a confirmed streak in its own frame.
     * The corridor is wider than the fragment tolerance because a streak through a star
     * pulls the measured centre off the line. The object is not absorbed.
     */
    static boolean liesOnKnownStreak(SourceExtractor.DetectedObject object, List<TrackLinker.Track> streakTracks) {
        if (object == null || streakTracks == null) {
            return false;
        }
        for (TrackLinker.Track track : streakTracks) {
            if (track == null || !track.isStreakTrack || track.points == null) {
                continue;
            }
            List<SourceExtractor.DetectedObject> parts = new ArrayList<>();
            for (SourceExtractor.DetectedObject part : track.points) {
                if (part != null && part.sourceFrameIndex == object.sourceFrameIndex) {
                    parts.add(part);
                }
            }
            if (parts.isEmpty()) {
                continue;
            }
            double[] axis = lineAxis(parts);
            double[] range = projectionRange(parts, axis[0], axis[1], axis[2], axis[3]);
            double along = projection(object.x, object.y, axis[0], axis[1], axis[2], axis[3]);
            double perp = Math.abs(projection(object.x, object.y, axis[0], axis[1], axis[4], axis[5]));
            if (perp <= ANOMALY_PATH_TOLERANCE_PX
                    && along >= range[0] - ANOMALY_PATH_MARGIN_PX
                    && along <= range[1] + ANOMALY_PATH_MARGIN_PX) {
                return true;
            }
        }
        return false;
    }

    private static void collectCandidates(List<List<SourceExtractor.DetectedObject>> frames,
                                          Map<Integer, List<SourceExtractor.DetectedObject>> byFrame,
                                          Set<SourceExtractor.DetectedObject> usedPoints,
                                          Set<SourceExtractor.DetectedObject> onStreak) {
        if (frames == null) {
            return;
        }
        for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
            List<SourceExtractor.DetectedObject> frame = frames.get(frameIndex);
            if (frame == null) {
                continue;
            }
            for (SourceExtractor.DetectedObject object : frame) {
                if (object != null && object.sourceFrameIndex < 0) {
                    object.sourceFrameIndex = frameIndex;
                }
                addCandidate(byFrame, object, usedPoints, onStreak);
            }
        }
    }

    private static void addCandidate(Map<Integer, List<SourceExtractor.DetectedObject>> byFrame,
                                     SourceExtractor.DetectedObject object,
                                     Set<SourceExtractor.DetectedObject> usedPoints,
                                     Set<SourceExtractor.DetectedObject> onStreak) {
        if (!isFragment(object, usedPoints, onStreak)) {
            return;
        }
        byFrame.computeIfAbsent(object.sourceFrameIndex, key -> new ArrayList<>()).add(object);
    }

    /** A piece large enough to join a line. Round pieces qualify; the angle rules come later. */
    private static boolean isFragment(SourceExtractor.DetectedObject object,
                                      Set<SourceExtractor.DetectedObject> usedPoints,
                                      Set<SourceExtractor.DetectedObject> onStreak) {
        return object != null
                && object.pixelArea >= SEED_MIN_PIXELS
                && !usedPoints.contains(object)
                && !onStreak.contains(object);
    }

    private static boolean isElongated(SourceExtractor.DetectedObject object) {
        return object.elongation >= SEED_MIN_ELONGATION;
    }

    private static void absorbOntoSegment(TrackLinker.Track track,
                                          List<SourceExtractor.DetectedObject> parts,
                                          List<SourceExtractor.DetectedObject> candidates,
                                          double tolerance,
                                          Set<SourceExtractor.DetectedObject> usedPoints,
                                          Set<SourceExtractor.DetectedObject> onStreak) {
        if (candidates == null || candidates.isEmpty() || parts.isEmpty()) {
            return;
        }
        double[] axis = lineAxis(parts);
        double reach = Math.max(END_MARGIN_PX, EXTENSION_GAP_PX);
        List<SourceExtractor.DetectedObject> absorbed = new ArrayList<>();
        List<SourceExtractor.DetectedObject> span = new ArrayList<>(parts);
        boolean grew = true;
        while (grew) {
            grew = false;
            double[] range = projectionRange(span, axis[0], axis[1], axis[2], axis[3]);
            List<SourceExtractor.DetectedObject> wave = new ArrayList<>();
            for (SourceExtractor.DetectedObject candidate : candidates) {
                if (!isFragment(candidate, usedPoints, onStreak) || absorbed.contains(candidate)) {
                    continue;
                }
                double along = projection(candidate.x, candidate.y, axis[0], axis[1], axis[2], axis[3]);
                double perp = Math.abs(projection(candidate.x, candidate.y, axis[0], axis[1], axis[4], axis[5]));
                if (perp > tolerance) {
                    continue;
                }
                if (along < range[0] - reach || along > range[1] + reach) {
                    continue;
                }
                if (isElongated(candidate)
                        && candidate.pixelArea >= STABLE_ANGLE_MIN_PIXELS
                        && !anglesAgree(candidate.angle, axis[6])) {
                    continue;
                }
                wave.add(candidate);
            }
            if (!wave.isEmpty()) {
                absorbed.addAll(wave);
                span.addAll(wave);
                grew = true;
            }
        }
        absorbed.sort(Comparator.comparingDouble(piece -> projection(piece.x, piece.y, axis[0], axis[1], axis[2], axis[3])));
        for (SourceExtractor.DetectedObject piece : absorbed) {
            track.addPoint(piece);
            usedPoints.add(piece);
            onStreak.add(piece);
        }
        candidates.removeAll(absorbed);
    }

    /**
     * Axis packed as originX, originY, dirX, dirY, normalX, normalY, angle.
     * A single part keeps its own angle and does not define an infinite line.
     */
    private static double[] lineAxis(List<SourceExtractor.DetectedObject> parts) {
        SourceExtractor.DetectedObject first = parts.get(0);
        double angle = parts.size() == 1 ? first.angle : principalAngle(parts);
        if (!Double.isFinite(angle)) {
            angle = 0.0;
        }
        double dirX = Math.cos(angle);
        double dirY = Math.sin(angle);
        return new double[]{first.x, first.y, dirX, dirY, -dirY, dirX, angle};
    }

    private static double[] projectionRange(List<SourceExtractor.DetectedObject> parts,
                                            double originX,
                                            double originY,
                                            double dirX,
                                            double dirY) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (SourceExtractor.DetectedObject part : parts) {
            boolean usedPixels = false;
            if (part.rawPixels != null) {
                for (SourceExtractor.Pixel pixel : part.rawPixels) {
                    double along = projection(pixel.x, pixel.y, originX, originY, dirX, dirY);
                    min = Math.min(min, along);
                    max = Math.max(max, along);
                    usedPixels = true;
                }
            }
            if (!usedPixels) {
                double along = projection(part.x, part.y, originX, originY, dirX, dirY);
                double half = part.majorExtent > 1.0 ? part.majorExtent / 2.0 : 0.0;
                min = Math.min(min, along - half);
                max = Math.max(max, along + half);
            }
        }
        if (min > max) {
            min = 0.0;
            max = 0.0;
        }
        return new double[]{min, max};
    }

    private static List<SourceExtractor.DetectedObject> bestNewLine(List<SourceExtractor.DetectedObject> pool,
                                                                    double tolerance) {
        List<SourceExtractor.DetectedObject> stable = new ArrayList<>();
        for (SourceExtractor.DetectedObject object : pool) {
            if (object.pixelArea >= STABLE_ANGLE_MIN_PIXELS && object.elongation >= SEED_MIN_ELONGATION) {
                stable.add(object);
            }
        }
        if (stable.size() < 3) {
            return null;
        }

        List<SourceExtractor.DetectedObject> best = null;
        double bestSpan = -1.0;
        for (SourceExtractor.DetectedObject seed : stable) {
            if (!Double.isFinite(seed.angle)) {
                continue;
            }
            List<SourceExtractor.DetectedObject> corridor = new ArrayList<>();
            double dirX = Math.cos(seed.angle);
            double dirY = Math.sin(seed.angle);
            double normalX = -dirY;
            double normalY = dirX;
            for (SourceExtractor.DetectedObject object : pool) {
                double perp = Math.abs(projection(object.x, object.y, seed.x, seed.y, normalX, normalY));
                if (perp <= tolerance) {
                    corridor.add(object);
                }
            }
            List<SourceExtractor.DetectedObject> line = refineLine(corridor, corridor, tolerance);
            if (line == null) {
                continue;
            }
            double span = spanOf(line);
            if (best == null || line.size() > best.size() || (line.size() == best.size() && span > bestSpan)) {
                best = line;
                bestSpan = span;
            }
        }
        return best;
    }

    private static List<SourceExtractor.DetectedObject> refineLine(List<SourceExtractor.DetectedObject> seeds,
                                                                   List<SourceExtractor.DetectedObject> pool,
                                                                   double tolerance) {
        if (seeds.size() < 3) {
            return null;
        }
        double angle = principalAngle(seeds);
        double dirX = Math.cos(angle);
        double dirY = Math.sin(angle);
        double normalX = -dirY;
        double normalY = dirX;
        double originX = seeds.get(0).x;
        double originY = seeds.get(0).y;
        List<SourceExtractor.DetectedObject> members = new ArrayList<>();
        for (SourceExtractor.DetectedObject object : pool) {
            if (object.pixelArea < SEED_MIN_PIXELS) {
                continue;
            }
            double perp = Math.abs(projection(object.x, object.y, originX, originY, normalX, normalY));
            if (perp <= tolerance) {
                members.add(object);
            }
        }
        if (members.size() < MIN_NEW_LINE_PIECES) {
            return null;
        }
        members.sort(Comparator.comparingDouble(piece -> projection(piece.x, piece.y, originX, originY, dirX, dirY)));
        List<SourceExtractor.DetectedObject> bestWindow = null;
        double bestSpan = -1.0;
        for (int start = 0; start < members.size(); start++) {
            for (int end = start + MIN_NEW_LINE_PIECES - 1; end < members.size(); end++) {
                List<SourceExtractor.DetectedObject> window = members.subList(start, end + 1);
                double span = spanOf(window);
                if (span < MIN_SPAN_PX || largestGap(window, originX, originY, dirX, dirY) > MAX_GAP_FRACTION * span) {
                    continue;
                }
                if (!anglesSupportLine(window, angle)) {
                    continue;
                }
                if (bestWindow == null || window.size() > bestWindow.size() || (window.size() == bestWindow.size() && span > bestSpan)) {
                    bestWindow = new ArrayList<>(window);
                    bestSpan = span;
                }
            }
        }
        return bestWindow;
    }

    private static boolean anglesSupportLine(List<SourceExtractor.DetectedObject> members, double angle) {
        int stableCount = 0;
        int agreeing = 0;
        for (SourceExtractor.DetectedObject member : members) {
            if (member.pixelArea < STABLE_ANGLE_MIN_PIXELS || !isElongated(member)) {
                continue;
            }
            stableCount++;
            if (anglesAgree(member.angle, angle)) {
                agreeing++;
            }
        }
        return agreeing >= 3 && agreeing * 2 > stableCount;
    }

    private static double spanOf(List<SourceExtractor.DetectedObject> members) {
        SourceExtractor.DetectedObject first = members.get(0);
        SourceExtractor.DetectedObject last = members.get(members.size() - 1);
        return Math.hypot(last.x - first.x, last.y - first.y);
    }

    private static double largestGap(List<SourceExtractor.DetectedObject> ordered,
                                     double originX,
                                     double originY,
                                     double dirX,
                                     double dirY) {
        double largest = 0.0;
        double previous = projection(ordered.get(0).x, ordered.get(0).y, originX, originY, dirX, dirY);
        for (int i = 1; i < ordered.size(); i++) {
            double current = projection(ordered.get(i).x, ordered.get(i).y, originX, originY, dirX, dirY);
            largest = Math.max(largest, current - previous);
            previous = current;
        }
        return largest;
    }

    private static double principalAngle(List<SourceExtractor.DetectedObject> points) {
        double meanX = 0.0;
        double meanY = 0.0;
        for (SourceExtractor.DetectedObject point : points) {
            meanX += point.x;
            meanY += point.y;
        }
        meanX /= points.size();
        meanY /= points.size();
        double sxx = 0.0;
        double syy = 0.0;
        double sxy = 0.0;
        for (SourceExtractor.DetectedObject point : points) {
            double dx = point.x - meanX;
            double dy = point.y - meanY;
            sxx += dx * dx;
            syy += dy * dy;
            sxy += dx * dy;
        }
        return 0.5 * Math.atan2(2.0 * sxy, sxx - syy);
    }

    private static boolean anglesAgree(double left, double right) {
        if (!Double.isFinite(left) || !Double.isFinite(right)) {
            return false;
        }
        double delta = Math.abs(left - right) % Math.PI;
        if (delta > Math.PI / 2.0) {
            delta = Math.PI - delta;
        }
        return delta <= ANGLE_TOLERANCE_RAD + 1e-9;
    }

    private static double projection(double x, double y, double originX, double originY, double dirX, double dirY) {
        return (x - originX) * dirX + (y - originY) * dirY;
    }
}
