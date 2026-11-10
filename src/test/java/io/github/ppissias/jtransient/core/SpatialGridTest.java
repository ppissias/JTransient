package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.model.DetectedObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link SpatialGrid}.
 */
public class SpatialGridTest {

    private static DetectedObject createPoint(double x, double y) {
        DetectedObject p = new DetectedObject(x, y, 10.0, 5);
        p.pixelArea = 4;
        return p;
    }

    @Test
    public void emptyGridReturnsZeroAndEmptyList() {
        SpatialGrid grid = new SpatialGrid(Collections.emptyList(), 64.0);
        assertEquals(0, grid.size());
        assertFalse(grid.hasMatch(100, 100, 50));
        assertTrue(grid.getCandidates(100, 100, 50).isEmpty());

        DetectedObject[] out = new DetectedObject[16];
        SpatialGrid.IndexedObject[] scratch = new SpatialGrid.IndexedObject[16];
        assertEquals(0, grid.queryCandidates(100, 100, 50, out, scratch));
    }

    @Test
    public void singleCellQueryPreservesOrder() {
        List<DetectedObject> points = new ArrayList<>();
        points.add(createPoint(10, 10)); // idx 0
        points.add(createPoint(15, 15)); // idx 1
        points.add(createPoint(12, 12)); // idx 2
        points.add(createPoint(500, 500)); // idx 3, far away

        SpatialGrid grid = new SpatialGrid(points, 64.0);
        assertEquals(4, grid.size());

        DetectedObject[] out = new DetectedObject[16];
        SpatialGrid.IndexedObject[] scratch = new SpatialGrid.IndexedObject[16];

        int count = grid.queryCandidates(12, 12, 10.0, out, scratch);
        assertEquals(3, count);
        assertEquals(points.get(0), out[0]);
        assertEquals(points.get(1), out[1]);
        assertEquals(points.get(2), out[2]);
    }

    @Test
    public void multipleCellsQueryPreservesOriginalSequenceOrder() {
        List<DetectedObject> points = new ArrayList<>();
        // Cell size 50. Put points across multiple neighbouring cells:
        // (40, 40) is cell (0, 0)
        // (60, 40) is cell (1, 0)
        // (40, 60) is cell (0, 1)
        // (60, 60) is cell (1, 1)
        points.add(createPoint(60, 60)); // idx 0 in cell (1, 1)
        points.add(createPoint(40, 40)); // idx 1 in cell (0, 0)
        points.add(createPoint(60, 40)); // idx 2 in cell (1, 0)
        points.add(createPoint(40, 60)); // idx 3 in cell (0, 1)

        SpatialGrid grid = new SpatialGrid(points, 50.0);
        assertEquals(4, grid.size());

        DetectedObject[] out = new DetectedObject[16];
        SpatialGrid.IndexedObject[] scratch = new SpatialGrid.IndexedObject[16];

        // Query centered at (50, 50) with radius 20 covers all 4 points
        int count = grid.queryCandidates(50, 50, 20.0, out, scratch);
        assertEquals(4, count);
        // Must be in exact original list order (idx 0, 1, 2, 3)
        assertEquals(points.get(0), out[0]);
        assertEquals(points.get(1), out[1]);
        assertEquals(points.get(2), out[2]);
        assertEquals(points.get(3), out[3]);
    }

    @Test
    public void pointJustInsideAndJustOutsideRadius() {
        List<DetectedObject> points = new ArrayList<>();
        points.add(createPoint(100.0, 100.0)); // Center
        points.add(createPoint(110.0, 100.0)); // Dist = 10.0
        points.add(createPoint(110.01, 100.0)); // Dist = 10.01 (just outside 10.0)

        SpatialGrid grid = new SpatialGrid(points, 64.0);

        List<DetectedObject> matches = grid.getCandidates(100.0, 100.0, 10.0);
        assertEquals(2, matches.size());
        assertEquals(points.get(0), matches.get(0));
        assertEquals(points.get(1), matches.get(1));
    }

    @Test
    public void queryOutsideGridBoundsReturnsZero() {
        List<DetectedObject> points = List.of(createPoint(100, 100));
        SpatialGrid grid = new SpatialGrid(points, 64.0);

        DetectedObject[] out = new DetectedObject[16];
        SpatialGrid.IndexedObject[] scratch = new SpatialGrid.IndexedObject[16];
        int count = grid.queryCandidates(5000, 5000, 10.0, out, scratch);
        assertEquals(0, count);
    }
}
