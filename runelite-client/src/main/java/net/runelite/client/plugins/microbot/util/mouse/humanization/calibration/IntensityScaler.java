package net.runelite.client.plugins.microbot.util.mouse.humanization.calibration;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;
import net.runelite.client.plugins.microbot.util.mouse.humanization.MotionProfile;
import net.runelite.client.plugins.microbot.util.mouse.humanization.MouseProfile;

/**
 * Scales a calibrated baseline {@link MouseProfile} to different activity intensity tiers.
 *
 * <p>The calibrated baseline profile represents MODERATE intensity (1.0x) - the user's natural
 * focused mouse behavior captured during the calibration session. Other intensity tiers scale
 * this baseline up (slower, more hesitant for VERY_LOW and LOW) or down (faster, more precise
 * for HIGH and EXTREME) using per-field multiplier arrays indexed by {@link ActivityIntensity}
 * ordinal.</p>
 *
 * <p>Multiplier array index mapping matches {@link ActivityIntensity} declaration order:
 * <ul>
 *   <li>0 - VERY_LOW</li>
 *   <li>1 - LOW</li>
 *   <li>2 - MODERATE (1.0x - baseline unchanged)</li>
 *   <li>3 - HIGH</li>
 *   <li>4 - EXTREME</li>
 * </ul></p>
 */
@Slf4j
public final class IntensityScaler
{
	// --- Multiplier tables indexed by ActivityIntensity.ordinal() ---
	// VERY_LOW=0, LOW=1, MODERATE=2, HIGH=3, EXTREME=4

	/** Speed is divided by this value - higher divisor produces slower movement. */
	private static final double[] SPEED_DIVISOR = {1.6, 1.2, 1.0, 0.85, 0.70};

	/** Fitts's Law intercept multiplier - higher value increases base movement time. */
	private static final double[] FITTS_INTERCEPT_MULTIPLIER = {1.6, 1.2, 1.0, 0.80, 0.60};

	/** Fitts's Law slope multiplier - higher value increases movement time scaling with distance. */
	private static final double[] FITTS_SLOPE_MULTIPLIER = {1.5, 1.2, 1.0, 0.85, 0.65};

	/** Bezier curve intensity multiplier - higher value produces more curved paths. */
	private static final double[] CURVE_INTENSITY_MULTIPLIER = {1.4, 1.1, 1.0, 0.80, 0.50};

	/** Overshoot probability multiplier - higher value increases overshoot frequency. */
	private static final double[] OVERSHOOT_PROBABILITY_MULTIPLIER = {1.5, 1.2, 1.0, 0.70, 0.35};

	/** Micro-tremor amplitude multiplier - higher value increases hand shake. */
	private static final double[] TREMOR_MULTIPLIER = {1.5, 1.2, 1.0, 0.70, 0.35};

	/** Hesitation probability multiplier - higher value increases mid-movement pauses. */
	private static final double[] HESITATION_PROBABILITY_MULTIPLIER = {1.8, 1.3, 1.0, 0.50, 0.0};

	/** Pause duration multiplier - applies to hesitation, submovement, and dwell timings. */
	private static final double[] PAUSE_DURATION_MULTIPLIER = {1.35, 1.1, 1.0, 0.85, 0.60};

	/** Click hold duration multiplier - higher value produces longer mouse button presses. */
	private static final double[] CLICK_HOLD_MULTIPLIER = {1.3, 1.1, 1.0, 0.85, 0.60};

	/** Pre-click dwell multiplier - higher value produces longer hover time before clicking. */
	private static final double[] PRE_CLICK_DWELL_MULTIPLIER = {1.4, 1.2, 1.0, 0.70, 0.40};

	private IntensityScaler()
	{
		// Non-instantiable utility class
	}

