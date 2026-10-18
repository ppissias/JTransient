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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The input rules the engine relies on, checked at its public entry points. They are the SpacePixels import rules
 * that still mean something once the pixels are in memory: frames of one size, each with its own sequence index, a
 * timestamp on every frame or on none, and an exposure time on every frame or on none, all within 2 % (at least
 * 0.1 s) of each other. File type, colour and sample format are resolved by whoever reads the files.
 *
 * <p>A missing timestamp is {@code -1}; any other value, including 0, is a real time. A missing exposure time is
 * {@code -1} (any value of 0 or less counts as missing).</p>
 */
final class InputRules {
    static final double EXPOSURE_RELATIVE_TOLERANCE = 0.02;
    static final long EXPOSURE_MIN_TOLERANCE_MILLIS = 100;
    private static final int MAX_NAMED_FRAMES = 3;

    private InputRules() {
    }

    /**
     * Checks the frames and an optional precomputed master stack for a pipeline run.
     *
     * @throws IllegalArgumentException naming the frames that break a rule
     */
    static void checkPipelineInput(List<ImageFrame> frames, short[][] masterStack) {
        if (frames != null && frames.isEmpty()) {
            throw new IllegalArgumentException("No frames to process.");
        }
        checkFrames(frames);
        if (masterStack != null) {
            int width = frames.get(0).pixelData[0].length;
            int height = frames.get(0).pixelData.length;
            if (masterStack.length != height || masterStack[0] == null || masterStack[0].length != width
                    || !isRectangular(masterStack)) {
                throw new IllegalArgumentException("The supplied master stack is " + describeSize(masterStack)
                        + ", but the frames are " + width + " × " + height + ".");
            }
        }
    }

    /**
     * Checks the frame rules. An empty list passes.
     *
     * @throws IllegalArgumentException naming the frames that break a rule
     */
    static void checkFrames(List<ImageFrame> frames) {
        if (frames == null) {
            throw new IllegalArgumentException("The frame list is null.");
        }
        for (int i = 0; i < frames.size(); i++) {
            ImageFrame frame = frames.get(i);
            if (frame == null) {
                throw new IllegalArgumentException("Frame " + (i + 1) + " of the list is null.");
            }
            if (frame.pixelData == null || frame.pixelData.length == 0 || frame.pixelData[0] == null
                    || frame.pixelData[0].length == 0) {
                throw new IllegalArgumentException(name(frame) + " has no pixel data.");
            }
            if (!isRectangular(frame.pixelData)) {
                throw new IllegalArgumentException(name(frame) + " has rows of different lengths.");
            }
        }
        if (frames.isEmpty()) {
            return;
        }
        checkSizes(frames);
        checkSequenceIndices(frames);
        checkTimestamps(frames);
        checkExposures(frames);
    }

    private static void checkSizes(List<ImageFrame> frames) {
        ImageFrame reference = frames.get(0);
        List<String> different = new ArrayList<>();
        for (ImageFrame frame : frames) {
            if (frame.pixelData.length != reference.pixelData.length
                    || frame.pixelData[0].length != reference.pixelData[0].length) {
                different.add(name(frame) + " (" + describeSize(frame.pixelData) + ")");
            }
        }
        if (!different.isEmpty()) {
            throw new IllegalArgumentException("All frames must have the same size. " + name(reference) + " is "
                    + describeSize(reference.pixelData) + "; " + nameList(different) + " differ.");
        }
    }

    private static void checkSequenceIndices(List<ImageFrame> frames) {
        Map<Integer, ImageFrame> byIndex = new HashMap<>();
        for (ImageFrame frame : frames) {
            if (frame.sequenceIndex < 0) {
                throw new IllegalArgumentException(name(frame) + " has a negative sequence index ("
                        + frame.sequenceIndex + ").");
            }
            ImageFrame other = byIndex.put(frame.sequenceIndex, frame);
            if (other != null) {
                throw new IllegalArgumentException("Each frame needs its own sequence index: " + name(other)
                        + " and " + name(frame) + " both have " + frame.sequenceIndex + ".");
            }
        }
    }

