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

import io.github.ppissias.jtransient.execution.CancellationScope;

import io.github.ppissias.jtransient.config.DetectionConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Filters compact common-motion sources before local rescue and activity clustering.
 *
 * <p>Only unclassified observations are examined. Chronological associations must be uniquely
 * nearest in both directions; ambiguous associations remain available for residual review.
 * Each observation belongs to at most one source and one accepted group. A group needs at least
 * two separated sources and three frames shared by every member.</p>
 */
public final class CommonMotionRejector {
    static final int MIN_SHARED_FRAMES = 3;
    static final double MAX_ELONGATION = 2.5;
    static final int MAX_COMPACT_PIXELS = 40;
    static final double MIN_SEPARATION_PX = 30.0;
    static final double MAX_LINK_SHIFT_PX = 25.0;
    static final int MAX_FRAME_GAP = 6;
    private static final double DISTANCE_TIE_PX = 1e-6;

    private CommonMotionRejector() {
    }

    /**
     * Finds common-motion groups and removes their observations from the supplied leftovers.
     * This stage is controlled by enableCommonMotionRejection, independently of the residual switch.
     * Removed observations remain available in the returned groups and the engine's allTransients.
     *
     * @param unclassified chronological per-frame leftovers; grouped observations are removed in place
     * @param config configuration, or null to leave the input unchanged
     * @return immutable groups; empty when disabled or no match is found
     */
    public static List<ResidualTransientAnalysis.CommonMotionGroup> reject(
            List<List<SourceExtractor.DetectedObject>> unclassified, DetectionConfig config) {
        if (config == null || !config.enableCommonMotionRejection) {
            return List.of();
        }
        List<Source> sources = sourcesFromUnclassified(unclassified);
        List<List<Source>> groups = groupSources(sources, Math.max(0.0, config.commonMotionShiftTolerance));
        Set<SourceExtractor.DetectedObject> removed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<ResidualTransientAnalysis.CommonMotionGroup> exported = new ArrayList<>();
        for (List<Source> group : groups) {
            CancellationScope.check();
            List<List<SourceExtractor.DetectedObject>> members = new ArrayList<>();
            for (Source source : group) {
                CancellationScope.check();
                List<SourceExtractor.DetectedObject> points = source.orderedPoints();
                members.add(points);
                removed.addAll(points);
            }
            exported.add(new ResidualTransientAnalysis.CommonMotionGroup(members, sharedFrames(group).size()));
        }
        if (unclassified != null && !removed.isEmpty()) {
            for (List<SourceExtractor.DetectedObject> frame : unclassified) {
                CancellationScope.check();
                if (frame != null) {
                    frame.removeIf(removed::contains);
                }
            }
        }
        return List.copyOf(exported);
    }

    /**
     * Compatibility entry point for callers that already performed residual analysis.
     * The source input is exclusively unclassified; rescues are never added as duplicate sources.
     * When observations are removed, rescues and clusters are recomputed from the remaining input.
     * Prefer reject before ResidualTransientAnalyzer.analyze to avoid computing residuals twice.
     */
    @Deprecated
    public static ResidualTransientAnalysis apply(ResidualTransientAnalysis residual,
                                                  List<List<SourceExtractor.DetectedObject>> unclassified,
                                                  DetectionConfig config) {
        List<ResidualTransientAnalysis.CommonMotionGroup> groups = reject(unclassified, config);
        if (groups.isEmpty()) {
            return residual == null ? ResidualTransientAnalysis.empty() : residual;
        }
        ResidualTransientAnalysis remaining = ResidualTransientAnalyzer.analyze(unclassified, config);
        List<ResidualTransientAnalysis.CommonMotionGroup> allGroups = new ArrayList<>();
        if (residual != null) {
            allGroups.addAll(residual.commonMotionGroups);
        }
        allGroups.addAll(groups);
        return new ResidualTransientAnalysis(remaining.localRescueCandidates, remaining.localActivityClusters, allGroups);
    }

