package io.github.ppissias.jtransient.core;

import java.util.List;

/**
 * Bounded, candidate-owned review pixels copied from the original search tile while it is resident. These are
 * processed evidence along a fitted path, not independent detections, photometry or calibrated significance.
 * No input image, tile or engine context is retained. Consumers must treat the arrays as read-only.
 */
public final class FaintMoverDiagnostics {
    /** Reason pixels are unavailable, or null when available. Selection is independent of this evidence. */
    public final String unavailableReason;
    /** Registered-image origin and dimensions of every residual cutout. */
    public final int x, y, width, height;
    /** Reference position on the integer scoring grid; the candidate additionally has a subpixel refinement. */
    public final int scoreX, scoreY;
    /** PSF-filtered, locally noise-normalised and clipped residuals, in searched-frame order. */
    public final List<Frame> frames;
    /**
     * Time-ordered half stacks, 31 by 31 around the integer scoring position, on the full-stack score scale.
     * Null when that half has insufficient unmasked support. The maximum in the central refinement area gives
     * the reported half score; it need not be the central pixel.
     */
    public final float[][] firstHalf, secondHalf;

    /** One owned residual cutout and the actual mask test at the rounded scoring-path sample. */
    public static final class Frame {
        /** Original zero-based sequence identity and exposure midpoint in UTC epoch milliseconds (fractional ms truncated). */
        public final int sequenceIndex;
        public final long midpointMillis;
        /** Sample position in registered pixels, using the search's whole-pixel motion shifts. */
        public final int sampleX, sampleY;
        /** Whether this sample lies outside the shared star/fill mask and this frame's streak mask. */
        public final boolean unmasked;
        /** Processed residual at the sample, in locally normalised PSF-filtered units, not flux or S/N. */
        public final float signal;
        /** Row-major residual pixels; mask zeros are applied before PSF filtering, which can spread nearby signal. */
        public final float[] residual;

        /** Takes ownership of a newly copied residual rectangle; never borrows a full frame. */
        Frame(int sequenceIndex, long midpointMillis, int sampleX, int sampleY, boolean unmasked,
              float signal, float[] residual) {
            this.sequenceIndex = sequenceIndex;
            this.midpointMillis = midpointMillis;
            this.sampleX = sampleX;
            this.sampleY = sampleY;
            this.unmasked = unmasked;
            this.signal = signal;
            this.residual = residual;
        }
    }

    /** Takes ownership of newly copied evidence, or supplies only an explicit unavailability reason. */
    FaintMoverDiagnostics(String reason, int x, int y, int width, int height, int scoreX, int scoreY,
                          List<Frame> frames, float[][] firstHalf, float[][] secondHalf) {
        this.unavailableReason = reason;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.scoreX = scoreX;
        this.scoreY = scoreY;
        this.frames = List.copyOf(frames);
        this.firstHalf = firstHalf;
        this.secondHalf = secondHalf;
    }

    /** Records a resource limit without removing or changing a selected candidate. */
    static FaintMoverDiagnostics unavailable(String reason) {
        return new FaintMoverDiagnostics(reason, 0, 0, 0, 0, 0, 0, List.of(), null, null);
    }
}
