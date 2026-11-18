/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.gpu;

import io.github.ppissias.jtransient.core.CoarseBackendException;
import org.jocl.CLException;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_event;

import static org.jocl.CL.*;

/**
 * Adaptive OpenCL launch sizer keeping each GPU kernel chunk around 50 ms (never targeting over 100 ms) so
 * cancellation/deadline checks stay responsive and Windows TDR (2 s watchdog) is avoided.
 */
final class OpenClLaunchSizer {

    static final double TARGET_MS = 50.0;
    static final double MAX_TARGET_MS = 100.0;

    private final int maxUnitsPerLaunch;
    private int unitsPerLaunch = 1;
    private double lastLaunchMs = Double.NaN;

    OpenClLaunchSizer(int initialUnits, int maxUnitsPerLaunch) {
        this.maxUnitsPerLaunch = Math.max(1, maxUnitsPerLaunch);
        this.unitsPerLaunch = Math.max(1, Math.min(initialUnits, this.maxUnitsPerLaunch));
    }

    int unitsPerLaunch() {
        return unitsPerLaunch;
    }

    double lastLaunchMs() {
        return lastLaunchMs;
    }

    /**
     * Waits for {@code events}, reads GPU profiling start/end times, releases every event, and adjusts the next
     * launch batch size toward {@link #TARGET_MS}.
     */
    void observeAndRelease(int unitsExecuted, cl_event... events) throws CoarseBackendException {
        if (events == null || events.length == 0) {
            return;
        }
        cl_event[] valid = filterNonNull(events);
        if (valid.length == 0) {
            return;
        }
        try {
            clWaitForEvents(valid.length, valid);
            long minStart = Long.MAX_VALUE;
            long maxEnd = Long.MIN_VALUE;
            long[] time = new long[1];
            for (cl_event ev : valid) {
                clGetEventProfilingInfo(ev, CL_PROFILING_COMMAND_START, Sizeof.cl_ulong, Pointer.to(time), null);
                minStart = Math.min(minStart, time[0]);
                clGetEventProfilingInfo(ev, CL_PROFILING_COMMAND_END, Sizeof.cl_ulong, Pointer.to(time), null);
                maxEnd = Math.max(maxEnd, time[0]);
            }
            if (maxEnd > minStart) {
                double ms = (maxEnd - minStart) / 1_000_000.0;
                this.lastLaunchMs = ms;
                double msPerUnit = Math.max(1e-4, ms / Math.max(1, unitsExecuted));
                double desiredTarget = ms > MAX_TARGET_MS ? TARGET_MS * 0.75 : TARGET_MS;
                int next = (int) Math.floor(desiredTarget / msPerUnit);
                // Smoothly grow by at most 4x per step so initial cold launches do not overshoot TDR.
                int cappedGrowth = Math.max(unitsPerLaunch * 4, 4);
                this.unitsPerLaunch = Math.max(1, Math.min(maxUnitsPerLaunch, Math.min(next, cappedGrowth)));
            }
        } catch (CLException e) {
            throw new CoarseBackendException(OpenClCoarseSearch.classifyLaunchFailure(e),
                    "GPU profiling/wait failed: " + e.getMessage(), e);
        } finally {
            for (cl_event ev : valid) {
                try {
                    clReleaseEvent(ev);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private static cl_event[] filterNonNull(cl_event[] events) {
        int count = 0;
        for (cl_event ev : events) {
            if (ev != null) {
                count++;
            }
        }
        cl_event[] out = new cl_event[count];
        int i = 0;
        for (cl_event ev : events) {
            if (ev != null) {
                out[i++] = ev;
            }
        }
        return out;
    }
}
