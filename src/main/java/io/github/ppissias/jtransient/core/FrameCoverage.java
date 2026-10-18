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

/**
 * Where one registered frame holds image data: the first and last data pixel of every row and of every column.
 *
 * <p>Registration fills the parts of a frame that the shifted image doesn't cover with values at the floor
 * ({@link DataArea#FILL_THRESHOLD}). The fill is a run from the frame edge, and the data start at the first
 * {@link #DATA_RUN} pixels in a row (or column) that are above the floor. A few stray data pixels inside the fill
 * therefore don't end it, and dark pixels inside the image (cold pixels, clipped noise) don't punch holes into the
 * data area.</p>
 */
public final class FrameCoverage {
    /** Data start at the first run of this many pixels above the fill threshold. */
    static final int DATA_RUN = 3;

    private final int sequenceIndex;
    private final int width;
    private final int height;
    final int[] rowStart;
    final int[] rowEnd;
    final int[] columnStart;
    final int[] columnEnd;

    private FrameCoverage(int sequenceIndex, int width, int height, int[] rowStart, int[] rowEnd,
                          int[] columnStart, int[] columnEnd) {
        this.sequenceIndex = sequenceIndex;
        this.width = width;
        this.height = height;
        this.rowStart = rowStart;
        this.rowEnd = rowEnd;
        this.columnStart = columnStart;
        this.columnEnd = columnEnd;
    }

    /**
     * Measures the coverage of one frame. A row or column with no data has a start past its end.
     *
     * @param sequenceIndex the frame's sequence index
     * @param frame the frame's pixels in signed-short sensor units
     */
    public static FrameCoverage measure(int sequenceIndex, short[][] frame) {
        int height = frame.length;
        int width = frame[0].length;
        int[] rowStart = new int[height];
        int[] rowEnd = new int[height];
        for (int y = 0; y < height; y++) {
            short[] row = frame[y];
            int start = width;
            for (int x = 0, run = 0; x < width; x++) {
                run = row[x] > DataArea.FILL_THRESHOLD ? run + 1 : 0;
                if (run == DATA_RUN) {
                    start = x - DATA_RUN + 1;
                    break;
                }
            }
            int end = -1;
            if (start < width) {
                for (int x = width - 1, run = 0; x >= start; x--) {
                    run = row[x] > DataArea.FILL_THRESHOLD ? run + 1 : 0;
                    if (run == DATA_RUN) {
                        end = x + DATA_RUN - 1;
                        break;
                    }
                }
            }
            rowStart[y] = start;
            rowEnd[y] = end;
        }
        int[] columnStart = new int[width];
        int[] columnEnd = new int[width];
        for (int x = 0; x < width; x++) {
            int start = height;
            for (int y = 0, run = 0; y < height; y++) {
                run = frame[y][x] > DataArea.FILL_THRESHOLD ? run + 1 : 0;
                if (run == DATA_RUN) {
                    start = y - DATA_RUN + 1;
                    break;
                }
            }
            int end = -1;
            if (start < height) {
                for (int y = height - 1, run = 0; y >= start; y--) {
                    run = frame[y][x] > DataArea.FILL_THRESHOLD ? run + 1 : 0;
                    if (run == DATA_RUN) {
                        end = y + DATA_RUN - 1;
                        break;
                    }
                }
            }
            columnStart[x] = start;
            columnEnd[x] = end;
        }
        return new FrameCoverage(sequenceIndex, width, height, rowStart, rowEnd, columnStart, columnEnd);
    }

    /** The frame's sequence index. */
    public int getSequenceIndex() {
        return sequenceIndex;
    }

    /** Frame width in pixels. */
    public int getWidth() {
        return width;
    }

    /** Frame height in pixels. */
    public int getHeight() {
        return height;
    }
}
