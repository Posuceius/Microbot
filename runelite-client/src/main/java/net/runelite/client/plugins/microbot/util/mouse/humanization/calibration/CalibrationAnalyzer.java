package net.runelite.client.plugins.microbot.util.mouse.humanization.calibration;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.mouse.humanization.MouseProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Statistical analysis engine that extracts a {@link MouseProfile} from a collection of
 * recorded {@link CalibrationTrial} instances. Each trial represents one completed dot-click
 * movement and contains the full trajectory, click timing, and metadata needed to characterize
 * a player's natural mouse behavior.
 *
 * <p>The analyzer validates raw trials, discards outliers, and then extracts nine independent
 * behavioral dimensions from the remaining data: distance-based speed, Fitts's Law parameters,
 * path curvature, peak-velocity position, overshoot behavior, micro-tremor amplitude,
 * hesitation patterns, submovement pauses, and click timing. Every dimension is independently
 * computed and written into a single {@link MouseProfile} that the mouse-movement generator
 * can use to produce humanized movements matching the user's observed style.
 *
 * <p>This class is non-instantiable. All methods are static.
 */
@Slf4j
public final class CalibrationAnalyzer
{
	private static final int MINIMUM_VALID_TRIAL_COUNT = 12;
	private static final int MINIMUM_SAMPLES_PER_TRIAL = 5;
	private static final long MINIMUM_MOVEMENT_DURATION_MS = 50L;
	private static final double CLICK_ACCURACY_RADIUS_MULTIPLIER = 2.0;
	private static final int DISTANCE_BAND_COUNT = 4;

	private CalibrationAnalyzer()
	{
		// Non-instantiable utility class
	}

	/**
	 * Analyzes a list of calibration trials and extracts a fully populated {@link MouseProfile}
	 * representing the user's natural mouse movement style. Returns {@code null} if fewer than
	 * 12 valid trials remain after filtering invalid or outlier data.
	 *
	 * @param trials the raw calibration trials to analyze
	 * @return a populated {@link MouseProfile}, or {@code null} if insufficient valid data
	 */
	public static MouseProfile analyze(List<CalibrationTrial> trials)
	{
		List<CalibrationTrial> validTrials = filterValidTrials(trials);
		if (validTrials.size() < MINIMUM_VALID_TRIAL_COUNT)
		{
			log.warn("Insufficient valid trials for calibration: {} (need at least {})",
				validTrials.size(), MINIMUM_VALID_TRIAL_COUNT);
			return null;
		}

		log.info("Analyzing {} valid calibration trials", validTrials.size());
		MouseProfile profile = new MouseProfile();

		extractDistanceSpeeds(profile, validTrials);
		extractFittsLawParameters(profile, validTrials);
		extractCurvatureParameters(profile, validTrials);
		extractPeakVelocityPosition(profile, validTrials);
		extractOvershootParameters(profile, validTrials);
		extractTremorAmplitude(profile, validTrials);
		extractHesitationParameters(profile, validTrials);
		extractSubmovementPauses(profile, validTrials);
		extractClickBehavior(profile, validTrials);

		log.info("Calibration analysis complete: {}", profile);
		return profile;
	}

	// ========== Validation ==========

	/**
	 * Removes trials that are too short, too fast, or whose click position landed too far from
	 * the target center to be considered a valid aimed movement. Each discarded trial is logged
	 * individually with the reason it was rejected.
	 */
	private static List<CalibrationTrial> filterValidTrials(List<CalibrationTrial> trials)
	{
		List<CalibrationTrial> validTrials = new ArrayList<>();

		for (CalibrationTrial trial : trials)
		{
			List<CalibrationSample> samples = trial.getSamples();

			if (samples.size() < MINIMUM_SAMPLES_PER_TRIAL)
			{
				log.warn("Discarding trial: only {} samples (minimum {})",
					samples.size(), MINIMUM_SAMPLES_PER_TRIAL);
				continue;
			}

			if (trial.getMovementDurationMs() < MINIMUM_MOVEMENT_DURATION_MS)
			{
				log.warn("Discarding trial: movement duration {}ms is below minimum {}ms",
					trial.getMovementDurationMs(), MINIMUM_MOVEMENT_DURATION_MS);
				continue;
			}

			double clickDistanceFromTarget = euclideanDistance(
				trial.getClickPosition().getX(), trial.getClickPosition().getY(),
				trial.getTargetPosition().getX(), trial.getTargetPosition().getY()
			);
			double maximumAllowedClickDistance = trial.getTargetRadius() * CLICK_ACCURACY_RADIUS_MULTIPLIER;

			if (clickDistanceFromTarget > maximumAllowedClickDistance)
			{
				log.warn("Discarding trial: click landed {:.1f}px from target (maximum {:.1f}px)",
					clickDistanceFromTarget, maximumAllowedClickDistance);
				continue;
			}

			validTrials.add(trial);
		}

		log.debug("Trial validation complete: {} of {} trials are valid",
			validTrials.size(), trials.size());
		return validTrials;
	}

