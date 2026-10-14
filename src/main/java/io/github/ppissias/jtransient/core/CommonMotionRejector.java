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
 * Groups compact sources that share one displacement and removes them from the residual review.
 *
 * <p>A group needs at least two sources, separated on the frame, whose positions relative to a
 * shared reference frame agree on at least three frames. One pair seen in only two frames is
 * kept. Local-rescue candidates are examined first, then compact detections that residual
 * analysis left unclassified.</p>
 */
public final class CommonMotionRejector {

    static final int MIN_SHARED_FRAMES = 3;
    static final double MAX_ELONGATION = 2.5;
    static final int MAX_COMPACT_PIXELS = 40;
    static final double MIN_SEPARATION_PX = 30.0;
    static final double MAX_LINK_SHIFT_PX = 25.0;
    static final int MAX_FRAME_GAP = 6;

    private CommonMotionRejector() {
    }

    /**
     * Removes grouped sources from the rescue list and from the unclassified detections.
     *
     * @param residual residual analysis to filter; returned unchanged when nothing is grouped
     * @param unclassified per-frame leftovers; matching detections are removed in place
     * @param config pipeline configuration
     * @return the original result when no group is found, otherwise a replacement
     */
    public static ResidualTransientAnalysis apply(ResidualTransientAnalysis residual,
                                                  List<List<SourceExtractor.DetectedObject>> unclassified,
                                                  DetectionConfig config) {
        if (config == null || !config.enableCommonMotionRejection) {
            return residual == null ? ResidualTransientAnalysis.empty() : residual;
        }
        if (residual == null) {
            residual = ResidualTransientAnalysis.empty();
        }

        double tolerance = Math.max(0.0, config.commonMotionShiftTolerance);
        List<Source> sources = new ArrayList<>();
        List<ResidualTransientAnalysis.LocalRescueCandidate> rescues = residual.localRescueCandidates;
        for (int i = 0; i < rescues.size(); i++) {
            Source source = sourceFromRescue(rescues.get(i), i);
            if (source != null) {
                sources.add(source);
            }
        }
        sources.addAll(sourcesFromUnclassified(unclassified));

        List<List<Source>> groups = groupSources(sources, tolerance);
        if (groups.isEmpty()) {
            return residual;
        }

        Set<Integer> removedRescues = new HashSet<>();
        Set<SourceExtractor.DetectedObject> removedPoints = new HashSet<>();
        List<ResidualTransientAnalysis.CommonMotionGroup> exported = new ArrayList<>();
        for (List<Source> group : groups) {
            List<List<SourceExtractor.DetectedObject>> exportedSources = new ArrayList<>();
            int shared = minimumSharedFrames(group);
            for (Source source : group) {
                exportedSources.add(source.orderedPoints());
                if (source.rescueIndex >= 0) {
                    removedRescues.add(source.rescueIndex);
                } else {
                    removedPoints.addAll(source.byFrame.values());
                }
            }
            exported.add(new ResidualTransientAnalysis.CommonMotionGroup(exportedSources, shared));
        }

        List<ResidualTransientAnalysis.LocalRescueCandidate> keptRescues = new ArrayList<>();
        for (int i = 0; i < rescues.size(); i++) {
            if (!removedRescues.contains(i)) {
                keptRescues.add(rescues.get(i));
            }
        }
        if (unclassified != null && !removedPoints.isEmpty()) {
            for (List<SourceExtractor.DetectedObject> frame : unclassified) {
                if (frame != null) {
                    frame.removeIf(removedPoints::contains);
                }
            }
        }
        List<ResidualTransientAnalysis.LocalActivityCluster> keptClusters = new ArrayList<>();
        for (ResidualTransientAnalysis.LocalActivityCluster cluster : residual.localActivityClusters) {
            boolean hit = false;
            for (SourceExtractor.DetectedObject point : cluster.points) {
                if (removedPoints.contains(point)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                keptClusters.add(cluster);
            }
        }
        return new ResidualTransientAnalysis(keptRescues, keptClusters, exported);
    }

    private static Source sourceFromRescue(ResidualTransientAnalysis.LocalRescueCandidate candidate, int rescueIndex) {
        if (candidate == null || candidate.points == null || candidate.points.size() < MIN_SHARED_FRAMES) {
            return null;
        }
        Source source = new Source(rescueIndex);
        for (SourceExtractor.DetectedObject point : candidate.points) {
            if (!isCompact(point) || source.byFrame.containsKey(point.sourceFrameIndex)) {
                return null;
            }
            source.byFrame.put(point.sourceFrameIndex, point);
        }
        return source.byFrame.size() >= MIN_SHARED_FRAMES ? source : null;
    }

    private static List<Source> sourcesFromUnclassified(List<List<SourceExtractor.DetectedObject>> unclassified) {
        List<Source> sources = new ArrayList<>();
        if (unclassified == null) {
            return sources;
        }
        Map<Integer, List<SourceExtractor.DetectedObject>> byFrame = new HashMap<>();
        for (int frameIndex = 0; frameIndex < unclassified.size(); frameIndex++) {
            List<SourceExtractor.DetectedObject> frame = unclassified.get(frameIndex);
            if (frame == null) {
                continue;
            }
            for (SourceExtractor.DetectedObject point : frame) {
                if (point == null) {
                    continue;
                }
                if (point.sourceFrameIndex < 0) {
                    point.sourceFrameIndex = frameIndex;
                }
                if (isCompact(point)) {
                    byFrame.computeIfAbsent(point.sourceFrameIndex, key -> new ArrayList<>()).add(point);
                }
            }
        }
        Map<Integer, Grid> grids = new HashMap<>();
        for (Map.Entry<Integer, List<SourceExtractor.DetectedObject>> entry : byFrame.entrySet()) {
            Grid grid = new Grid(MAX_LINK_SHIFT_PX);
            for (SourceExtractor.DetectedObject point : entry.getValue()) {
                grid.add(point);
            }
            grids.put(entry.getKey(), grid);
        }

        List<Integer> frames = new ArrayList<>(byFrame.keySet());
        frames.sort(Comparator.naturalOrder());
        for (int frame : frames) {
            for (SourceExtractor.DetectedObject start : byFrame.get(frame)) {
                if (hasPredecessor(start, frame, grids)) {
                    continue;
                }
                Source source = new Source(-1);
                source.byFrame.put(frame, start);
                SourceExtractor.DetectedObject current = start;
                int currentFrame = frame;
                while (true) {
                    SourceExtractor.DetectedObject next = nearestAhead(current, currentFrame, grids);
                    if (next == null) {
                        break;
                    }
                    source.byFrame.put(next.sourceFrameIndex, next);
                    current = next;
                    currentFrame = next.sourceFrameIndex;
                }
                if (source.byFrame.size() >= MIN_SHARED_FRAMES) {
                    sources.add(source);
                }
            }
        }
        return sources;
    }

    private static boolean hasPredecessor(SourceExtractor.DetectedObject start,
                                          int frame,
                                          Map<Integer, Grid> grids) {
        for (int earlier = frame - MAX_FRAME_GAP; earlier < frame; earlier++) {
            Grid grid = grids.get(earlier);
            if (grid == null) {
                continue;
            }
            if (!grid.near(start.x, start.y, MAX_LINK_SHIFT_PX).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static SourceExtractor.DetectedObject nearestAhead(SourceExtractor.DetectedObject current,
                                                               int frame,
                                                               Map<Integer, Grid> grids) {
        SourceExtractor.DetectedObject best = null;
        double bestDistance = MAX_LINK_SHIFT_PX + 1.0;
        int bestFrame = Integer.MAX_VALUE;
        for (int later = frame + 1; later <= frame + MAX_FRAME_GAP; later++) {
            Grid grid = grids.get(later);
            if (grid == null) {
                continue;
            }
            for (SourceExtractor.DetectedObject candidate : grid.near(current.x, current.y, MAX_LINK_SHIFT_PX)) {
                double distance = Math.hypot(candidate.x - current.x, candidate.y - current.y);
                if (distance < bestDistance - 1e-6 || (Math.abs(distance - bestDistance) <= 1e-6 && later < bestFrame)) {
                    best = candidate;
                    bestDistance = distance;
                    bestFrame = later;
                }
            }
        }
        return best;
    }

    private static List<List<Source>> groupSources(List<Source> sources, double tolerance) {
        int count = sources.size();
        boolean[] used = new boolean[count];
        List<List<Source>> groups = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (used[i]) {
                continue;
            }
            List<Integer> members = new ArrayList<>();
            members.add(i);
            for (int j = i + 1; j < count; j++) {
                if (used[j] || sharesPoint(sources, members, sources.get(j))) {
                    continue;
                }
                boolean agrees = true;
                for (int member : members) {
                    if (!agrees(sources.get(member), sources.get(j), tolerance)) {
                        agrees = false;
                        break;
                    }
                }
                if (agrees) {
                    members.add(j);
                }
            }
            if (members.size() < 2) {
                continue;
            }
            List<Source> group = new ArrayList<>();
            for (int member : members) {
                used[member] = true;
                group.add(sources.get(member));
            }
            group.sort(Comparator.comparingDouble(Source::centroidX).thenComparingDouble(Source::centroidY));
            groups.add(group);
        }
        return groups;
    }

    private static boolean sharesPoint(List<Source> sources, List<Integer> members, Source candidate) {
        for (int member : members) {
            for (SourceExtractor.DetectedObject point : sources.get(member).byFrame.values()) {
                if (candidate.byFrame.containsValue(point)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean agrees(Source left, Source right, double tolerance) {
        if (Math.hypot(left.centroidX() - right.centroidX(), left.centroidY() - right.centroidY()) < MIN_SEPARATION_PX) {
            return false;
        }
        List<Integer> shared = new ArrayList<>();
        for (int frame : left.byFrame.keySet()) {
            if (right.byFrame.containsKey(frame)) {
                shared.add(frame);
            }
        }
        if (shared.size() < MIN_SHARED_FRAMES) {
            return false;
        }
        shared.sort(Comparator.naturalOrder());
        int reference = shared.get(0);
        SourceExtractor.DetectedObject leftReference = left.byFrame.get(reference);
        SourceExtractor.DetectedObject rightReference = right.byFrame.get(reference);
        for (int frame : shared) {
            SourceExtractor.DetectedObject leftPoint = left.byFrame.get(frame);
            SourceExtractor.DetectedObject rightPoint = right.byFrame.get(frame);
            double dx = (leftPoint.x - leftReference.x) - (rightPoint.x - rightReference.x);
            double dy = (leftPoint.y - leftReference.y) - (rightPoint.y - rightReference.y);
            if (Math.hypot(dx, dy) > tolerance) {
                return false;
            }
        }
        return true;
    }

    private static int minimumSharedFrames(List<Source> group) {
        int minimum = Integer.MAX_VALUE;
        for (int i = 0; i < group.size(); i++) {
            for (int j = i + 1; j < group.size(); j++) {
                int shared = 0;
                for (int frame : group.get(i).byFrame.keySet()) {
                    if (group.get(j).byFrame.containsKey(frame)) {
                        shared++;
                    }
                }
                minimum = Math.min(minimum, shared);
            }
        }
        return minimum;
    }

    private static boolean isCompact(SourceExtractor.DetectedObject point) {
        return point != null
                && point.elongation <= MAX_ELONGATION
                && point.pixelArea >= 1.0
                && point.pixelArea <= MAX_COMPACT_PIXELS;
    }

    private static final class Source {
        private final int rescueIndex;
        private final Map<Integer, SourceExtractor.DetectedObject> byFrame = new HashMap<>();

        private Source(int rescueIndex) {
            this.rescueIndex = rescueIndex;
        }

        private List<SourceExtractor.DetectedObject> orderedPoints() {
            List<SourceExtractor.DetectedObject> points = new ArrayList<>(byFrame.values());
            points.sort(Comparator.comparingInt(point -> point.sourceFrameIndex));
            return points;
        }

        private double centroidX() {
            double sum = 0.0;
            for (SourceExtractor.DetectedObject point : byFrame.values()) {
                sum += point.x;
            }
            return sum / byFrame.size();
        }

        private double centroidY() {
            double sum = 0.0;
            for (SourceExtractor.DetectedObject point : byFrame.values()) {
                sum += point.y;
            }
            return sum / byFrame.size();
        }
    }

    private static final class Grid {
        private final double cell;
        private final Map<Long, List<SourceExtractor.DetectedObject>> cells = new HashMap<>();

        private Grid(double cell) {
            this.cell = cell;
        }

        private void add(SourceExtractor.DetectedObject point) {
            cells.computeIfAbsent(key(point.x, point.y), ignored -> new ArrayList<>()).add(point);
        }

        private List<SourceExtractor.DetectedObject> near(double x, double y, double radius) {
            int centerX = (int) Math.floor(x / cell);
            int centerY = (int) Math.floor(y / cell);
            int reach = (int) Math.ceil(radius / cell);
            List<SourceExtractor.DetectedObject> found = new ArrayList<>();
            for (int ix = centerX - reach; ix <= centerX + reach; ix++) {
                for (int iy = centerY - reach; iy <= centerY + reach; iy++) {
                    List<SourceExtractor.DetectedObject> bucket = cells.get(pack(ix, iy));
                    if (bucket == null) {
                        continue;
                    }
                    for (SourceExtractor.DetectedObject point : bucket) {
                        if (Math.hypot(point.x - x, point.y - y) <= radius) {
                            found.add(point);
                        }
                    }
                }
            }
            return found;
        }

        private long key(double x, double y) {
            return pack((int) Math.floor(x / cell), (int) Math.floor(y / cell));
        }

        private static long pack(int x, int y) {
            return (((long) x) << 32) ^ (y & 0xffffffffL);
        }
    }
}