    private static List<Source> sourcesFromUnclassified(List<List<SourceExtractor.DetectedObject>> unclassified) {
        List<Source> sources = new ArrayList<>();
        if (unclassified == null) {
            return sources;
        }
        Map<Integer, List<SourceExtractor.DetectedObject>> byFrame = new HashMap<>();
        Set<SourceExtractor.DetectedObject> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (int frameIndex = 0; frameIndex < unclassified.size(); frameIndex++) {
            CancellationScope.check();
            List<SourceExtractor.DetectedObject> frame = unclassified.get(frameIndex);
            if (frame == null) {
                continue;
            }
            for (SourceExtractor.DetectedObject point : frame) {
                CancellationScope.check();
                if (point == null || !seen.add(point)) {
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
            CancellationScope.check();
            Grid grid = new Grid(MAX_LINK_SHIFT_PX);
            for (SourceExtractor.DetectedObject point : entry.getValue()) {
                CancellationScope.check();
                grid.add(point);
            }
            grids.put(entry.getKey(), grid);
        }
        Set<SourceExtractor.DetectedObject> assigned = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<Integer> frames = new ArrayList<>(byFrame.keySet());
        frames.sort(Comparator.naturalOrder());
        for (int frame : frames) {
            CancellationScope.check();
            for (SourceExtractor.DetectedObject start : byFrame.get(frame)) {
                CancellationScope.check();
                if (assigned.contains(start)) {
                    continue;
                }
                Source source = new Source();
                SourceExtractor.DetectedObject current = start;
                while (current != null) {
                    assigned.add(current);
                    source.byFrame.put(current.sourceFrameIndex, current);
                    current = nextUnambiguous(current, grids, assigned);
                }
                if (source.byFrame.size() >= MIN_SHARED_FRAMES) {
                    sources.add(source);
                }
            }
        }
        return sources;
    }

    /** Prefer the first frame with a possible observation; do not jump over an ambiguous match. */
    private static SourceExtractor.DetectedObject nextUnambiguous(SourceExtractor.DetectedObject current,
                                                                 Map<Integer, Grid> grids,
                                                                 Set<SourceExtractor.DetectedObject> assigned) {
        for (int later = current.sourceFrameIndex + 1; later <= current.sourceFrameIndex + MAX_FRAME_GAP; later++) {
            CancellationScope.check();
            Grid grid = grids.get(later);
            if (grid == null) {
                continue;
            }
            List<SourceExtractor.DetectedObject> nearby = grid.near(current.x, current.y, MAX_LINK_SHIFT_PX);
            if (nearby.isEmpty()) {
                continue;
            }
            SourceExtractor.DetectedObject candidate = uniqueNearest(nearby, current.x, current.y);
            if (candidate == null || assigned.contains(candidate)) {
                return null;
            }
            Grid previous = grids.get(current.sourceFrameIndex);
            SourceExtractor.DetectedObject reverse = uniqueNearest(
                    previous.near(candidate.x, candidate.y, MAX_LINK_SHIFT_PX), candidate.x, candidate.y);
            return reverse == current ? candidate : null;
        }
        return null;
    }

    private static SourceExtractor.DetectedObject uniqueNearest(List<SourceExtractor.DetectedObject> points,
                                                                double x, double y) {
        SourceExtractor.DetectedObject best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        double secondDistance = Double.POSITIVE_INFINITY;
        for (SourceExtractor.DetectedObject point : points) {
            CancellationScope.check();
            double distance = Math.hypot(point.x - x, point.y - y);
            if (distance < bestDistance) {
                secondDistance = bestDistance;
                bestDistance = distance;
                best = point;
            } else if (distance < secondDistance) {
                secondDistance = distance;
            }
        }
        return secondDistance - bestDistance <= DISTANCE_TIE_PX ? null : best;
    }

    private static List<List<Source>> groupSources(List<Source> sources, double tolerance) {
        boolean[] used = new boolean[sources.size()];
        List<List<Source>> groups = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            CancellationScope.check();
            if (used[i]) {
                continue;
            }
            List<Integer> members = new ArrayList<>();
            members.add(i);
            Set<Integer> commonFrames = new HashSet<>(sources.get(i).byFrame.keySet());
            for (int j = i + 1; j < sources.size(); j++) {
                CancellationScope.check();
                if (used[j]) {
                    continue;
                }
                Set<Integer> intersection = new HashSet<>(commonFrames);
                intersection.retainAll(sources.get(j).byFrame.keySet());
                if (intersection.size() < MIN_SHARED_FRAMES) {
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
                    commonFrames = intersection;
                }
            }
            if (members.size() < 2) {
                continue;
            }
            List<Source> group = new ArrayList<>();
            for (int member : members) {
                CancellationScope.check();
                used[member] = true;
                group.add(sources.get(member));
            }
            group.sort(Comparator.comparingDouble(Source::centroidX).thenComparingDouble(Source::centroidY));
            groups.add(group);
        }
        return groups;
    }

    private static boolean agrees(Source left, Source right, double tolerance) {
        if (Math.hypot(left.centroidX() - right.centroidX(), left.centroidY() - right.centroidY()) < MIN_SEPARATION_PX) {
            return false;
        }
        List<Integer> shared = new ArrayList<>();
        for (int frame : left.byFrame.keySet()) {
            CancellationScope.check();
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
            CancellationScope.check();
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

    private static Set<Integer> sharedFrames(List<Source> group) {
        Set<Integer> shared = new HashSet<>(group.get(0).byFrame.keySet());
        for (int i = 1; i < group.size(); i++) {
            CancellationScope.check();
            shared.retainAll(group.get(i).byFrame.keySet());
        }
        return shared;
    }

    private static boolean isCompact(SourceExtractor.DetectedObject point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y)
                && point.elongation <= MAX_ELONGATION
                && point.pixelArea >= 1.0 && point.pixelArea <= MAX_COMPACT_PIXELS;
    }

    private static final class Source {
        private final Map<Integer, SourceExtractor.DetectedObject> byFrame = new HashMap<>();
        private List<SourceExtractor.DetectedObject> orderedPoints() {
            List<SourceExtractor.DetectedObject> points = new ArrayList<>(byFrame.values());
            points.sort(Comparator.comparingInt(point -> point.sourceFrameIndex));
            return points;
        }
        private double centroidX() {
            return byFrame.values().stream().mapToDouble(point -> point.x).average().orElse(Double.NaN);
        }
        private double centroidY() {
            return byFrame.values().stream().mapToDouble(point -> point.y).average().orElse(Double.NaN);
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
                CancellationScope.check();
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