	// ========== Statistical Helpers ==========

	/**
	 * Computes a percentile value from a pre-sorted array using linear interpolation between
	 * adjacent elements.
	 *
	 * @param sortedValues an array of values already sorted in ascending order
	 * @param percentileValue the desired percentile as a fraction in [0.0, 1.0]
	 * @return the interpolated value at the requested percentile
	 */
	private static double percentile(double[] sortedValues, double percentileValue)
	{
		if (sortedValues.length == 0)
		{
			return 0.0;
		}
		if (sortedValues.length == 1)
		{
			return sortedValues[0];
		}

		double fractionalIndex = percentileValue * (sortedValues.length - 1);
		int lowerIndex = (int) fractionalIndex;
		int upperIndex = Math.min(lowerIndex + 1, sortedValues.length - 1);
		double interpolationFraction = fractionalIndex - lowerIndex;

		return sortedValues[lowerIndex] + interpolationFraction * (sortedValues[upperIndex] - sortedValues[lowerIndex]);
	}

	/**
	 * Computes the median of an array by sorting a copy and calling {@link #percentile}.
	 */
	private static double median(double[] values)
	{
		double[] sortedCopy = Arrays.copyOf(values, values.length);
		Arrays.sort(sortedCopy);
		return percentile(sortedCopy, 0.5);
	}

	/**
	 * Clamps a value to the specified inclusive range.
	 */
	private static double clamp(double value, double minimum, double maximum)
	{
		return Math.max(minimum, Math.min(maximum, value));
	}

	/**
	 * Computes the Euclidean distance between two points.
	 */
	private static double euclideanDistance(double x1, double y1, double x2, double y2)
	{
		double deltaX = x2 - x1;
		double deltaY = y2 - y1;
		return Math.sqrt(deltaX * deltaX + deltaY * deltaY);
	}

	/**
	 * Computes the shortest (perpendicular) distance from a point to the infinite line defined
	 * by two points. Uses the cross-product method for numerical stability.
	 *
	 * @param pointX the X coordinate of the query point
	 * @param pointY the Y coordinate of the query point
	 * @param lineStartX the X coordinate of the first line endpoint
	 * @param lineStartY the Y coordinate of the first line endpoint
	 * @param lineEndX the X coordinate of the second line endpoint
	 * @param lineEndY the Y coordinate of the second line endpoint
	 * @return the perpendicular distance from the point to the line
	 */
	private static double perpendicularDistance(
		double pointX, double pointY,
		double lineStartX, double lineStartY,
		double lineEndX, double lineEndY)
	{
		double lineDeltaX = lineEndX - lineStartX;
		double lineDeltaY = lineEndY - lineStartY;
		double lineLength = Math.sqrt(lineDeltaX * lineDeltaX + lineDeltaY * lineDeltaY);

		if (lineLength < 1e-9)
		{
			return euclideanDistance(pointX, pointY, lineStartX, lineStartY);
		}

		double crossProduct = (pointX - lineStartX) * lineDeltaY - (pointY - lineStartY) * lineDeltaX;
		return Math.abs(crossProduct) / lineLength;
	}

	// ========== Extraction Methods ==========

