package io.github.ppissias.jtransient.config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Owns detection-setting metadata, operation-scoped validation and explicit copy-based alignment.
 *
 * <p>Validation never changes the public {@link DetectionConfig} DTO. Non-finite consumed values are errors;
 * finite domain and consistency findings remain advisory for legacy compatibility. Alignment changes only
 * settings selected by the requested policy and returns structured changes for application-owned presentation.</p>
 *
 * <p>The service is stateless and returned collections are immutable. Callers must synchronize writes that
 * overlap validation or snapshot capture; copying primitive fields does not provide a multi-field transaction.</p>
 */
public final class DetectionConfigRules {
    /** Minimum usable reference-star count required by the photometry ensemble solver. */
    public static final int MIN_ENSEMBLE_STARS = 10;
    /** Selects the read set; CONFIGURATION checks persistence, AUTO_TUNE legacy tuning, and CALIBRATION calibrated tuning. */
    public enum Operation { CONFIGURATION, PIPELINE, TRANSIENTS, MASTER_STACK, AUTO_TUNE, CALIBRATION, EXTRACTION }
    /** Timestamp availability used to scope geometric-linking advice; UNKNOWN retains conditional diagnostics. */
    public enum Timing { UNKNOWN, AVAILABLE, MISSING }
    /** Engine responsibility associated with a setting or diagnostic, independent of GUI page layout. */
    public enum Stage { BACKGROUND, EXTRACTION, QUALITY, REFERENCE, SLOW_MOVERS, TRACKING, RESIDUAL, PHOTOMETRY }
    /** Distinguishes blocking errors from advisory warnings and informational findings. */
    public enum Severity { ERROR, WARNING, INFO }
    /** Whether alignment lowers a pair's first field or raises its second field. */
    public enum Direction { LOWER, RAISE }
    /** Legacy GUI policy preserves six pairs; reviewed consistency adds quality/amplitude policies and excludes rhythm/jump. */
    public enum AlignmentPolicy { LEGACY_GUI, REVIEWED_CONSISTENCY }
    /** Stable rule identifiers for diagnostics, alignment and application-owned messages. */
    public enum RuleId {
        NON_FINITE, NUMERIC_DOMAIN, GROW_SIGMA, MASTER_GROW_SIGMA, SLOW_MOVER_GROW_SIGMA,
        SLOW_MOVER_AXIS_RATIO, MEDIAN_MASK_OVERLAP, RHYTHM_STATIONARY_THRESHOLD, QUALITY_GROW_SIGMA,
        GEOMETRIC_JITTER_JUMP, PHOTOMETRY_STAR_CAP, PHOTOMETRY_GEOMETRY_FACTORS, PHOTOMETRY_EFFECTIVE_GEOMETRY,
        LIMITED_AMPLITUDE, ANOMALY_RESCUE_SUBSUMED, REDUNDANT_MINIMUM_PIXELS
    }
    /** Recommended scalar domains, including sentinel-aware categories; these do not introduce hard rejection bounds. */
    public enum Domain {
        BOOLEAN, FINITE, POSITIVE, NON_NEGATIVE, FRACTION, PERCENTAGE, CORRELATION, ANGLE,
        ELONGATION, STRICT_ELONGATION, DISABLED_RATIO, NONPOSITIVE_UNCAPPED, NONPOSITIVE_DEFAULT,
        MASTER_GROW, ENCODING_LEVELS
    }

    /**
     * Describes one public primitive configuration field without introducing a second settings representation.
     *
     * @param id existing DetectionConfig field name
     * @param type declared primitive type
     * @param stage owning engine responsibility
     * @param domain recommended scalar domain, including legacy sentinel semantics
     * @param defaultValue boxed engine default, not an application-profile override
     */
    public record FieldMetadata(String id, Class<?> type, Stage stage, Domain domain, Object defaultValue) {}

    /**
     * Describes a validation finding using identifiers and values rather than application-specific sentences.
     *
     * @param ruleId violated rule
     * @param severity whether the finding blocks snapshot creation or is advisory
     * @param stage affected engine responsibility
     * @param fields affected configuration field names, in explanation order
     * @param values numeric evidence keyed by field name or a rule-specific derived-value identifier
     */
    public record Issue(RuleId ruleId, Severity severity, Stage stage, List<String> fields, Map<String, Number> values) {
        /** Copies diagnostic collections so their membership cannot change after publication. */
        public Issue {
            fields = List.copyOf(fields);
            values = Map.copyOf(values);
        }
    }

