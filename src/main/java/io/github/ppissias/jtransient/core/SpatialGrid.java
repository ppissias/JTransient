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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SpatialGrid {

    private static final int MAX_GRID_WINDOW_BEFORE_LINEAR_SCAN = 512;

    private final double cellSize;
    private final Map<String, List<SourceExtractor.DetectedObject>> cells;
    private final List<SourceExtractor.DetectedObject> allObjects;

    public SpatialGrid(List<SourceExtractor.DetectedObject> objects, double cellSize) {
        // Ensure cell size is at least 1.0 to prevent infinite/tiny grids
        this.cellSize = Math.max(1.0, cellSize);
        this.cells = new HashMap<>(objects.size());
        this.allObjects = new ArrayList<>(objects);

        for (SourceExtractor.DetectedObject obj : objects) {
            String key = getCellKey(obj.x, obj.y);
            cells.computeIfAbsent(key, k -> new ArrayList<>()).add(obj);
        }
    }

    private String getCellKey(double x, double y) {
        int cx = (int) (x / cellSize);
        int cy = (int) (y / cellSize);
        return cx + "," + cy;
    }

    public boolean hasMatch(double targetX, double targetY, double searchRadius) {
        for (SourceExtractor.DetectedObject obj : getCandidates(targetX, targetY, searchRadius)) {
            double dx = targetX - obj.x;
            double dy = targetY - obj.y;
            if (dx * dx + dy * dy <= searchRadius * searchRadius) {
                return true;
            }
        }
        return false;
    }

    public List<SourceExtractor.DetectedObject> getCandidates(double targetX, double targetY, double searchRadius) {
        int minX = (int) ((targetX - searchRadius) / cellSize);
        int maxX = (int) ((targetX + searchRadius) / cellSize);
        int minY = (int) ((targetY - searchRadius) / cellSize);
        int maxY = (int) ((targetY + searchRadius) / cellSize);

        List<SourceExtractor.DetectedObject> candidates = new ArrayList<>();
        double radiusSquared = searchRadius * searchRadius;
        long cellWindow = (long) (maxX - minX + 1) * (maxY - minY + 1);

        if (shouldUseLinearScan(cellWindow)) {
            for (SourceExtractor.DetectedObject obj : allObjects) {
                double dx = targetX - obj.x;
                double dy = targetY - obj.y;
                if (dx * dx + dy * dy <= radiusSquared) {
                    candidates.add(obj);
                }
            }
            return candidates;
        }

        for (int cx = minX; cx <= maxX; cx++) {
            for (int cy = minY; cy <= maxY; cy++) {
                List<SourceExtractor.DetectedObject> cell = cells.get(cx + "," + cy);
                if (cell != null) {
                    for (SourceExtractor.DetectedObject obj : cell) {
                        double dx = targetX - obj.x;
                        double dy = targetY - obj.y;
                        if (dx * dx + dy * dy <= radiusSquared) {
                            candidates.add(obj);
                        }
                    }
                }
            }
        }
        return candidates;
    }

    public List<SourceExtractor.DetectedObject> getNearestCandidates(double targetX,
                                                                     double targetY,
                                                                     double searchRadius,
                                                                     int maxCandidates) {
        if (maxCandidates <= 0) {
            return new ArrayList<>();
        }

        int minX = (int) ((targetX - searchRadius) / cellSize);
        int maxX = (int) ((targetX + searchRadius) / cellSize);
        int minY = (int) ((targetY - searchRadius) / cellSize);
        int maxY = (int) ((targetY + searchRadius) / cellSize);

        double radiusSquared = searchRadius * searchRadius;
        List<SourceExtractor.DetectedObject> nearest = new ArrayList<>(maxCandidates);
        List<Double> nearestDistances = new ArrayList<>(maxCandidates);
        long cellWindow = (long) (maxX - minX + 1) * (maxY - minY + 1);

        if (shouldUseLinearScan(cellWindow)) {
            for (SourceExtractor.DetectedObject obj : allObjects) {
                double dx = targetX - obj.x;
                double dy = targetY - obj.y;
                double distSq = dx * dx + dy * dy;
                if (distSq > radiusSquared) {
                    continue;
                }
                insertNearest(nearest, nearestDistances, obj, distSq, maxCandidates);
            }
            return nearest;
        }

        for (int cx = minX; cx <= maxX; cx++) {
            for (int cy = minY; cy <= maxY; cy++) {
                List<SourceExtractor.DetectedObject> cell = cells.get(cx + "," + cy);
                if (cell == null) {
                    continue;
                }

                for (SourceExtractor.DetectedObject obj : cell) {
                    double dx = targetX - obj.x;
                    double dy = targetY - obj.y;
                    double distSq = dx * dx + dy * dy;
                    if (distSq > radiusSquared) {
                        continue;
                    }
                    insertNearest(nearest, nearestDistances, obj, distSq, maxCandidates);
                }
            }
        }

        return nearest;
    }

    /**
     * Finds the absolute closest object within the search radius.
     * Returns the distance in pixels, or -1 if nothing is found.
     */
    public double getNearestDistance(double targetX, double targetY, double searchRadius) {
        double minDistanceSquared = Double.MAX_VALUE;
        boolean found = false;

        for (SourceExtractor.DetectedObject obj : getCandidates(targetX, targetY, searchRadius)) {
            double dx = targetX - obj.x;
            double dy = targetY - obj.y;
            double distSq = dx * dx + dy * dy;
            if (distSq < minDistanceSquared) {
                minDistanceSquared = distSq;
                found = true;
            }
        }
        return found ? Math.sqrt(minDistanceSquared) : -1.0;
    }

    private boolean shouldUseLinearScan(long cellWindow) {
        return cellWindow > MAX_GRID_WINDOW_BEFORE_LINEAR_SCAN && allObjects.size() < cellWindow;
    }

    private void insertNearest(List<SourceExtractor.DetectedObject> nearest,
                               List<Double> nearestDistances,
                               SourceExtractor.DetectedObject obj,
                               double distSq,
                               int maxCandidates) {
        int insertAt = nearestDistances.size();
        while (insertAt > 0 && distSq < nearestDistances.get(insertAt - 1)) {
            insertAt--;
        }

        if (insertAt >= maxCandidates) {
            return;
        }

        nearest.add(insertAt, obj);
        nearestDistances.add(insertAt, distSq);

        if (nearest.size() > maxCandidates) {
            nearest.remove(nearest.size() - 1);
            nearestDistances.remove(nearestDistances.size() - 1);
        }
    }
}
