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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 2D spatial grid used to accelerate bounded radius lookups between detected objects within a single frame.
 * <p>
 * Objects are partitioned into uniform square cells. Candidate queries return objects in their original list
 * ordering so that tie-breaking and candidate evaluation remain deterministic.
 */
public class SpatialGrid {

    private static final int MAX_GRID_WINDOW_BEFORE_LINEAR_SCAN = 512;
    private static final double MIN_CELL_SIZE = 1.0;
    private static final long MAX_CELL_COUNT = 1_000_000L;

    /**
     * Paired object reference and its original sequence index in the indexed population.
     */
    static final class IndexedObject {
        final int originalIndex;
        final DetectedObject object;

        IndexedObject(int originalIndex, DetectedObject object) {
            this.originalIndex = originalIndex;
            this.object = object;
        }
    }

    private final double cellSize;
    private final int minCol;
    private final int minRow;
    private final int cols;
    private final int rows;
    private final IndexedObject[][] cells;
    private final List<DetectedObject> allObjects;

    /**
     * Builds a grid over the supplied objects.
     *
     * @param objects detected objects to index
     * @param cellSize requested cell size in pixels; values below {@code 1.0} are clamped
     */
    public SpatialGrid(List<DetectedObject> objects, double cellSize) {
        this.cellSize = Math.max(MIN_CELL_SIZE, cellSize);
        this.allObjects = objects.isEmpty() ? Collections.emptyList() : new ArrayList<>(objects);

        if (objects.isEmpty()) {
            this.minCol = 0;
            this.minRow = 0;
            this.cols = 0;
            this.rows = 0;
            this.cells = new IndexedObject[0][];
            return;
        }

        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;

        for (DetectedObject obj : objects) {
            if (Double.isFinite(obj.x) && Double.isFinite(obj.y)) {
                if (obj.x < minX) minX = obj.x;
                if (obj.x > maxX) maxX = obj.x;
                if (obj.y < minY) minY = obj.y;
                if (obj.y > maxY) maxY = obj.y;
            }
        }

        if (minX > maxX) {
            this.minCol = 0;
            this.minRow = 0;
            this.cols = 0;
            this.rows = 0;
            this.cells = new IndexedObject[0][];
            return;
        }

        int col0 = (int) Math.floor(minX / this.cellSize);
        int col1 = (int) Math.floor(maxX / this.cellSize);
        int row0 = (int) Math.floor(minY / this.cellSize);
        int row1 = (int) Math.floor(maxY / this.cellSize);

        long c = (long) col1 - col0 + 1;
        long r = (long) row1 - row0 + 1;

        if (c * r > MAX_CELL_COUNT || c <= 0 || r <= 0) {
            this.minCol = 0;
            this.minRow = 0;
            this.cols = 0;
            this.rows = 0;
            this.cells = new IndexedObject[0][];
            return;
        }

        this.minCol = col0;
        this.minRow = row0;
        this.cols = (int) c;
        this.rows = (int) r;

        int totalCells = this.cols * this.rows;
        int[] cellCounts = new int[totalCells];
        for (DetectedObject obj : objects) {
            if (Double.isFinite(obj.x) && Double.isFinite(obj.y)) {
                int cx = (int) Math.floor(obj.x / this.cellSize) - this.minCol;
                int cy = (int) Math.floor(obj.y / this.cellSize) - this.minRow;
                if (cx >= 0 && cx < this.cols && cy >= 0 && cy < this.rows) {
                    cellCounts[cy * this.cols + cx]++;
                }
            }
        }

        this.cells = new IndexedObject[totalCells][];
        for (int i = 0; i < totalCells; i++) {
            if (cellCounts[i] > 0) {
                this.cells[i] = new IndexedObject[cellCounts[i]];
            }
        }

        int[] cellOffsets = new int[totalCells];
        for (int i = 0; i < objects.size(); i++) {
            DetectedObject obj = objects.get(i);
            if (Double.isFinite(obj.x) && Double.isFinite(obj.y)) {
                int cx = (int) Math.floor(obj.x / this.cellSize) - this.minCol;
                int cy = (int) Math.floor(obj.y / this.cellSize) - this.minRow;
                if (cx >= 0 && cx < this.cols && cy >= 0 && cy < this.rows) {
                    int cellIdx = cy * this.cols + cx;
                    this.cells[cellIdx][cellOffsets[cellIdx]++] = new IndexedObject(i, obj);
                }
            }
        }
    }