	/**
	 * Extracts the user's natural movement speed (pixels per millisecond) at each of the four
	 * distance bands. Trials are grouped by their distance band, and the median speed is taken
	 * per band. Bands with no trials are interpolated from the nearest occupied band(s).
	 */
	private static void extractDistanceSpeeds(MouseProfile profile, List<CalibrationTrial> validTrials)
	{
		log.debug("Extracting distance-based speed parameters");

		@SuppressWarnings("unchecked")
		List<Double>[] speedsPerBand = new List[DISTANCE_BAND_COUNT];
		for (int bandIndex = 0; bandIndex < DISTANCE_BAND_COUNT; bandIndex++)
		{
			speedsPerBand[bandIndex] = new ArrayList<>();
		}

		for (CalibrationTrial trial : validTrials)
		{
			long durationMs = trial.getMovementDurationMs();
			if (durationMs > 0)
			{
				double speedPixelsPerMs = trial.getDistance() / durationMs;
				speedsPerBand[trial.getDistanceBand()].add(speedPixelsPerMs);
			}
		}

		double[] bandMedianSpeeds = new double[DISTANCE_BAND_COUNT];
		boolean[] bandHasData = new boolean[DISTANCE_BAND_COUNT];

		for (int bandIndex = 0; bandIndex < DISTANCE_BAND_COUNT; bandIndex++)
		{
			List<Double> bandSpeeds = speedsPerBand[bandIndex];
			if (!bandSpeeds.isEmpty())
			{
				double[] speedArray = bandSpeeds.stream().mapToDouble(Double::doubleValue).toArray();
				bandMedianSpeeds[bandIndex] = median(speedArray);
				bandHasData[bandIndex] = true;
				log.debug("Distance band {}: {} trials, median speed = {:.3f} px/ms",
					bandIndex, bandSpeeds.size(), bandMedianSpeeds[bandIndex]);
			}
		}

		// Interpolate empty bands from nearest occupied neighbors
		for (int bandIndex = 0; bandIndex < DISTANCE_BAND_COUNT; bandIndex++)
		{
			if (!bandHasData[bandIndex])
			{
				int nearestLowerBand = -1;
				int nearestUpperBand = -1;

				for (int searchIndex = bandIndex - 1; searchIndex >= 0; searchIndex--)
				{
					if (bandHasData[searchIndex])
					{
						nearestLowerBand = searchIndex;
						break;
					}
				}
				for (int searchIndex = bandIndex + 1; searchIndex < DISTANCE_BAND_COUNT; searchIndex++)
				{
					if (bandHasData[searchIndex])
					{
						nearestUpperBand = searchIndex;
						break;
					}
				}

				if (nearestLowerBand >= 0 && nearestUpperBand >= 0)
				{
					double interpolationFraction = (double)(bandIndex - nearestLowerBand)
						/ (nearestUpperBand - nearestLowerBand);
					bandMedianSpeeds[bandIndex] = bandMedianSpeeds[nearestLowerBand]
						+ interpolationFraction * (bandMedianSpeeds[nearestUpperBand] - bandMedianSpeeds[nearestLowerBand]);
				}
				else if (nearestLowerBand >= 0)
				{
					bandMedianSpeeds[bandIndex] = bandMedianSpeeds[nearestLowerBand];
				}
				else if (nearestUpperBand >= 0)
				{
					bandMedianSpeeds[bandIndex] = bandMedianSpeeds[nearestUpperBand];
				}
				else
				{
					bandMedianSpeeds[bandIndex] = 0.9; // fallback default
				}

				log.debug("Distance band {} had no data - interpolated to {:.3f} px/ms", bandIndex, bandMedianSpeeds[bandIndex]);
			}
		}

		profile.setDistanceSpeedPixelsPerMs(bandMedianSpeeds);
	}

	/**
	 * Fits Fitts's Law parameters (intercept, slope, variance) to the calibration data using
	 * ordinary least-squares regression on index-of-difficulty vs. movement time. The fitted
	 * variance captures how consistently the user follows the prediction.
	 */
	private static void extractFittsLawParameters(MouseProfile profile, List<CalibrationTrial> validTrials)
	{
		log.debug("Extracting Fitts's Law parameters from {} trials", validTrials.size());

		double sumX = 0.0;
		double sumY = 0.0;
		double sumXY = 0.0;
		double sumXX = 0.0;
		int trialCount = validTrials.size();

		double[] indexesOfDifficulty = new double[trialCount];
		double[] movementTimes = new double[trialCount];

		for (int trialIndex = 0; trialIndex < trialCount; trialIndex++)
		{
			CalibrationTrial trial = validTrials.get(trialIndex);
			double indexOfDifficulty = Math.log(trial.getDistance() / (trial.getTargetRadius() * 2.0) + 1.0)
				/ Math.log(2.0);
			double movementTime = trial.getMovementDurationMs();

			indexesOfDifficulty[trialIndex] = indexOfDifficulty;
			movementTimes[trialIndex] = movementTime;

			sumX += indexOfDifficulty;
			sumY += movementTime;
			sumXY += indexOfDifficulty * movementTime;
			sumXX += indexOfDifficulty * indexOfDifficulty;
		}

		double slope = (trialCount * sumXY - sumX * sumY) / (trialCount * sumXX - sumX * sumX);
		double intercept = (sumY - slope * sumX) / trialCount;

		// Compute variance as stddev of relative prediction error
		double sumSquaredRelativeErrors = 0.0;
		for (int trialIndex = 0; trialIndex < trialCount; trialIndex++)
		{
			double predictedTime = intercept + slope * indexesOfDifficulty[trialIndex];
			if (predictedTime > 0.0)
			{
				double relativeError = (movementTimes[trialIndex] - predictedTime) / predictedTime;
				sumSquaredRelativeErrors += relativeError * relativeError;
			}
		}
		double variance = Math.sqrt(sumSquaredRelativeErrors / trialCount);

		intercept = clamp(intercept, 5.0, 200.0);
		slope = clamp(slope, 30.0, 300.0);
		variance = clamp(variance, 0.05, 0.40);

		log.debug("Fitts's Law: intercept={:.2f}, slope={:.2f}, variance={:.3f}", intercept, slope, variance);

		profile.setFittsIntercept(intercept);
		profile.setFittsSlope(slope);
		profile.setFittsVariance(variance);
	}

