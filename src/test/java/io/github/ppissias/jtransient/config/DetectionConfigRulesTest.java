package io.github.ppissias.jtransient.config;

import org.junit.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static io.github.ppissias.jtransient.config.DetectionConfigRules.*;
import static org.junit.Assert.*;

/** Verifies complete rule metadata, scoped diagnostics, explicit alignment and legacy-setting compatibility. */
public class DetectionConfigRulesTest {
    /** Detects field-inventory drift and confirms metadata retains the DTO's types and engine defaults. */
    @Test
    public void metadataCoversEveryFieldAndPreservesItsTypeAndDefault() throws Exception {
        Set<String> fields = Arrays.stream(DetectionConfig.class.getFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers())).map(java.lang.reflect.Field::getName).collect(Collectors.toSet());
        assertEquals(107, fields.size());
        assertEquals(fields, metadata().keySet());
        for (var metadata : metadata().values()) {
            var field = DetectionConfig.class.getField(metadata.id());
            assertEquals(field.getType(), metadata.type());
            assertEquals(field.get(new DetectionConfig()), metadata.defaultValue());
        }
        assertTrue(validate(new DetectionConfig(), Operation.CONFIGURATION).isEmpty());
    }

    /** Checks that every floating field produces one identifiable non-finite error without modifying the DTO. */
    @Test
    public void everyFloatingFieldHasOneStructuredNonFiniteError() throws Exception {
        for (var field : DetectionConfig.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != double.class) continue;
            DetectionConfig config = new DetectionConfig();
            field.setDouble(config, Double.NaN);
            var errors = validate(config, Operation.CONFIGURATION).stream().filter(issue -> issue.severity() == Severity.ERROR).toList();
            assertEquals(field.getName(), 1, errors.size());
            assertEquals(RuleId.NON_FINITE, errors.get(0).ruleId());
            assertEquals(java.util.List.of(field.getName()), errors.get(0).fields());
            assertTrue(Double.isNaN(field.getDouble(config)));
        }
    }

    /** Separates active-operation read sets from the all-fields finite-number requirement for persisted profiles. */
    @Test
    public void operationScopesIgnoreUnreadOptionalValuesButProfilesRemainSerializable() {
        DetectionConfig config = new DetectionConfig();
        config.variableScoreSigma = Double.NaN;
        config.rhythmAllowedVariance = Double.POSITIVE_INFINITY;
        snapshot(config, Operation.TRANSIENTS);
        snapshot(config, Operation.MASTER_STACK);
        snapshot(config, Operation.CALIBRATION);
        config.rhythmAllowedVariance = 8;
        snapshot(config, Operation.PIPELINE);
        assertThrows(InvalidConfigurationException.class, () -> snapshot(config, Operation.CONFIGURATION));
        config.enableVariableStarDetection = true;
        var failure = assertThrows(InvalidConfigurationException.class, () -> snapshot(config, Operation.PIPELINE));
        assertEquals("variableScoreSigma", failure.issues().get(0).fields().get(0));
        assertEquals(Stage.PHOTOMETRY, failure.issues().get(0).stage());
    }

    /** Protects historical pair directions and defensive copies of aligned configurations. */
    @Test
    public void legacyAlignmentReturnsDetachedChangesInTheOriginalDirections() {
        DetectionConfig config = new DetectionConfig();
        config.growSigmaMultiplier = 7;
        config.slowMoverMinAxisRatio = 3;
        config.slowMoverMaxAxisRatio = 2;
        config.rhythmStationaryThreshold = 500;
        Alignment aligned = align(config, Operation.CONFIGURATION);
        assertEquals(3, aligned.adjustments().size());
        assertEquals(5, aligned.config().growSigmaMultiplier, 0);
        assertEquals(3, aligned.config().slowMoverMaxAxisRatio, 0);
        assertEquals(400, aligned.config().rhythmStationaryThreshold, 0);
        assertEquals(7, config.growSigmaMultiplier, 0);
        assertEquals(Direction.LOWER, aligned.adjustments().get(0).direction());
        assertEquals("growSigmaMultiplier", aligned.adjustments().get(0).field());
        aligned.config().growSigmaMultiplier = 19;
        assertEquals(5, aligned.config().growSigmaMultiplier, 0);
    }

    /** Keeps new scientific-policy findings advisory until alignment is explicitly requested. */
    @Test
    public void reviewFindingsWarnWithoutRewritingAcceptedPoliciesAndSentinels() {
        DetectionConfig config = new DetectionConfig();
        config.qualityGrowSigmaMultiplier = 8;
        config.masterGrowSigmaMultiplier = -1;
        config.enableVariableStarDetection = true;
        config.photometryMaxStars = 9;
        config.variableLimitedMinAmplitudeMag = 0.01;
        config.variableMinPersistenceFrames = 30;
        config.variableMinFrames = 20;
        config.timeBasedVelocityTolerance = 2;
        config.variableMaxApertureAmplitudeDifference = 2;
        config.variableMinSplitHalfCorrelation = -0.5;
        assertTrue(validate(config, Operation.CONFIGURATION).stream().noneMatch(issue -> issue.severity() == Severity.ERROR));
        assertTrue(has(config, RuleId.QUALITY_GROW_SIGMA));
        assertTrue(has(config, RuleId.PHOTOMETRY_STAR_CAP));
        assertTrue(has(config, RuleId.LIMITED_AMPLITUDE));
        DetectionConfig captured = snapshot(config, Operation.PIPELINE);
        assertEquals(8, captured.qualityGrowSigmaMultiplier, 0);
        assertEquals(-1, captured.masterGrowSigmaMultiplier, 0);
        assertEquals(9, captured.photometryMaxStars);
        assertFalse(validate(config, Operation.CONFIGURATION).stream().anyMatch(issue -> issue.ruleId() == RuleId.NUMERIC_DOMAIN));
        Alignment explicit = align(config, Operation.CONFIGURATION, AlignmentPolicy.REVIEWED_CONSISTENCY);
        assertEquals(5, explicit.config().qualityGrowSigmaMultiplier, 0);
        assertEquals(0.05, explicit.config().variableLimitedMinAmplitudeMag, 0);
        assertEquals(9, explicit.config().photometryMaxStars);
        assertEquals(-1, explicit.config().masterGrowSigmaMultiplier, 0);
    }

    /** Exercises geometric/timed branch applicability, accepted equality and the non-universal rhythm/jump policy. */
    @Test
    public void geometricConflictsDependOnTheActualBranchAndEqualityIsNotRejected() {
        DetectionConfig config = new DetectionConfig();
        config.maxJumpPixels = 1;
        config.maxStarJitter = 2;
        assertFalse(validate(config, Operation.PIPELINE, Timing.AVAILABLE).stream().anyMatch(issue -> issue.ruleId() == RuleId.GEOMETRIC_JITTER_JUMP));
        assertTrue(validate(config, Operation.PIPELINE, Timing.MISSING).stream().anyMatch(issue -> issue.ruleId() == RuleId.GEOMETRIC_JITTER_JUMP));
        config.enableGeometricTrackLinking = true;
        assertTrue(validate(config, Operation.PIPELINE, Timing.AVAILABLE).stream().anyMatch(issue -> issue.ruleId() == RuleId.GEOMETRIC_JITTER_JUMP));
        config.maxStarJitter = 1;
        assertFalse(has(config, RuleId.GEOMETRIC_JITTER_JUMP));
        config.rhythmStationaryThreshold = 1.2;
        snapshot(config, Operation.PIPELINE);
        assertTrue(has(config, RuleId.RHYTHM_STATIONARY_THRESHOLD));
        assertEquals(1.2, align(config, Operation.CONFIGURATION, AlignmentPolicy.REVIEWED_CONSISTENCY).config().rhythmStationaryThreshold, 0);
        config.enableGeometricTrackLinking = false;
        config.maxJumpPixels = Double.NaN;
        snapshot(config, Operation.PIPELINE, Timing.AVAILABLE);
        assertThrows(InvalidConfigurationException.class, () -> snapshot(config, Operation.PIPELINE, Timing.MISSING));
        config.maxJumpPixels = 1;
        config.rhythmAllowedVariance = 1;
        config.rhythmStationaryThreshold = 1;
        config.timeBasedVelocityTolerance = Double.NaN;
        config.streakTimeConsistencyTolerance = Double.NaN;
        snapshot(config, Operation.PIPELINE, Timing.MISSING);
        assertThrows(InvalidConfigurationException.class, () -> snapshot(config, Operation.PIPELINE, Timing.AVAILABLE));
    }

    /** Preserves uncapped/disabled sentinels and independence of thresholds measured on different images. */
    @Test
    public void uncappedPhotometryAndIndependentImagesHaveNoInventedPairRules() {
        DetectionConfig config = new DetectionConfig();
        config.enableVariableStarDetection = true;
        config.masterSigmaMultiplier = 15;
        config.detectionSigmaMultiplier = 3;
        config.growSigmaMultiplier = 2;
        config.maxFwhmRatio = -1;
        config.maxSurfaceBrightnessRatio = 0;
        for (int cap : new int[]{0, -10, 50}) {
            config.photometryMaxStars = cap;
            assertFalse(has(config, RuleId.PHOTOMETRY_STAR_CAP));
            assertTrue(validate(config, Operation.CONFIGURATION).isEmpty());
        }
        config.maxFwhmRatio = 0.5;
        assertTrue(has(config, RuleId.NUMERIC_DOMAIN));
        config.enableVariableStarDetection = false;
        config.photometryMaxStars = 1;
        assertFalse(has(config, RuleId.PHOTOMETRY_STAR_CAP));
    }

    /** Finds a rule by its stable identifier in configuration-boundary diagnostics. */
    private static boolean has(DetectionConfig config, RuleId rule) {
        return validate(config, Operation.CONFIGURATION).stream().anyMatch(issue -> issue.ruleId() == rule);
    }

    /** Checks shared shape fields and distinguishes calibrated/legacy tuner inputs from overridden settings. */
    @Test
    public void sharedExtractionShapeAndTunerOverridesUseTheirActualReadScopes() {
        DetectionConfig config = new DetectionConfig();
        config.streakMinElongation = Double.NaN;
        for (Operation operation : Operation.values()) {
            assertThrows(operation.name(), InvalidConfigurationException.class, () -> snapshot(config, operation));
        }
        config.streakMinElongation = 6;
        config.singleStreakMinPeakSigma = Double.NaN;
        snapshot(config, Operation.EXTRACTION);
        snapshot(config, Operation.TRANSIENTS);
        snapshot(config, Operation.MASTER_STACK);
        snapshot(config, Operation.CALIBRATION);
        assertThrows(InvalidConfigurationException.class, () -> snapshot(config, Operation.PIPELINE));
        config.singleStreakMinPeakSigma = 7;
        config.detectionSigmaMultiplier = Double.NaN;
        config.growSigmaMultiplier = Double.NaN;
        config.masterGrowSigmaMultiplier = Double.NaN;
        config.maxStarJitter = Double.NaN;
        config.maxMaskOverlapFraction = Double.NaN;
        config.minBackgroundDeviationADU = Double.NaN;
        snapshot(config, Operation.CALIBRATION);
        config.qualityGrowSigmaMultiplier = Double.NaN;
        snapshot(config, Operation.CALIBRATION);
        config.masterSigmaMultiplier = Double.NaN;
        assertThrows(InvalidConfigurationException.class, () -> snapshot(config, Operation.CALIBRATION));
    }
}