    /**
     * Total number of objects indexed in this grid.
     */
    public int size() {
        return allObjects.size();
    }

    /**
     * Fast bounded lookup for tight loops. Queries all objects whose grid cell overlaps the square bounding
     * box around {@code (targetX, targetY)} of half-width {@code searchRadius}.
     * <p>
     * Candidates are written into {@code outBuffer} in strictly increasing original sequence order.
     *
     * @param targetX center X
     * @param targetY center Y
     * @param searchRadius search radius in pixels
     * @param outBuffer preallocated destination buffer
     * @param scratch preallocated scratch buffer used for sorting across multiple cells
     * @return the number of candidate objects placed into {@code outBuffer}, or {@code -1} if the search
     *         window requires a full linear scan
     */
    public int queryCandidates(double targetX, double targetY, double searchRadius,
                               DetectedObject[] outBuffer, IndexedObject[] scratch) {
        if (cols == 0 || rows == 0) {
            return 0;
        }

        int startCol = Math.max(0, (int) Math.floor((targetX - searchRadius) / cellSize) - minCol);
        int endCol = Math.min(cols - 1, (int) Math.floor((targetX + searchRadius) / cellSize) - minCol);
        int startRow = Math.max(0, (int) Math.floor((targetY - searchRadius) / cellSize) - minRow);
        int endRow = Math.min(rows - 1, (int) Math.floor((targetY + searchRadius) / cellSize) - minRow);

        if (startCol > endCol || startRow > endRow) {
            return 0;
        }

        long cellWindow = (long) (endCol - startCol + 1) * (endRow - startRow + 1);
        if (shouldUseLinearScan(cellWindow)) {
            return -1;
        }

        // Single cell fast path: objects within a cell are already sorted by originalIndex
        if (startCol == endCol && startRow == endRow) {
            IndexedObject[] cell = cells[startRow * cols + startCol];
            if (cell == null) {
                return 0;
            }
            if (cell.length > outBuffer.length) {
                return -1;
            }
            for (int i = 0; i < cell.length; i++) {
                outBuffer[i] = cell[i].object;
            }
            return cell.length;
        }

        // Multiple cells
        int count = 0;
        for (int r = startRow; r <= endRow; r++) {
            int rowOffset = r * cols;
            for (int c = startCol; c <= endCol; c++) {
                IndexedObject[] cell = cells[rowOffset + c];
                if (cell != null) {
                    for (IndexedObject item : cell) {
                        if (count >= scratch.length || count >= outBuffer.length) {
                            return -1;
                        }
                        scratch[count++] = item;
                    }
                }
            }
        }

        if (count <= 1) {
            if (count == 1) {
                outBuffer[0] = scratch[0].object;
            }
            return count;
        }

        // Insertion sort by originalIndex
        for (int i = 1; i < count; i++) {
            IndexedObject key = scratch[i];
            int j = i - 1;
            while (j >= 0 && scratch[j].originalIndex > key.originalIndex) {
                scratch[j + 1] = scratch[j];
                j--;
            }
            scratch[j + 1] = key;
        }

        for (int i = 0; i < count; i++) {
            outBuffer[i] = scratch[i].object;
        }
        return count;
    }

