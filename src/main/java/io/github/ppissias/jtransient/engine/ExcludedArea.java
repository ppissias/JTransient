/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.engine;

/**
 * A rectangle of the frames that detection leaves out, such as a sensor defect or a blurred edge, in the pixel
 * coordinates of the frames as given. The pipeline treats its pixels as having no data in every frame once it has
 * measured the frames' registration from their borders; parts outside the frames are ignored.
 *
 * @param x first column, in pixels
 * @param y first row, in pixels
 * @param width columns, at least 1
 * @param height rows, at least 1
 */
public record ExcludedArea(int x, int y, int width, int height) {
    /** Checks that the rectangle has a size. */
    public ExcludedArea {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("An excluded area needs a width and a height of at least 1 pixel.");
        }
    }

    /**
     * A copy of the image as detection sees it with these areas: registration fill (0 ADU) inside them, the image
     * unchanged elsewhere. The image itself is not changed; it is returned as it is when there are no areas.
     *
     * @param areas rectangles in the image's pixel coordinates; null or empty for none
     */
    public static short[][] filledCopy(short[][] image, java.util.List<ExcludedArea> areas) {
        return new ExcludedPixels(areas).paintedCopy(image);
    }

    /** Whether the pixel at column {@code px}, row {@code py} lies inside the area. */
    public boolean contains(int px, int py) {
        return px >= x && px < (long) x + width && py >= y && py < (long) y + height;
    }
}