	/**
	 * Extracts curvature parameters by measuring the maximum perpendicular deviation of each
	 * trial's path from the straight line between start and target. The left/right bias is
	 * determined by the sign of the cross product at the point of maximum deviation.
	 */
	private static void extractCurvatureParameters(MouseProfile profile, List<CalibrationTrial> validTrials)
	{
		log.debug("Extracting curvature parameters from {} trials", validTrials.size());

		List<Double> curveRatios = new ArrayList<>();
		int rightDeviationCount = 0;
		int leftDeviationCount = 0;

		for (CalibrationTrial trial : validTrials)
		{
			List<CalibrationSample> samples = trial.getSamples();
			double startX = trial.getStartPosition().getX();
			double startY = trial.getStartPosition().getY();
			double targetX = trial.getTargetPosition().getX();
			double targetY = trial.getTargetPosition().getY();
			double trialDistance = trial.getDistance();

			if (trialDistance < 1.0)
			{
				continue;
			}

			double maximumAbsoluteDeviation = 0.0;
			double deviationSignAtMax = 0.0;

			for (CalibrationSample sample : samples)
			{
				double sampleX = sample.getX();
				double sampleY = sample.getY();

				double deviation = perpendicularDistance(sampleX, sampleY, startX, startY, targetX, targetY);

				if (deviation > maximumAbsoluteDeviation)
				{
					maximumAbsoluteDeviation = deviation;

					// Determine which side of the line the sample is on using cross product sign
					double crossProduct = (targetX - startX) * (sampleY - startY)
						- (targetY - startY) * (sampleX - startX);
					deviationSignAtMax = Math.signum(crossProduct);
				}
			}

			double curveRatio = maximumAbsoluteDeviation / trialDistance;
			curveRatios.add(curveRatio);

			if (deviationSignAtMax > 0)
			{
				rightDeviationCount++;
			}
			else if (deviationSignAtMax < 0)
			{
				leftDeviationCount++;
			}
		}

		if (curveRatios.isEmpty())
		{
			log.warn("No curvature data found - using profile defaults");
			return;
		}

		double[] sortedCurveRatios = curveRatios.stream().mapToDouble(Double::doubleValue).sorted().toArray();

		double curveIntensityMin = clamp(percentile(sortedCurveRatios, 0.25), 0.01, 0.30);
		double curveIntensityMax = clamp(percentile(sortedCurveRatios, 0.75), 0.02, 0.40);
		double curveBiasDirection = clamp(
			(double)(rightDeviationCount - leftDeviationCount) / curveRatios.size(),
			-1.0, 1.0
		);

		log.debug("Curvature: min={:.3f}, max={:.3f}, bias={:.3f} ({} right, {} left)",
			curveIntensityMin, curveIntensityMax, curveBiasDirection, rightDeviationCount, leftDeviationCount);

		profile.setCurveIntensityMin(curveIntensityMin);
		profile.setCurveIntensityMax(curveIntensityMax);
		profile.setCurveBiasDirection(curveBiasDirection);
	}

	/**
	 * Extracts the position (as a fraction of total time) at which the user's hand reaches
	 * peak velocity. This characterizes the velocity envelope shape - whether the user
	 * accelerates quickly and decelerates slowly, or vice versa.
	 */
	private static void extractPeakVelocityPosition(MouseProfile profile, List<CalibrationTrial> validTrials)
	{
		log.debug("Extracting peak velocity position from {} trials", validTrials.size());

		List<Double> peakFractions = new ArrayList<>();

		for (CalibrationTrial trial : validTrials)
		{
			List<CalibrationSample> samples = trial.getSamples();
			int sampleCount = samples.size();

			if (sampleCount < 2)
			{
				continue;
			}

			double maximumVelocity = -1.0;
			int peakVelocityIndex = 0;

			for (int sampleIndex = 1; sampleIndex < sampleCount; sampleIndex++)
			{
				CalibrationSample previousSample = samples.get(sampleIndex - 1);
				CalibrationSample currentSample = samples.get(sampleIndex);

				long timeDeltaMs = currentSample.getTimestampMs() - previousSample.getTimestampMs();
				if (timeDeltaMs <= 0)
				{
					continue;
				}

				double stepDistance = euclideanDistance(
					currentSample.getX(), currentSample.getY(),
					previousSample.getX(), previousSample.getY()
				);
				double velocity = stepDistance / timeDeltaMs;

				if (velocity > maximumVelocity)
				{
					maximumVelocity = velocity;
					peakVelocityIndex = sampleIndex;
				}
			}

			double peakFraction = (double) peakVelocityIndex / (sampleCount - 1);
			peakFractions.add(peakFraction);
		}

		if (peakFractions.isEmpty())
		{
			log.warn("No peak velocity data found - using profile defaults");
			return;
		}

		double[] peakFractionArray = peakFractions.stream().mapToDouble(Double::doubleValue).toArray();
		double medianPeakFraction = clamp(median(peakFractionArray), 0.20, 0.60);

		log.debug("Peak velocity position: {:.3f} (median across {} trials)", medianPeakFraction, peakFractions.size());

		profile.setPeakVelocityPosition(medianPeakFraction);
	}

