package io.github.ppissias.jtransient.photometry;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/** Scientific controls for recovery, noise rejection and the interpretation of photometry gate outcomes. */
public class PhotometrySensitivityTest {
    private static final int FRAMES = 80;
    private static final int REFERENCES = 120;

    /** Coherent changes above the default amplitude floor survive independent comparison-star noise. */
    @Test
    public void defaultsRecoverCoherentVariabilityWithoutPromotingQuietReferences() {
        for (int seed = 1; seed <= 5; seed++) {
            Fixture fixture = fixture(0.03, 40, 2, seed);
            score(fixture, false, new DetectionConfig());
            assertEquals(fixture.target().failedGates.toString(), VariabilityTier.HIGH_CONFIDENCE, fixture.target().tier);
            assertTrue(fixture.target().scatterZ >= 5);
            assertTrue(fixture.target().stetsonJZ >= 5);
            assertNoQuietCandidates(fixture);
        }
    }

    /** White-noise target and reference curves do not create candidate detections in the fixed control. */
    @Test
    public void independentNoiseIsNotReportedAsVariability() {
        Fixture fixture = fixture(0, 40, 2, 1);
        score(fixture, false, new DetectionConfig());
        assertEquals(VariabilityTier.CONSTANT, fixture.target().tier);
        assertNoQuietCandidates(fixture);
    }

    /** A known short observing span fails DATA and receives the one-failure candidate tier. */
    @Test
    public void knownShortSpanIsPossibleRatherThanHighConfidence() {
        Fixture fixture = fixture(0.10, 40, 0.1, 1);
        score(fixture, false, new DetectionConfig());
        assertEquals(List.of("DATA"), fixture.target().failedGates);
        assertEquals(VariabilityTier.POSSIBLE, fixture.target().tier);
        assertEquals(7.9, fixture.target().timeSpanMinutes, 1e-5);
    }

    /** LIMITED data require the higher amplitude floor even when the same curve passes on READY data. */
    @Test
    public void limitedReadinessRaisesTheCandidateAmplitudeRequirement() {
        Fixture ready = fixture(0.03, 40, 2, 1);
        Fixture limited = fixture(0.03, 40, 2, 1);
        score(ready, false, new DetectionConfig());
        score(limited, true, new DetectionConfig());
        assertEquals(VariabilityTier.HIGH_CONFIDENCE, ready.target().tier);
        assertEquals(List.of("AMPLITUDE"), limited.target().failedGates);
        assertEquals(VariabilityTier.POSSIBLE, limited.target().tier);
    }

    /** Two separate evidence failures reject a candidate that would be POSSIBLE with only one failure. */
    @Test
    public void independentDataAndPersistenceFailuresRejectTheCandidate() {
        Fixture oneFailure = fixture(0.10, 8, 2, 1);
        score(oneFailure, false, new DetectionConfig());
        assertEquals(List.of("PERSISTENCE"), oneFailure.target().failedGates);
        assertEquals(VariabilityTier.POSSIBLE, oneFailure.target().tier);

        DetectionConfig longerSpan = new DetectionConfig();
        longerSpan.variableMinSpanMinutes = 200;
        Fixture twoFailures = fixture(0.10, 8, 2, 1);
        score(twoFailures, false, longerSpan);
        assertEquals(List.of("DATA", "PERSISTENCE"), twoFailures.target().failedGates);
        assertEquals(VariabilityTier.REJECTED, twoFailures.target().tier);
    }

    /** A strong periodic signal sampled three times per cycle can have a negative adjacent-pair J index. */
    @Test
    public void rapidPeriodicSignalNeedNotHavePositiveSerialEvidence() {
        double[] curve = new double[FRAMES];
        for (int j = 0; j < FRAMES; j++) curve[j] = 0.10 * Math.sin(2 * Math.PI * j / 3.0 + 0.37);
        assertTrue(PhotometryMath.std(curve) > 0.05);
        assertTrue(VariabilityScorer.stetsonJ(curve, 0.01) < 0);
    }

    /** A low-duty-cycle eclipse can be deep while lying outside the central ninety percent of measurements. */
    @Test
    public void percentileAmplitudeDoesNotMeasureTheDepthOfAShortEclipse() {
        double[] curve = new double[FRAMES];
        curve[35] = curve[36] = 0.30;
        assertEquals(0.30, Arrays.stream(curve).max().orElseThrow(), 0);
        assertEquals(0, PhotometryMath.percentile(curve, 0.95) - PhotometryMath.percentile(curve, 0.05), 0);
    }

