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

import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.model.Track;
import io.github.ppissias.jtransient.execution.CancellationScope;

import io.github.ppissias.jtransient.config.DetectionConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
 *
 * <p>Rescued anomalies on a confirmed streak's path, such as a streak crossing a star or a satellite's
 * flash, join that streak ({@link #joinAnomaliesOnStreakPaths}).</p>
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
    /** Perpendicular distance at which an anomaly's centre still counts as lying on a streak. */
    static final double ANOMALY_PATH_TOLERANCE_PX = 6.0;
    /** Perpendicular distance at which one of an anomaly's pixels puts it on a streak, for a streak through a star. */
    static final double ANOMALY_FOOTPRINT_TOLERANCE_PX = 2.0;
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
    public static void assemble(List<Track> streakTracks,
                                List<List<DetectedObject>> pointTransients,
                                List<DetectedObject> looseStreaks,
                                Set<DetectedObject> usedPoints,
                                DetectionConfig config) {
        if (streakTracks == null || config == null || !config.enableFaintStreakFragments) {
            return;
        }

        double tolerance = Math.max(0.0, config.faintStreakLineTolerance);
        Set<DetectedObject> onStreak = new HashSet<>();
        for (Track track : streakTracks) {
            CancellationScope.check();
            if (track != null && track.isStreakTrack && track.points != null) {
                onStreak.addAll(track.points);
            }
        }

        Map<Integer, List<DetectedObject>> byFrame = new HashMap<>();
        collectCandidates(pointTransients, byFrame, usedPoints, onStreak);
        if (looseStreaks != null) {
            for (DetectedObject streak : looseStreaks) {
                CancellationScope.check();
                addCandidate(byFrame, streak, usedPoints, onStreak);
            }
        }

        absorbExisting(streakTracks, byFrame, tolerance, usedPoints, onStreak);

        List<Integer> frames = new ArrayList<>(byFrame.keySet());
        frames.sort(Comparator.naturalOrder());
        for (int frame : frames) {
            CancellationScope.check();
            List<DetectedObject> pool = byFrame.get(frame);
            if (pool == null) {
                continue;
            }
            while (true) {
                CancellationScope.check();
                List<DetectedObject> line = bestNewLine(pool, tolerance);
                if (line == null) {
                    break;
                }
                Track track = new Track();
                track.isStreakTrack = true;
                for (DetectedObject piece : line) {
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

    private static void absorbExisting(List<Track> streakTracks,
                                       Map<Integer, List<DetectedObject>> byFrame,
                                       double tolerance,
                                       Set<DetectedObject> usedPoints,
                                       Set<DetectedObject> onStreak) {
        for (Track track : streakTracks) {
            CancellationScope.check();
            if (track == null || !track.isStreakTrack || track.points == null || track.points.isEmpty()) {
                continue;
            }
            Map<Integer, List<DetectedObject>> partsByFrame = new HashMap<>();
            for (DetectedObject part : track.points) {
                CancellationScope.check();
                if (part == null) {
                    continue;
                }
                partsByFrame.computeIfAbsent(part.sourceFrameIndex, key -> new ArrayList<>()).add(part);
            }
            for (Map.Entry<Integer, List<DetectedObject>> entry : partsByFrame.entrySet()) {
                CancellationScope.check();
                absorbOntoSegment(track, entry.getValue(), byFrame.get(entry.getKey()), tolerance, usedPoints, onStreak);
            }
        }
    }

    /**
     * Joins rescued anomalies that lie on the path of a confirmed streak to that streak and returns them. A faint
     * streak through a star changes the star's shape, and a tumbling satellite flashes along its path; both are
     * rescued as anomalies although they are pieces of the streak.
     * <ul>
     * <li>In a frame with parts of the streak, an anomaly joins when it lies on that frame's line, at most
     * {@link #ANOMALY_PATH_MARGIN_PX} past the parts.</li>
     * <li>A streak seen in several frames also takes an anomaly that lies on its line, measured on the nearest frames,
     * where its motion puts it in the anomaly's frame: between its trails in the frames before and after, or one
     * frame's motion past its first or last frame. That frame may be one the streak skipped or the frame just before
     * or after it.</li>
     * </ul>
     * An anomaly is on a line when its centre is within {@link #ANOMALY_PATH_TOLERANCE_PX} of it, or one of its pixels
     * within {@link #ANOMALY_FOOTPRINT_TOLERANCE_PX}: a streak through a star pulls the centre off the line. Joining
     * repeats until nothing more joins, so a frame filled in helps place the next one.
     */
    static Set<DetectedObject> joinAnomaliesOnStreakPaths(List<Track> streakTracks,
                                                                         List<DetectedObject> anomalies) {
        Set<DetectedObject> joined = new HashSet<>();
        if (streakTracks == null || anomalies == null) {
            return joined;
        }
        boolean grew = true;
        while (grew) {
            CancellationScope.check();
            grew = false;
            for (DetectedObject anomaly : anomalies) {
                CancellationScope.check();
                if (anomaly == null || joined.contains(anomaly)) {
                    continue;
                }
                Track best = null;
                double bestDistance = Double.POSITIVE_INFINITY;
                for (Track track : streakTracks) {
                    if (track == null || !track.isStreakTrack || track.points == null || track.points.isEmpty()) {
                        continue;
                    }
                    double distance = pathDistance(track, anomaly, joined);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = track;
                    }
                }
                if (best != null) {
                    best.addPoint(anomaly);
                    joined.add(anomaly);
                    grew = true;
                }
            }
        }
        return joined;
    }

    /**
     * How far the anomaly's centre is from the streak's line where it lies on the streak's path; infinite otherwise.
     * Anomalies already joined count where the streak was in their frame, but a round anomaly has no reliable
     * direction, so they never draw a frame's line on their own.
     */
    private static double pathDistance(Track track, DetectedObject anomaly,
                                       Set<DetectedObject> joined) {
        Map<Integer, List<DetectedObject>> byFrame = new TreeMap<>();
        for (DetectedObject part : track.points) {
            CancellationScope.check();
            if (part != null) {
                byFrame.computeIfAbsent(part.sourceFrameIndex, key -> new ArrayList<>()).add(part);
            }
        }
        int frame = anomaly.sourceFrameIndex;
        List<DetectedObject> own = streakParts(byFrame.getOrDefault(frame, List.of()), joined);
        double best = Double.POSITIVE_INFINITY;
        if (!own.isEmpty()) {
            double[] axis = lineAxis(own);
            double[] range = projectionRange(own, axis[0], axis[1], axis[2], axis[3]);
            double along = projection(anomaly.x, anomaly.y, axis[0], axis[1], axis[2], axis[3]);
            if (along >= range[0] - ANOMALY_PATH_MARGIN_PX && along <= range[1] + ANOMALY_PATH_MARGIN_PX) {
                best = lineDistance(anomaly, axis);
            }
        }
        if (byFrame.size() >= 2) {
            best = Math.min(best, motionPathDistance(byFrame, anomaly, joined));
        }
        return best;
    }

    /**
     * For a streak seen in several frames: the distance from its line near the anomaly's frame when the anomaly lies
     * where the streak's motion puts it in that frame; infinite otherwise.
     */
    private static double motionPathDistance(Map<Integer, List<DetectedObject>> byFrame,
                                             DetectedObject anomaly,
                                             Set<DetectedObject> joined) {
        TreeMap<Integer, List<DetectedObject>> frames = new TreeMap<>(byFrame);
        int frame = anomaly.sourceFrameIndex;
        Integer previous = frames.lowerKey(frame);
        Integer next = frames.higherKey(frame);
        boolean own = frames.containsKey(frame);
        if (!own && (previous == null || next == null)
                && frame != frames.firstKey() - 1 && frame != frames.lastKey() + 1) {
            return Double.POSITIVE_INFINITY;
        }

        List<DetectedObject> all = new ArrayList<>();
        for (List<DetectedObject> parts : frames.values()) {
            CancellationScope.check();
            all.addAll(parts);
        }
        double[] axis = lineAxis(all);
        boolean timed = anomaly.timestamp != -1L;
        Map<Integer, double[]> ranges = new HashMap<>();
        Map<Integer, Double> times = new HashMap<>();
        for (Map.Entry<Integer, List<DetectedObject>> entry : frames.entrySet()) {
            CancellationScope.check();
            ranges.put(entry.getKey(), projectionRange(entry.getValue(), axis[0], axis[1], axis[2], axis[3]));
            long timestamp = entry.getValue().get(0).timestamp;
            timed &= timestamp != -1L;
            times.put(entry.getKey(), (double) timestamp);
        }
        // Time is the frames' timestamps when every frame has one, otherwise the frame index.
        if (!timed) {
            for (Integer key : frames.keySet()) {
                CancellationScope.check();
                times.put(key, (double) key);
            }
        }
        double time = timed ? anomaly.timestamp : frame;

        // Positions along the line, oriented so that the streak moves towards larger values.
        double[] first = ranges.get(frames.firstKey());
        double[] last = ranges.get(frames.lastKey());
        double sign = Math.signum((last[0] + last[1]) - (first[0] + first[1]));
        if (sign == 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        List<Double> speeds = new ArrayList<>();
        Integer before = null;
        for (Integer key : frames.keySet()) {
            CancellationScope.check();
            if (before != null && times.get(key) > times.get(before)) {
                double step = sign * ((ranges.get(key)[0] + ranges.get(key)[1]) - (ranges.get(before)[0] + ranges.get(before)[1])) / 2.0;
                speeds.add(step / (times.get(key) - times.get(before)));
            }
            before = key;
        }
        if (speeds.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        speeds.sort(Comparator.naturalOrder());
        double speed = speeds.get(speeds.size() / 2);
        if (!(speed > 0.0)) {
            return Double.POSITIVE_INFINITY;
        }

        double lower;
        double upper;
        if (previous != null) {
            lower = orientedEnd(ranges.get(previous), sign);
        } else {
            lower = orientedStart(ranges.get(next), sign) - speed * (times.get(next) - time);
        }
        if (next != null) {
            upper = orientedStart(ranges.get(next), sign);
        } else {
            upper = orientedEnd(ranges.get(previous), sign) + speed * (time - times.get(previous));
        }
        if (own) {
            lower = Math.min(lower, orientedStart(ranges.get(frame), sign));
            upper = Math.max(upper, orientedEnd(ranges.get(frame), sign));
        }
        double position = sign * projection(anomaly.x, anomaly.y, axis[0], axis[1], axis[2], axis[3]);
        if (position < lower - ANOMALY_PATH_MARGIN_PX || position > upper + ANOMALY_PATH_MARGIN_PX) {
            return Double.POSITIVE_INFINITY;
        }

        // The line near the anomaly: the streak's own parts in the two nearest other frames that have them, and in the
        // anomaly's frame. A long path bends a little across the image, so the whole streak's line can miss a piece by
        // several pixels; joined anomalies are left out because a star pulls their centres off the line.
        List<Integer> nearest = new ArrayList<>();
        for (Map.Entry<Integer, List<DetectedObject>> entry : frames.entrySet()) {
            CancellationScope.check();
            if (entry.getKey() != frame && !streakParts(entry.getValue(), joined).isEmpty()) {
                nearest.add(entry.getKey());
            }
        }
        nearest.sort(Comparator.comparingInt(key -> Math.abs(key - frame)));
        List<DetectedObject> local = new ArrayList<>();
        for (int i = 0; i < Math.min(2, nearest.size()); i++) {
            CancellationScope.check();
            local.addAll(streakParts(frames.get(nearest.get(i)), joined));
        }
        if (own) {
            local.addAll(streakParts(frames.get(frame), joined));
        }
        if (local.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        return lineDistance(anomaly, lineAxis(local));
    }

    /** The parts of a frame that belong to the streak itself, not anomalies joined to it. */
    private static List<DetectedObject> streakParts(List<DetectedObject> parts,
                                                                    Set<DetectedObject> joined) {
        List<DetectedObject> own = new ArrayList<>();
        for (DetectedObject part : parts) {
            CancellationScope.check();
            if (!joined.contains(part)) {
                own.add(part);
            }
        }
        return own;
    }

    private static double orientedStart(double[] range, double sign) {
        return sign > 0 ? range[0] : -range[1];
    }

    private static double orientedEnd(double[] range, double sign) {
        return sign > 0 ? range[1] : -range[0];
    }

    /**
     * The distance of the anomaly's centre from the line when it lies on the line: its centre within
     * {@link #ANOMALY_PATH_TOLERANCE_PX}, or one of its pixels within {@link #ANOMALY_FOOTPRINT_TOLERANCE_PX}.
     * Infinite otherwise.
     */
    private static double lineDistance(DetectedObject anomaly, double[] axis) {
        double centre = Math.abs(projection(anomaly.x, anomaly.y, axis[0], axis[1], axis[4], axis[5]));
        if (centre <= ANOMALY_PATH_TOLERANCE_PX) {
            return centre;
        }
        if (anomaly.rawPixels != null) {
            for (Pixel pixel : anomaly.rawPixels) {
                CancellationScope.check();
                if (Math.abs(projection(pixel.x, pixel.y, axis[0], axis[1], axis[4], axis[5])) <= ANOMALY_FOOTPRINT_TOLERANCE_PX) {
                    return centre;
                }
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    private static void collectCandidates(List<List<DetectedObject>> frames,
                                          Map<Integer, List<DetectedObject>> byFrame,
                                          Set<DetectedObject> usedPoints,
                                          Set<DetectedObject> onStreak) {
        if (frames == null) {
            return;
        }
        for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
            CancellationScope.check();
            List<DetectedObject> frame = frames.get(frameIndex);
            if (frame == null) {
                continue;
            }
            for (DetectedObject object : frame) {
                CancellationScope.check();
                if (object != null && object.sourceFrameIndex < 0) {
                    object.sourceFrameIndex = frameIndex;
                }
                addCandidate(byFrame, object, usedPoints, onStreak);
            }
        }
    }

    private static void addCandidate(Map<Integer, List<DetectedObject>> byFrame,
                                     DetectedObject object,
                                     Set<DetectedObject> usedPoints,
                                     Set<DetectedObject> onStreak) {
        if (!isFragment(object, usedPoints, onStreak)) {
            return;
        }
        byFrame.computeIfAbsent(object.sourceFrameIndex, key -> new ArrayList<>()).add(object);
    }

    /** A piece large enough to join a line. Round pieces qualify; the angle rules come later. */
    private static boolean isFragment(DetectedObject object,
                                      Set<DetectedObject> usedPoints,
                                      Set<DetectedObject> onStreak) {
        return object != null
                && object.pixelArea >= SEED_MIN_PIXELS
                && !usedPoints.contains(object)
                && !onStreak.contains(object);
    }

    private static boolean isElongated(DetectedObject object) {
        return object.elongation >= SEED_MIN_ELONGATION;
    }

    private static void absorbOntoSegment(Track track,
                                          List<DetectedObject> parts,
                                          List<DetectedObject> candidates,
                                          double tolerance,
                                          Set<DetectedObject> usedPoints,
                                          Set<DetectedObject> onStreak) {
        if (candidates == null || candidates.isEmpty() || parts.isEmpty()) {
            return;
        }
        double[] axis = lineAxis(parts);
        double reach = Math.max(END_MARGIN_PX, EXTENSION_GAP_PX);
        List<DetectedObject> absorbed = new ArrayList<>();
        List<DetectedObject> span = new ArrayList<>(parts);
        boolean grew = true;
        while (grew) {
            CancellationScope.check();
            grew = false;
            double[] range = projectionRange(span, axis[0], axis[1], axis[2], axis[3]);
            List<DetectedObject> wave = new ArrayList<>();
            for (DetectedObject candidate : candidates) {
                CancellationScope.check();
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
        for (DetectedObject piece : absorbed) {
            CancellationScope.check();
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
    private static double[] lineAxis(List<DetectedObject> parts) {
        DetectedObject first = parts.get(0);
        double angle = parts.size() == 1 ? first.angle : principalAngle(parts);
        if (!Double.isFinite(angle)) {
            angle = 0.0;
        }
        double dirX = Math.cos(angle);
        double dirY = Math.sin(angle);
        return new double[]{first.x, first.y, dirX, dirY, -dirY, dirX, angle};
    }

    private static double[] projectionRange(List<DetectedObject> parts,
                                            double originX,
                                            double originY,
                                            double dirX,
                                            double dirY) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (DetectedObject part : parts) {
            CancellationScope.check();
            boolean usedPixels = false;
            if (part.rawPixels != null) {
                for (Pixel pixel : part.rawPixels) {
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

    private static List<DetectedObject> bestNewLine(List<DetectedObject> pool,
                                                                    double tolerance) {
        List<DetectedObject> stable = new ArrayList<>();
        for (DetectedObject object : pool) {
            CancellationScope.check();
            if (object.pixelArea >= STABLE_ANGLE_MIN_PIXELS && object.elongation >= SEED_MIN_ELONGATION) {
                stable.add(object);
            }
        }
        if (stable.size() < 3) {
            return null;
        }

        List<DetectedObject> best = null;
        double bestSpan = -1.0;
        for (DetectedObject seed : stable) {
            CancellationScope.check();
            if (!Double.isFinite(seed.angle)) {
                continue;
            }
            List<DetectedObject> corridor = new ArrayList<>();
            double dirX = Math.cos(seed.angle);
            double dirY = Math.sin(seed.angle);
            double normalX = -dirY;
            double normalY = dirX;
            for (DetectedObject object : pool) {
                CancellationScope.check();
                double perp = Math.abs(projection(object.x, object.y, seed.x, seed.y, normalX, normalY));
                if (perp <= tolerance) {
                    corridor.add(object);
                }
            }
            List<DetectedObject> line = refineLine(corridor, corridor, tolerance);
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

    private static List<DetectedObject> refineLine(List<DetectedObject> seeds,
                                                                   List<DetectedObject> pool,
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
        List<DetectedObject> members = new ArrayList<>();
        for (DetectedObject object : pool) {
            CancellationScope.check();
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
        List<DetectedObject> bestWindow = null;
        double bestSpan = -1.0;
        for (int start = 0; start < members.size(); start++) {
            CancellationScope.check();
            for (int end = start + MIN_NEW_LINE_PIECES - 1; end < members.size(); end++) {
                CancellationScope.check();
                List<DetectedObject> window = members.subList(start, end + 1);
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

    private static boolean anglesSupportLine(List<DetectedObject> members, double angle) {
        int stableCount = 0;
        int agreeing = 0;
        for (DetectedObject member : members) {
            CancellationScope.check();
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

    private static double spanOf(List<DetectedObject> members) {
        DetectedObject first = members.get(0);
        DetectedObject last = members.get(members.size() - 1);
        return Math.hypot(last.x - first.x, last.y - first.y);
    }

    private static double largestGap(List<DetectedObject> ordered,
                                     double originX,
                                     double originY,
                                     double dirX,
                                     double dirY) {
        double largest = 0.0;
        double previous = projection(ordered.get(0).x, ordered.get(0).y, originX, originY, dirX, dirY);
        for (int i = 1; i < ordered.size(); i++) {
            CancellationScope.check();
            double current = projection(ordered.get(i).x, ordered.get(i).y, originX, originY, dirX, dirY);
            largest = Math.max(largest, current - previous);
            previous = current;
        }
        return largest;
    }

    private static double principalAngle(List<DetectedObject> points) {
        double meanX = 0.0;
        double meanY = 0.0;
        for (DetectedObject point : points) {
            CancellationScope.check();
            meanX += point.x;
            meanY += point.y;
        }
        meanX /= points.size();
        meanY /= points.size();
        double sxx = 0.0;
        double syy = 0.0;
        double sxy = 0.0;
        for (DetectedObject point : points) {
            CancellationScope.check();
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