	/**
	 * Extracts overshoot behavior by detecting whether the mouse traveled past the target along
	 * the movement axis before returning. The probability, minimum, and maximum overshoot
	 * magnitudes are derived from the observed data.
	 */
	private static void extractOvershootParameters(MouseProfile profile, List<CalibrationTrial> validTrials)
	{
		log.debug("Extracting overshoot parameters from {} trials", validTrials.size());

		List<Double> overshootMagnitudes = new ArrayList<>();
		int overshootTrialCount = 0;

		for (CalibrationTrial trial : validTrials)
		{
			List<CalibrationSample> samples = trial.getSamples();
			double startX = trial.getStartPosition().getX();
			double startY = trial.getStartPosition().getY();
			double targetX = trial.getTargetPosition().getX();
			double targetY = trial.getTargetPosition().getY();
			double trialDistance = trial.getDistance();

			if (trialDistance < 1.0 || samples.size() < 3)
			{
				continue;
			}

			double directionX = (targetX - startX) / trialDistance;
			double directionY = (targetY - startY) / trialDistance;

			double maximumProgress = 0.0;
			boolean hasProgressedBeyondTarget = false;
			boolean hasReturned = false;

			for (CalibrationSample sample : samples)
			{
				double vectorX = sample.getX() - startX;
				double vectorY = sample.getY() - startY;
				double progress = vectorX * directionX + vectorY * directionY;
				double progressFraction = progress / trialDistance;

				if (progressFraction > maximumProgress)
				{
					maximumProgress = progressFraction;
				}

				if (progressFraction > 1.0)
				{
					hasProgressedBeyondTarget = true;
				}
				else if (hasProgressedBeyondTarget && progressFraction < maximumProgress)
				{
					hasReturned = true;
				}
			}

			if (hasProgressedBeyondTarget && hasReturned)
			{
				overshootTrialCount++;
				double overshootMagnitudePixels = (maximumProgress - 1.0) * trialDistance;
				overshootMagnitudes.add(overshootMagnitudePixels);
			}
		}

		int totalTrials = validTrials.size();

		if (overshootMagnitudes.isEmpty())
		{
			log.debug("No overshoots detected - using default overshoot parameters");
			profile.setOvershootProbability(0.0);
			profile.setOvershootMinPixels(5.0);
			profile.setOvershootMaxPixels(15.0);
			return;
		}

		double[] sortedMagnitudes = overshootMagnitudes.stream().mapToDouble(Double::doubleValue).sorted().toArray();

		double overshootProbability = clamp((double) overshootTrialCount / totalTrials, 0.0, 0.5);
		double overshootMinPixels = clamp(percentile(sortedMagnitudes, 0.10), 1.0, 50.0);
		double overshootMaxPixels = clamp(percentile(sortedMagnitudes, 0.90), 2.0, 80.0);

		log.debug("Overshoot: probability={:.3f}, min={:.1f}px, max={:.1f}px ({} of {} trials)",
			overshootProbability, overshootMinPixels, overshootMaxPixels, overshootTrialCount, totalTrials);

		profile.setOvershootProbability(overshootProbability);
		profile.setOvershootMinPixels(overshootMinPixels);
		profile.setOvershootMaxPixels(overshootMaxPixels);
	}

