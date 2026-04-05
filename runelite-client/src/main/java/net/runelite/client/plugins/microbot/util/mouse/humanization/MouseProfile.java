package net.runelite.client.plugins.microbot.util.mouse.humanization;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores personal mouse movement parameters extracted from calibration data.
 * These parameters personalize the synthetic mouse movement generator to match
 * the user's real movement style, making automation harder to detect.
 *
 * Parameters cover Fitts's Law timing, path curvature, velocity profile,
 * overshoot behavior, micro-tremor, and hesitation patterns.
 */
public class MouseProfile
{
	private static final Logger log = LoggerFactory.getLogger(MouseProfile.class);

	private static final String PROFILE_FILE_NAME = "mouse-profile.json";

	// Fitts's Law parameters
	private double fittsIntercept = 30.0;
	private double fittsSlope = 100.0;
	private double fittsVariance = 0.18;

	// Path curvature parameters
	private double curveBiasDirection = 0.0;
	private double curveIntensityMin = 0.05;
	private double curveIntensityMax = 0.15;

	// Velocity profile
	private double peakVelocityPosition = 0.40;

	// Overshoot parameters
	private double overshootProbability = 0.15;
	private double overshootMinPixels = 5;
	private double overshootMaxPixels = 15;

	// Micro-tremor
	private double tremorAmplitude = 1.5;

	// Hesitation parameters
	private double hesitationProbability = 0.07;
	private double hesitationMinMs = 50;
	private double hesitationMaxMs = 200;

	// Submovement pause parameters
	private double submovementPauseMinMs = 40;
	private double submovementPauseMaxMs = 100;

	// Click behavior
	private double clickHoldMinMs = 40;
	private double clickHoldMaxMs = 120;
	private double clickDriftPixels = 1.5;
	private double preClickDwellMinMs = 20;
	private double preClickDwellMaxMs = 60;

	// Distance-based speed multipliers (measured pixels/ms at each distance band)
	// These are derived from calibration and represent how fast the user actually
	// moves at different distances. The movement generator uses these to scale timing.
	// Index 0 = short (0-100px), 1 = medium (100-300px), 2 = long (300-600px), 3 = very long (600+px)
	private double[] distanceSpeedPixelsPerMs = new double[]{0.5, 0.9, 1.3, 1.6};

	// Motion profile for flow-based velocity modulation
	private transient MotionProfile motionProfile = MotionProfile.normalGamer();

	public MouseProfile()
	{
	}

	public static MouseProfile getDefault()
	{
		return new MouseProfile();
	}

	

