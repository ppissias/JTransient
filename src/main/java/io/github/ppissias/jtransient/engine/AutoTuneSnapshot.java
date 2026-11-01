package io.github.ppissias.jtransient.engine;

import java.util.BitSet;
import java.util.List;

/**
 * Display facts from one point in calibration, built only on request. Image pixels and mask bits are borrowed,
 * read-only, and valid only during {@link AutoTuneObserver#update}; copy any data retained after that callback.
 * Marker and region coordinates use this image's pixels. No image rendering or detection is performed here.
 */
public record AutoTuneSnapshot(Kind kind, String caption, short[][] image, BitSet mask,
                               List<Marker> markers, List<Region> regions) {
    /** Separates field views from regional measurements without interpreting captions. */
    public enum Kind { FRAME, REGIONS, REFERENCE, STARS, TEST_SOURCES, MASK, EXTRACTION, RECOVERY, NOISE }

    /** The meaning of a marked position; recovery always refers to artificial calibration sources. */
    public enum Mark { TEST, RECOVERED, MISSED, DETECTION }

    /** A measured or injected position in image pixels. */
    public record Marker(double x, double y, Mark mark) { }

    /** One square sampling region in the field image, in image pixels. */
    public record Region(int x, int y, int size) { }

    /** Detaches the small annotation lists; the large image and mask remain borrowed for the callback only. */
    public AutoTuneSnapshot {
        markers = markers == null ? List.of() : List.copyOf(markers);
        regions = regions == null ? List.of() : List.copyOf(regions);
    }
}
