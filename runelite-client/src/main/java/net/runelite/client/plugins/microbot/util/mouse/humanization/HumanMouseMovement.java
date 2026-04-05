package net.runelite.client.plugins.microbot.util.mouse.humanization;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates human-like mouse movement paths based on peer-reviewed HCI research.
 *
 * Key models implemented:
 * - Fitts's Law (Shannon formulation) for movement duration
 * - Minimum Jerk Trajectory for bell-shaped velocity profiles
 * - Cubic Bezier curves for natural path curvature
 * - Submovement decomposition (ballistic phase + corrective phase)
 * - Gaussian endpoint scatter and micro-tremor noise
 * - Asymmetric speed profile (peak at ~40% of duration, not 50%)
 *
 * Sources:
 * - Fitts (1954), Card et al. (1978), MacKenzie (1992) - Fitts's Law
 * - Flash & Hogan (1985) - Minimum Jerk Trajectory
 * - Meyer et al. (1988) - Stochastic Optimized Submovement Model
 */
public class HumanMouseMovement
{
	private static final double PEAK_VELOCITY_RATIO = 1.875;

	private static final double BEZIER_CONTROL1_POSITION_MIN = 0.25;
	private static final double BEZIER_CONTROL1_POSITION_MAX = 0.40;
	private static final double BEZIER_CONTROL2_POSITION_MIN = 0.60;
	private static final double BEZIER_CONTROL2_POSITION_MAX = 0.75;

	private static final double SUBMOVEMENT_PAUSE_PROBABILITY = 0.50;

	private static final double PRE_CLICK_DWELL_MIN_MS = 80;
	private static final double PRE_CLICK_DWELL_MAX_MS = 250;

	private static final double DEFAULT_TARGET_WIDTH = 30.0;
	private static final double STEP_SIZE_PIXELS = 1.5;

	private HumanMouseMovement()
	{
	}

	/**
	 * Distance-based scaling factors derived from HCI research.
	 * These scale the profile parameters based on movement distance so that
	 * short movements behave differently from long movements.
	 *
	 * Research basis:
	 * - Short moves (<100px): near-straight, fast flick, MAD ~3% of distance
	 * - Medium moves (100-400px): slight curve, MAD ~5-8% of distance
	 * - Long moves (400px+): clear curvature, MAD ~8-12%, submovements visible
	 * - Overshoot increases with distance (rare for short, common for long)
	 * - Tremor is constant but more visible at slow speeds (near target)
	 * - Hesitation only occurs on longer, more difficult movements
	 */
	private static double getDistanceCurveScale(double distance)
	{
		if (distance < 50) return 0.15;
		if (distance < 100) return 0.35;
		if (distance < 200) return 0.6;
		if (distance < 400) return 0.85;
		return 1.0;
	}

	private static double getDistanceOvershootScale(double distance)
	{
		if (distance < 80) return 0.0;
		if (distance < 150) return 0.2;
		if (distance < 300) return 0.5;
		if (distance < 500) return 0.8;
		return 1.0;
	}

	private static double getDistanceTremorScale(double distance)
	{
		if (distance < 30) return 0.2;
		if (distance < 100) return 0.5;
		if (distance < 200) return 0.8;
		return 1.0;
	}

	private static double getDistanceHesitationScale(double distance)
	{
		if (distance < 100) return 0.0;
		if (distance < 200) return 0.2;
		if (distance < 400) return 0.6;
		return 1.0;
	}

	private static double getDistanceSubmovementScale(double distance)
	{
		// Short movements are single ballistic flicks with no submovement pause
		if (distance < 60) return 0.0;
		if (distance < 120) return 0.3;
		if (distance < 250) return 0.6;
		return 1.0;
	}

	private static double getDistanceBallisticFraction(double distance)
	{
		// Short movements: the ballistic phase covers nearly all the distance
		// Long movements: ballistic phase covers 85-90%, then corrective
		if (distance < 60) return 0.97;
		if (distance < 120) return 0.95;
		if (distance < 250) return 0.92;
		return 0.85 + ThreadLocalRandom.current().nextDouble() * 0.10;
	}

