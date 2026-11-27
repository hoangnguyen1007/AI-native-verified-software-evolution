package com.evolution.analysis.assessment.v2;

import com.evolution.analysis.contract.common.ContractChecks;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic 4-anchor piecewise-linear calibration curve mapping a raw metric value
 * to a normalized [0, 100] architectural quality score using exact BigDecimal arithmetic.
 *
 * <p>Anchors map monotonically as:
 * <ul>
 *   <li>{@code ideal}      -&gt; 100</li>
 *   <li>{@code acceptable} -&gt; 75</li>
 *   <li>{@code poor}       -&gt; 35</li>
 *   <li>{@code critical}   -&gt; 0</li>
 * </ul>
 */
public record CalibrationCurve(
        Direction direction,
        BigDecimal ideal,
        BigDecimal acceptable,
        BigDecimal poor,
        BigDecimal critical,
        Optional<BigDecimal> nonZeroCap) {

    public static final int SCALE = 12;
    public static final int DISPLAY_SCALE = 2;
    public static final MathContext SQRT_CONTEXT = new MathContext(24, RoundingMode.HALF_EVEN);
    public static final BigDecimal HUNDRED = new BigDecimal("100");
    public static final BigDecimal SCORE_ACCEPTABLE = new BigDecimal("75");
    public static final BigDecimal SCORE_POOR = new BigDecimal("35");
    private static final BigDecimal SPAN_IDEAL_ACCEPTABLE = new BigDecimal("25");
    private static final BigDecimal SPAN_ACCEPTABLE_POOR = new BigDecimal("40");
    private static final BigDecimal SPAN_POOR_CRITICAL = new BigDecimal("35");

    public enum Direction { LOWER_IS_BETTER, HIGHER_IS_BETTER }

    public CalibrationCurve {
        Objects.requireNonNull(direction, "direction");
        ideal = normalize(Objects.requireNonNull(ideal, "ideal"));
        acceptable = normalize(Objects.requireNonNull(acceptable, "acceptable"));
        poor = normalize(Objects.requireNonNull(poor, "poor"));
        critical = normalize(Objects.requireNonNull(critical, "critical"));
        Objects.requireNonNull(nonZeroCap, "nonZeroCap");
        nonZeroCap = nonZeroCap.map(v -> {
            BigDecimal n = normalize(v);
            if (n.signum() < 0 || n.compareTo(HUNDRED) > 0) {
                throw new IllegalArgumentException("nonZeroCap must lie in [0, 100]");
            }
            return n;
        });
        if (direction == Direction.LOWER_IS_BETTER) {
            if (!(ideal.compareTo(acceptable) < 0 && acceptable.compareTo(poor) < 0 && poor.compareTo(critical) < 0)) {
                throw new IllegalArgumentException("LOWER_IS_BETTER anchors must satisfy ideal < acceptable < poor < critical");
            }
        } else {
            if (!(ideal.compareTo(acceptable) > 0 && acceptable.compareTo(poor) > 0 && poor.compareTo(critical) > 0)) {
                throw new IllegalArgumentException("HIGHER_IS_BETTER anchors must satisfy ideal > acceptable > poor > critical");
            }
        }
    }

    public static CalibrationCurve lowerIsBetter(String ideal, String acceptable, String poor, String critical) {
        return new CalibrationCurve(Direction.LOWER_IS_BETTER,
                new BigDecimal(ideal), new BigDecimal(acceptable), new BigDecimal(poor), new BigDecimal(critical),
                Optional.empty());
    }

    public static CalibrationCurve lowerIsBetter(String ideal, String acceptable, String poor, String critical, String nonZeroCap) {
        return new CalibrationCurve(Direction.LOWER_IS_BETTER,
                new BigDecimal(ideal), new BigDecimal(acceptable), new BigDecimal(poor), new BigDecimal(critical),
                Optional.of(new BigDecimal(nonZeroCap)));
    }

    public static CalibrationCurve higherIsBetter(String ideal, String acceptable, String poor, String critical) {
        return new CalibrationCurve(Direction.HIGHER_IS_BETTER,
                new BigDecimal(ideal), new BigDecimal(acceptable), new BigDecimal(poor), new BigDecimal(critical),
                Optional.empty());
    }

    /** Evaluates the curve without applying non-zero defect cap. */
    public BigDecimal evaluate(BigDecimal rawValue) {
        return evaluate(rawValue, false);
    }

    /**
     * Evaluates the curve at {@code rawValue}. When {@code hasDefect} is true and
     * {@code nonZeroCap} is present, caps the score so non-zero defects never score 100.
     */
    public BigDecimal evaluate(BigDecimal rawValue, boolean hasDefect) {
        Objects.requireNonNull(rawValue, "rawValue");
        BigDecimal score;
        if (direction == Direction.LOWER_IS_BETTER) {
            if (rawValue.compareTo(ideal) <= 0) {
                score = HUNDRED;
            } else if (rawValue.compareTo(acceptable) <= 0) {
                score = HUNDRED.subtract(SPAN_IDEAL_ACCEPTABLE.multiply(
                        divide(rawValue.subtract(ideal), acceptable.subtract(ideal))));
            } else if (rawValue.compareTo(poor) <= 0) {
                score = SCORE_ACCEPTABLE.subtract(SPAN_ACCEPTABLE_POOR.multiply(
                        divide(rawValue.subtract(acceptable), poor.subtract(acceptable))));
            } else if (rawValue.compareTo(critical) <= 0) {
                score = SCORE_POOR.subtract(SPAN_POOR_CRITICAL.multiply(
                        divide(rawValue.subtract(poor), critical.subtract(poor))));
            } else {
                score = BigDecimal.ZERO;
            }
        } else {
            if (rawValue.compareTo(ideal) >= 0) {
                score = HUNDRED;
            } else if (rawValue.compareTo(acceptable) >= 0) {
                score = SCORE_ACCEPTABLE.add(SPAN_IDEAL_ACCEPTABLE.multiply(
                        divide(rawValue.subtract(acceptable), ideal.subtract(acceptable))));
            } else if (rawValue.compareTo(poor) >= 0) {
                score = SCORE_POOR.add(SPAN_ACCEPTABLE_POOR.multiply(
                        divide(rawValue.subtract(poor), acceptable.subtract(poor))));
            } else if (rawValue.compareTo(critical) >= 0) {
                score = SPAN_POOR_CRITICAL.multiply(
                        divide(rawValue.subtract(critical), poor.subtract(critical)));
            } else {
                score = BigDecimal.ZERO;
            }
        }
        score = clamp(round(score, SCALE), BigDecimal.ZERO, HUNDRED);
        if (hasDefect && nonZeroCap.isPresent()) {
            score = score.min(nonZeroCap.orElseThrow());
        }
        return normalize(score);
    }

    public static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() == 0) {
            throw new ArithmeticException("Division by zero");
        }
        return normalize(numerator.divide(denominator, SCALE, RoundingMode.HALF_EVEN));
    }

    public static BigDecimal divide(long numerator, long denominator) {
        return divide(BigDecimal.valueOf(numerator), BigDecimal.valueOf(denominator));
    }

    public static BigDecimal round(BigDecimal value, int scale) {
        return normalize(value.setScale(scale, RoundingMode.HALF_EVEN));
    }

    public static BigDecimal roundDisplay(BigDecimal value) {
        return normalize(value.setScale(DISPLAY_SCALE, RoundingMode.HALF_EVEN));
    }

    public static BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value.compareTo(min) < 0) return normalize(min);
        if (value.compareTo(max) > 0) return normalize(max);
        return normalize(value);
    }

    public static BigDecimal normalize(BigDecimal value) {
        Objects.requireNonNull(value, "value");
        return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    /** Exact decimal square root via BigDecimal.sqrt rounded to canonical scale. */
    public static BigDecimal sqrt(BigDecimal value) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException("Cannot compute square root of negative number");
        }
        if (value.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return round(value.sqrt(SQRT_CONTEXT), SCALE);
    }

    /** JLS-guaranteed cross-platform deterministic log2(n) converted to canonical BigDecimal. */
    public static BigDecimal strictLog2(int n) {
        if (n <= 0) throw new IllegalArgumentException("log2 requires positive input");
        if (n == 1) return BigDecimal.ZERO;
        double val = StrictMath.log(n) / StrictMath.log(2.0);
        return round(BigDecimal.valueOf(val), SCALE);
    }

    /** JLS-guaranteed cross-platform deterministic log5(n) converted to canonical BigDecimal. */
    public static BigDecimal strictLog5(int n) {
        if (n <= 0) throw new IllegalArgumentException("log5 requires positive input");
        if (n == 1) return BigDecimal.ZERO;
        double val = StrictMath.log(n) / StrictMath.log(5.0);
        return round(BigDecimal.valueOf(val), SCALE);
    }

    /** JLS-guaranteed cross-platform deterministic log10(n) converted to canonical BigDecimal. */
    public static BigDecimal strictLog10(int n) {
        if (n <= 0) throw new IllegalArgumentException("log10 requires positive input");
        if (n == 1) return BigDecimal.ZERO;
        double val = StrictMath.log10(n);
        return round(BigDecimal.valueOf(val), SCALE);
    }

    /** Lakos balanced binary tree baseline: CCD_balanced(N) = (N + 1) * log2(N + 1) - N. */
    public static BigDecimal balancedBinaryTreeCcd(int n) {
        if (n < 1) throw new IllegalArgumentException("N must be >= 1");
        if (n == 1) return BigDecimal.ONE;
        BigDecimal nPlusOne = BigDecimal.valueOf(n + 1L);
        return normalize(nPlusOne.multiply(strictLog2(n + 1)).subtract(BigDecimal.valueOf(n)));
    }

    static String requireId(String id, String label) {
        return ContractChecks.namespacedId(id, label);
    }
}