	/**
	 * Extracts micro-tremor amplitude by analyzing the fine positional noise in the tail of
	 * each trial (the last 30% of samples), where the hand has decelerated and tremor is most
	 * visible. A 5-sample moving average defines the local trend, and deviations from it are
	 * the tremor signal.
	 */
	private static void extractTremorAmplitude(MouseProfile profile, List<CalibrationTrial> validTrials)
	{
		log.debug("Extracting tremor amplitude from {} trials", validTrials.size());

		List<Double> trialTremorStdDevs = new ArrayList<>();

		for (CalibrationTrial trial : validTrials)
		{
			List<CalibrationSample> samples = trial.getSamples();
			int sampleCount = samples.size();
			int tailStartIndex = (int) Math.max(0, sampleCount * 0.70);
			int tailSampleCount = sampleCount - tailStartIndex;

			if (tailSampleCount < 5)
			{
				continue;
			}

			// Compute 5-sample moving average over the tail
			int movingAverageWindowSize = 5;
			List<Double> deviations = new ArrayList<>();

			for (int sampleIndex = tailStartIndex + movingAverageWindowSize - 1; sampleIndex < sampleCount; sampleIndex++)
			{
				double sumX = 0.0;
				double sumY = 0.0;
				for (int windowIndex = sampleIndex - movingAverageWindowSize + 1; windowIndex <= sampleIndex; windowIndex++)
				{
					sumX += samples.get(windowIndex).getX();
					sumY += samples.get(windowIndex).getY();
				}
				double averageX = sumX / movingAverageWindowSize;
				double averageY = sumY / movingAverageWindowSize;

				// The deviation of the center sample from the moving average
				int centerSampleIndex = sampleIndex - movingAverageWindowSize / 2;
				double centerX = samples.get(centerSampleIndex).getX();
				double centerY = samples.get(centerSampleIndex).getY();
				double deviation = euclideanDistance(centerX, centerY, averageX, averageY);
				deviations.add(deviation);
			}

			if (deviations.isEmpty())
			{
				continue;
			}

			double deviationMean = deviations.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
			double sumSquaredDeviation = 0.0;
			for (double deviation : deviations)
			{
				double difference = deviation - deviationMean;
				sumSquaredDeviation += difference * difference;
			}
			double stdDev = Math.sqrt(sumSquaredDeviation / deviations.size());
			trialTremorStdDevs.add(stdDev);
		}

		if (trialTremorStdDevs.isEmpty())
		{
			log.warn("No tremor data found - using profile defaults");
			return;
		}

		double[] tremorArray = trialTremorStdDevs.stream().mapToDouble(Double::doubleValue).toArray();
		double medianTremorAmplitude = clamp(median(tremorArray), 0.2, 5.0);

		log.debug("Tremor amplitude: {:.3f}px (median across {} trials)", medianTremorAmplitude, trialTremorStdDevs.size());

		profile.setTremorAmplitude(medianTremorAmplitude);
	}

	/**
	 * Extracts hesitation behavior by scanning each trial for velocity pauses (velocity below
	 * 0.1 px/ms for more than 30ms) that occur between 10% and 85% of the movement's temporal
	 * progress. These are micro-pauses that happen during movement, not before clicking.
	 */
	private static void extractHesitationParameters(MouseProfile profile, List<CalibrationTrial> validTrials)
	{
		log.debug("Extracting hesitation parameters from {} trials", validTrials.size());

		List<Double> hesitationDurations = new ArrayList<>();
		int trialsWithHesitation = 0;

		for (CalibrationTrial trial : validTrials)
		{
			List<CalibrationSample> samples = trial.getSamples();
			int sampleCount = samples.size();

			if (sampleCount < 4)
			{
				continue;
			}

			long trialStartMs = samples.get(0).getTimestampMs();
			long trialEndMs = samples.get(sampleCount - 1).getTimestampMs();
			long totalDurationMs = trialEndMs - trialStartMs;

			if (totalDurationMs <= 0)
			{
				continue;
			}

			boolean trialHasHesitation = false;
			long pauseStartMs = -1;

			for (int sampleIndex = 1; sampleIndex < sampleCount; sampleIndex++)
			{
				CalibrationSample previousSample = samples.get(sampleIndex - 1);
				CalibrationSample currentSample = samples.get(sampleIndex);

				long timeDeltaMs = currentSample.getTimestampMs() - previousSample.getTimestampMs();
				if (timeDeltaMs <= 0)
				{
					continue;
				}

				double stepDistance = euclideanDistance(
					currentSample.getX(), currentSample.getY(),
					previousSample.getX(), previousSample.getY()
				);
				double velocity = stepDistance / timeDeltaMs;

				double temporalProgress = (double)(currentSample.getTimestampMs() - trialStartMs) / totalDurationMs;
				boolean isInHesitationWindow = temporalProgress >= 0.10 && temporalProgress <= 0.85;

				if (velocity < 0.1 && isInHesitationWindow)
				{
					if (pauseStartMs < 0)
					{
						pauseStartMs = previousSample.getTimestampMs();
					}
				}
				else
				{
					if (pauseStartMs >= 0)
					{
						long pauseDurationMs = previousSample.getTimestampMs() - pauseStartMs;
						if (pauseDurationMs > 30)
						{
							hesitationDurations.add((double) pauseDurationMs);
							trialHasHesitation = true;
						}
						pauseStartMs = -1;
					}
				}
			}

			if (trialHasHesitation)
			{
				trialsWithHesitation++;
			}
		}

		int totalTrials = validTrials.size();

		if (hesitationDurations.isEmpty())
		{
			log.debug("No hesitations detected - using default hesitation parameters");
			profile.setHesitationProbability(0.0);
			profile.setHesitationMinMs(50.0);
			profile.setHesitationMaxMs(200.0);
			return;
		}

		double[] sortedDurations = hesitationDurations.stream().mapToDouble(Double::doubleValue).sorted().toArray();

		double hesitationProbability = clamp((double) trialsWithHesitation / totalTrials, 0.0, 0.3);
		double hesitationMinMs = clamp(percentile(sortedDurations, 0.10), 20.0, 500.0);
		double hesitationMaxMs = clamp(percentile(sortedDurations, 0.90), 50.0, 1000.0);

		log.debug("Hesitation: probability={:.3f}, min={:.0f}ms, max={:.0f}ms ({} of {} trials)",
			hesitationProbability, hesitationMinMs, hesitationMaxMs, trialsWithHesitation, totalTrials);

		profile.setHesitationProbability(hesitationProbability);
		profile.setHesitationMinMs(hesitationMinMs);
		profile.setHesitationMaxMs(hesitationMaxMs);
	}

