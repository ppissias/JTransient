package io.github.ppissias.jtransient.engine;

public class ImageFrame {
    public final int sequenceIndex;
    public final String identifier; // Usually the filename
    public final short[][] pixelData;

    public ImageFrame(int sequenceIndex, String identifier, short[][] pixelData) {
        this.sequenceIndex = sequenceIndex;
        this.identifier = identifier;
        this.pixelData = pixelData;
    }
}