	/**
	 * Creates a new {@link MouseProfile} by scaling the given baseline to the requested intensity tier.
	 *
	 * <p>The baseline profile is expected to represent MODERATE intensity - the user's natural,
	 * focused mouse movement as captured by the calibration system. Multipliers from each field's
	 * corresponding array are applied to produce the scaled profile. Personal characteristics that
	 * do not change with activity intensity (handedness, peak velocity position, overshoot pixel
	 * distances, click drift) are copied unchanged from the baseline.</p>
	 *
	 * <p>All output values are clamped to valid ranges before the scaled profile is returned.</p>
	 *
	 * @param baseline  the calibrated baseline MouseProfile (MODERATE intensity reference)
	 * @param intensity the target activity intensity tier
	 * @return a new MouseProfile scaled to the requested intensity
	 */
	public static MouseProfile scale(MouseProfile baseline, ActivityIntensity intensity)
	{
		log.info("Scaling calibrated profile for intensity {}", intensity);

		int intensityIndex = intensity.ordinal();
		MouseProfile scaled = new MouseProfile();

		// --- Speed (divide by divisor - higher divisor = slower movement) ---
		double[] baselineSpeeds = baseline.getDistanceSpeedPixelsPerMs();
		double[] scaledSpeeds = new double[baselineSpeeds.length];
		for (int bandIndex = 0; bandIndex < scaledSpeeds.length; bandIndex++)
		{
			scaledSpeeds[bandIndex] = baselineSpeeds[bandIndex] / SPEED_DIVISOR[intensityIndex];
		}
		scaled.setDistanceSpeedPixelsPerMs(scaledSpeeds);
		log.debug("Speed scaling: divisor={}, band[0] {} -> {}",
			SPEED_DIVISOR[intensityIndex], baselineSpeeds[0], scaledSpeeds[0]);

		// --- Fitts's Law ---
		double scaledFittsIntercept = baseline.getFittsIntercept() * FITTS_INTERCEPT_MULTIPLIER[intensityIndex];
		double scaledFittsSlope = baseline.getFittsSlope() * FITTS_SLOPE_MULTIPLIER[intensityIndex];
		scaled.setFittsIntercept(scaledFittsIntercept);
		scaled.setFittsSlope(scaledFittsSlope);
		scaled.setFittsVariance(baseline.getFittsVariance()); // personal characteristic - unchanged
		log.debug("Fitts scaling: intercept {} -> {}, slope {} -> {}",
			baseline.getFittsIntercept(), scaledFittsIntercept,
			baseline.getFittsSlope(), scaledFittsSlope);

		// --- Curvature ---
		scaled.setCurveIntensityMin(baseline.getCurveIntensityMin() * CURVE_INTENSITY_MULTIPLIER[intensityIndex]);
		scaled.setCurveIntensityMax(baseline.getCurveIntensityMax() * CURVE_INTENSITY_MULTIPLIER[intensityIndex]);
		scaled.setCurveBiasDirection(baseline.getCurveBiasDirection()); // handedness - unchanged

		// --- Velocity profile ---
		scaled.setPeakVelocityPosition(baseline.getPeakVelocityPosition()); // personal characteristic - unchanged

		// --- Overshoot ---
		scaled.setOvershootProbability(baseline.getOvershootProbability() * OVERSHOOT_PROBABILITY_MULTIPLIER[intensityIndex]);
		scaled.setOvershootMinPixels(baseline.getOvershootMinPixels()); // pixel size unchanged with intensity
		scaled.setOvershootMaxPixels(baseline.getOvershootMaxPixels()); // pixel size unchanged with intensity

		// --- Micro-tremor ---
		scaled.setTremorAmplitude(baseline.getTremorAmplitude() * TREMOR_MULTIPLIER[intensityIndex]);

		// --- Hesitation ---
		scaled.setHesitationProbability(baseline.getHesitationProbability() * HESITATION_PROBABILITY_MULTIPLIER[intensityIndex]);
		scaled.setHesitationMinMs(baseline.getHesitationMinMs() * PAUSE_DURATION_MULTIPLIER[intensityIndex]);
		scaled.setHesitationMaxMs(baseline.getHesitationMaxMs() * PAUSE_DURATION_MULTIPLIER[intensityIndex]);

		// --- Submovement pauses ---
		scaled.setSubmovementPauseMinMs(baseline.getSubmovementPauseMinMs() * PAUSE_DURATION_MULTIPLIER[intensityIndex]);
		scaled.setSubmovementPauseMaxMs(baseline.getSubmovementPauseMaxMs() * PAUSE_DURATION_MULTIPLIER[intensityIndex]);

		// --- Click behavior ---
		scaled.setClickHoldMinMs(baseline.getClickHoldMinMs() * CLICK_HOLD_MULTIPLIER[intensityIndex]);
		scaled.setClickHoldMaxMs(baseline.getClickHoldMaxMs() * CLICK_HOLD_MULTIPLIER[intensityIndex]);
		scaled.setClickDriftPixels(baseline.getClickDriftPixels()); // personal characteristic - unchanged
		scaled.setPreClickDwellMinMs(baseline.getPreClickDwellMinMs() * PRE_CLICK_DWELL_MULTIPLIER[intensityIndex]);
		scaled.setPreClickDwellMaxMs(baseline.getPreClickDwellMaxMs() * PRE_CLICK_DWELL_MULTIPLIER[intensityIndex]);

		// --- Assign MotionProfile for the target intensity tier ---
		switch (intensity)
		{
			case VERY_LOW:
				scaled.setMotionProfile(MotionProfile.casualUser());
				break;
			case LOW:
				scaled.setMotionProfile(MotionProfile.normalGamer());
				break;
			case MODERATE:
				scaled.setMotionProfile(MotionProfile.normalGamer());
				break;
			case HIGH:
				scaled.setMotionProfile(MotionProfile.fastGamer());
				break;
			case EXTREME:
				scaled.setMotionProfile(MotionProfile.superFastGamer());
				break;
			default:
				scaled.setMotionProfile(MotionProfile.normalGamer());
				break;
		}

		clampProfile(scaled);

		log.info("Scaled profile complete for intensity {}", intensity);
		return scaled;
	}

