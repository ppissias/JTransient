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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mines leftover per-frame point detections for weak local motion and broader repeat activity.
 */
public final class ResidualTransientAnalyzer {
    private static final LocalRescueThresholds LOCAL_RESCUE_THRESHOLDS = new LocalRescueThresholds();

    private ResidualTransientAnalyzer() {
    }

    /**
     * Runs the residual-transient analysis stage on the final uncategorized per-frame detections.
     */
    public static ResidualTransientAnalysis analyze(List<List<SourceExtractor.DetectedObject>> unclassifiedTransients,
                                                    DetectionConfig config) {
        if (config == null || !config.enableResidualTransientAnalysis
                || unclassifiedTransients == null || unclassifiedTransients.isEmpty()) {
            return ResidualTransientAnalysis.empty();
        }

        List<ResidualTransientAnalysis.LocalRescueCandidate> localRescueCandidates = config.enableLocalRescueCandidates
                ? findLocalRescueCandidates(unclassifiedTransients)
                : Collections.emptyList();

        Set<SourceExtractor.DetectedObject> rescueConsumedDetections = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ResidualTransientAnalysis.LocalRescueCandidate candidate : localRescueCandidates) {
            rescueConsumedDetections.addAll(candidate.points);
        }

        List<List<SourceExtractor.DetectedObject>> residualsAfterLocalRescue = removeConsumedDetections(
                unclassifiedTransients,
                rescueConsumedDetections
        );

        List<ResidualTransientAnalysis.LocalActivityCluster> localActivityClusters = config.enableLocalActivityClusters
                ? findLocalActivityClusters(residualsAfterLocalRescue, config)
                : Collections.emptyList();

