package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.engine.ImageFrame;

import java.util.List;

/**
 * Read-only candidate-local aperture positions and an unweighted timed linear fit for inspection.
 * The strongest aperture can follow a star or an instrumental trend; this is neither astrometry,
 * a motion confirmation nor a calibrated significance. No detection gate uses these values.
 * Contains copied scalar metadata only, with no borrowed frame or pixel buffers.
 */
public final class SlowMoverMotionEvidence {
    /**
     * One supported aperture centroid: zero-based retained order, original sequence ID, capture milliseconds
     * (-1 when unknown), X/Y in image pixels and the local-background integrated aperture SNR.
     */
    public record Position(int retainedFrameIndex, int sourceFrameIndex, long timestamp,
                           double x, double y, double signalToNoise) {}

    /** Supported positions in retained-frame order; the list and its scalar records are immutable. */
    public final List<Position> positions;
    /** At least three ordered positions with valid increasing capture times permit a descriptive fit. */
    public final boolean fitAvailable;
    /** Why the fit is unavailable; null when available. Missing time never falls back to frame-rate units. */
    public final String unavailableReason;
    /** Whether position variance along the footprint's moment axis permits a Pearson coefficient. */
    public final boolean correlationAvailable;
    /** Signed Pearson coefficient against capture time, or zero when correlationAvailable is false. */
    public final double projectedCorrelation;
    /** Fitted X drift in pixels per minute, or zero when fitAvailable is false. */
    public final double velocityXPixelsPerMinute;
    /** Fitted Y drift in pixels per minute, or zero when fitAvailable is false. */
    public final double velocityYPixelsPerMinute;
    /** Magnitude of the fitted drift in pixels per minute; not an independently confirmed source velocity. */
    public final double speedPixelsPerMinute;
    /** Fitted displacement over the supported time span, in pixels, rather than an extrapolation over missing frames. */
    public final double displacementPixels;
    /** Two-dimensional RMS centroid residual about the line, in pixels; not an uncertainty estimate. */
    public final double rmsPixels;
    /** Time from the first to last supported position, in minutes, or zero without valid timing. */
    public final double supportedSpanMinutes;
    /** Time from the first to last retained frame, in minutes, or zero without valid timing. */
    public final double sessionSpanMinutes;
    /** Supported time span divided by retained-session span; does not count the gaps as measured positions. */
    public final double sessionSpanFraction;

    /** Captures copied positions and a finite fit, using explicit availability instead of NaN sentinels. */
    private SlowMoverMotionEvidence(List<Position> positions, String unavailableReason,
                                    double supportedMinutes, double sessionMinutes,
                                    double vx, double vy, double rms,
                                    double correlation, boolean correlationAvailable) {
        this.positions = List.copyOf(positions);
        this.unavailableReason = unavailableReason;
        this.fitAvailable = unavailableReason == null;
        this.supportedSpanMinutes = supportedMinutes;
        this.sessionSpanMinutes = sessionMinutes;
        this.sessionSpanFraction = sessionMinutes > 0 ? supportedMinutes / sessionMinutes : 0;
        this.velocityXPixelsPerMinute = vx;
        this.velocityYPixelsPerMinute = vy;
        this.speedPixelsPerMinute = Math.hypot(vx, vy);
        this.displacementPixels = speedPixelsPerMinute * supportedMinutes;
        this.rmsPixels = rms;
        this.projectedCorrelation = correlation;
        this.correlationAvailable = correlationAvailable;
    }

    /** Returns an empty snapshot for stack-only calls; no original-frame measurements were made. */
    static SlowMoverMotionEvidence unavailable() {
        return unavailable(List.of(), "Original-frame positions are unavailable", 0, 0);
    }

    /** Preserves existing positions even when timing or support cannot provide a fit. */
    private static SlowMoverMotionEvidence unavailable(List<Position> positions, String reason,
                                                       double supportedMinutes, double sessionMinutes) {
        return new SlowMoverMotionEvidence(positions, reason, supportedMinutes, sessionMinutes, 0, 0, 0, 0, false);
    }

    /**
     * Fits copied centroid samples against relative capture times, borrowing frame metadata only during this call.
     * Uneven cadence is retained. Missing/duplicate/decreasing times disable the fit; no cut on r or speed is applied.
     */
    static SlowMoverMotionEvidence measure(List<Position> positions, List<ImageFrame> frames, double orientation) {
        if (positions.isEmpty()) return unavailable();
        int previousIndex = -1;
        for (Position p : positions) {
            if (p.retainedFrameIndex() <= previousIndex || p.retainedFrameIndex() >= frames.size()
                    || !Double.isFinite(p.x()) || !Double.isFinite(p.y())) {
                return unavailable(positions, "Position order or coordinates are invalid", 0, 0);
            }
            previousIndex = p.retainedFrameIndex();
        }
        long previousTime = -1;
        for (ImageFrame frame : frames) {
            if (frame == null || frame.timestamp < 0 || frame.timestamp <= previousTime) {
                return unavailable(positions, "Capture times are missing, repeated or out of order", 0, 0);
            }
            previousTime = frame.timestamp;
        }
        long origin = positions.get(0).timestamp();
        long last = positions.get(positions.size() - 1).timestamp();
        double supportedMinutes = (last - origin) / 60000.0;
        double sessionMinutes = (frames.get(frames.size() - 1).timestamp - frames.get(0).timestamp) / 60000.0;
        if (positions.size() < 3) {
            return unavailable(positions, "At least three supported frames are required", supportedMinutes, sessionMinutes);
        }
        double meanTime = 0, meanX = 0, meanY = 0;
        for (Position p : positions) {
            meanTime += (p.timestamp() - origin) / 60000.0;
            meanX += p.x();
            meanY += p.y();
        }
        int count = positions.size();
        meanTime /= count;
        meanX /= count;
        meanY /= count;
        double timeVariance = 0, tx = 0, ty = 0, projectedVariance = 0;
        double cos = Math.cos(orientation), sin = Math.sin(orientation);
        for (Position p : positions) {
            double t = (p.timestamp() - origin) / 60000.0 - meanTime;
            double x = p.x() - meanX, y = p.y() - meanY;
            timeVariance += t * t;
            tx += t * x;
            ty += t * y;
            double projected = x * cos + y * sin;
            projectedVariance += projected * projected;
        }
        if (!(timeVariance > 0)) return unavailable(positions, "Supported capture times have no span", 0, sessionMinutes);
        double vx = tx / timeVariance, vy = ty / timeVariance;
        double residualSquares = 0;
        for (Position p : positions) {
            double t = (p.timestamp() - origin) / 60000.0 - meanTime;
            double dx = p.x() - meanX - vx * t, dy = p.y() - meanY - vy * t;
            residualSquares += dx * dx + dy * dy;
        }
        double rms = Math.sqrt(residualSquares / count);
        boolean correlationAvailable = projectedVariance > 0 && Double.isFinite(projectedVariance);
        double r = correlationAvailable ? (tx * cos + ty * sin) / Math.sqrt(timeVariance * projectedVariance) : 0;
        if (!Double.isFinite(vx) || !Double.isFinite(vy) || !Double.isFinite(rms) || !Double.isFinite(r)) {
            return unavailable(positions, "A finite fit could not be measured", supportedMinutes, sessionMinutes);
        }
        return new SlowMoverMotionEvidence(positions, null, supportedMinutes, sessionMinutes,
                vx, vy, rms, Math.max(-1, Math.min(1, r)), correlationAvailable);
    }
}