	/**
	 * A point along the mouse path with timing information.
	 * The delayAfterMillis field indicates how long to wait after dispatching
	 * a mouse event at this position before moving to the next point.
	 */
	public static class TimedPoint
	{
		public final int positionX;
		public final int positionY;
		public final double delayAfterMillis;

		public TimedPoint(int positionX, int positionY, double delayAfterMillis)
		{
			this.positionX = positionX;
			this.positionY = positionY;
			this.delayAfterMillis = delayAfterMillis;
		}
	}

	/**
	 * Generates a complete human-like mouse path with per-step timing using default parameters.
	 * Delegates to the profile-aware overload with the default MouseProfile.
	 *
	 * @param startX starting X coordinate
	 * @param startY starting Y coordinate
	 * @param endX target X coordinate
	 * @param endY target Y coordinate
	 * @return list of timed points along the path
	 */
	public static List<TimedPoint> generateTimedPath(int startX, int startY, int endX, int endY)
	{
		return generateTimedPath(startX, startY, endX, endY, MouseProfile.getDefault());
	}

	/**
	 * Generates a complete human-like mouse path with per-step timing, personalized
	 * by the given MouseProfile.
	 *
	 * The path includes:
	 * 1. A ballistic phase covering ~85-95% of the distance
	 * 2. An optional pause between phases
	 * 3. A corrective phase for the remaining distance
	 * 4. Optional overshoot and micro-correction
	 *
	 * @param startX starting X coordinate
	 * @param startY starting Y coordinate
	 * @param endX target X coordinate
	 * @param endY target Y coordinate
	 * @param profile the mouse profile containing personalized movement parameters
	 * @return list of timed points along the path
	 */
	public static List<TimedPoint> generateTimedPath(int startX, int startY, int endX, int endY,
		MouseProfile profile)
	{
		List<TimedPoint> completePath = new ArrayList<>();
		double totalDistance = Math.hypot(endX - startX, endY - startY);

		if (totalDistance < 3)
		{
			completePath.add(new TimedPoint(endX, endY, 0));
			return completePath;
		}

		// Scale all parameters by distance using research-based factors
		double curveScale = getDistanceCurveScale(totalDistance);
		double overshootScale = getDistanceOvershootScale(totalDistance);
		double submovementScale = getDistanceSubmovementScale(totalDistance);

		double ballisticFraction = getDistanceBallisticFraction(totalDistance);
		int ballisticEndX = startX + (int)((endX - startX) * ballisticFraction);
		int ballisticEndY = startY + (int)((endY - startY) * ballisticFraction);

		double ballisticDistance = totalDistance * ballisticFraction;
		long ballisticDurationMs = calculateFittsTime(ballisticDistance, DEFAULT_TARGET_WIDTH * 3, profile);

		List<TimedPoint> ballisticPhase = generateBezierPhase(
			startX, startY, ballisticEndX, ballisticEndY, ballisticDurationMs, profile, curveScale
		);
		completePath.addAll(ballisticPhase);

		// Submovement pause: scaled by distance (short moves = no pause)
		double pauseProbability = SUBMOVEMENT_PAUSE_PROBABILITY * submovementScale;
		if (ThreadLocalRandom.current().nextDouble() < pauseProbability)
		{
			long pauseMinMs = (long) profile.getSubmovementPauseMinMs();
			long pauseMaxMs = (long) profile.getSubmovementPauseMaxMs();
			long pauseMs = ThreadLocalRandom.current().nextLong(pauseMinMs, pauseMaxMs + 1);
			if (!completePath.isEmpty())
			{
				TimedPoint lastPoint = completePath.get(completePath.size() - 1);
				completePath.set(completePath.size() - 1,
					new TimedPoint(lastPoint.positionX, lastPoint.positionY, lastPoint.delayAfterMillis + pauseMs));
			}
		}

		// Overshoot: scaled by distance (never for short moves)
		// motionProfile.getOvershootCount() acts as an enable/disable gate - 0 means overshoots are fully disabled
		int maxOvershoots = profile.getMotionProfile().getOvershootCount();
		boolean shouldOvershoot = maxOvershoots > 0
			&& ThreadLocalRandom.current().nextDouble() < (profile.getOvershootProbability() * overshootScale);

		if (shouldOvershoot)
		{
			int overshootMinPixels = (int) profile.getOvershootMinPixels();
			int overshootMaxPixels = (int) profile.getOvershootMaxPixels();
			double overshootDistance = ThreadLocalRandom.current().nextInt(overshootMinPixels, overshootMaxPixels + 1);
			double directionX = (endX - startX) / totalDistance;
			double directionY = (endY - startY) / totalDistance;

			double perpOffsetX = -directionY * ThreadLocalRandom.current().nextGaussian() * 3;
			double perpOffsetY = directionX * ThreadLocalRandom.current().nextGaussian() * 3;

			int overshootX = endX + (int)(directionX * overshootDistance + perpOffsetX);
			int overshootY = endY + (int)(directionY * overshootDistance + perpOffsetY);

			double correctiveDistance1 = Math.hypot(overshootX - ballisticEndX, overshootY - ballisticEndY);
			long corrective1DurationMs = calculateFittsTime(correctiveDistance1, DEFAULT_TARGET_WIDTH, profile);
			// Corrective phases use minimal curvature
			List<TimedPoint> overshootPhase = generateBezierPhase(
				ballisticEndX, ballisticEndY, overshootX, overshootY, corrective1DurationMs, profile, 0.3
			);
			completePath.addAll(overshootPhase);

			long correctionPauseMs = ThreadLocalRandom.current().nextLong(30, 80);
			if (!completePath.isEmpty())
			{
				TimedPoint lastPoint = completePath.get(completePath.size() - 1);
				completePath.set(completePath.size() - 1,
					new TimedPoint(lastPoint.positionX, lastPoint.positionY, lastPoint.delayAfterMillis + correctionPauseMs));
			}

			double correctionDistance = Math.hypot(endX - overshootX, endY - overshootY);
			long correctionDurationMs = calculateFittsTime(correctionDistance, DEFAULT_TARGET_WIDTH, profile);
			List<TimedPoint> correctionPhase = generateBezierPhase(
				overshootX, overshootY, endX, endY, correctionDurationMs, profile, 0.2
			);
			completePath.addAll(correctionPhase);
		}
		else
		{
			double correctiveDistance = Math.hypot(endX - ballisticEndX, endY - ballisticEndY);
			long correctiveDurationMs = calculateFittsTime(correctiveDistance, DEFAULT_TARGET_WIDTH, profile);
			// Corrective phase: less curvature than ballistic
			double correctiveCurveScale = curveScale * 0.3;
			List<TimedPoint> correctivePhase = generateBezierPhase(
				ballisticEndX, ballisticEndY, endX, endY, correctiveDurationMs, profile, correctiveCurveScale
			);
			completePath.addAll(correctivePhase);
		}

		return completePath;
	}

