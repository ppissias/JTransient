package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.engine.CalibratedAutoTuner.Calibration;
import io.github.ppissias.jtransient.engine.CalibratedAutoTuner.Candidate;
import io.github.ppissias.jtransient.engine.CalibratedAutoTuner.SigmaChoice;
import io.github.ppissias.jtransient.engine.CalibratedAutoTuner.Tradeoff;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner.AutoTuneProfile;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CalibratedAutoTunerTradeoffTest {

    @Test
    public void keepsTheMostSensitiveSettingInsideTheBudgetAtEachSigma() {
        Calibration calibration = new Calibration();
        calibration.measuredMpixFrames = 100;
        calibration.candidates.add(candidate(3.0, 0.2, 0.40));
        calibration.candidates.add(candidate(3.0, 0.5, 0.80));
        calibration.candidates.add(candidate(3.0, 2.0, 0.95));
        calibration.candidates.add(candidate(4.0, 4.0, 0.99));

        Tradeoff tradeoff = CalibratedAutoTuner.buildTradeoff(calibration);
        SigmaChoice highAt3 = choice(tradeoff, AutoTuneProfile.HIGH, 3.0);
        assertTrue(highAt3.withinBudget);
        assertEquals(0.80, highAt3.recoveredFraction, 1e-9);
        assertEquals(0.5, highAt3.falsePositivesPerMpixFrame, 1e-6);

        SigmaChoice highAt4 = choice(tradeoff, AutoTuneProfile.HIGH, 4.0);
        assertTrue(highAt4.evaluated);
        assertFalse(highAt4.withinBudget);
        assertEquals(4.0, highAt4.falsePositivesPerMpixFrame, 1e-6);

        SigmaChoice lowAt3 = choice(tradeoff, AutoTuneProfile.LOW, 3.0);
        assertFalse(lowAt3.withinBudget);
        assertEquals(0.2, lowAt3.falsePositivesPerMpixFrame, 1e-6);

        CalibratedAutoTuner.discardSweep(calibration);
        assertTrue(calibration.candidates.isEmpty());
        assertEquals(0.80, choice(calibration.tradeoff, AutoTuneProfile.HIGH, 3.0).recoveredFraction, 1e-9);
    }

    private static SigmaChoice choice(Tradeoff tradeoff, AutoTuneProfile profile, double sigma) {
        SigmaChoice[] choices = tradeoff.profiles[profile.ordinal()].bySigma;
        for (SigmaChoice choice : choices) {
            if (choice.sigma == sigma) {
                return choice;
            }
        }
        throw new AssertionError("no choice at sigma " + sigma);
    }

    /** Upper estimate matches a large measured area, so the budget comparison is essentially the raw rate. */
    private static Candidate candidate(double sigma, double falsePositives, double recovered) {
        Candidate candidate = new Candidate();
        candidate.sigma = sigma;
        candidate.falsePositivesPerMpixFrame = falsePositives;
        candidate.falsePositiveUpperPerMpixFrame = falsePositives + 0.01;
        candidate.recoveredFraction = recovered;
        candidate.noisePerMpixFrame = falsePositives;
        return candidate;
    }
}
