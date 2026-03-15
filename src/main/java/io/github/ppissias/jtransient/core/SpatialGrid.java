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

    private final double cellSize;
    private final Map<String, List<SourceExtractor.DetectedObject>> cells;

    public SpatialGrid(List<SourceExtractor.DetectedObject> objects, double cellSize) {
        // Ensure cell size is at least 1.0 to prevent infinite/tiny grids
        this.cellSize = Math.max(1.0, cellSize);
        this.cells = new HashMap<>(objects.size());

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
        int minX = (int) ((targetX - searchRadius) / cellSize);
        int maxX = (int) ((targetX + searchRadius) / cellSize);
        int minY = (int) ((targetY - searchRadius) / cellSize);
        int maxY = (int) ((targetY + searchRadius) / cellSize);

        double radiusSquared = searchRadius * searchRadius;

        for (int cx = minX; cx <= maxX; cx++) {
            for (int cy = minY; cy <= maxY; cy++) {
                List<SourceExtractor.DetectedObject> cell = cells.get(cx + "," + cy);
                if (cell != null) {
                    for (SourceExtractor.DetectedObject obj : cell) {
                        double dx = targetX - obj.x;
                        double dy = targetY - obj.y;
                        if (dx * dx + dy * dy <= radiusSquared) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Finds the absolute closest object within the search radius.
     * Returns the distance in pixels, or -1 if nothing is found.
     */
    public double getNearestDistance(double targetX, double targetY, double searchRadius) {
        int minX = (int) ((targetX - searchRadius) / cellSize);
        int maxX = (int) ((targetX + searchRadius) / cellSize);
        int minY = (int) ((targetY - searchRadius) / cellSize);
        int maxY = (int) ((targetY + searchRadius) / cellSize);

        double radiusSquared = searchRadius * searchRadius;
        double minDistanceSquared = Double.MAX_VALUE;
        boolean found = false;

        for (int cx = minX; cx <= maxX; cx++) {
            for (int cy = minY; cy <= maxY; cy++) {
                List<SourceExtractor.DetectedObject> cell = cells.get(cx + "," + cy);
                if (cell != null) {
                    for (SourceExtractor.DetectedObject obj : cell) {
                        double dx = targetX - obj.x;
                        double dy = targetY - obj.y;
                        double distSq = dx * dx + dy * dy;
                        if (distSq <= radiusSquared && distSq < minDistanceSquared) {
                            minDistanceSquared = distSq;
                            found = true;
                        }
                    }
                }
            }
        }
        return found ? Math.sqrt(minDistanceSquared) : -1.0;
    }
}