    /**
     * Defines an ordering policy in which the first value should not exceed the second.
     *
     * @param ruleId ordering rule identifier
     * @param firstField lower-side configuration field
     * @param secondField upper-side configuration field
     * @param direction which side moves when explicitly aligned
     * @param stage affected engine responsibility
     * @param legacyAlignment whether the original GUI policy includes this pair
     */
    public record Pair(RuleId ruleId, String firstField, String secondField, Direction direction,
                       Stage stage, boolean legacyAlignment) {
        /**
         * Checks ordering and master-grow sentinel semantics, not stage applicability or numeric validity.
         *
         * @param config settings to inspect without mutation
         * @return false only for a finite, non-sentinel ordering violation; non-finite values are validated separately
         */
        public boolean holds(DetectionConfig config) { return !crossed(config, this); }
    }

    /**
     * Records one explicit alignment change without embedding GUI wording.
     *
     * @param ruleId policy responsible for the change
     * @param stage affected engine responsibility
     * @param field configuration field changed in the aligned copy
     * @param relatedFields configuration fields explaining the relationship
     * @param oldValue original boxed value
     * @param newValue aligned boxed value
     * @param direction whether the changed value was lowered or raised
     */
    public record Adjustment(RuleId ruleId, Stage stage, String field, List<String> relatedFields,
                             Number oldValue, Number newValue, Direction direction) {
        /** Detaches the related-field list from the caller's collection. */
        public Adjustment { relatedFields = List.copyOf(relatedFields); }
    }

    /**
     * Holds an aligned settings snapshot and the changes needed to explain it.
     *
     * @param config aligned settings, defensively cloned on construction and access
     * @param adjustments immutable list of applied changes
     */
    public record Alignment(DetectionConfig config, List<Adjustment> adjustments) {
        /** Captures an independent configuration and immutable adjustment list. */
        public Alignment {
            config = config.clone();
            adjustments = List.copyOf(adjustments);
        }
        /**
         * Returns a fresh copy so a consumer cannot modify this alignment result's captured settings.
         *
         * @return detached aligned configuration
         */
        @Override public DetectionConfig config() { return config.clone(); }
    }

    /** Rejects snapshot creation while preserving structured validation errors for non-GUI callers. */
    public static final class InvalidConfigurationException extends IllegalArgumentException {
        private final List<Issue> issues;
        /**
         * Captures the blocking findings and builds a concise identifier-based exception message.
         *
         * @param issues errors reported for the rejected snapshot
         */
        public InvalidConfigurationException(List<Issue> issues) {
            super("Invalid detection configuration: " + issues.stream()
                    .map(issue -> issue.ruleId() + " " + issue.fields()).toList());
            this.issues = List.copyOf(issues);
        }
        /**
         * Returns the immutable findings retained when the snapshot was rejected.
         *
         * @return structured blocking issues
         */
        public List<Issue> issues() { return issues; }
    }

