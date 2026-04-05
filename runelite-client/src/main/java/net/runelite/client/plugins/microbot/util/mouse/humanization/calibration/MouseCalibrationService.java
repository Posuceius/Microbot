package net.runelite.client.plugins.microbot.util.mouse.humanization.calibration;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;
import net.runelite.client.plugins.microbot.util.mouse.humanization.MotionProfile;
import net.runelite.client.plugins.microbot.util.mouse.humanization.MouseProfile;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.List;

/**
 * Static facade for the mouse calibration system. This is the single public entry point
 * that all callers (NaturalMouse, MousePanel, etc.) use to interact with calibration.
 * Internal details - the dialog, analyzer, scaler, and file format - are fully hidden
 * behind this class. No caller should import any other calibration type directly.
 *
 * <p>The calibrated profile is loaded lazily from disk on first access and cached in memory.
 * The cache is invalidated when {@link #clearCalibration()} is called.</p>
 */
@Slf4j
public final class MouseCalibrationService
{
	private static final File CALIBRATED_PROFILE_FILE =
		new File(System.getProperty("user.home") + File.separator + ".runelite" + File.separator + "calibrated-mouse-profile.json");

	private static volatile MouseProfile cachedBaseline = null;

	private MouseCalibrationService()
	{
		// Non-instantiable static facade
	}

	/**
	 * Opens the calibration dialog and, when the user completes it, analyzes the recorded
	 * trials and saves the resulting profile to disk. The dialog is modal - this method
	 * blocks until the user closes or completes the calibration session.
	 *
	 * <p>If the user cancels, or if fewer than the minimum required valid trials were
	 * collected, this method logs the outcome and shows an appropriate message dialog
	 * without modifying any existing calibration data.</p>
	 *
	 * @param parent the AWT component to use as the dialog owner (may be null)
	 */
	public static void startCalibration(Component parent)
	{
		log.info("Starting mouse calibration session");
		CalibrationDialog dialog = new CalibrationDialog(parent);
		dialog.setVisible(true); // Modal - blocks until closed

		List<CalibrationTrial> completedTrials = dialog.getCompletedTrials();
		if (completedTrials == null || completedTrials.isEmpty())
		{
			log.info("Calibration cancelled by user");
			return;
		}

		log.info("Calibration session complete with {} trials, analyzing...", completedTrials.size());
		MouseProfile analyzedProfile = CalibrationAnalyzer.analyze(completedTrials);

		if (analyzedProfile == null)
		{
			log.warn("Calibration analysis failed - insufficient valid data");
			JOptionPane.showMessageDialog(parent,
				"Calibration failed - not enough valid movement data.\nPlease try again and click each dot carefully.",
				"Calibration Failed",
				JOptionPane.WARNING_MESSAGE);
			return;
		}

		analyzedProfile.save(CALIBRATED_PROFILE_FILE);
		cachedBaseline = analyzedProfile;
		log.info("Mouse calibration saved successfully to {}", CALIBRATED_PROFILE_FILE.getAbsolutePath());
	}

	/**
	 * Returns a {@link MouseProfile} scaled to the requested activity intensity tier, derived
	 * from the user's calibrated baseline. Returns {@code null} if the user has not yet
	 * completed a calibration session.
	 *
	 * @param intensity the activity intensity tier to scale the baseline profile to
	 * @return a scaled profile matching the requested intensity, or {@code null} if not calibrated
	 */
	public static MouseProfile getScaledProfile(ActivityIntensity intensity)
	{
		MouseProfile baseline = loadBaseline();
		if (baseline == null)
		{
			return null;
		}
		return IntensityScaler.scale(baseline, intensity);
	}

	/**
	 * Returns {@code true} if a calibrated profile exists either in memory or on disk.
	 *
	 * @return true if calibration data is available
	 */
	public static boolean isCalibrated()
	{
		return cachedBaseline != null || CALIBRATED_PROFILE_FILE.exists();
	}

	/**
	 * Clears the in-memory cache and deletes the calibration profile file from disk.
	 * After this call, {@link #isCalibrated()} will return {@code false} until a new
	 * calibration session is completed.
	 */
	public static void clearCalibration()
	{
		cachedBaseline = null;
		if (CALIBRATED_PROFILE_FILE.exists())
		{
			boolean deleted = CALIBRATED_PROFILE_FILE.delete();
			if (deleted)
			{
				log.info("Calibration profile cleared");
			}
			else
			{
				log.warn("Failed to delete calibration profile file: {}", CALIBRATED_PROFILE_FILE.getAbsolutePath());
			}
		}
	}

	/**
	 * Returns the raw calibrated baseline profile for display purposes, such as rendering
	 * parameter values in the settings UI. Returns {@code null} if the user has not yet
	 * completed a calibration session.
	 *
	 * @return the calibrated baseline profile, or {@code null} if not calibrated
	 */
	public static MouseProfile getCalibratedBaseline()
	{
		MouseProfile baseline = loadBaseline();
		if (baseline == null)
		{
			return null;
		}
		// Return a defensive copy to prevent callers from mutating the cached instance
		Gson gson = new Gson();
		MouseProfile copy = gson.fromJson(gson.toJson(baseline), MouseProfile.class);
		copy.setMotionProfile(MotionProfile.normalGamer());
		return copy;
	}

	/**
	 * Loads and caches the baseline profile. Returns the in-memory cached instance if
	 * available, otherwise reads from disk. Returns {@code null} if no profile file exists.
	 *
	 * <p>After loading from disk, the transient {@link MotionProfile} field - which is not
	 * serialized by Gson - is restored to a sensible default so the profile is immediately
	 * usable by the movement generator.</p>
	 *
	 * @return the cached or freshly-loaded baseline profile, or {@code null} if not calibrated
	 */
	private static MouseProfile loadBaseline()
	{
		if (cachedBaseline != null)
		{
			return cachedBaseline;
		}

		if (!CALIBRATED_PROFILE_FILE.exists())
		{
			return null;
		}

		MouseProfile loaded = MouseProfile.load(CALIBRATED_PROFILE_FILE);
		// Restore the transient MotionProfile field that Gson does not deserialize
		loaded.setMotionProfile(MotionProfile.normalGamer());
		cachedBaseline = loaded;
		log.info("Loaded calibrated mouse profile from disk");
		return cachedBaseline;
	}
}
