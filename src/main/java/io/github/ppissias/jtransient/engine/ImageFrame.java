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

public class ImageFrame {
    public final int sequenceIndex;
    public final String identifier; // Usually the filename
    public final short[][] pixelData;
    public final long timestamp;

    public ImageFrame(int sequenceIndex, String identifier, short[][] pixelData, long timestamp) {
        this.sequenceIndex = sequenceIndex;
        this.identifier = identifier;
        this.pixelData = pixelData;
        this.timestamp = timestamp;
    }
}