	/**
	 * Generates a single phase of movement along a cubic Bezier curve with flow-based
	 * velocity timing, speed-dependent noise, and sinusoidal deviation.
	 *
	 * The flow template system replaces the fixed minimum-jerk velocity profile with
	 * one of 9 randomly selected velocity patterns per movement, creating more variety.
	 * The noise layer adds tremor that scales inversely with step speed (physiologically
	 * accurate - the hand shakes more during slow, precise movements).
	 */
	private static List<TimedPoint> generateBezierPhase(
		int startX, int startY, int endX, int endY, long durationMs, MouseProfile profile, double curveScale)
	{
		List<TimedPoint> phasePoints = new ArrayList<>();

		double distance = Math.hypot(endX - startX, endY - startY);
		if (distance < 2)
		{
			phasePoints.add(new TimedPoint(endX, endY, 1.0));
			return phasePoints;
		}

		double directionX = (endX - startX) / distance;
		double directionY = (endY - startY) / distance;
		double perpX = -directionY;
		double perpY = directionX;

		// Apply distance-based curve scaling to profile values
		double curveIntensityMin = profile.getCurveIntensityMin() * curveScale;
		double curveIntensityMax = profile.getCurveIntensityMax() * curveScale;
		double controlOffset = distance * (curveIntensityMin
			+ ThreadLocalRandom.current().nextDouble() * (curveIntensityMax - curveIntensityMin));

		// Apply curve bias direction: bias > 0 favors right curves, bias < 0 favors left
		double curveBias = profile.getCurveBiasDirection();
		double curveSide;
		if (Math.abs(curveBias) < 0.01)
		{
			curveSide = ThreadLocalRandom.current().nextBoolean() ? 1.0 : -1.0;
		}
		else
		{
			double biasThreshold = 0.5 + curveBias * 0.5;
			curveSide = ThreadLocalRandom.current().nextDouble() < biasThreshold ? 1.0 : -1.0;
		}

		double controlPosition1 = BEZIER_CONTROL1_POSITION_MIN
			+ ThreadLocalRandom.current().nextDouble() * (BEZIER_CONTROL1_POSITION_MAX - BEZIER_CONTROL1_POSITION_MIN);
		double controlPosition2 = BEZIER_CONTROL2_POSITION_MIN
			+ ThreadLocalRandom.current().nextDouble() * (BEZIER_CONTROL2_POSITION_MAX - BEZIER_CONTROL2_POSITION_MIN);

		double control1Offset = controlOffset * (0.8 + ThreadLocalRandom.current().nextDouble() * 0.4);
		double control2Offset = controlOffset * (0.5 + ThreadLocalRandom.current().nextDouble() * 0.5);

		double control1X = startX + (endX - startX) * controlPosition1 + perpX * control1Offset * curveSide;
		double control1Y = startY + (endY - startY) * controlPosition1 + perpY * control1Offset * curveSide;
		double control2X = startX + (endX - startX) * controlPosition2 + perpX * control2Offset * curveSide;
		double control2Y = startY + (endY - startY) * controlPosition2 + perpY * control2Offset * curveSide;

		int numberOfSteps = Math.max(5, (int)(distance / STEP_SIZE_PIXELS));

		// Hesitation setup (unchanged)
		double hesitationScale = getDistanceHesitationScale(distance);
		double hesitationProbability = profile.getHesitationProbability() * hesitationScale;
		boolean shouldHesitate = ThreadLocalRandom.current().nextDouble() < hesitationProbability;
		double hesitationPosition = 0.3 + ThreadLocalRandom.current().nextDouble() * 0.4;

		// Select a random flow template for velocity modulation
		MotionProfile motionProfile = profile.getMotionProfile();
		FlowTemplate.Flow selectedFlow;
		boolean isShortMovement = distance < 10;
		if (isShortMovement)
		{
			// Short movements use constant speed - flow bucketing degenerates at < 5 steps
			selectedFlow = new FlowTemplate.Flow(FlowTemplate.constantSpeed());
		}
		else
		{
			selectedFlow = motionProfile.getRandomFlow();
		}

		// Noise and deviation parameters from MotionProfile
		double noisinessDivider = motionProfile.getNoisinessDivider();
		double slopeDivider = motionProfile.getSlopeDivider();

		// Add compensation time for zero-value flow buckets (pauses)
		int zeroBucketCount = selectedFlow.getZeroBucketCount();
		double adjustedDurationMs = durationMs;
		if (zeroBucketCount > 0)
		{
			double timePerBucket = durationMs / (double) selectedFlow.getBucketCount();
			adjustedDurationMs += timePerBucket * zeroBucketCount;
		}

		int previousX = startX;
		int previousY = startY;

		for (int stepIndex = 1; stepIndex <= numberOfSteps; stepIndex++)
		{
			double linearProgress = (double) stepIndex / numberOfSteps;

			// Use flow template for position mapping instead of minimum jerk
			double flowStepSize = selectedFlow.getStepSize(distance, numberOfSteps, linearProgress);
			double curvedProgress = linearProgress; // Flow modulates timing, Bezier provides path shape
			// Apply asymmetric jerk to the progress for path position (keeps our research-based curvature)
			curvedProgress = applyAsymmetricMinJerk(linearProgress, profile.getPeakVelocityPosition());

			double bezierX = cubicBezier(startX, control1X, control2X, endX, curvedProgress);
			double bezierY = cubicBezier(startY, control1Y, control2Y, endY, curvedProgress);

			// Sinusoidal deviation: smooth S-shaped perpendicular drift
			if (!isShortMovement && slopeDivider > 0.001)
			{
				double deviationFactor = (1.0 - Math.cos(linearProgress * Math.PI * 2.0)) / 2.0;
				double deviationMagnitude = distance / slopeDivider * deviationFactor;
				bezierX += perpX * deviationMagnitude * curveSide * 0.3;
				bezierY += perpY * deviationMagnitude * curveSide * 0.3;
			}

			// Speed-dependent noise layer (replaces old constant-amplitude Gaussian tremor)
			// Noise activates when step size < 8px, with more noise at slower speeds.
			// This is physiologically accurate: hand tremor is more visible during slow precision movements.
			double noiseX = 0;
			double noiseY = 0;
			if (!isShortMovement)
			{
				double stepPixelSize = Math.max(0.1, flowStepSize);
				double noisiness = Math.max(0, (8.0 - stepPixelSize)) / 50.0;
				if (ThreadLocalRandom.current().nextDouble() < noisiness)
				{
					double baseNoiseAmplitude = Math.max(0, (8.0 - stepPixelSize)) / noisinessDivider;
					double tremorScale = profile.getTremorAmplitude() / 1.5; // Normalize around default (1.5)
					double noiseAmplitude = baseNoiseAmplitude * tremorScale;
					noiseX = (ThreadLocalRandom.current().nextDouble() - 0.5) * noiseAmplitude;
					noiseY = (ThreadLocalRandom.current().nextDouble() - 0.5) * noiseAmplitude;
				}

				// Dampen noise near the end of movement (approaching target precisely)
				if (linearProgress > 0.9)
				{
					double dampening = (1.0 - linearProgress) / 0.1;
					noiseX *= dampening;
					noiseY *= dampening;
				}
			}

			int pointX = (int) Math.round(bezierX + noiseX);
			int pointY = (int) Math.round(bezierY + noiseY);

			if (pointX == previousX && pointY == previousY && stepIndex < numberOfSteps)
			{
				continue;
			}

			// Flow-based timing: flow step size modulates the base timing per step
			double baseStepDelayMs = adjustedDurationMs / (double) numberOfSteps;
			double flowSpeedFactor = Math.max(0.3, flowStepSize / Math.max(0.1, distance / numberOfSteps));
			double stepDelayMs = baseStepDelayMs / flowSpeedFactor;

			if (shouldHesitate && Math.abs(linearProgress - hesitationPosition) < 0.02)
			{
				long hesitationMinMs = (long) profile.getHesitationMinMs();
				long hesitationMaxMs = (long) profile.getHesitationMaxMs();
				stepDelayMs += ThreadLocalRandom.current().nextLong(hesitationMinMs, hesitationMaxMs + 1);
				shouldHesitate = false;
			}

			phasePoints.add(new TimedPoint(pointX, pointY, Math.max(0.5, stepDelayMs)));
			previousX = pointX;
			previousY = pointY;
		}

		if (phasePoints.isEmpty() || phasePoints.get(phasePoints.size() - 1).positionX != endX
			|| phasePoints.get(phasePoints.size() - 1).positionY != endY)
		{
			phasePoints.add(new TimedPoint(endX, endY, 1.0));
		}

		return phasePoints;
	}

