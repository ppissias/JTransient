/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.photometry;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Test;

import java.util.function.IntToDoubleFunction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PhotometricReadinessCheckerTest {

    private static final int STARS = 300;
    private static final double CI = 0.66;

    /** Stars evenly spread over {@code spanMag}, brightest first, with the given concentration per star. */
    private static ApertureMeasurer.FrameMeasurement frame(double spanMag, IntToDoubleFunction concentration) {
        ApertureMeasurer.FrameMeasurement m = new ApertureMeasurer.FrameMeasurement(3.0, STARS);
        for (int i = 0; i < STARS; i++) {
            double mag = -15.0 + spanMag * i / (STARS - 1);
            double outer = Math.pow(10.0, -0.4 * mag);
            m.flux[ApertureMeasurer.R_MAIN][i] = outer;
            m.flux[ApertureMeasurer.R_CI_OUTER][i] = outer;
            m.flux[ApertureMeasurer.R_CI_INNER][i] = outer * concentration.applyAsDouble(i);
            m.magError[i] = 0.005;
        }
        return m;
    }

    private static PhotometricReadinessChecker.ShapeResult analyze(ApertureMeasurer.FrameMeasurement m,
                                                                   PipelineTelemetry.PhotometryFrameStat stat) {
        return PhotometricReadinessChecker.analyzeShape(m, new DetectionConfig(), stat);
    }

    @Test
    public void linearFrameWithLongRangePasses() {
        PipelineTelemetry.PhotometryFrameStat stat = new PipelineTelemetry.PhotometryFrameStat();
        PhotometricReadinessChecker.ShapeResult result = analyze(frame(4.0, i -> CI), stat);

        assertEquals(PhotometricReadiness.CheckStatus.PASS, result.status);
        assertFalse(result.departureFound);
        assertTrue(stat.linearRangeMag > 3.0);
    }

    @Test
    public void deviatingFaintReferenceBinDoesNotEndTheWalk() {
        // The faintest bin lies inside the faint-star reference; its departure is noise, not non-linearity.
        PipelineTelemetry.PhotometryFrameStat stat = new PipelineTelemetry.PhotometryFrameStat();
        PhotometricReadinessChecker.ShapeResult result =
                analyze(frame(4.0, i -> i >= 250 && i < 270 ? CI - 0.1 : CI), stat);

        assertEquals(PhotometricReadiness.CheckStatus.PASS, result.status);
        assertFalse(result.departureFound);
        assertTrue("range " + stat.linearRangeMag, stat.linearRangeMag > 3.0);
    }

    @Test
    public void flatterBrightStarsLimitTheLinearRange() {
        // The brightest third is flatter: linearity ends there, leaving about 2.6 mag.
        PipelineTelemetry.PhotometryFrameStat stat = new PipelineTelemetry.PhotometryFrameStat();
        PhotometricReadinessChecker.ShapeResult result = analyze(frame(4.0, i -> i < 75 ? CI - 0.1 : CI), stat);

        assertEquals(PhotometricReadiness.CheckStatus.PASS, result.status);
        assertTrue(result.departureFound);
        assertTrue("range " + stat.linearRangeMag, stat.linearRangeMag > 2.0 && stat.linearRangeMag < 3.0);
    }

    @Test
    public void moderateSnrStarsExtendTheVerifiedRange() {
        // Background-limited frame: only the brightest 1.5 mag reach SNR 50, the next 1.5 mag have SNR 25-50.
        ApertureMeasurer.FrameMeasurement m = frame(3.0, i -> CI);
        for (int i = STARS / 2; i < STARS; i++) {
            m.magError[i] = 0.04;
        }
        PipelineTelemetry.PhotometryFrameStat stat = new PipelineTelemetry.PhotometryFrameStat();
        PhotometricReadinessChecker.ShapeResult result = analyze(m, stat);

        assertEquals(PhotometricReadiness.CheckStatus.PASS, result.status);
        assertTrue("range " + stat.linearRangeMag, stat.linearRangeMag > 2.0);
    }

    @Test
    public void unmeasurableResponseSlopeIsInconclusiveNotPass() {
        // Every star has the same mean magnitude, so no frame has the leverage to fit a slope.
        int nStars = 60;
        int nFrames = 40;
        EnsembleSolver.Solution solution = new EnsembleSolver.Solution();
        solution.starMag = new double[nStars];
        solution.starSigma = new double[nStars];
        solution.zeroPoint = new double[nFrames];
        solution.frameSolved = new boolean[nFrames];
        solution.residual = new double[nStars][nFrames];
        boolean[][] usable = new boolean[nStars][nFrames];
        java.util.Random random = new java.util.Random(1);
        for (int i = 0; i < nStars; i++) {
            solution.starMag[i] = -12.0;
            solution.starSigma[i] = 0.01;
            for (int j = 0; j < nFrames; j++) {
                solution.residual[i][j] = 0.01 * random.nextGaussian();
                usable[i][j] = true;
            }
        }
        PipelineTelemetry.PhotometryFrameStat[] stats = new PipelineTelemetry.PhotometryFrameStat[nFrames];
        for (int j = 0; j < nFrames; j++) {
            solution.frameSolved[j] = true;
            solution.zeroPoint[j] = 0.1 * j / (nFrames - 1.0);
            stats[j] = new PipelineTelemetry.PhotometryFrameStat();
        }

        PhotometricReadinessChecker.ResponseResult result = PhotometricReadinessChecker.checkResponse(
                solution, usable, new double[nFrames], stats, new DetectionConfig(), new PhotometricReadiness());

        assertEquals(PhotometricReadiness.CheckStatus.INCONCLUSIVE, result.status);
        assertEquals("INCONCLUSIVE", stats[0].responseStatus);
    }

    @Test
    public void shortButLinearRangeFailsWithoutDeparture() {
        PipelineTelemetry.PhotometryFrameStat stat = new PipelineTelemetry.PhotometryFrameStat();
        PhotometricReadinessChecker.ShapeResult result = analyze(frame(1.5, i -> CI), stat);

        assertEquals(PhotometricReadiness.CheckStatus.FAIL, result.status);
        assertFalse(result.departureFound);
    }
}