    /**
     * Returns whether any indexed object lies within the given search radius.
     */
    public boolean hasMatch(double targetX, double targetY, double searchRadius) {
        for (DetectedObject obj : getCandidates(targetX, targetY, searchRadius)) {
            double dx = targetX - obj.x;
            double dy = targetY - obj.y;
            if (dx * dx + dy * dy <= searchRadius * searchRadius) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns all indexed objects within the supplied search radius.
     */
    public List<DetectedObject> getCandidates(double targetX, double targetY, double searchRadius) {
        if (cols == 0 || rows == 0) {
            return Collections.emptyList();
        }

        int startCol = Math.max(0, (int) Math.floor((targetX - searchRadius) / cellSize) - minCol);
        int endCol = Math.min(cols - 1, (int) Math.floor((targetX + searchRadius) / cellSize) - minCol);
        int startRow = Math.max(0, (int) Math.floor((targetY - searchRadius) / cellSize) - minRow);
        int endRow = Math.min(rows - 1, (int) Math.floor((targetY + searchRadius) / cellSize) - minRow);

        double radiusSquared = searchRadius * searchRadius;
        long cellWindow = (long) (endCol - startCol + 1) * (endRow - startRow + 1);

        if (startCol > endCol || startRow > endRow || shouldUseLinearScan(cellWindow)) {
            List<DetectedObject> candidates = new ArrayList<>();
            for (DetectedObject obj : allObjects) {
                double dx = targetX - obj.x;
                double dy = targetY - obj.y;
                if (dx * dx + dy * dy <= radiusSquared) {
                    candidates.add(obj);
                }
            }
            return candidates;
        }

        List<IndexedObject> matched = new ArrayList<>();
        for (int r = startRow; r <= endRow; r++) {
            int rowOffset = r * cols;
            for (int c = startCol; c <= endCol; c++) {
                IndexedObject[] cell = cells[rowOffset + c];
                if (cell != null) {
                    for (IndexedObject item : cell) {
                        double dx = targetX - item.object.x;
                        double dy = targetY - item.object.y;
                        if (dx * dx + dy * dy <= radiusSquared) {
                            matched.add(item);
                        }
                    }
                }
            }
        }

        if (matched.size() > 1 && !(startCol == endCol && startRow == endRow)) {
            matched.sort((a, b) -> Integer.compare(a.originalIndex, b.originalIndex));
        }

        List<DetectedObject> result = new ArrayList<>(matched.size());
        for (IndexedObject item : matched) {
            result.add(item.object);
        }
        return result;
    }

    /**
     * Returns the nearest candidates in ascending distance order, capped to {@code maxCandidates}.
     */
    public List<DetectedObject> getNearestCandidates(double targetX,
                                                     double targetY,
                                                     double searchRadius,
                                                     int maxCandidates) {
        if (maxCandidates <= 0) {
            return Collections.emptyList();
        }

        List<DetectedObject> candidates = getCandidates(targetX, targetY, searchRadius);
        if (candidates.size() <= maxCandidates) {
            candidates.sort((a, b) -> {
                double d1 = (a.x - targetX) * (a.x - targetX) + (a.y - targetY) * (a.y - targetY);
                double d2 = (b.x - targetX) * (b.x - targetX) + (b.y - targetY) * (b.y - targetY);
                return Double.compare(d1, d2);
            });
            return candidates;
        }

        candidates.sort((a, b) -> {
            double d1 = (a.x - targetX) * (a.x - targetX) + (a.y - targetY) * (a.y - targetY);
            double d2 = (b.x - targetX) * (b.x - targetX) + (b.y - targetY) * (b.y - targetY);
            return Double.compare(d1, d2);
        });
        return new ArrayList<>(candidates.subList(0, maxCandidates));
    }

    /**
     * Finds the absolute closest object within the search radius.
     * Returns the distance in pixels, or -1 if nothing is found.
     */
    public double getNearestDistance(double targetX, double targetY, double searchRadius) {
        double minDistanceSquared = Double.MAX_VALUE;
        boolean found = false;

        for (DetectedObject obj : getCandidates(targetX, targetY, searchRadius)) {
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

    /**
     * Falls back to a linear scan when the requested grid window is larger than the object population.
     */
    private boolean shouldUseLinearScan(long cellWindow) {
        return cellWindow > MAX_GRID_WINDOW_BEFORE_LINEAR_SCAN && allObjects.size() < cellWindow;
    }
}
