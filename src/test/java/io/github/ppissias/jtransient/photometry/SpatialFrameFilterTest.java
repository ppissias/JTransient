/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.photometry;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpatialFrameFilterTest {

    private static final int STARS_PER_CELL = 40;

    @Test
    public void aUniformFieldAndASmallOffsetStayIn() {
        Fixture even = fixture(0.0);
        SpatialFrameFilter.FrameDecision[] decisions = SpatialFrameFilter.evaluate(
                even.residual, even.usable, even.xNorm, even.yNorm, new boolean[]{true});
        assertFalse(decisions[0].drop);
        assertEquals(0.0, decisions[0].maxOffset, 1e-9);
        assertEquals(STARS_PER_CELL, decisions[0].centreStars);

        Fixture small = fixture(0.09);
        decisions = SpatialFrameFilter.evaluate(small.residual, small.usable, small.xNorm, small.yNorm, new boolean[]{true});
        assertFalse(decisions[0].drop);
        assertEquals(0.09, decisions[0].maxOffset, 1e-9);
    }

    @Test
    public void aCornerLeftFaintAfterThePlaneDropsTheFrame() {
        Fixture corner = fixture(0.20);
        SpatialFrameFilter.FrameDecision[] decisions = SpatialFrameFilter.evaluate(
                corner.residual, corner.usable, corner.xNorm, corner.yNorm, new boolean[]{true});
        assertTrue(decisions[0].drop);
        assertEquals(0.20, decisions[0].maxOffset, 1e-9);
        assertEquals(2, decisions[0].worstCell % SpatialFrameFilter.CELL_COUNT);
        assertEquals(2, decisions[0].worstCell / SpatialFrameFilter.CELL_COUNT);
        assertEquals(STARS_PER_CELL, decisions[0].worstStars);
    }

    @Test
    public void aSparseCornerAndAnInactiveFrameDoNotDrop() {
        Fixture sparse = fixture(0.50);
        int highXHighY = 8;
        for (int i = 0; i < sparse.xNorm.length; i++) {
            int col = SpatialFrameFilter.cellIndex(sparse.xNorm[i]);
            int row = SpatialFrameFilter.cellIndex(sparse.yNorm[i]);
            if (row * 3 + col == highXHighY && i % STARS_PER_CELL >= 10) {
                sparse.usable[i][0] = false;
            }
        }
        SpatialFrameFilter.FrameDecision[] decisions = SpatialFrameFilter.evaluate(
                sparse.residual, sparse.usable, sparse.xNorm, sparse.yNorm, new boolean[]{true});
        assertFalse(decisions[0].drop);
        assertEquals(10, decisions[0].cellCount[highXHighY]);

        Fixture corner = fixture(0.50);
        decisions = SpatialFrameFilter.evaluate(corner.residual, corner.usable, corner.xNorm, corner.yNorm, new boolean[]{false});
        assertFalse(decisions[0].evaluated);
        assertFalse(decisions[0].drop);
    }

    /** Nine cells. The high-x, high-y cell is offset by {@code cornerOffset}; every other residual is 0. */
    private static Fixture fixture(double cornerOffset) {
        int n = SpatialFrameFilter.CELL_COUNT * SpatialFrameFilter.CELL_COUNT * STARS_PER_CELL;
        Fixture fixture = new Fixture();
        fixture.xNorm = new double[n];
        fixture.yNorm = new double[n];
        fixture.residual = new double[n][1];
        fixture.usable = new boolean[n][1];
        int i = 0;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                for (int k = 0; k < STARS_PER_CELL; k++) {
                    fixture.xNorm[i] = -1.0 + (col + 0.5) * (2.0 / 3.0);
                    fixture.yNorm[i] = -1.0 + (row + 0.5) * (2.0 / 3.0);
                    fixture.residual[i][0] = (row == 2 && col == 2) ? cornerOffset : 0.0;
                    fixture.usable[i][0] = true;
                    i++;
                }
            }
        }
        return fixture;
    }

    private static final class Fixture {
        double[] xNorm;
        double[] yNorm;
        double[][] residual;
        boolean[][] usable;
    }
}
