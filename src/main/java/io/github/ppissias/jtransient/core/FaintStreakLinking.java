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
import io.github.ppissias.jtransient.model.AnomalyDetection;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.model.Track;
import io.github.ppissias.jtransient.telemetry.FaintStreakSearchTelemetry;
import io.github.ppissias.jtransient.telemetry.TrackerTelemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adds the faint-streak search's candidates ({@link FaintStreakSearch}) to the streak tracks with normal priority:
 * the normal streaks are linked and consolidated first, exactly as without the search, and the faint candidates can
 * then only join those tracks or form new ones.
 *
 * <ol>
 *   <li>A candidate that shares footprint pixels with a normal streak detection of the same frame, at an angle within
 *       {@code angleToleranceDegrees}, measures the same piece of trail: it is dropped and the normal measurement kept.
 *       A candidate crossing a normal streak at another angle stays.</li>
 *   <li>The other candidates are linked among themselves; an unlinked one becomes a single-frame streak without a peak
 *       gate, after the binary-star shape screen.</li>
 *   <li>The resulting tracks are offered to the consolidated tracks ({@link StreakConsolidator#addStreakTracks}).</li>
 *   <li>Rescued anomalies on the path of a track with faint detections join it, as on any streak, and leave the anomaly
 *       list.</li>
 * </ol>
 * <p>A candidate that ends in no track is discarded: no other result receives the search's objects.</p>
 */
final class FaintStreakLinking {

    private FaintStreakLinking() {
    }

    /**
     * Adds the candidates to the streak tracks.
     *
     * @param streakTracks consolidated streak tracks of the normal pass; not modified
     * @param normalStreaks the normal pass's post-veto streak detections
     * @param candidates the search's candidates, by kept frame
     * @param anomalies rescued anomalies; those joining a track are removed
     * @param config the run's configuration
     * @param telemetry the search's counts, completed here
     * @return the streak tracks with the candidates added
     */
    static List<Track> add(List<Track> streakTracks,
                           List<DetectedObject> normalStreaks,
                           List<List<DetectedObject>> candidates,
                           List<AnomalyDetection> anomalies,
                           DetectionConfig config,
                           FaintStreakSearchTelemetry telemetry) {
        List<DetectedObject> pool = new ArrayList<>();
        for (List<DetectedObject> frame : candidates) {
            pool.addAll(frame);
        }
        pool.sort(Comparator.<DetectedObject>comparingInt(object -> object.sourceFrameIndex)
                .thenComparingDouble(object -> object.y)
                .thenComparingDouble(object -> object.x));

        double angleTolerance = Math.toRadians(config.angleToleranceDegrees);
        Map<Integer, List<NormalStreak>> normalByFrame = new HashMap<>();
        for (DetectedObject streak : normalStreaks) {
            normalByFrame.computeIfAbsent(streak.sourceFrameIndex, frame -> new ArrayList<>()).add(new NormalStreak(streak));
        }
        List<DetectedObject> linked = new ArrayList<>();
        for (DetectedObject candidate : pool) {
            CancellationScope.check();
            if (duplicatesNormal(candidate, normalByFrame.get(candidate.sourceFrameIndex), angleTolerance)) {
                telemetry.duplicatesOfNormalStreaks++;
            } else {
                linked.add(candidate);
            }
        }
        telemetry.linkedCandidates = linked.size();
        if (linked.isEmpty()) {
            return streakTracks;
        }

        TrackerTelemetry linkTelemetry = new TrackerTelemetry();
        List<Track> faintTracks = StreakLinker.link(linked, config, linkTelemetry, 0.0);
        telemetry.rejectedBinaryStarShape = linkTelemetry.rejectedBinaryStarStreakShape;
        List<Track> tracks = faintTracks.isEmpty() ? streakTracks
                : StreakConsolidator.addStreakTracks(streakTracks, faintTracks, config);

        List<Track> withFaint = new ArrayList<>();
        for (Track track : tracks) {
            if (containsFaint(track)) {
                withFaint.add(track);
            }
        }
        if (!withFaint.isEmpty() && !anomalies.isEmpty()) {
            List<DetectedObject> rescued = new ArrayList<>();
            for (AnomalyDetection anomaly : anomalies) {
                rescued.add(anomaly.object);
            }
            Set<DetectedObject> joined = FaintStreakFragmentGrouper.joinAnomaliesOnStreakPaths(withFaint, rescued);
            if (!joined.isEmpty()) {
                anomalies.removeIf(anomaly -> joined.contains(anomaly.object));
                for (Track track : withFaint) {
                    StreakConsolidator.normalizeStreakTrackOrder(track);
                }
                telemetry.anomaliesJoined = joined.size();
            }
        }

        Set<DetectedObject> inTracks = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Track track : withFaint) {
            int faint = 0;
            for (DetectedObject point : track.points) {
                if (point.fromFaintStreakSearch) {
                    inTracks.add(point);
                    faint++;
                }
            }
            telemetry.tracksWithFaintDetections++;
            if (faint == track.points.size()) {
                telemetry.faintOnlyTracks++;
            }
        }
        telemetry.detectionsInTracks = inTracks.size();
        telemetry.discarded = linked.size() - inTracks.size();
        return tracks;
    }

    /** Whether the track holds at least one faint-search detection. */
    private static boolean containsFaint(Track track) {
        for (DetectedObject point : track.points) {
            if (point.fromFaintStreakSearch) {
                return true;
            }
        }
        return false;
    }

    /** Whether the candidate measures the same piece of trail as one of the frame's normal streak detections. */
    private static boolean duplicatesNormal(DetectedObject candidate, List<NormalStreak> normal, double angleTolerance) {
        if (normal == null || candidate.rawPixels == null) {
            return false;
        }
        for (NormalStreak streak : normal) {
            if (!TrackGeometry.anglesMatch(candidate.angle, streak.object.angle, angleTolerance)) {
                continue;
            }
            for (Pixel pixel : candidate.rawPixels) {
                if (streak.contains(pixel)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A normal streak detection with its footprint as a pixel set. */
    private static final class NormalStreak {
        final DetectedObject object;
        private final Set<Long> pixels = new HashSet<>();

        NormalStreak(DetectedObject object) {
            this.object = object;
            if (object.rawPixels != null) {
                for (Pixel pixel : object.rawPixels) {
                    pixels.add(key(pixel));
                }
            }
        }

        boolean contains(Pixel pixel) {
            return pixels.contains(key(pixel));
        }

        private static long key(Pixel pixel) {
            return ((long) pixel.y << 32) | (pixel.x & 0xffffffffL);
        }
    }
}