    /**
     * Paired controls that differ only in whether the frame holding the target's non-linear measurement is still
     * in the analysis. On an excluded frame the flag is history and costs nothing; on an active frame it fails
     * LINEARITY even though that measurement itself is unusable. With one other failure the active case is rejected.
     */
    @Test
    public void linearityCountsClippedMeasurementsOnActiveFramesOnly() {
        Fixture excluded = fixture(0.03, 40, 2, 1);
        clipTarget(excluded, 10, false);
        score(excluded, false, new DetectionConfig());
        assertEquals(List.of(), excluded.target().failedGates);
        assertEquals(VariabilityTier.HIGH_CONFIDENCE, excluded.target().tier);
        assertTrue("the historical flag stays", (excluded.target().flags[10] & PhotometryFlags.NONLINEAR) != 0);

        Fixture active = fixture(0.03, 40, 2, 1);
        clipTarget(active, 10, true);
        score(active, false, new DetectionConfig());
        assertEquals(List.of("LINEARITY"), active.target().failedGates);
        assertEquals(VariabilityTier.POSSIBLE, active.target().tier);

        Fixture shortExcluded = fixture(0.10, 40, 0.1, 1);
        clipTarget(shortExcluded, 10, false);
        score(shortExcluded, false, new DetectionConfig());
        assertEquals(List.of("DATA"), shortExcluded.target().failedGates);
        assertEquals(VariabilityTier.POSSIBLE, shortExcluded.target().tier);

        Fixture shortActive = fixture(0.10, 40, 0.1, 1);
        clipTarget(shortActive, 10, true);
        score(shortActive, false, new DetectionConfig());
        assertEquals(List.of("DATA", "LINEARITY"), shortActive.target().failedGates);
        assertEquals(VariabilityTier.REJECTED, shortActive.target().tier);
    }

    /**
     * The aperture difference is the share of the scored pattern by which the large and small curves differ: 0 when
     * they agree, -2 for opposite curves, -0.5 when the large aperture shows half, and the leak pattern when only the
     * large aperture carries most of it. Fewer than three shared frames give no measurement.
     */
    @Test
    public void apertureDifferenceMeasuresTheShareOfThePatternTheAperturesDisagreeOn() {
        double[] r = new double[FRAMES];
        double[] half = new double[FRAMES];
        double[] opposite = new double[FRAMES];
        double[] faint = new double[FRAMES];
        for (int j = 0; j < FRAMES; j++) {
            r[j] = 0.1 * Math.sin(2 * Math.PI * j / 20.0);
            half[j] = 0.5 * r[j];
            opposite[j] = -r[j];
            faint[j] = 0.2 * r[j];
        }
        assertEquals(0.0, VariabilityScorer.aperturePatternDifference(r, r, r)[0], 1e-12);
        assertEquals(0.0, VariabilityScorer.aperturePatternDifference(r, r, r)[1], 1e-12);
        assertEquals(-2.0, VariabilityScorer.aperturePatternDifference(r, r, opposite)[0], 1e-12);
        assertEquals(-0.5, VariabilityScorer.aperturePatternDifference(r, r, half)[0], 1e-12);
        assertEquals(0.8, VariabilityScorer.aperturePatternDifference(r, faint, r)[0], 1e-12);
        double[] two = Arrays.copyOf(r, FRAMES);
        Arrays.fill(two, 2, FRAMES, Double.NaN);
        assertTrue(Double.isNaN(VariabilityScorer.aperturePatternDifference(two, r, r)[0]));
    }

    /** The gate fails a difference beyond the allowed share only when it is also beyond two standard errors. */
    @Test
    public void apertureGateNeedsADifferenceBeyondTheShareAndTwoStandardErrors() {
        assertFalse(VariabilityScorer.apertureFails(new double[]{0.2, 0.01}, 0.3));
        assertFalse("a noisy difference is not evidence", VariabilityScorer.apertureFails(new double[]{0.45, 0.25}, 0.3));
        assertTrue(VariabilityScorer.apertureFails(new double[]{0.45, 0.10}, 0.3));
        assertTrue(VariabilityScorer.apertureFails(new double[]{-0.45, 0.10}, 0.3));
        assertTrue("an unmeasurable difference fails", VariabilityScorer.apertureFails(new double[]{Double.NaN, Double.NaN}, 0.3));
    }