	/**
	 * Calculates movement time using Fitts's Law with default parameters.
	 * Delegates to the profile-aware overload with the default MouseProfile.
	 */
	public static long calculateFittsTime(double distance, double targetWidth)
	{
		return calculateFittsTime(distance, targetWidth, MouseProfile.getDefault());
	}

	/**
	 * Calculates movement time personalized by the given MouseProfile.
	 * Uses the profile's calibrated distance-speed data if available,
	 * falling back to Fitts's Law (MT = a + b * log2(D/W + 1)) otherwise.
	 * Result is randomized by +/- profile.fittsVariance.
	 */
	public static long calculateFittsTime(double distance, double targetWidth, MouseProfile profile)
	{
		if (distance < 1)
		{
			return 50;
		}

		// Use Fitts's Law: MT = a + b * log2(D/W + 1)
		double fittsIntercept = profile.getFittsIntercept();
		double fittsSlope = profile.getFittsSlope();

		if (fittsIntercept > 0 && fittsSlope > 0)
		{
			double effectiveTargetWidth = Math.max(1.0, targetWidth);
			double indexOfDifficulty = Math.log(distance / effectiveTargetWidth + 1) / Math.log(2);
			long durationMs = (long) (fittsIntercept + fittsSlope * indexOfDifficulty);

			// Apply personal variance from profile
			double varianceFactor = 1.0 + (ThreadLocalRandom.current().nextGaussian() * profile.getFittsVariance());
			varianceFactor = Math.max(0.7, Math.min(1.3, varianceFactor));

			return Math.max(30, (long) (durationMs * varianceFactor));
		}

		// Fallback to distance-speed profile data
		return profile.getMovementDurationForDistance(distance);
	}