	/**
	 * Creates a modified copy of this profile with fatigue degradation applied.
	 * Fatigue affects timing and precision but not path geometry.
	 *
	 * Applied AFTER withPreset() in the chain:
	 *   base profile -> activity adjustment -> fatigue degradation
	 *
	 * A fatigued player doing combat is still faster than a fatigued player skilling,
	 * because the activity preset sets the baseline and fatigue degrades from there.
	 *
	 * @param fatigueMultiplier the fatigue factor (1.0 = no fatigue, 1.4 = 40% degraded)
	 * @return a new MouseProfile with degraded parameters, or this if no fatigue
	 */
	public MouseProfile withFatigue(double fatigueMultiplier)
	{
		if (fatigueMultiplier <= 1.0001)
		{
			return this;
		}

		MouseProfile fatigued = new MouseProfile();

		// Slower movements: divide speed by fatigue multiplier
		double[] adjustedSpeeds = new double[distanceSpeedPixelsPerMs.length];
		for (int bandIndex = 0; bandIndex < adjustedSpeeds.length; bandIndex++)
		{
			adjustedSpeeds[bandIndex] = distanceSpeedPixelsPerMs[bandIndex] / fatigueMultiplier;
		}
		fatigued.distanceSpeedPixelsPerMs = adjustedSpeeds;

		// Fitts's Law: slower = higher intercept and slope
		fatigued.fittsIntercept = fittsIntercept * fatigueMultiplier;
		fatigued.fittsSlope = fittsSlope * fatigueMultiplier;
		fatigued.fittsVariance = fittsVariance;

		// Path shape: unchanged - fatigue affects timing, not geometry
		fatigued.curveBiasDirection = curveBiasDirection;
		fatigued.curveIntensityMin = curveIntensityMin;
		fatigued.curveIntensityMax = curveIntensityMax;
		fatigued.peakVelocityPosition = peakVelocityPosition;

		// More tremor: tired hand shakes more
		fatigued.tremorAmplitude = tremorAmplitude * fatigueMultiplier;

		// More overshoot: less precise
		fatigued.overshootProbability = Math.min(0.5, overshootProbability * fatigueMultiplier);
		fatigued.overshootMinPixels = overshootMinPixels;
		fatigued.overshootMaxPixels = overshootMaxPixels;

		// More hesitation: tired brain pauses more
		fatigued.hesitationProbability = Math.min(0.3, hesitationProbability * fatigueMultiplier);
		fatigued.hesitationMinMs = hesitationMinMs * fatigueMultiplier;
		fatigued.hesitationMaxMs = hesitationMaxMs * fatigueMultiplier;

		// Longer submovement pauses
		fatigued.submovementPauseMinMs = submovementPauseMinMs * fatigueMultiplier;
		fatigued.submovementPauseMaxMs = submovementPauseMaxMs * fatigueMultiplier;

		// Slower clicks: tired finger
		fatigued.clickHoldMinMs = clickHoldMinMs * fatigueMultiplier;
		fatigued.clickHoldMaxMs = clickHoldMaxMs * fatigueMultiplier;
		fatigued.clickDriftPixels = clickDriftPixels;
		fatigued.preClickDwellMinMs = preClickDwellMinMs * fatigueMultiplier;
		fatigued.preClickDwellMaxMs = preClickDwellMaxMs * fatigueMultiplier;

		// Preserve motion profile from activity preset
		fatigued.motionProfile = this.motionProfile;

		return fatigued;
	}

	public void save(File file)
	{
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		try
		{
			File parentDirectory = file.getParentFile();
			if (parentDirectory != null && !parentDirectory.exists())
			{
				parentDirectory.mkdirs();
			}

			try (FileWriter writer = new FileWriter(file))
			{
				gson.toJson(this, writer);
			}
			log.info("Mouse profile saved to {}", file.getAbsolutePath());
		}
		catch (IOException exception)
		{
			log.error("Failed to save mouse profile to {}", file.getAbsolutePath(), exception);
		}
	}

	public static MouseProfile load(File file)
	{
		if (!file.exists())
		{
			log.info("No mouse profile found at {}, using defaults", file.getAbsolutePath());
			return getDefault();
		}

		Gson gson = new Gson();
		try (FileReader reader = new FileReader(file))
		{
			MouseProfile loadedProfile = gson.fromJson(reader, MouseProfile.class);
			if (loadedProfile == null)
			{
				log.warn("Mouse profile file was empty, using defaults");
				return getDefault();
			}
			log.info("Mouse profile loaded from {}", file.getAbsolutePath());
			return loadedProfile;
		}
		catch (Exception exception)
		{
			log.error("Failed to load mouse profile from {}, using defaults", file.getAbsolutePath(), exception);
			return getDefault();
		}
	}

	public static File getProfileFile()
	{
		return new File(System.getProperty("user.home") + "/.runelite/" + PROFILE_FILE_NAME);
	}

	// Getters

	public double getFittsIntercept()
	{
		return fittsIntercept;
	}

	public double getFittsSlope()
	{
		return fittsSlope;
	}

	public double getFittsVariance()
	{
		return fittsVariance;
	}

	public double getCurveBiasDirection()
	{
		return curveBiasDirection;
	}

	public double getCurveIntensityMin()
	{
		return curveIntensityMin;
	}

	public double getCurveIntensityMax()
	{
		return curveIntensityMax;
	}