	/**
	 * Extracts the duration of submovement pauses - brief velocity dips near the end of long
	 * movements where the user makes a corrective micromovement before clicking. Only trials
	 * with distance greater than 150px are considered, since submovements are a feature of
	 * longer aimed movements.
	 */
	private static void extractSubmovementPauses(MouseProfile profile, List<CalibrationTrial> validTrials)
	{
		log.debug("Extracting submovement pause parameters");

		List<Double> dipDurations = new ArrayList<>();

		for (CalibrationTrial trial : validTrials)
		{
			if (trial.getDistance() <= 150.0)
			{
				continue;
			}

			List<CalibrationSample> samples = trial.getSamples();
			int sampleCount = samples.size();

			if (sampleCount < 4)
			{
				continue;
			}

			// Compute per-sample velocities and find peak velocity
			double[] velocities = new double[sampleCount];
			double peakVelocity = 0.0;

			for (int sampleIndex = 1; sampleIndex < sampleCount; sampleIndex++)
			{
				CalibrationSample previousSample = samples.get(sampleIndex - 1);
				CalibrationSample currentSample = samples.get(sampleIndex);
				long timeDeltaMs = currentSample.getTimestampMs() - previousSample.getTimestampMs();

				if (timeDeltaMs > 0)
				{
					double stepDistance = euclideanDistance(
						currentSample.getX(), currentSample.getY(),
						previousSample.getX(), previousSample.getY()
					);
					velocities[sampleIndex] = stepDistance / timeDeltaMs;
					peakVelocity = Math.max(peakVelocity, velocities[sampleIndex]);
				}
			}

			if (peakVelocity < 1e-9)
			{
				continue;
			}

			double dipThresholdVelocity = peakVelocity * 0.30;
			long trialStartMs = samples.get(0).getTimestampMs();
			long totalDurationMs = samples.get(sampleCount - 1).getTimestampMs() - trialStartMs;

			if (totalDurationMs <= 0)
			{
				continue;
			}

			long dipStartMs = -1;

			for (int sampleIndex = 1; sampleIndex < sampleCount; sampleIndex++)
			{
				CalibrationSample currentSample = samples.get(sampleIndex);
				double temporalProgress = (double)(currentSample.getTimestampMs() - trialStartMs) / totalDurationMs;
				boolean isInSubmovementWindow = temporalProgress >= 0.60 && temporalProgress <= 0.95;

				if (velocities[sampleIndex] < dipThresholdVelocity && isInSubmovementWindow)
				{
					if (dipStartMs < 0)
					{
						dipStartMs = samples.get(sampleIndex - 1).getTimestampMs();
					}
				}
				else
				{
					if (dipStartMs >= 0)
					{
						long dipDurationMs = samples.get(sampleIndex - 1).getTimestampMs() - dipStartMs;
						if (dipDurationMs > 0)
						{
							dipDurations.add((double) dipDurationMs);
						}
						dipStartMs = -1;
					}
				}
			}
		}

		if (dipDurations.isEmpty())
		{
			log.debug("No submovement dips detected - using default submovement pause parameters");
			profile.setSubmovementPauseMinMs(40.0);
			profile.setSubmovementPauseMaxMs(100.0);
			return;
		}

		double[] sortedDipDurations = dipDurations.stream().mapToDouble(Double::doubleValue).sorted().toArray();

		double submovementPauseMinMs = clamp(percentile(sortedDipDurations, 0.25), 10.0, 200.0);
		double submovementPauseMaxMs = clamp(percentile(sortedDipDurations, 0.75), 20.0, 400.0);

		log.debug("Submovement pauses: min={:.0f}ms, max={:.0f}ms from {} dip observations",
			submovementPauseMinMs, submovementPauseMaxMs, dipDurations.size());

		profile.setSubmovementPauseMinMs(submovementPauseMinMs);
		profile.setSubmovementPauseMaxMs(submovementPauseMaxMs);
	}

