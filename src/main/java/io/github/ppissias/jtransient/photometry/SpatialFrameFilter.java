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

/**
 * Drops a frame from variable-star scoring when one part of the field is left brighter or fainter
 * than the centre after the ensemble plane has been removed.
 *
 * <p>The field is split into a 3 by 3 grid in the same normalised coordinates as the ensemble plane.
 * A frame is dropped when any cell's median residual differs from the centre cell by
 * {@link #MAX_CELL_OFFSET_MAG} or more and both cells hold at least {@link #MIN_STARS} usable stars.
 * A uniform cloud is absorbed by the zero point, and a tilt the plane fits does not trip this check.
 * The constants are fixed: this is not a detection-profile setting.</p>
 */
final class SpatialFrameFilter {

    /** Cells along each axis. The centre cell is the middle one. */
    static final int CELL_COUNT = 3;
    /** Both the centre and the cell being compared need at least this many usable stars. */
    static final int MIN_STARS = 30;
    /**
     * A cell this far from the centre, in magnitudes, drops the frame. The same size as the smallest
     * amplitude a limited session is allowed to report.
     */
    static final double MAX_CELL_OFFSET_MAG = 0.10;

    /** One frame's cell residuals. */
    static final class FrameDecision {
        /** Photometry-array index of the frame. */
        int index;
        /** False when the frame was already out of the ensemble, so the cells were not measured. */
        boolean evaluated;
        /** True when a populated cell sits far enough from the centre. */
        boolean drop;
        /** Largest absolute cell-minus-centre median among cells with enough stars, or NaN. */
        double maxOffset = Double.NaN;
        /** Index of that cell, row-major, or -1. */
        int worstCell = -1;
        int centreStars;
        int worstStars;
        double centreMedian = Double.NaN;
        double worstMedian = Double.NaN;
        /** Median residual of each cell, row-major, NaN when the cell is empty. */
        final double[] cellMedian = new double[CELL_COUNT * CELL_COUNT];
        /** Usable stars in each cell, row-major. */
        final int[] cellCount = new int[CELL_COUNT * CELL_COUNT];

        FrameDecision() {
            for (int i = 0; i < cellMedian.length; i++) {
                cellMedian[i] = Double.NaN;
            }
        }
    }

    private SpatialFrameFilter() {
    }

    /**
     * Measures every frame. Only frames that are still active are evaluated; the others stay
     * {@code drop == false}.
     *
     * @param residual ensemble residual [star][frame], positive when the star is fainter than the model
     * @param usable measurements that may be used [star][frame]
     * @param xNorm star position in [-1, 1], left to right
     * @param yNorm star position in [-1, 1], low image y to high image y
     * @param frameActive frames still in the ensemble
     */
    static FrameDecision[] evaluate(double[][] residual, boolean[][] usable, double[] xNorm, double[] yNorm,
                                    boolean[] frameActive) {
        int nStars = xNorm.length;
        int nFrames = frameActive.length;
        int cells = CELL_COUNT * CELL_COUNT;
        int centre = (CELL_COUNT / 2) * CELL_COUNT + CELL_COUNT / 2;
        int[] cellOfStar = new int[nStars];
        for (int i = 0; i < nStars; i++) {
            int col = cellIndex(xNorm[i]);
            int row = cellIndex(yNorm[i]);
            cellOfStar[i] = row * CELL_COUNT + col;
        }

        FrameDecision[] decisions = new FrameDecision[nFrames];
        double[] buffer = new double[nStars];
        for (int j = 0; j < nFrames; j++) {
            FrameDecision decision = new FrameDecision();
            decision.index = j;
            decisions[j] = decision;
            if (!frameActive[j]) {
                continue;
            }
            decision.evaluated = true;
            int[] count = new int[cells];
            for (int i = 0; i < nStars; i++) {
                if (usable[i][j] && Double.isFinite(residual[i][j])) {
                    count[cellOfStar[i]]++;
                }
            }
            System.arraycopy(count, 0, decision.cellCount, 0, cells);
            for (int c = 0; c < cells; c++) {
                if (count[c] == 0) {
                    continue;
                }
                int n = 0;
                for (int i = 0; i < nStars; i++) {
                    if (cellOfStar[i] == c && usable[i][j] && Double.isFinite(residual[i][j])) {
                        buffer[n++] = residual[i][j];
                    }
                }
                decision.cellMedian[c] = PhotometryMath.median(buffer, n);
            }
            decision.centreStars = count[centre];
            decision.centreMedian = decision.cellMedian[centre];
            for (int c = 0; c < cells; c++) {
                if (c == centre || count[c] < MIN_STARS || count[centre] < MIN_STARS) {
                    continue;
                }
                double offset = Math.abs(decision.cellMedian[c] - decision.centreMedian);
                if (!Double.isFinite(decision.maxOffset) || offset > decision.maxOffset) {
                    decision.maxOffset = offset;
                    decision.worstCell = c;
                    decision.worstStars = count[c];
                    decision.worstMedian = decision.cellMedian[c];
                }
            }
            decision.drop = Double.isFinite(decision.maxOffset) && decision.maxOffset >= MAX_CELL_OFFSET_MAG;
        }
        return decisions;
    }

    /** Cell along one axis for a normalised coordinate in [-1, 1]. The edges stay inside the grid. */
    static int cellIndex(double norm) {
        int cell = (int) Math.floor((norm + 1.0) * 0.5 * CELL_COUNT);
        if (cell < 0) {
            return 0;
        }
        if (cell >= CELL_COUNT) {
            return CELL_COUNT - 1;
        }
        return cell;
    }
}
