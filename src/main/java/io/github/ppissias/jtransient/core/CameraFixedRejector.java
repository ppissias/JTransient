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
import io.github.ppissias.jtransient.execution.CancellationScope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds detections that stay at one camera position while the sky moves: hot pixels, warm pixels and dust that the
 * calibration left. Registration moves them with the dither and the drift, so in the registered frames they look like
 * faint movers, local repeats or common motion.
 *
 * <p>Each detection is mapped to camera coordinates with its frame's {@link FrameRegistration}. A group is the
 * detections of at least {@link #MIN_FRAMES} frames, one per frame, within {@link #CAMERA_TOLERANCE_PX} of the group's
 * median camera position, whose positions in the frames spread over at least {@link #MIN_SKY_SPREAD_PX}; with less, the
 * frames did not move enough to tell a camera position from a position on the sky. Frames whose registration is not
 * known or not usable are left out.</p>
 */
public final class CameraFixedRejector {
    /** Largest distance of a member from the group's median camera position, in pixels. */
    static final double CAMERA_TOLERANCE_PX = 2.0;
    /** Smallest spread of the members' positions in the frames, in pixels. */
    static final double MIN_SKY_SPREAD_PX = 4.0;
    /** Fewest frames in a group. */
    static final int MIN_FRAMES = 3;

    private CameraFixedRejector() {
    }

    /** Detections of one camera position, one per frame. */
    public static final class Group {
        /** Median camera x of the members. */
        public final double cameraX;
        /** Median camera y of the members. */
        public final double cameraY;
        /** The detections, in frame order. */
        public final List<DetectedObject> points;

        Group(double cameraX, double cameraY, List<DetectedObject> points) {
            this.cameraX = cameraX;
            this.cameraY = cameraY;
            this.points = Collections.unmodifiableList(points);
        }
    }

    private static final class Entry {
        final DetectedObject detection;
        final double u;
        final double v;

        Entry(DetectedObject detection, double u, double v) {
            this.detection = detection;
            this.u = u;
            this.v = v;
        }
    }

    /**
     * Finds the groups and removes their detections from the frames' lists.
     *
     * @param frames point detections per frame; a detection's {@code sourceFrameIndex} is its frame's sequence index
     * @param registrations the frames' registrations by sequence index
     * @return the groups, ordered by their first frame and position
     */
    public static List<Group> reject(List<List<DetectedObject>> frames,
                                     Map<Integer, FrameRegistration> registrations) {
        if (frames == null || registrations == null || registrations.isEmpty()) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        for (List<DetectedObject> frame : frames) {
            CancellationScope.check();
            if (frame == null) {
                continue;
            }
            for (DetectedObject detection : frame) {
                CancellationScope.check();
                FrameRegistration registration = detection == null || detection.isStreak
                        ? null : registrations.get(detection.sourceFrameIndex);
                if (registration != null && registration.usable) {
                    double[] camera = registration.toCamera(detection.x, detection.y);
                    entries.add(new Entry(detection, camera[0], camera[1]));
                }
            }
        }
        double cell = 2 * CAMERA_TOLERANCE_PX;
        Map<Long, List<Entry>> grid = new HashMap<>();
        for (Entry entry : entries) {
            CancellationScope.check();
            grid.computeIfAbsent(key(entry.u, entry.v, cell), k -> new ArrayList<>()).add(entry);
        }

        Set<Entry> assigned = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Group> groups = new ArrayList<>();
        for (Entry seed : entries) {
            CancellationScope.check();
            if (assigned.contains(seed)) {
                continue;
            }
            List<Entry> first = nearestPerFrame(grid, cell, seed.u, seed.v, 2 * CAMERA_TOLERANCE_PX, assigned);
            if (first.size() < MIN_FRAMES) {
                continue;
            }
            double medianU = median(first, true);
            double medianV = median(first, false);
            List<Entry> members = nearestPerFrame(grid, cell, medianU, medianV, CAMERA_TOLERANCE_PX, assigned);
            if (members.size() < MIN_FRAMES || !members.contains(seed) || skySpread(members) < MIN_SKY_SPREAD_PX) {
                continue;
            }
            members.sort(Comparator.comparingInt(e -> e.detection.sourceFrameIndex));
            List<DetectedObject> points = new ArrayList<>(members.size());
            for (Entry member : members) {
                CancellationScope.check();
                assigned.add(member);
                points.add(member.detection);
            }
            groups.add(new Group(median(members, true), median(members, false), points));
        }

        if (!groups.isEmpty()) {
            Set<DetectedObject> removed = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Group group : groups) {
                CancellationScope.check();
                removed.addAll(group.points);
            }
            for (List<DetectedObject> frame : frames) {
                CancellationScope.check();
                if (frame != null) {
                    frame.removeIf(removed::contains);
                }
            }
        }
        return Collections.unmodifiableList(groups);
    }

    /** The entry nearest to (u, v) within the radius in each frame, leaving out assigned entries. */
    private static List<Entry> nearestPerFrame(Map<Long, List<Entry>> grid, double cell, double u, double v,
                                               double radius, Set<Entry> assigned) {
        Map<Integer, Entry> best = new HashMap<>();
        Map<Integer, Double> bestDistance = new HashMap<>();
        int reach = (int) Math.ceil(radius / cell);
        long cu = (long) Math.floor(u / cell);
        long cv = (long) Math.floor(v / cell);
        for (long du = -reach; du <= reach; du++) {
            CancellationScope.check();
            for (long dv = -reach; dv <= reach; dv++) {
                CancellationScope.check();
                List<Entry> bucket = grid.get(pack(cu + du, cv + dv));
                if (bucket == null) {
                    continue;
                }
                for (Entry entry : bucket) {
                    if (assigned.contains(entry)) {
                        continue;
                    }
                    double d = Math.hypot(entry.u - u, entry.v - v);
                    int frame = entry.detection.sourceFrameIndex;
                    if (d <= radius && d < bestDistance.getOrDefault(frame, Double.MAX_VALUE)) {
                        best.put(frame, entry);
                        bestDistance.put(frame, d);
                    }
                }
            }
        }
        return new ArrayList<>(best.values());
    }

    private static double median(List<Entry> entries, boolean u) {
        double[] values = new double[entries.size()];
        for (int i = 0; i < values.length; i++) {
            CancellationScope.check();
            values[i] = u ? entries.get(i).u : entries.get(i).v;
        }
        java.util.Arrays.sort(values);
        int n = values.length;
        return n % 2 == 1 ? values[n / 2] : 0.5 * (values[n / 2 - 1] + values[n / 2]);
    }

    /** Largest distance between two members' positions in their frames. */
    private static double skySpread(List<Entry> entries) {
        double spread = 0;
        for (int i = 0; i < entries.size(); i++) {
            CancellationScope.check();
            for (int j = i + 1; j < entries.size(); j++) {
                CancellationScope.check();
                DetectedObject a = entries.get(i).detection;
                DetectedObject b = entries.get(j).detection;
                spread = Math.max(spread, Math.hypot(a.x - b.x, a.y - b.y));
            }
        }
        return spread;
    }

    private static long key(double u, double v, double cell) {
        return pack((long) Math.floor(u / cell), (long) Math.floor(v / cell));
    }

    private static long pack(long cu, long cv) {
        return (cu << 32) ^ (cv & 0xffffffffL);
    }
}