	/**
	 * Extracts click-timing behavior including how long the mouse button is held down,
	 * how far the cursor drifts between the last trajectory sample and the actual click
	 * position, and how long the user dwells near the target before pressing the button.
	 */
	private static void extractClickBehavior(MouseProfile profile, List<CalibrationTrial> validTrials)
	{
		log.debug("Extracting click behavior parameters from {} trials", validTrials.size());

		List<Double> clickHoldDurations = new ArrayList<>();
		List<Double> clickDriftDistances = new ArrayList<>();
		List<Double> preClickDwellDurations = new ArrayList<>();

		for (CalibrationTrial trial : validTrials)
		{
			List<CalibrationSample> samples = trial.getSamples();
			int sampleCount = samples.size();

			if (sampleCount < 2)
			{
				continue;
			}

			// Click hold duration
			long holdDurationMs = trial.getMouseUpTimestampMs() - trial.getMouseDownTimestampMs();
			if (holdDurationMs > 0)
			{
				clickHoldDurations.add((double) holdDurationMs);
			}

			// Click drift: distance from last sample to actual click position
			CalibrationSample lastSample = samples.get(sampleCount - 1);
			double driftDistance = euclideanDistance(
				lastSample.getX(), lastSample.getY(),
				trial.getClickPosition().getX(), trial.getClickPosition().getY()
			);
			clickDriftDistances.add(driftDistance);

			// Pre-click dwell: find when the cursor first slows to near-zero velocity before clicking
			long arrivalTimeMs = -1;
			for (int sampleIndex = sampleCount - 1; sampleIndex >= 1; sampleIndex--)
			{
				CalibrationSample previousSample = samples.get(sampleIndex - 1);
				CalibrationSample currentSample = samples.get(sampleIndex);
				long timeDeltaMs = currentSample.getTimestampMs() - previousSample.getTimestampMs();

				if (timeDeltaMs <= 0)
				{
					continue;
				}

				double stepDistance = euclideanDistance(
					currentSample.getX(), currentSample.getY(),
					previousSample.getX(), previousSample.getY()
				);
				double velocity = stepDistance / timeDeltaMs;

				if (velocity >= 0.05)
				{
					// The cursor was still moving here - arrival happened at the next sample
					if (sampleIndex + 1 < sampleCount)
					{
						arrivalTimeMs = samples.get(sampleIndex + 1).getTimestampMs();
					}
					break;
				}
			}

			if (arrivalTimeMs >= 0)
			{
				long dwellDurationMs = trial.getMouseDownTimestampMs() - arrivalTimeMs;
				if (dwellDurationMs >= 0)
				{
					preClickDwellDurations.add((double) dwellDurationMs);
				}
			}
		}

		// Click hold
		if (!clickHoldDurations.isEmpty())
		{
			double[] sortedHoldDurations = clickHoldDurations.stream().mapToDouble(Double::doubleValue).sorted().toArray();
			double clickHoldMinMs = clamp(percentile(sortedHoldDurations, 0.10), 10.0, 200.0);
			double clickHoldMaxMs = clamp(percentile(sortedHoldDurations, 0.90), 30.0, 500.0);
			log.debug("Click hold: min={:.0f}ms, max={:.0f}ms", clickHoldMinMs, clickHoldMaxMs);
			profile.setClickHoldMinMs(clickHoldMinMs);
			profile.setClickHoldMaxMs(clickHoldMaxMs);
		}

		// Click drift
		if (!clickDriftDistances.isEmpty())
		{
			double[] driftArray = clickDriftDistances.stream().mapToDouble(Double::doubleValue).toArray();
			double clickDriftPixels = clamp(median(driftArray), 0.0, 10.0);
			log.debug("Click drift: {:.2f}px median", clickDriftPixels);
			profile.setClickDriftPixels(clickDriftPixels);
		}

		// Pre-click dwell
		if (!preClickDwellDurations.isEmpty())
		{
			double[] sortedDwellDurations = preClickDwellDurations.stream().mapToDouble(Double::doubleValue).sorted().toArray();
			double preClickDwellMinMs = clamp(percentile(sortedDwellDurations, 0.10), 5.0, 200.0);
			double preClickDwellMaxMs = clamp(percentile(sortedDwellDurations, 0.90), 10.0, 500.0);
			log.debug("Pre-click dwell: min={:.0f}ms, max={:.0f}ms", preClickDwellMinMs, preClickDwellMaxMs);
			profile.setPreClickDwellMinMs(preClickDwellMinMs);
			profile.setPreClickDwellMaxMs(preClickDwellMaxMs);
		}
	}
}