	/**
	 * Enforces sanity bounds on all fields of the given profile, clamping probabilities to [0, 1],
	 * ensuring durations are positive, and guaranteeing that min values do not exceed their
	 * corresponding max values.
	 *
	 * @param profile the profile to clamp in place
	 */
	private static void clampProfile(MouseProfile profile)
	{
		// Probabilities must be in [0, 1]
		profile.setOvershootProbability(clamp(profile.getOvershootProbability(), 0.0, 1.0));
		profile.setHesitationProbability(clamp(profile.getHesitationProbability(), 0.0, 1.0));

		// Amplitudes and pixel values must be positive
		profile.setTremorAmplitude(Math.max(0.1, profile.getTremorAmplitude()));
		profile.setOvershootMinPixels(Math.max(1.0, profile.getOvershootMinPixels()));
		profile.setOvershootMaxPixels(Math.max(profile.getOvershootMinPixels(), profile.getOvershootMaxPixels()));

		// Duration minimums enforced, then max >= min
		profile.setHesitationMinMs(Math.max(10, profile.getHesitationMinMs()));
		profile.setHesitationMaxMs(Math.max(profile.getHesitationMinMs(), profile.getHesitationMaxMs()));
		profile.setSubmovementPauseMinMs(Math.max(5, profile.getSubmovementPauseMinMs()));
		profile.setSubmovementPauseMaxMs(Math.max(profile.getSubmovementPauseMinMs(), profile.getSubmovementPauseMaxMs()));
		profile.setClickHoldMinMs(Math.max(10, profile.getClickHoldMinMs()));
		profile.setClickHoldMaxMs(Math.max(profile.getClickHoldMinMs(), profile.getClickHoldMaxMs()));
		profile.setPreClickDwellMinMs(Math.max(5, profile.getPreClickDwellMinMs()));
		profile.setPreClickDwellMaxMs(Math.max(profile.getPreClickDwellMinMs(), profile.getPreClickDwellMaxMs()));

		// Speed bands must be positive
		double[] speeds = profile.getDistanceSpeedPixelsPerMs();
		for (int bandIndex = 0; bandIndex < speeds.length; bandIndex++)
		{
			speeds[bandIndex] = Math.max(0.01, speeds[bandIndex]);
		}

		// Fitts parameters must be positive
		profile.setFittsIntercept(Math.max(1.0, profile.getFittsIntercept()));
		profile.setFittsSlope(Math.max(1.0, profile.getFittsSlope()));

		// Curve intensity must be non-negative, max >= min
		profile.setCurveIntensityMin(Math.max(0.0, profile.getCurveIntensityMin()));
		profile.setCurveIntensityMax(Math.max(profile.getCurveIntensityMin(), profile.getCurveIntensityMax()));
	}

	/**
	 * Clamps {@code value} to the inclusive range [{@code minimum}, {@code maximum}].
	 *
	 * @param value   the value to clamp
	 * @param minimum the lower bound (inclusive)
	 * @param maximum the upper bound (inclusive)
	 * @return the clamped value
	 */
	private static double clamp(double value, double minimum, double maximum)
	{
		return Math.max(minimum, Math.min(maximum, value));
	}
}