	/**
	 * Calculates the pre-click dwell time (pause before clicking after arriving at target).
	 * Uses the active MouseProfile's calibrated values when available, falling back to
	 * hardcoded defaults otherwise.
	 */
	public static long calculatePreClickDwellMs(MouseProfile profile)
	{
		double minMs = PRE_CLICK_DWELL_MIN_MS;
		double maxMs = PRE_CLICK_DWELL_MAX_MS;
		if (profile != null && profile.getPreClickDwellMaxMs() > profile.getPreClickDwellMinMs())
		{
			minMs = profile.getPreClickDwellMinMs();
			maxMs = profile.getPreClickDwellMaxMs();
		}
		return (long)(minMs + ThreadLocalRandom.current().nextDouble() * (maxMs - minMs));
	}

	/**
	 * Calculates the pre-click dwell time using hardcoded defaults.
	 * Kept for backwards compatibility with callers that don't have a profile.
	 */
	public static long calculatePreClickDwellMs()
	{
		return calculatePreClickDwellMs(null);
	}

	/**
	 * Minimum jerk velocity profile (normalized).
	 * Returns a value between 0 and 1 representing the instantaneous velocity
	 * at the given progress through the movement.
	 *
	 * v(tau) = 30*tau^2 - 60*tau^3 + 30*tau^4
	 * Peak value is 1.875 at tau = 0.5 for the symmetric case.
	 * We normalize so peak = 1.0.
	 */
	private static double minimumJerkVelocity(double tau)
	{
		double velocity = 30 * tau * tau - 60 * tau * tau * tau + 30 * tau * tau * tau * tau;
		return velocity / 1.875;
	}

	/**
	 * Applies the asymmetric minimum jerk position profile with a configurable
	 * peak velocity position.
	 * Maps linear progress [0,1] to position progress [0,1] with
	 * the velocity peak shifted to the specified position.
	 */
	private static double applyAsymmetricMinJerk(double linearProgress, double peakPosition)
	{
		double skewedTau;
		if (linearProgress < peakPosition)
		{
			skewedTau = (linearProgress / peakPosition) * 0.5;
		}
		else
		{
			skewedTau = 0.5 + ((linearProgress - peakPosition) / (1.0 - peakPosition)) * 0.5;
		}

		return 10 * Math.pow(skewedTau, 3) - 15 * Math.pow(skewedTau, 4) + 6 * Math.pow(skewedTau, 5);
	}

	/**
	 * Evaluates a cubic Bezier curve at parameter t.
	 */
	private static double cubicBezier(double point0, double point1, double point2, double point3, double parameter)
	{
		double oneMinusT = 1.0 - parameter;
		return oneMinusT * oneMinusT * oneMinusT * point0
			+ 3 * oneMinusT * oneMinusT * parameter * point1
			+ 3 * oneMinusT * parameter * parameter * point2
			+ parameter * parameter * parameter * point3;
	}
}