	public double getPeakVelocityPosition()
	{
		return peakVelocityPosition;
	}

	public double getOvershootProbability()
	{
		return overshootProbability;
	}

	public double getOvershootMinPixels()
	{
		return overshootMinPixels;
	}

	public double getOvershootMaxPixels()
	{
		return overshootMaxPixels;
	}

	public double getTremorAmplitude()
	{
		return tremorAmplitude;
	}

	public double getHesitationProbability()
	{
		return hesitationProbability;
	}

	public double getHesitationMinMs()
	{
		return hesitationMinMs;
	}

	public double getHesitationMaxMs()
	{
		return hesitationMaxMs;
	}

	public double getSubmovementPauseMinMs()
	{
		return submovementPauseMinMs;
	}

	public double getSubmovementPauseMaxMs()
	{
		return submovementPauseMaxMs;
	}

	// Setters

	public void setFittsIntercept(double fittsIntercept)
	{
		this.fittsIntercept = fittsIntercept;
	}

	public void setFittsSlope(double fittsSlope)
	{
		this.fittsSlope = fittsSlope;
	}

	public void setFittsVariance(double fittsVariance)
	{
		this.fittsVariance = fittsVariance;
	}

	public void setCurveBiasDirection(double curveBiasDirection)
	{
		this.curveBiasDirection = curveBiasDirection;
	}

	public void setCurveIntensityMin(double curveIntensityMin)
	{
		this.curveIntensityMin = curveIntensityMin;
	}

	public void setCurveIntensityMax(double curveIntensityMax)
	{
		this.curveIntensityMax = curveIntensityMax;
	}

	public void setPeakVelocityPosition(double peakVelocityPosition)
	{
		this.peakVelocityPosition = peakVelocityPosition;
	}

	public void setOvershootProbability(double overshootProbability)
	{
		this.overshootProbability = overshootProbability;
	}

	public void setOvershootMinPixels(double overshootMinPixels)
	{
		this.overshootMinPixels = overshootMinPixels;
	}

	public void setOvershootMaxPixels(double overshootMaxPixels)
	{
		this.overshootMaxPixels = overshootMaxPixels;
	}

	public void setTremorAmplitude(double tremorAmplitude)
	{
		this.tremorAmplitude = tremorAmplitude;
	}

	public void setHesitationProbability(double hesitationProbability)
	{
		this.hesitationProbability = hesitationProbability;
	}

	public void setHesitationMinMs(double hesitationMinMs)
	{
		this.hesitationMinMs = hesitationMinMs;
	}

	public void setHesitationMaxMs(double hesitationMaxMs)
	{
		this.hesitationMaxMs = hesitationMaxMs;
	}

	public void setSubmovementPauseMinMs(double submovementPauseMinMs)
	{
		this.submovementPauseMinMs = submovementPauseMinMs;
	}

	public void setSubmovementPauseMaxMs(double submovementPauseMaxMs)
	{
		this.submovementPauseMaxMs = submovementPauseMaxMs;
	}

	public double getClickHoldMinMs()
	{
		return clickHoldMinMs;
	}

	public void setClickHoldMinMs(double clickHoldMinMs)
	{
		this.clickHoldMinMs = clickHoldMinMs;
	}

	public double getClickHoldMaxMs()
	{
		return clickHoldMaxMs;
	}

	public void setClickHoldMaxMs(double clickHoldMaxMs)
	{
		this.clickHoldMaxMs = clickHoldMaxMs;
	}

	public double getClickDriftPixels()
	{
		return clickDriftPixels;
	}

	public void setClickDriftPixels(double clickDriftPixels)
	{
		this.clickDriftPixels = clickDriftPixels;
	}

	public double getPreClickDwellMinMs()
	{
		return preClickDwellMinMs;
	}

	public void setPreClickDwellMinMs(double preClickDwellMinMs)
	{
		this.preClickDwellMinMs = preClickDwellMinMs;
	}

	public double getPreClickDwellMaxMs()
	{
		return preClickDwellMaxMs;
	}