    private static final Map<String, Field> FIELDS = fields();
    private static final Map<String, FieldMetadata> METADATA = metadataDefinitions();
    private static final Set<String> CALIBRATION_FIELDS = Set.of("bgClippingIterations", "bgClippingFactor",
            "streakMinElongation", "streakMinPixels", "minDetectionPixels", "masterSigmaMultiplier", "masterMinDetectionPixels");
    private static final Set<String> TUNING_QUALITY_FIELDS = Set.of("qualitySigmaMultiplier", "qualityGrowSigmaMultiplier",
            "qualityMinDetectionPixels", "qualityBrightStarMinStars", "qualityBrightStarPeakSigmaOffset",
            "qualityMaxElongationForFwhm", "errorFallbackValue");
    private static final List<Pair> PAIRS = List.of(
            new Pair(RuleId.GROW_SIGMA, "growSigmaMultiplier", "detectionSigmaMultiplier", Direction.LOWER, Stage.EXTRACTION, true),
            new Pair(RuleId.MASTER_GROW_SIGMA, "masterGrowSigmaMultiplier", "masterSigmaMultiplier", Direction.LOWER, Stage.REFERENCE, true),
            new Pair(RuleId.SLOW_MOVER_GROW_SIGMA, "masterSlowMoverGrowSigmaMultiplier", "masterSlowMoverSigmaMultiplier", Direction.LOWER, Stage.SLOW_MOVERS, true),
            new Pair(RuleId.SLOW_MOVER_AXIS_RATIO, "slowMoverMinAxisRatio", "slowMoverMaxAxisRatio", Direction.RAISE, Stage.SLOW_MOVERS, true),
            new Pair(RuleId.MEDIAN_MASK_OVERLAP, "slowMoverMedianSupportOverlapFraction", "slowMoverMedianSupportMaxOverlapFraction", Direction.RAISE, Stage.SLOW_MOVERS, true),
            new Pair(RuleId.RHYTHM_STATIONARY_THRESHOLD, "rhythmStationaryThreshold", "maxJumpPixels", Direction.LOWER, Stage.TRACKING, true),
            new Pair(RuleId.QUALITY_GROW_SIGMA, "qualityGrowSigmaMultiplier", "qualitySigmaMultiplier", Direction.LOWER, Stage.QUALITY, false));

    /** Prevents instantiation of this stateless rules service. */
    private DetectionConfigRules() {}

    /**
     * Returns complete metadata keyed by existing configuration field names.
     *
     * @return immutable field definitions with engine defaults
     */
    public static Map<String, FieldMetadata> metadata() { return METADATA; }
    /**
     * Returns shared ordering definitions in deterministic diagnostic/alignment order.
     *
     * @return immutable pair policies
     */
    public static List<Pair> pairs() { return PAIRS; }

    /**
     * Validates settings with unknown timestamp availability, retaining conditional geometric advice.
     *
     * @param config settings to inspect without mutation
     * @param operation read set to validate
     * @return immutable structured findings
     */
    public static List<Issue> validate(DetectionConfig config, Operation operation) {
        return validate(config, operation, Timing.UNKNOWN);
    }