    private static void checkTimestamps(List<ImageFrame> frames) {
        List<String> without = new ArrayList<>();
        for (ImageFrame frame : frames) {
            if (frame.timestamp == -1L) {
                without.add(name(frame));
            }
        }
        if (!without.isEmpty() && without.size() < frames.size()) {
            throw new IllegalArgumentException("Either every frame has a timestamp or none has. "
                    + without.size() + " of " + frames.size() + " frames have none: " + nameList(without) + ".");
        }
    }

    private static void checkExposures(List<ImageFrame> frames) {
        List<ImageFrame> timed = new ArrayList<>();
        List<String> without = new ArrayList<>();
        for (ImageFrame frame : frames) {
            if (frame.exposureDuration > 0L) {
                timed.add(frame);
            } else {
                without.add(name(frame));
            }
        }
        if (timed.isEmpty()) {
            return;
        }
        if (!without.isEmpty()) {
            throw new IllegalArgumentException("Either every frame has an exposure time or none has. "
                    + without.size() + " of " + frames.size() + " frames have none: " + nameList(without) + ".");
        }
        timed.sort(Comparator.comparingLong(frame -> frame.exposureDuration));
        List<List<ImageFrame>> groups = new ArrayList<>();
        List<ImageFrame> group = null;
        long groupShortest = 0;
        for (ImageFrame frame : timed) {
            if (group == null || frame.exposureDuration - groupShortest > exposureTolerance(groupShortest)) {
                group = new ArrayList<>();
                groups.add(group);
                groupShortest = frame.exposureDuration;
            }
            group.add(frame);
        }
        if (groups.size() == 1) {
            return;
        }
        groups.sort(Comparator.comparingInt((List<ImageFrame> g) -> g.size()).reversed());
        StringBuilder message = new StringBuilder("All frames must share one exposure time (within 2 %, at least 0.1 s): ");
        for (int i = 0; i < groups.size(); i++) {
            List<ImageFrame> g = groups.get(i);
            message.append(i == 0 ? "" : i == groups.size() - 1 ? " and " : ", ")
                    .append(g.size()).append(g.size() == 1 ? " frame of " : " frames of ").append(exposureLabel(g));
            if (i > 0) {
                List<String> names = new ArrayList<>();
                for (ImageFrame frame : g) {
                    names.add(name(frame));
                }
                message.append(" (").append(nameList(names)).append(')');
            }
        }
        throw new IllegalArgumentException(message.append('.').toString());
    }

    /** Two exposure times count as one when they differ by at most this much from the shorter one. */
    static long exposureTolerance(long shortestMillis) {
        return Math.max(EXPOSURE_MIN_TOLERANCE_MILLIS, Math.round(EXPOSURE_RELATIVE_TOLERANCE * shortestMillis));
    }

    private static String exposureLabel(List<ImageFrame> group) {
        String shortest = seconds(group.get(0).exposureDuration);
        String longest = seconds(group.get(group.size() - 1).exposureDuration);
        return (shortest.equals(longest) ? shortest : shortest + "–" + longest) + " s";
    }

    private static String seconds(long millis) {
        return BigDecimal.valueOf(millis, 3).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static boolean isRectangular(short[][] pixels) {
        for (short[] row : pixels) {
            if (row == null || row.length != pixels[0].length) {
                return false;
            }
        }
        return true;
    }

    private static String describeSize(short[][] pixels) {
        return (pixels.length == 0 || pixels[0] == null ? 0 : pixels[0].length) + " × " + pixels.length;
    }

    private static String name(ImageFrame frame) {
        return frame.filename != null && !frame.filename.isBlank() ? frame.filename : "frame " + frame.sequenceIndex;
    }

    private static String nameList(List<String> names) {
        if (names.size() <= MAX_NAMED_FRAMES) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, MAX_NAMED_FRAMES)) + " and " + (names.size() - MAX_NAMED_FRAMES)
                + " more";
    }
}