        return new ResidualTransientAnalysis(localRescueCandidates, localActivityClusters);
    }

    static List<SourceExtractor.DetectedObject> collectAlreadyClassifiedPoints(List<TrackLinker.Track> tracks,
                                                                               List<TrackLinker.AnomalyDetection> anomalies) {
        List<SourceExtractor.DetectedObject> excludedPoints = new ArrayList<>();
        if (tracks != null) {
            for (TrackLinker.Track track : tracks) {
                if (track == null || track.points == null) {
                    continue;
                }
                for (SourceExtractor.DetectedObject point : track.points) {
                    if (point != null) {
                        excludedPoints.add(point);
                    }
                }
            }
        }
        if (anomalies != null) {
            for (TrackLinker.AnomalyDetection anomaly : anomalies) {
                if (anomaly != null && anomaly.object != null) {
                    excludedPoints.add(anomaly.object);
                }
            }
        }
        return excludedPoints;
    }

    static List<List<SourceExtractor.DetectedObject>> filterExcludedDetections(List<List<SourceExtractor.DetectedObject>> transients,
                                                                               List<SourceExtractor.DetectedObject> excludedDetections) {
        if (transients == null || transients.isEmpty()) {
            return Collections.emptyList();
        }

        if (excludedDetections == null || excludedDetections.isEmpty()) {
            return copyFrameList(transients);
        }

        Set<SourceExtractor.DetectedObject> identityExcluded = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Integer, List<SourceExtractor.DetectedObject>> excludedByFrame = new HashMap<>();
        for (SourceExtractor.DetectedObject detection : excludedDetections) {
            if (detection == null) {
                continue;
            }

            identityExcluded.add(detection);
            excludedByFrame.computeIfAbsent(detection.sourceFrameIndex, ignored -> new ArrayList<>()).add(detection);
        }

        List<List<SourceExtractor.DetectedObject>> filtered = new ArrayList<>(transients.size());
        for (int frameIndex = 0; frameIndex < transients.size(); frameIndex++) {
            List<SourceExtractor.DetectedObject> frameDetections = transients.get(frameIndex);
            List<SourceExtractor.DetectedObject> accepted = new ArrayList<>();
            if (frameDetections != null) {
                List<SourceExtractor.DetectedObject> frameExcluded = excludedByFrame.get(frameIndex);
                for (SourceExtractor.DetectedObject detection : frameDetections) {
                    if (!matchesExcludedDetection(detection, frameExcluded, identityExcluded)) {
                        accepted.add(detection);
                    }
                }
            }
            filtered.add(accepted);
        }
        return filtered;
    }

    static List<ResidualTransientAnalysis.LocalRescueCandidate> findLocalRescueCandidates(
            List<List<SourceExtractor.DetectedObject>> transients) {
        if (transients == null || transients.isEmpty()) {
            return Collections.emptyList();
        }

        List<Node> nodes = collectNodes(transients);
        if (nodes.size() < LOCAL_RESCUE_THRESHOLDS.minRepeatPoints) {
            return Collections.emptyList();
        }

        List<LocalRescueCandidateScore> rawCandidates = new ArrayList<>();
        for (Node start : nodes) {
            LocalRescueCandidateScore candidate = growLocalRescueCandidate(start, nodes);
            if (candidate != null) {
                rawCandidates.add(candidate);
            }
        }
        rawCandidates.addAll(buildClusterCandidates(nodes));

        rawCandidates.sort((left, right) -> {
            int scoreComparison = Double.compare(right.score, left.score);
            if (scoreComparison != 0) {
                return scoreComparison;
            }

            int pointComparison = Integer.compare(right.metrics.pointCount, left.metrics.pointCount);
            if (pointComparison != 0) {
                return pointComparison;
            }

            int spanComparison = Integer.compare(right.metrics.frameSpan, left.metrics.frameSpan);
            if (spanComparison != 0) {
                return spanComparison;
            }

            return Double.compare(left.metrics.linearityRmsePixels, right.metrics.linearityRmsePixels);
        });

        List<ResidualTransientAnalysis.LocalRescueCandidate> accepted = new ArrayList<>();
        Set<SourceExtractor.DetectedObject> consumedDetections = Collections.newSetFromMap(new IdentityHashMap<>());
        for (LocalRescueCandidateScore candidate : rawCandidates) {
            boolean overlapsAccepted = false;
            for (SourceExtractor.DetectedObject point : candidate.points) {
                if (consumedDetections.contains(point)) {
                    overlapsAccepted = true;
                    break;
                }
            }
            if (overlapsAccepted) {
                continue;
            }

            accepted.add(candidate.materialize());
            consumedDetections.addAll(candidate.points);
        }

        return accepted;
    }

    static List<ResidualTransientAnalysis.LocalActivityCluster> findLocalActivityClusters(
            List<List<SourceExtractor.DetectedObject>> transients,
            DetectionConfig config) {
        if (transients == null || transients.isEmpty()) {
            return Collections.emptyList();
        }

        List<Node> nodes = collectNodes(transients);
        if (nodes.isEmpty()) {
            return Collections.emptyList();
        }

        List<ResidualTransientAnalysis.LocalActivityCluster> clusters = new ArrayList<>();
        double linkageRadius = Math.max(0.0, config.localActivityClusterRadiusPixels);
        int minimumFrames = Math.max(1, config.localActivityClusterMinFrames);
        for (List<Node> clusterNodes : clusterNodesByProximity(nodes, linkageRadius)) {
            List<SourceExtractor.DetectedObject> points = orderPoints(clusterNodes);
            ResidualTransientAnalysis.LocalTransientMetrics metrics = computeMetrics(points);
            if (metrics.uniqueFrameCount < minimumFrames) {
                continue;
            }
            clusters.add(new ResidualTransientAnalysis.LocalActivityCluster(points, metrics, linkageRadius));
        }

        clusters.sort((left, right) -> {
            int pointComparison = Integer.compare(right.metrics.pointCount, left.metrics.pointCount);
            if (pointComparison != 0) {
                return pointComparison;
            }
            int frameComparison = Integer.compare(right.metrics.uniqueFrameCount, left.metrics.uniqueFrameCount);
            if (frameComparison != 0) {
                return frameComparison;
            }
            return Double.compare(left.metrics.clusterRadiusPixels, right.metrics.clusterRadiusPixels);
        });
        return clusters;
    }

    private static List<Node> collectNodes(List<List<SourceExtractor.DetectedObject>> transients) {
        List<Node> nodes = new ArrayList<>();
        for (int frameIndex = 0; frameIndex < transients.size(); frameIndex++) {
            List<SourceExtractor.DetectedObject> frameDetections = transients.get(frameIndex);
            if (frameDetections == null) {
                continue;
            }
            for (SourceExtractor.DetectedObject detection : frameDetections) {
                if (detection == null || detection.isStreak) {
                    continue;
                }
                nodes.add(new Node(frameIndex, detection));
            }
        }
        nodes.sort(Comparator.comparingInt((Node node) -> node.frameIndex)
                .thenComparingDouble(node -> node.detection.x)
                .thenComparingDouble(node -> node.detection.y));
        return nodes;
    }

    private static LocalRescueCandidateScore growLocalRescueCandidate(Node start,
                                                                      List<Node> nodes) {
        List<Node> chain = new ArrayList<>();
        chain.add(start);

        int lastFrame = start.frameIndex;
        while (true) {
            Node bestNext = null;
            double bestScore = Double.POSITIVE_INFINITY;

            double expectedX = chain.get(chain.size() - 1).detection.x;
            double expectedY = chain.get(chain.size() - 1).detection.y;
            if (chain.size() >= 2) {
                Node previous = chain.get(chain.size() - 2);
                Node last = chain.get(chain.size() - 1);
                int frameDelta = Math.max(1, last.frameIndex - previous.frameIndex);
                double vx = (last.detection.x - previous.detection.x) / frameDelta;
                double vy = (last.detection.y - previous.detection.y) / frameDelta;
                expectedX = last.detection.x + vx;
                expectedY = last.detection.y + vy;
            }

            double centroidX = 0.0;
            double centroidY = 0.0;
            for (Node point : chain) {
                centroidX += point.detection.x;
                centroidY += point.detection.y;
            }
            centroidX /= chain.size();
            centroidY /= chain.size();

            for (Node candidate : nodes) {
                if (candidate.frameIndex <= lastFrame
                        || candidate.frameIndex > lastFrame + LOCAL_RESCUE_THRESHOLDS.maxFrameGap) {
                    continue;
                }

                int frameGap = candidate.frameIndex - lastFrame;
                double dx = candidate.detection.x - chain.get(chain.size() - 1).detection.x;
                double dy = candidate.detection.y - chain.get(chain.size() - 1).detection.y;
                double distanceToLast = Math.hypot(dx, dy);
                double maxAllowedStep = (LOCAL_RESCUE_THRESHOLDS.maxStepPixelsPerFrame * frameGap)
                        + LOCAL_RESCUE_THRESHOLDS.edgeDistanceBiasPixels;
                if (distanceToLast > maxAllowedStep) {
                    continue;
                }

                double chainRadius = Math.hypot(candidate.detection.x - centroidX, candidate.detection.y - centroidY);
                if (chainRadius > LOCAL_RESCUE_THRESHOLDS.maxChainRadiusPixels) {
                    continue;
                }

                double distanceToExpected = Math.hypot(candidate.detection.x - expectedX, candidate.detection.y - expectedY);
                double signalBonus = Math.log1p(Math.max(candidate.detection.peakSigma, candidate.detection.integratedSigma));
                double score = distanceToExpected + (0.35 * distanceToLast) + ((frameGap - 1) * 0.75) - (0.1 * signalBonus);
                if (score < bestScore) {
                    bestScore = score;
                    bestNext = candidate;
                }
            }

            if (bestNext == null) {
                break;
            }

            chain.add(bestNext);
            lastFrame = bestNext.frameIndex;
        }

        if (chain.size() < LOCAL_RESCUE_THRESHOLDS.minRepeatPoints) {
            return null;
        }

        return evaluateCandidate(orderPoints(chain));
    }

    private static List<LocalRescueCandidateScore> buildClusterCandidates(List<Node> nodes) {
        if (nodes.size() < LOCAL_RESCUE_THRESHOLDS.minRepeatPoints) {
            return Collections.emptyList();
        }

        List<LocalRescueCandidateScore> candidates = new ArrayList<>();
        for (List<Node> clusterNodes : clusterNodesByProximity(nodes, LOCAL_RESCUE_THRESHOLDS.clusterLinkRadiusPixels)) {
            if (clusterNodes.size() < LOCAL_RESCUE_THRESHOLDS.minRepeatPoints) {
                continue;
            }

            LocalRescueCandidateScore candidate = evaluateCandidate(orderPoints(clusterNodes));
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    private static LocalRescueCandidateScore evaluateCandidate(List<SourceExtractor.DetectedObject> points) {
        ResidualTransientAnalysis.LocalTransientMetrics metrics = computeMetrics(points);
        ResidualTransientAnalysis.LocalRescueKind kind = classifyLocalRescueKind(metrics);
        if (kind == null) {
            return null;
        }

        double summedSignal = metrics.averageSignal * metrics.pointCount;
        double score = (metrics.pointCount * 100.0)
                + (Math.min(summedSignal, 300.0) * 0.25)
                - (metrics.linearityRmsePixels * 25.0)
                - (metrics.totalGapFrames * 10.0)
                - (metrics.averageStepPixels * 3.0)
                + Math.min(metrics.totalDisplacementPixels, 12.0);
        if (kind == ResidualTransientAnalysis.LocalRescueKind.LOCAL_REPEAT) {
            score -= 35.0;
        } else if (kind == ResidualTransientAnalysis.LocalRescueKind.SPARSE_LOCAL_DRIFT) {
            score -= 20.0;
        }

        return new LocalRescueCandidateScore(kind, points, metrics, score);
    }

    private static ResidualTransientAnalysis.LocalRescueKind classifyLocalRescueKind(
            ResidualTransientAnalysis.LocalTransientMetrics metrics) {
        double absoluteMaxStep = (LOCAL_RESCUE_THRESHOLDS.maxStepPixelsPerFrame * LOCAL_RESCUE_THRESHOLDS.maxFrameGap)
                + LOCAL_RESCUE_THRESHOLDS.edgeDistanceBiasPixels;
        boolean microDrift = metrics.pointCount >= LOCAL_RESCUE_THRESHOLDS.minMotionPoints
                && metrics.totalDisplacementPixels >= LOCAL_RESCUE_THRESHOLDS.microDriftMinTotalDisplacementPixels
                && metrics.totalDisplacementPixels <= LOCAL_RESCUE_THRESHOLDS.microDriftMaxTotalDisplacementPixels
                && metrics.frameCoverage >= LOCAL_RESCUE_THRESHOLDS.microDriftMinFrameCoverage
                && metrics.linearityRmsePixels <= LOCAL_RESCUE_THRESHOLDS.microDriftMaxLinearityRmsePixels
                && metrics.maxStepPixels <= absoluteMaxStep;
        if (microDrift) {
            return ResidualTransientAnalysis.LocalRescueKind.MICRO_DRIFT;
        }

        boolean sparseLocalDrift = metrics.pointCount >= LOCAL_RESCUE_THRESHOLDS.minMotionPoints
                && metrics.totalDisplacementPixels >= LOCAL_RESCUE_THRESHOLDS.sparseDriftMinTotalDisplacementPixels
                && metrics.totalDisplacementPixels <= LOCAL_RESCUE_THRESHOLDS.sparseDriftMaxTotalDisplacementPixels
                && metrics.clusterRadiusPixels <= LOCAL_RESCUE_THRESHOLDS.sparseDriftMaxClusterRadiusPixels
                && metrics.averageStepPixels <= LOCAL_RESCUE_THRESHOLDS.sparseDriftMaxAverageStepPixels
                && metrics.linearityRmsePixels <= LOCAL_RESCUE_THRESHOLDS.sparseDriftMaxLinearityRmsePixels
                && metrics.frameCoverage >= LOCAL_RESCUE_THRESHOLDS.sparseDriftMinFrameCoverage
                && metrics.averageSignal >= LOCAL_RESCUE_THRESHOLDS.sparseDriftMinAverageSignal
                && metrics.maxStepPixels <= LOCAL_RESCUE_THRESHOLDS.sparseDriftMaxStepPixels
                && metrics.frameSpan <= (metrics.pointCount * 4);
        if (sparseLocalDrift) {
            return ResidualTransientAnalysis.LocalRescueKind.SPARSE_LOCAL_DRIFT;
        }

        boolean localRepeat = metrics.pointCount >= LOCAL_RESCUE_THRESHOLDS.minRepeatPoints
                && metrics.totalDisplacementPixels <= LOCAL_RESCUE_THRESHOLDS.localRepeatMaxTotalDisplacementPixels
                && metrics.clusterRadiusPixels <= LOCAL_RESCUE_THRESHOLDS.localRepeatMaxClusterRadiusPixels
                && metrics.averageStepPixels <= LOCAL_RESCUE_THRESHOLDS.localRepeatMaxAverageStepPixels
                && metrics.linearityRmsePixels <= LOCAL_RESCUE_THRESHOLDS.localRepeatMaxLinearityRmsePixels
                && metrics.frameCoverage >= LOCAL_RESCUE_THRESHOLDS.localRepeatMinFrameCoverage
                && metrics.averageSignal >= LOCAL_RESCUE_THRESHOLDS.localRepeatMinAverageSignal
                && metrics.maxStepPixels <= LOCAL_RESCUE_THRESHOLDS.localRepeatMaxTotalDisplacementPixels
                && (metrics.pointCount <= 3
                || metrics.totalDisplacementPixels >= LOCAL_RESCUE_THRESHOLDS.localRepeatMinMultiPointDisplacementPixels)
                && metrics.frameSpan <= (metrics.pointCount + LOCAL_RESCUE_THRESHOLDS.maxFrameGap);
        return localRepeat ? ResidualTransientAnalysis.LocalRescueKind.LOCAL_REPEAT : null;
    }

    private static List<List<Node>> clusterNodesByProximity(List<Node> nodes,
                                                            double linkageRadiusPixels) {
        if (nodes.isEmpty()) {
            return Collections.emptyList();
        }

        double linkageRadius = Math.max(0.0, linkageRadiusPixels);
        List<List<Node>> clusters = new ArrayList<>();
        boolean[] visited = new boolean[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            if (visited[i]) {
                continue;
            }

            List<Node> cluster = new ArrayList<>();
            List<Integer> queue = new ArrayList<>();
            visited[i] = true;
            queue.add(i);

            for (int q = 0; q < queue.size(); q++) {
                int currentIndex = queue.get(q);
                Node current = nodes.get(currentIndex);
                cluster.add(current);

                for (int j = 0; j < nodes.size(); j++) {
                    if (visited[j]) {
                        continue;
                    }
                    Node candidate = nodes.get(j);
                    double distance = Math.hypot(
                            candidate.detection.x - current.detection.x,
                            candidate.detection.y - current.detection.y
                    );
                    if (distance <= linkageRadius) {
                        visited[j] = true;
                        queue.add(j);
                    }
                }
            }

            cluster.sort(Comparator.comparingInt((Node node) -> node.frameIndex)
                    .thenComparingDouble(node -> node.detection.x)
                    .thenComparingDouble(node -> node.detection.y));
            clusters.add(cluster);
        }
        return clusters;
    }

    private static ResidualTransientAnalysis.LocalTransientMetrics computeMetrics(List<SourceExtractor.DetectedObject> points) {
        if (points == null || points.isEmpty()) {
            return new ResidualTransientAnalysis.LocalTransientMetrics(
                    0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
            );
        }

        List<SourceExtractor.DetectedObject> orderedPoints = new ArrayList<>(points);
        orderedPoints.sort(Comparator.comparingInt((SourceExtractor.DetectedObject point) -> point.sourceFrameIndex)
                .thenComparingDouble(point -> point.x)
                .thenComparingDouble(point -> point.y));

        double summedSignal = 0.0;
        double totalStep = 0.0;
        double maxStep = 0.0;
        double sumX = 0.0;
        double sumY = 0.0;
        int totalGapFrames = 0;
        Set<Integer> uniqueFrames = new HashSet<>();

        SourceExtractor.DetectedObject first = orderedPoints.get(0);
        SourceExtractor.DetectedObject last = orderedPoints.get(orderedPoints.size() - 1);
        int frameSpan = (last.sourceFrameIndex - first.sourceFrameIndex) + 1;
        double totalDisplacement = Math.hypot(last.x - first.x, last.y - first.y);

        for (int i = 0; i < orderedPoints.size(); i++) {
            SourceExtractor.DetectedObject point = orderedPoints.get(i);
            summedSignal += Math.max(point.peakSigma, point.integratedSigma);
            sumX += point.x;
            sumY += point.y;
            uniqueFrames.add(point.sourceFrameIndex);
            if (i == 0) {
                continue;
            }
            SourceExtractor.DetectedObject previous = orderedPoints.get(i - 1);
            int gap = Math.max(1, point.sourceFrameIndex - previous.sourceFrameIndex);
            totalGapFrames += Math.max(0, gap - 1);
            double step = Math.hypot(point.x - previous.x, point.y - previous.y);
            totalStep += step;
            if (step > maxStep) {
                maxStep = step;
            }
        }

        double averageStep = orderedPoints.size() > 1 ? totalStep / (orderedPoints.size() - 1) : 0.0;
        double frameCoverage = frameSpan > 0 ? (double) orderedPoints.size() / frameSpan : 0.0;
        double averageSignal = summedSignal / orderedPoints.size();
        double centroidX = sumX / orderedPoints.size();
        double centroidY = sumY / orderedPoints.size();

        return new ResidualTransientAnalysis.LocalTransientMetrics(
                orderedPoints.size(),
                uniqueFrames.size(),
                frameSpan,
                totalGapFrames,
                totalDisplacement,
                averageStep,
                maxStep,
                computeLinearityRmse(orderedPoints),
                computeClusterRadius(orderedPoints, centroidX, centroidY),
                averageSignal,
                frameCoverage,
                centroidX,
                centroidY
        );
    }

    private static double computeLinearityRmse(List<SourceExtractor.DetectedObject> points) {
        if (points == null || points.size() < 2) {
            return 0.0;
        }

        double[] time = new double[points.size()];
        double[] xs = new double[points.size()];
        double[] ys = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            SourceExtractor.DetectedObject point = points.get(i);
            time[i] = point.sourceFrameIndex;
            xs[i] = point.x;
            ys[i] = point.y;
        }

        LineFit fitX = fitLine(time, xs);
        LineFit fitY = fitLine(time, ys);

        double sumSquaredError = 0.0;
        for (int i = 0; i < points.size(); i++) {
            double dx = xs[i] - fitX.predict(time[i]);
            double dy = ys[i] - fitY.predict(time[i]);
            sumSquaredError += (dx * dx) + (dy * dy);
        }
        return Math.sqrt(sumSquaredError / points.size());
    }

    private static double computeClusterRadius(List<SourceExtractor.DetectedObject> points,
                                               double centroidX,
                                               double centroidY) {
        if (points == null || points.isEmpty()) {
            return 0.0;
        }

        double maxRadius = 0.0;
        for (SourceExtractor.DetectedObject point : points) {
            double radius = Math.hypot(point.x - centroidX, point.y - centroidY);
            if (radius > maxRadius) {
                maxRadius = radius;
            }
        }
        return maxRadius;
    }

    private static List<SourceExtractor.DetectedObject> orderPoints(List<Node> nodes) {
        List<SourceExtractor.DetectedObject> points = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            points.add(node.detection);
        }
        points.sort(Comparator.comparingInt((SourceExtractor.DetectedObject point) -> point.sourceFrameIndex)
                .thenComparingDouble(point -> point.x)
                .thenComparingDouble(point -> point.y));
        return points;
    }

    private static List<List<SourceExtractor.DetectedObject>> removeConsumedDetections(
            List<List<SourceExtractor.DetectedObject>> frames,
            Set<SourceExtractor.DetectedObject> consumedDetections) {
        if (frames == null || frames.isEmpty()) {
            return Collections.emptyList();
        }

        if (consumedDetections == null || consumedDetections.isEmpty()) {
            return copyFrameList(frames);
        }

        List<List<SourceExtractor.DetectedObject>> filtered = new ArrayList<>(frames.size());
        for (List<SourceExtractor.DetectedObject> frame : frames) {
            List<SourceExtractor.DetectedObject> kept = new ArrayList<>();
            if (frame != null) {
                for (SourceExtractor.DetectedObject detection : frame) {
                    if (!consumedDetections.contains(detection)) {
                        kept.add(detection);
                    }
                }
            }
            filtered.add(kept);
        }
        return filtered;
    }

    private static List<List<SourceExtractor.DetectedObject>> copyFrameList(List<List<SourceExtractor.DetectedObject>> frames) {
        List<List<SourceExtractor.DetectedObject>> copy = new ArrayList<>(frames.size());
        for (List<SourceExtractor.DetectedObject> frame : frames) {
            copy.add(frame == null ? new ArrayList<>() : new ArrayList<>(frame));
        }
        return copy;
    }

    private static boolean matchesExcludedDetection(SourceExtractor.DetectedObject detection,
                                                    List<SourceExtractor.DetectedObject> excludedInFrame,
                                                    Set<SourceExtractor.DetectedObject> identityExcluded) {
        if (detection == null) {
            return true;
        }
        if (identityExcluded.contains(detection)) {
            return true;
        }
        if (excludedInFrame == null || excludedInFrame.isEmpty()) {
            return false;
        }

        String detectionSource = normalizeSourceName(detection.sourceFilename);
        for (SourceExtractor.DetectedObject excluded : excludedInFrame) {
            if (excluded == null) {
                continue;
            }
            String excludedSource = normalizeSourceName(excluded.sourceFilename);
            if (!detectionSource.isEmpty() && !excludedSource.isEmpty() && !detectionSource.equals(excludedSource)) {
                continue;
            }
            double distance = Math.hypot(detection.x - excluded.x, detection.y - excluded.y);
            if (distance <= LOCAL_RESCUE_THRESHOLDS.classifiedMatchRadiusPixels) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeSourceName(String sourceFilename) {
        return sourceFilename == null ? "" : sourceFilename.trim().toLowerCase(Locale.US);
    }

    private static LineFit fitLine(double[] xs, double[] ys) {
        double sumX = 0.0;
        double sumY = 0.0;
        double sumXY = 0.0;
        double sumX2 = 0.0;

        for (int i = 0; i < xs.length; i++) {
            sumX += xs[i];
            sumY += ys[i];
            sumXY += xs[i] * ys[i];
            sumX2 += xs[i] * xs[i];
        }

        double n = xs.length;
        double denominator = (n * sumX2) - (sumX * sumX);
        if (Math.abs(denominator) < 1.0e-9) {
            double mean = sumY / n;
            return new LineFit(0.0, mean);
        }

        double slope = ((n * sumXY) - (sumX * sumY)) / denominator;
        double intercept = (sumY - (slope * sumX)) / n;
        return new LineFit(slope, intercept);
    }

    private static final class Node {
        private final int frameIndex;
        private final SourceExtractor.DetectedObject detection;

        private Node(int frameIndex, SourceExtractor.DetectedObject detection) {
            this.frameIndex = frameIndex;
            this.detection = detection;
        }
    }

    private static final class LineFit {
        private final double slope;
        private final double intercept;

        private LineFit(double slope, double intercept) {
            this.slope = slope;
            this.intercept = intercept;
        }

        private double predict(double x) {
            return (slope * x) + intercept;
        }
    }

    private static final class LocalRescueCandidateScore {
        private final ResidualTransientAnalysis.LocalRescueKind kind;
        private final List<SourceExtractor.DetectedObject> points;
        private final ResidualTransientAnalysis.LocalTransientMetrics metrics;
        private final double score;

        private LocalRescueCandidateScore(ResidualTransientAnalysis.LocalRescueKind kind,
                                          List<SourceExtractor.DetectedObject> points,
                                          ResidualTransientAnalysis.LocalTransientMetrics metrics,
                                          double score) {
            this.kind = kind;
            this.points = new ArrayList<>(points);
            this.metrics = metrics;
            this.score = score;
        }

        private ResidualTransientAnalysis.LocalRescueCandidate materialize() {
            return new ResidualTransientAnalysis.LocalRescueCandidate(kind, points, metrics, score);
        }
    }

    /**
     * Internal rescue heuristics kept out of DetectionConfig so the public config surface stays compact.
     */
    private static final class LocalRescueThresholds {
        private final int minRepeatPoints = 2;
        private final int minMotionPoints = 4;
        private final int maxFrameGap = 2;
        private final double maxStepPixelsPerFrame = 4.0;
        private final double edgeDistanceBiasPixels = 1.25;
        private final double maxChainRadiusPixels = 18.0;
        private final double microDriftMinTotalDisplacementPixels = 1.25;
        private final double microDriftMaxTotalDisplacementPixels = 32.0;
        private final double microDriftMaxLinearityRmsePixels = 1.8;
        private final double microDriftMinFrameCoverage = 0.55;
        private final double localRepeatMaxTotalDisplacementPixels = 3.0;
        private final double localRepeatMaxClusterRadiusPixels = 2.5;
        private final double localRepeatMaxAverageStepPixels = 1.75;
        private final double localRepeatMaxLinearityRmsePixels = 0.9;
        private final double localRepeatMinFrameCoverage = 0.4;
        private final double localRepeatMinAverageSignal = 5.0;
        private final double localRepeatMinMultiPointDisplacementPixels = 0.35;
        private final double sparseDriftMinTotalDisplacementPixels = 2.0;
        private final double sparseDriftMaxTotalDisplacementPixels = 10.0;
        private final double sparseDriftMaxClusterRadiusPixels = 4.5;
        private final double sparseDriftMaxAverageStepPixels = 2.75;
        private final double sparseDriftMaxLinearityRmsePixels = 0.45;
        private final double sparseDriftMinFrameCoverage = 0.25;
        private final double sparseDriftMinAverageSignal = 5.0;
        private final double sparseDriftMaxStepPixels = 3.5;
        private final double classifiedMatchRadiusPixels = 0.75;
        private final double clusterLinkRadiusPixels = 4.0;
    }
}