	public void setPreClickDwellMaxMs(double preClickDwellMaxMs)
	{
		this.preClickDwellMaxMs = preClickDwellMaxMs;
	}

	public double[] getDistanceSpeedPixelsPerMs()
	{
		return distanceSpeedPixelsPerMs;
	}

	public void setDistanceSpeedPixelsPerMs(double[] distanceSpeedPixelsPerMs)
	{
		this.distanceSpeedPixelsPerMs = distanceSpeedPixelsPerMs;
	}

	/**
	 * Returns the motion profile used for flow-based velocity modulation.
	 * The motion profile determines which flow templates and noise/deviation
	 * parameters are used during mouse path generation.
	 */
	public MotionProfile getMotionProfile()
	{
		return motionProfile;
	}

	public void setMotionProfile(MotionProfile motionProfile)
	{
		this.motionProfile = motionProfile;
	}

	/**
	 * Returns the user's measured speed in pixels/ms for a given movement distance.
	 * Interpolates between the distance bands for smooth transitions.
	 *
	 * @param distancePixels the distance of the intended movement
	 * @return speed in pixels per millisecond
	 */
	public double getSpeedForDistance(double distancePixels)
	{
		// Distance band boundaries
		double[] bandBoundaries = {0, 100, 300, 600, 1200};

		if (distanceSpeedPixelsPerMs == null || distanceSpeedPixelsPerMs.length < 4)
		{
			return 0.5; // fallback
		}

		// Find which band the distance falls into and interpolate
		for (int bandIndex = 0; bandIndex < bandBoundaries.length - 1; bandIndex++)
		{
			if (distancePixels <= bandBoundaries[bandIndex + 1] || bandIndex == bandBoundaries.length - 2)
			{
				if (bandIndex >= distanceSpeedPixelsPerMs.length)
				{
					return distanceSpeedPixelsPerMs[distanceSpeedPixelsPerMs.length - 1];
				}

				if (bandIndex + 1 >= distanceSpeedPixelsPerMs.length)
				{
					return distanceSpeedPixelsPerMs[bandIndex];
				}

				double bandStart = bandBoundaries[bandIndex];
				double bandEnd = bandBoundaries[bandIndex + 1];
				double progress = (distancePixels - bandStart) / (bandEnd - bandStart);
				progress = Math.max(0, Math.min(1, progress));

				return distanceSpeedPixelsPerMs[bandIndex]
					+ (distanceSpeedPixelsPerMs[bandIndex + 1] - distanceSpeedPixelsPerMs[bandIndex]) * progress;
			}
		}

		return distanceSpeedPixelsPerMs[distanceSpeedPixelsPerMs.length - 1];
	}

	/**
	 * Calculates the expected movement duration in milliseconds for a given distance,
	 * based on the user's measured speed profile. This replaces Fitts's Law for
	 * calibrated profiles since it uses actual measured data.
	 *
	 * @param distancePixels the distance to move
	 * @return estimated duration in milliseconds
	 */
	public long getMovementDurationForDistance(double distancePixels)
	{
		double speed = getSpeedForDistance(distancePixels);
		if (speed <= 0.01)
		{
			return 500;
		}

		long durationMs = (long)(distancePixels / speed);

		// Apply personal variance
		double varianceFactor = 1.0 + (java.util.concurrent.ThreadLocalRandom.current().nextGaussian() * fittsVariance);
		varianceFactor = Math.max(0.7, Math.min(1.3, varianceFactor));

		return Math.max(30, (long)(durationMs * varianceFactor));
	}