    /**
     * VS-11's counterexample: the large curve is the opposite of the main and small curves, with equal ranges. The
     * old amplitude comparison passed it; the temporal comparison fails it, and only APERTURE fails.
     */
    @Test
    public void oppositeApertureCurvesFailTheApertureGate() {
        for (int seed = 1; seed <= 5; seed++) {
            Fixture fixture = fixture(0.03, 40, 2, seed);
            for (int j = 0; j < FRAMES; j++) fixture.large[0][j] = -fixture.target().deltaMag[j];
            score(fixture, false, new DetectionConfig());
            assertEquals(List.of("APERTURE"), fixture.target().failedGates);
            assertEquals(VariabilityTier.POSSIBLE, fixture.target().tier);
            assertEquals(-2.0, fixture.target().aperturePatternDifference, 0.1);
            assertNoQuietCandidates(fixture);
        }
    }

    /**
     * Flags the target's measurement in {@code frame} as non-linear, as check B does. On an active frame only that
     * measurement becomes unusable; an excluded frame is unusable and unsolved for every star, as the analyzer
     * leaves it whichever check excluded it.
     */
    private static void clipTarget(Fixture fixture, int frame, boolean frameActive) {
        fixture.target().flags[frame] |= PhotometryFlags.NONLINEAR;
        fixture.usable[0][frame] = false;
        if (frameActive) {
            return;
        }
        for (int i = 0; i < fixture.stars.size(); i++) {
            StarLightCurve star = fixture.stars.get(i);
            star.flags[frame] |= PhotometryFlags.FRAME_EXCLUDED;
            star.deltaMag[frame] = Double.NaN;
            fixture.small[i][frame] = Double.NaN;
            fixture.large[i][frame] = Double.NaN;
            fixture.usable[i][frame] = false;
        }
    }

    /** Makes a solved-ensemble control; input residuals are magnitudes and cadence is in minutes. */
    private static Fixture fixture(double semiAmplitude, double periodFrames, double cadenceMinutes, int seed) {
        Fixture fixture = new Fixture();
        Random random = new Random(87113L + seed);
        for (int i = 0; i <= REFERENCES; i++) {
            StarLightCurve star = new StarLightCurve(i, i == 0 ? 100 : 175 + (i % 10) * 4,
                    i == 0 ? 100 : 70 + (i / 10) * 4, 10000, FRAMES);
            star.meanMag = -10 + (i == 0 ? 0 : (i - 60) * 0.004);
            for (int j = 0; j < FRAMES; j++) {
                double signal = i == 0 ? semiAmplitude * Math.sin(2 * Math.PI * j / periodFrames + seed * 0.37) : 0;
                star.deltaMag[j] = signal + 0.01 * random.nextGaussian();
                fixture.small[i][j] = star.deltaMag[j];
                fixture.large[i][j] = star.deltaMag[j];
                fixture.sky[i][j] = 1000;
                fixture.usable[i][j] = true;
            }
            fixture.stars.add(star);
        }
        fixture.series.zeroPoint = new double[FRAMES];
        fixture.series.fwhm = new double[FRAMES];
        Arrays.fill(fixture.series.fwhm, 2);
        fixture.series.offsetX = new double[FRAMES];
        fixture.series.offsetY = new double[FRAMES];
        fixture.series.julianDate = new double[FRAMES];
        for (int j = 0; j < FRAMES; j++) fixture.series.julianDate[j] = 2459000 + j * cadenceMinutes / 1440;
        return fixture;
    }

    /** Runs the production scorer on fresh mutable fixture values. */
    private static void score(Fixture fixture, boolean limited, DetectionConfig config) {
        VariabilityScorer.score(fixture.stars, fixture.usable, fixture.small, fixture.large, fixture.sky,
                fixture.series, limited, config, new PipelineTelemetry.PhotometryTelemetry());
    }

    /** Confirms every injected constant reference stayed outside both reported candidate tiers. */
    private static void assertNoQuietCandidates(Fixture fixture) {
        for (StarLightCurve star : fixture.stars.subList(1, fixture.stars.size())) {
            assertNotEquals(VariabilityTier.HIGH_CONFIDENCE, star.tier);
            assertNotEquals(VariabilityTier.POSSIBLE, star.tier);
        }
    }

    /** Owns one synthetic ensemble's arrays; every measurement slot has a matching frame identity. */
    private static final class Fixture {
        final List<StarLightCurve> stars = new ArrayList<>();
        final boolean[][] usable = new boolean[REFERENCES + 1][FRAMES];
        final double[][] small = new double[REFERENCES + 1][FRAMES];
        final double[][] large = new double[REFERENCES + 1][FRAMES];
        final double[][] sky = new double[REFERENCES + 1][FRAMES];
        final VariabilityScorer.FrameSeries series = new VariabilityScorer.FrameSeries();

        /** Returns the injected target, whose index and public star identity are both zero. */
        StarLightCurve target() { return stars.get(0); }
    }
}
