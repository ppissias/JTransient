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

import io.github.ppissias.jtransient.config.DetectionConfig;

/** Result of one {@link CalibratedAutoTuner#tune} run. */
public class AutoTunerResult {
    /** Whether the calibration succeeded and {@link #optimizedConfig} holds tuned settings. */
    public boolean success;
    /** Tuned configuration, or a copy of the base configuration when the calibration did not succeed. */
    public DetectionConfig optimizedConfig;
    /** Human-readable report of the measurement and the chosen settings. */
    public String telemetryReport;
    /**
     * Noise detections (false positives from pure noise) per megapixel per frame, measured for the chosen settings
     * of the requested profile; 0 when the calibration did not succeed.
     */
    public double noisePerMpixFrame;
    /** Short plain-language summary of the chosen settings and their measured effect; null when unsuccessful. */
    public String summary;
    /**
     * Full measurement, including the chosen settings of every profile, so a caller can switch profiles without
     * re-running.
     */
    public CalibratedAutoTuner.Calibration calibration;
}