	@Override
	public String toString()
	{
		return "MouseProfile{"
			+ "fittsIntercept=" + fittsIntercept
			+ ", fittsSlope=" + fittsSlope
			+ ", fittsVariance=" + fittsVariance
			+ ", curveBiasDirection=" + curveBiasDirection
			+ ", curveIntensityMin=" + curveIntensityMin
			+ ", curveIntensityMax=" + curveIntensityMax
			+ ", peakVelocityPosition=" + peakVelocityPosition
			+ ", overshootProbability=" + overshootProbability
			+ ", overshootMinPixels=" + overshootMinPixels
			+ ", overshootMaxPixels=" + overshootMaxPixels
			+ ", tremorAmplitude=" + tremorAmplitude
			+ ", hesitationProbability=" + hesitationProbability
			+ ", hesitationMinMs=" + hesitationMinMs
			+ ", hesitationMaxMs=" + hesitationMaxMs
			+ ", submovementPauseMinMs=" + submovementPauseMinMs
			+ ", submovementPauseMaxMs=" + submovementPauseMaxMs
			+ "}";
	}

	// ========== Intensity-Tier Factory Methods (matching Microbot's ActivityIntensity) ==========

	/**
	 * VERY_LOW intensity = averageComputerUser (400ms base, relaxed, high tremor).
	 * Used for casual browsing, slow skilling, idle activities.
	 */
	public static MouseProfile veryLow()
	{
		MouseProfile profile = new MouseProfile();
		profile.distanceSpeedPixelsPerMs = new double[]{0.3, 0.5, 0.8, 1.0};
		profile.fittsIntercept = 50.0;
		profile.fittsSlope = 130.0;
		profile.tremorAmplitude = 2.0;
		profile.overshootProbability = 0.20;
		profile.hesitationProbability = 0.12;
		profile.motionProfile = MotionProfile.casualUser();
		return profile;
	}

	/**
	 * LOW intensity = normalGamer (175ms base, standard gameplay).
	 * Used for general skilling: woodcutting, mining, fishing, cooking.
	 */
	public static MouseProfile low()
	{
		MouseProfile profile = new MouseProfile();
		profile.motionProfile = MotionProfile.normalGamer();
		return profile;
	}

	/**
	 * MODERATE intensity = fastGamer (145ms base, efficient clicking).
	 * Used for agility, thieving, slayer, active combat.
	 */
	public static MouseProfile moderate()
	{
		MouseProfile profile = new MouseProfile();
		profile.distanceSpeedPixelsPerMs = new double[]{0.7, 1.1, 1.5, 1.9};
		profile.fittsIntercept = 25.0;
		profile.fittsSlope = 85.0;
		profile.tremorAmplitude = 1.0;
		profile.overshootProbability = 0.10;
		profile.hesitationProbability = 0.04;
		profile.curveIntensityMin = 0.03;
		profile.curveIntensityMax = 0.10;
		profile.motionProfile = MotionProfile.fastGamer();
		return profile;
	}

	/**
	 * HIGH intensity = fastGamer+ (slightly faster than moderate).
	 * Used for high-intensity combat, bossing, rapid switching.
	 */
	public static MouseProfile high()
	{
		MouseProfile profile = moderate();
		profile.distanceSpeedPixelsPerMs = new double[]{0.8, 1.2, 1.7, 2.1};
		profile.fittsIntercept = 20.0;
		profile.fittsSlope = 75.0;
		return profile;
	}

	/**
	 * EXTREME intensity = superFastGamer (105ms base, tick-perfect clicking).
	 * Used for prayer flicking, tick manipulation, PvP.
	 */
	public static MouseProfile extreme()
	{
		MouseProfile profile = new MouseProfile();
		profile.distanceSpeedPixelsPerMs = new double[]{1.0, 1.5, 2.0, 2.5};
		profile.fittsIntercept = 15.0;
		profile.fittsSlope = 60.0;
		profile.tremorAmplitude = 0.5;
		profile.overshootProbability = 0.05;
		profile.hesitationProbability = 0.0;
		profile.curveIntensityMin = 0.02;
		profile.curveIntensityMax = 0.06;
		profile.submovementPauseMinMs = 15;
		profile.submovementPauseMaxMs = 40;
		profile.clickHoldMinMs = 20;
		profile.clickHoldMaxMs = 60;
		profile.motionProfile = MotionProfile.superFastGamer();
		return profile;
	}

}