    /**
     * Diagnoses numeric and relationship issues for the operation and enabled branches without changing settings.
     * CONFIGURATION requires every persisted number to be finite, including disabled preferences.
     *
     * @param config settings to inspect without mutation
     * @param operation read set or persistence boundary to validate
     * @param timing known or unknown timestamp availability
     * @return immutable errors, warnings and informational findings; warnings do not imply rejection
     */
    public static List<Issue> validate(DetectionConfig config, Operation operation, Timing timing) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(timing, "timing");
        List<Issue> issues = new ArrayList<>();
        for (FieldMetadata metadata : METADATA.values()) {
            if (metadata.type() == boolean.class) continue;
            boolean active = active(metadata, config, operation, timing);
            if (!active && operation != Operation.CONFIGURATION) continue;
            Number value = number(config, metadata.id());
            if (!Double.isFinite(value.doubleValue())) {
                issues.add(issue(RuleId.NON_FINITE, Severity.ERROR, metadata.stage(),
                        List.of(metadata.id()), Map.of(metadata.id(), value)));
            } else if (active && !inDomain(metadata.domain(), value.doubleValue())) {
                issues.add(issue(RuleId.NUMERIC_DOMAIN, Severity.WARNING, metadata.stage(),
                        List.of(metadata.id()), Map.of(metadata.id(), value)));
            }
        }
        for (Pair pair : PAIRS) {
            if (active(METADATA.get(pair.firstField()), config, operation, timing)
                    && active(METADATA.get(pair.secondField()), config, operation, timing) && crossed(config, pair)) {
                issues.add(pairIssue(config, pair));
            }
        }
        if (operation == Operation.PIPELINE || operation == Operation.CONFIGURATION) {
            if ((config.enableGeometricTrackLinking || timing != Timing.AVAILABLE)
                    && config.maxStarJitter > config.maxJumpPixels) {
                issues.add(issue(RuleId.GEOMETRIC_JITTER_JUMP, Severity.WARNING, Stage.TRACKING,
                        List.of("maxStarJitter", "maxJumpPixels"), values(config, "maxStarJitter", "maxJumpPixels")));
            }
            if (config.enableVariableStarDetection) {
                int minimum = Math.max(config.linearityMinStars, MIN_ENSEMBLE_STARS);
                if (config.photometryMaxStars > 0 && config.photometryMaxStars < minimum) {
                    issues.add(issue(RuleId.PHOTOMETRY_STAR_CAP, Severity.WARNING, Stage.PHOTOMETRY,
                            List.of("photometryMaxStars", "linearityMinStars"),
                            Map.of("photometryMaxStars", config.photometryMaxStars, "requiredStars", minimum)));
                }
                if (config.photometryAnnulusInnerFwhmFactor <= Math.max(PhotometryGeometry.LARGEST_COMPARISON_FACTOR, config.photometryApertureFwhmFactor)
                        || config.photometryAnnulusOuterFwhmFactor <= config.photometryAnnulusInnerFwhmFactor) {
                    issues.add(issue(RuleId.PHOTOMETRY_GEOMETRY_FACTORS, Severity.WARNING, Stage.PHOTOMETRY,
                            List.of("photometryApertureFwhmFactor", "photometryAnnulusInnerFwhmFactor", "photometryAnnulusOuterFwhmFactor"),
                            values(config, "photometryApertureFwhmFactor", "photometryAnnulusInnerFwhmFactor", "photometryAnnulusOuterFwhmFactor")));
                }
                if (config.variableLimitedMinAmplitudeMag < config.variableMinAmplitudeMag) {
                    issues.add(issue(RuleId.LIMITED_AMPLITUDE, Severity.WARNING, Stage.PHOTOMETRY,
                            List.of("variableMinAmplitudeMag", "variableLimitedMinAmplitudeMag"),
                            values(config, "variableMinAmplitudeMag", "variableLimitedMinAmplitudeMag")));
                }
            }
            if (config.enableAnomalyRescue) {
                if (config.anomalyMinPeakSigmaFloor >= config.anomalyMinPeakSigma) {
                    issues.add(issue(RuleId.ANOMALY_RESCUE_SUBSUMED, Severity.INFO, Stage.TRACKING,
                            List.of("anomalyMinPeakSigmaFloor", "anomalyMinPeakSigma"),
                            values(config, "anomalyMinPeakSigmaFloor", "anomalyMinPeakSigma")));
                }
                if (config.anomalyMinIntegratedPixels < config.anomalyMinPixels) {
                    issues.add(issue(RuleId.REDUNDANT_MINIMUM_PIXELS, Severity.INFO, Stage.TRACKING,
                            List.of("anomalyMinIntegratedPixels", "anomalyMinPixels"),
                            values(config, "anomalyMinIntegratedPixels", "anomalyMinPixels")));
                }
            }
        }
        return List.copyOf(issues);
    }

    /**
     * Captures and validates a private settings copy with unknown timestamp availability.
     *
     * @param config caller-owned settings to capture
     * @param operation read set to validate
     * @return detached configuration, without implicit alignment
     * @throws InvalidConfigurationException if validation reports blocking errors
     */
    public static DetectionConfig snapshot(DetectionConfig config, Operation operation) {
        return snapshot(config, operation, Timing.UNKNOWN);
    }

    /**
     * Clones before validation so subsequent caller edits cannot affect the returned operation settings.
     * Callers must synchronize writes that overlap the primitive-field capture itself.
     *
     * @param config caller-owned settings to capture
     * @param operation read set or persistence boundary to validate
     * @param timing timestamp availability for branch-dependent validation
     * @return detached configuration preserving advisory findings and legacy values
     * @throws InvalidConfigurationException if the captured settings contain error-level findings
     */
    public static DetectionConfig snapshot(DetectionConfig config, Operation operation, Timing timing) {
        DetectionConfig snapshot = Objects.requireNonNull(config, "config").clone();
        List<Issue> errors = validate(snapshot, operation, timing).stream().filter(issue -> issue.severity() == Severity.ERROR).toList();
        if (!errors.isEmpty()) throw new InvalidConfigurationException(errors);
        return snapshot;
    }

    /**
     * Aligns a copy using the original six-pair GUI policy, preserving historical adjustment directions.
     *
     * @param config original settings, never modified
     * @param operation scope to validate and align
     * @return detached aligned settings and structured changes
     * @throws InvalidConfigurationException if the input cannot form a valid snapshot
     */
    public static Alignment align(DetectionConfig config, Operation operation) {
        return align(config, operation, AlignmentPolicy.LEGACY_GUI);
    }

    /**
     * Applies only the requested consistency policy to a validated copy and records every change.
     * Neither policy chooses new jitter/star caps or rewrites persisted annulus factors.
     *
     * @param config original settings, never modified
     * @param operation scope to validate and align
     * @param policy legacy widget behavior or explicit reviewed consistency choices
     * @return detached aligned settings and immutable structured changes
     * @throws InvalidConfigurationException if the input cannot form a valid snapshot
     */
    public static Alignment align(DetectionConfig config, Operation operation, AlignmentPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        DetectionConfig aligned = snapshot(config, operation);
        List<Adjustment> changes = new ArrayList<>();
        for (Pair pair : PAIRS) {
            boolean requested = policy == AlignmentPolicy.LEGACY_GUI ? pair.legacyAlignment()
                    : pair.ruleId() != RuleId.RHYTHM_STATIONARY_THRESHOLD;
            boolean applicable = active(METADATA.get(pair.firstField()), aligned, operation, Timing.UNKNOWN)
                    && active(METADATA.get(pair.secondField()), aligned, operation, Timing.UNKNOWN)
                    || operation == Operation.CONFIGURATION && policy == AlignmentPolicy.LEGACY_GUI;
            if (!requested || !applicable || !crossed(aligned, pair)) continue;
            String field = pair.direction() == Direction.LOWER ? pair.firstField() : pair.secondField();
            String other = pair.direction() == Direction.LOWER ? pair.secondField() : pair.firstField();
            Number before = number(aligned, field);
            Number after = number(aligned, other);
            set(aligned, field, after);
            changes.add(new Adjustment(pair.ruleId(), pair.stage(), field,
                    List.of(pair.firstField(), pair.secondField()), before, number(aligned, field), pair.direction()));
        }
        if (policy == AlignmentPolicy.REVIEWED_CONSISTENCY && aligned.enableVariableStarDetection
                && (operation == Operation.PIPELINE || operation == Operation.CONFIGURATION)
                && aligned.variableLimitedMinAmplitudeMag < aligned.variableMinAmplitudeMag) {
            double before = aligned.variableLimitedMinAmplitudeMag;
            aligned.variableLimitedMinAmplitudeMag = aligned.variableMinAmplitudeMag;
            changes.add(new Adjustment(RuleId.LIMITED_AMPLITUDE, Stage.PHOTOMETRY, "variableLimitedMinAmplitudeMag",
                    List.of("variableMinAmplitudeMag", "variableLimitedMinAmplitudeMag"), before,
                    aligned.variableLimitedMinAmplitudeMag, Direction.RAISE));
        }
        return new Alignment(aligned, changes);
    }

    /** Resolves a field's actual read scope, including tuner overrides, optional branches and timestamp availability. */
    private static boolean active(FieldMetadata metadata, DetectionConfig config, Operation operation, Timing timing) {
        if (operation == Operation.CALIBRATION) return CALIBRATION_FIELDS.contains(metadata.id());
        if (operation == Operation.AUTO_TUNE) return (CALIBRATION_FIELDS.contains(metadata.id())
                && !metadata.id().equals("minDetectionPixels")) || TUNING_QUALITY_FIELDS.contains(metadata.id());
        if (operation == Operation.MASTER_STACK && metadata.stage() == Stage.EXTRACTION) {
            return metadata.id().equals("streakMinElongation") || metadata.id().equals("streakMinPixels");
        }
        if (!stageActive(metadata.stage(), config, operation)) return false;
        if (metadata.id().equals("maxJumpPixels") && operation != Operation.CONFIGURATION
                && !config.enableGeometricTrackLinking && timing == Timing.AVAILABLE) return false;
        if (operation == Operation.PIPELINE && timing == Timing.MISSING
                && (metadata.id().equals("timeBasedVelocityTolerance") || metadata.id().equals("streakTimeConsistencyTolerance"))) return false;
        if (metadata.id().equals("brightStarEccentricitySigmaDeviation") && !config.enableBrightStarEccentricityFilter) return false;
        if (metadata.id().startsWith("anomaly") && !config.enableAnomalyRescue) return false;
        if (metadata.id().equals("faintStreakLineTolerance") && !config.enableFaintStreakFragments) return false;
        if (metadata.id().equals("commonMotionShiftTolerance") && !config.enableCommonMotionRejection) return false;
        if (metadata.id().startsWith("localActivityCluster") && !config.enableLocalActivityClusters) return false;
        return true;
    }

    /** Applies coarse operation/stage eligibility before field-specific consumption checks. */
    private static boolean stageActive(Stage stage, DetectionConfig config, Operation operation) {
        return switch (stage) {
            case BACKGROUND -> true;
            case EXTRACTION -> operation != Operation.MASTER_STACK;
            case QUALITY -> operation != Operation.EXTRACTION;
            case REFERENCE -> operation == Operation.CONFIGURATION || operation == Operation.PIPELINE
                    || operation == Operation.TRANSIENTS || operation == Operation.AUTO_TUNE;
            case SLOW_MOVERS -> config.enableSlowMoverDetection && (operation == Operation.CONFIGURATION || operation == Operation.PIPELINE);
            case TRACKING -> operation == Operation.CONFIGURATION || operation == Operation.PIPELINE;
            case RESIDUAL -> config.enableResidualTransientAnalysis && (operation == Operation.CONFIGURATION || operation == Operation.PIPELINE);
            case PHOTOMETRY -> config.enableVariableStarDetection && (operation == Operation.CONFIGURATION || operation == Operation.PIPELINE);
        };
    }

    /** Detects a finite pair inversion while preserving the automatic nonpositive master-grow sentinel. */
    private static boolean crossed(DetectionConfig config, Pair pair) {
        double first = number(config, pair.firstField()).doubleValue();
        double second = number(config, pair.secondField()).doubleValue();
        return Double.isFinite(first) && Double.isFinite(second)
                && !(pair.ruleId() == RuleId.MASTER_GROW_SIGMA && first <= 0) && first > second;
    }

    /** Converts a crossed ordering policy into an advisory finding with both original values. */
    private static Issue pairIssue(DetectionConfig config, Pair pair) {
        return issue(pair.ruleId(), Severity.WARNING, pair.stage(), List.of(pair.firstField(), pair.secondField()),
                values(config, pair.firstField(), pair.secondField()));
    }

    /** Builds a structured finding through the record's defensive-copy boundary. */
    private static Issue issue(RuleId rule, Severity severity, Stage stage, List<String> fields, Map<String, Number> values) {
        return new Issue(rule, severity, stage, fields, values);
    }

    /** Collects numeric field evidence without duplicating reflection access in individual rules. */
    private static Map<String, Number> values(DetectionConfig config, String... fields) {
        Map<String, Number> values = new LinkedHashMap<>();
        for (String field : fields) values.put(field, number(config, field));
        return values;
    }

    /** Reads a known numeric DTO field through the validated field registry. */
    private static Number number(DetectionConfig config, String name) {
        try { return (Number) FIELDS.get(name).get(config); }
        catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }

    /** Writes an alignment value to the private copy while retaining the field's primitive type. */
    private static void set(DetectionConfig config, String name, Number value) {
        try {
            Field field = FIELDS.get(name);
            if (field.getType() == int.class) field.setInt(config, value.intValue());
            else field.setDouble(config, value.doubleValue());
        } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }

    /** Tests a finite scalar against its recommended domain, leaving sentinel-only categories unrestricted. */
    private static boolean inDomain(Domain domain, double value) {
        return switch (domain) {
            case POSITIVE -> value > 0;
            case NON_NEGATIVE -> value >= 0;
            case FRACTION -> value >= 0 && value <= 1;
            case PERCENTAGE -> value >= 0 && value <= 100;
            case CORRELATION -> value >= -1 && value <= 1;
            case ANGLE -> value >= 0 && value <= 180;
            case ELONGATION -> value >= 1;
            case STRICT_ELONGATION -> value > 1;
            case DISABLED_RATIO -> value <= 0 || value >= 1;
            case ENCODING_LEVELS -> value >= 0 && value <= 65_536;
            default -> true;
        };
    }

    /** Indexes public instance fields once so numeric rules use the existing DTO names consistently. */
    private static Map<String, Field> fields() {
        Map<String, Field> fields = new LinkedHashMap<>();
        for (Field field : DetectionConfig.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())) fields.put(field.getName(), field);
        }
        return Collections.unmodifiableMap(fields);
    }

    /** Defines all field domains/stages and fails initialization if the metadata inventory drifts from the DTO. */
    private static Map<String, FieldMetadata> metadataDefinitions() {
        Map<String, FieldMetadata> metadata = new LinkedHashMap<>();
        add(metadata, Stage.BACKGROUND, Domain.POSITIVE, "bgClippingIterations bgClippingFactor");
        add(metadata, Stage.EXTRACTION, Domain.POSITIVE, "detectionSigmaMultiplier growSigmaMultiplier minDetectionPixels streakMinPixels");
        add(metadata, Stage.EXTRACTION, Domain.ELONGATION, "streakMinElongation");
        add(metadata, Stage.TRACKING, Domain.POSITIVE, "singleStreakMinPeakSigma");
        add(metadata, Stage.TRACKING, Domain.NON_NEGATIVE, "faintStreakLineTolerance");
        add(metadata, Stage.EXTRACTION, Domain.BOOLEAN, "enableBinaryStarLikeStreakShapeVeto enableFaintStreakFragments");
        add(metadata, Stage.REFERENCE, Domain.POSITIVE, "masterSigmaMultiplier masterMinDetectionPixels");
        add(metadata, Stage.REFERENCE, Domain.MASTER_GROW, "masterGrowSigmaMultiplier");
        add(metadata, Stage.SLOW_MOVERS, Domain.BOOLEAN, "enableSlowMoverDetection");
        add(metadata, Stage.SLOW_MOVERS, Domain.POSITIVE, "masterSlowMoverMinPixels masterSlowMoverSigmaMultiplier masterSlowMoverGrowSigmaMultiplier");
        add(metadata, Stage.SLOW_MOVERS, Domain.ELONGATION, "slowMoverMinAxisRatio slowMoverMaxAxisRatio");
        add(metadata, Stage.SLOW_MOVERS, Domain.FRACTION, "slowMoverMinFillFactor slowMoverMedianSupportOverlapFraction slowMoverMedianSupportMaxOverlapFraction");
        add(metadata, Stage.SLOW_MOVERS, Domain.PERCENTAGE, "slowMoverMinFrameSupport slowMoverMaxStationaryLikelihood");
        add(metadata, Stage.QUALITY, Domain.POSITIVE, "qualitySigmaMultiplier qualityGrowSigmaMultiplier qualityMinDetectionPixels qualityBrightStarMinStars errorFallbackValue minFramesForAnalysis zeroSigmaFallback");
        add(metadata, Stage.QUALITY, Domain.FINITE, "qualityBrightStarPeakSigmaOffset");
        add(metadata, Stage.QUALITY, Domain.STRICT_ELONGATION, "qualityMaxElongationForFwhm");
        add(metadata, Stage.QUALITY, Domain.NON_NEGATIVE, "minBackgroundDeviationADU minEccentricityEnvelope minBrightStarEccentricityEnvelope minFwhmEnvelope starCountSigmaDeviation fwhmSigmaDeviation eccentricitySigmaDeviation brightStarEccentricitySigmaDeviation backgroundSigmaDeviation");
        add(metadata, Stage.QUALITY, Domain.BOOLEAN, "enableBrightStarEccentricityFilter");
        add(metadata, Stage.REFERENCE, Domain.NON_NEGATIVE, "maxStarJitter");
        add(metadata, Stage.REFERENCE, Domain.FRACTION, "maxMaskOverlapFraction");
        add(metadata, Stage.TRACKING, Domain.NON_NEGATIVE, "maxJumpPixels predictionTolerance timeBasedVelocityTolerance streakTimeConsistencyTolerance rhythmStationaryThreshold rhythmAllowedVariance commonMotionShiftTolerance");
        add(metadata, Stage.TRACKING, Domain.ANGLE, "angleToleranceDegrees");
        add(metadata, Stage.TRACKING, Domain.BOOLEAN, "strictExposureKinematics enableGeometricTrackLinking enableAnomalyRescue enableCameraFixedRejection enableCommonMotionRejection");
        add(metadata, Stage.TRACKING, Domain.NONPOSITIVE_DEFAULT, "trackMinFrameRatio absoluteMaxPointsRequired");
        add(metadata, Stage.TRACKING, Domain.DISABLED_RATIO, "maxFwhmRatio maxSurfaceBrightnessRatio");
        add(metadata, Stage.TRACKING, Domain.FRACTION, "rhythmMinConsistencyRatio");
        add(metadata, Stage.TRACKING, Domain.POSITIVE, "anomalyMinPixels anomalyMinPeakSigma anomalyMinIntegratedPixels anomalyMinIntegratedSigma");
        add(metadata, Stage.TRACKING, Domain.NON_NEGATIVE, "anomalyMinPeakSigmaFloor");
        add(metadata, Stage.RESIDUAL, Domain.BOOLEAN, "enableResidualTransientAnalysis enableLocalRescueCandidates enableLocalActivityClusters");
        add(metadata, Stage.RESIDUAL, Domain.NON_NEGATIVE, "localActivityClusterRadiusPixels");
        add(metadata, Stage.RESIDUAL, Domain.POSITIVE, "localActivityClusterMinFrames");
        add(metadata, Stage.PHOTOMETRY, Domain.BOOLEAN, "enableVariableStarDetection photometryFitPlane");
        add(metadata, Stage.PHOTOMETRY, Domain.NONPOSITIVE_UNCAPPED, "photometryMaxStars");
        add(metadata, Stage.PHOTOMETRY, Domain.FINITE, "photometryMinSnr");
        add(metadata, Stage.PHOTOMETRY, Domain.ELONGATION, "photometryMaxElongation");
        add(metadata, Stage.PHOTOMETRY, Domain.POSITIVE, "photometryApertureFwhmFactor photometryAnnulusInnerFwhmFactor photometryAnnulusOuterFwhmFactor linearityMinStars variableMinFrames variableNoiseModelNeighbors variableMinPersistenceFrames");
        add(metadata, Stage.PHOTOMETRY, Domain.FRACTION, "photometrySaturationFraction linearityMaxFloorClippedFraction linearityMaxSlopeTrackingCorrelation linearityMaxFailingFrameFraction variableMaxSystematicsCorrelation variableMaxLocalCorrelation");
        add(metadata, Stage.PHOTOMETRY, Domain.NON_NEGATIVE, "photometryMaxRegistrationSpreadPixels linearityMaxConcentrationDrift linearityMinRangeMag linearityMaxFrameSlope linearityMinZeroPointRangeMag variableMinSpanMinutes variableScoreSigma variableMinAmplitudeMag variableLimitedMinAmplitudeMag variableAmplitudeNoiseFactor variableMaxApertureAmplitudeDifference variableSystematicsResponseFactor variableLocalRadiusPixels");
        add(metadata, Stage.PHOTOMETRY, Domain.ENCODING_LEVELS, "linearityMinDistinctLevels");
        add(metadata, Stage.PHOTOMETRY, Domain.CORRELATION, "variableMinSplitHalfCorrelation");
        if (!metadata.keySet().equals(FIELDS.keySet())) {
            throw new IllegalStateException("DetectionConfig metadata must cover every instance field exactly once.");
        }
        return Collections.unmodifiableMap(metadata);
    }

    /** Registers named fields with their actual types/defaults, rejecting unknown or duplicate definitions. */
    private static void add(Map<String, FieldMetadata> metadata, Stage stage, Domain domain, String names) {
        DetectionConfig defaults = new DetectionConfig();
        for (String name : names.split(" ")) {
            Field field = Objects.requireNonNull(FIELDS.get(name), name);
            try {
                if (metadata.put(name, new FieldMetadata(name, field.getType(), stage, domain, field.get(defaults))) != null) {
                    throw new IllegalStateException("Duplicate detection field metadata: " + name);
                }
            } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
        }
    }
}
