package net.runelite.client.plugins.microbot.util.mouse.naturalmouse;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.mouse.humanization.HumanMouseMovement;
import net.runelite.client.plugins.microbot.util.mouse.humanization.MotionProfile;
import net.runelite.client.plugins.microbot.util.mouse.humanization.MouseProfile;
import net.runelite.client.plugins.microbot.util.mouse.humanization.calibration.MouseCalibrationService;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.api.SystemCalls;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.support.DefaultMouseMotionNature;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.support.MouseMotionNature;

import javax.inject.Inject;
import java.awt.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public class NaturalMouse {
	public final MouseMotionNature nature;
	private final ThreadLocalRandom random = ThreadLocalRandom.current();
	private final ExecutorService executorService = Executors.newSingleThreadExecutor();
	@Inject
	private Client client;

	@Inject
	public NaturalMouse() {
		nature = new DefaultMouseMotionNature();
		nature.setSystemCalls(new SystemCallsImpl());
	}

	public synchronized void moveTo(int dx, int dy) {
//		if(Rs2UiHelper.isStretchedEnabled())
//		{
//			dx = Rs2UiHelper.stretchX(dx);
//			dy = Rs2UiHelper.stretchY(dy);
//		}
		int finalDx = dx;
		int finalDy = dy;
		Point mousePos = Microbot.getMouse().getMousePosition();
		// check if current mouse position is already at the destination
		if (mousePos.x == finalDx && mousePos.y == finalDy) {
			return;
		}

		if (!Microbot.getClient().isClientThread()) {
			move(finalDx, finalDy);
		} else {

			executorService.submit(() -> move(finalDx, finalDy));
		}
	}

	private synchronized void move(int dx, int dy) {
		Point currentPos = null;
		try {
			currentPos = Microbot.getMouse().getMousePosition();
		} catch (Exception exception) {
			// Fallback if mouse position unavailable
		}
		int startX = currentPos != null ? currentPos.x : 0;
		int startY = currentPos != null ? currentPos.y : 0;

		MouseProfile profile = getProfileForIntensity();

		List<HumanMouseMovement.TimedPoint> path =
			HumanMouseMovement.generateTimedPath(startX, startY, dx, dy, profile);

		for (HumanMouseMovement.TimedPoint point : path) {
			nature.getSystemCalls().setMousePosition(point.positionX, point.positionY);
			if (point.delayAfterMillis > 0.5) {
				nature.getSystemCalls().sleep((long) point.delayAfterMillis);
			}
		}
	}

	/**
	 * Maps the current {@link ActivityIntensity} to a {@link MouseProfile} with
	 * research-based humanization parameters.
	 *
	 * Integrates with the existing antiban system:
	 * - Applies mouse fatigue (gradually increasing base time) when simulateFatigue is enabled
	 * - Disables overshoots when simulateMistakes is disabled
	 *
	 * Fatigue max base times preserve the same differential as the original FactoryTemplates:
	 * normalGamer +50ms, fastGamer +50ms, superFastGamer +30ms.
	 */
	private MouseProfile getProfileForIntensity() {
		ActivityIntensity intensity = Rs2Antiban.getActivityIntensity();
		if (intensity == null) intensity = ActivityIntensity.LOW;

		MouseProfile profile;
		int maxBaseTimeMs = getMaxBaseTimeMsForIntensity(intensity);

		if (MouseCalibrationService.isCalibrated()) {
			log.debug("Using calibrated mouse profile for intensity {}", intensity);
			profile = MouseCalibrationService.getScaledProfile(intensity);
		} else {
			switch (intensity) {
				case VERY_LOW:
					log.debug("Using casual user mouse profile");
					profile = MouseProfile.veryLow();
					break;
				case LOW:
					log.debug("Using normal gamer mouse profile");
					profile = MouseProfile.low();
					break;
				case MODERATE:
					log.debug("Using fast gamer mouse profile");
					profile = MouseProfile.moderate();
					break;
				case HIGH:
					log.debug("Using fast gamer mouse profile");
					profile = MouseProfile.high();
					break;
				case EXTREME:
					log.debug("Using super fast gamer mouse profile");
					profile = MouseProfile.extreme();
					break;
				default:
					log.debug("Default: Using normal gamer mouse profile");
					profile = MouseProfile.low();
					break;
			}
		}

		MotionProfile motionProfile = profile.getMotionProfile();

		// Apply fatigue: gradually increase base time over session (matches original FactoryTemplates behavior)
		if (Rs2AntibanSettings.simulateFatigue && maxBaseTimeMs > 0) {
			int fatiguedBaseTime = Rs2Antiban.mouseFatigue.calculateBaseTimeWithNoise(
				motionProfile.getBaseTimeMs(), maxBaseTimeMs);
			motionProfile = motionProfile.withBaseTimeMs(fatiguedBaseTime);
		}

		// Respect simulateMistakes setting: disable overshoots when off (matches original FactoryTemplates behavior)
		if (!Rs2AntibanSettings.simulateMistakes) {
			motionProfile = motionProfile.withOvershootCount(0);
		}

		profile.setMotionProfile(motionProfile);
		return profile;
	}

	private static int getMaxBaseTimeMsForIntensity(ActivityIntensity intensity) {
		switch (intensity) {
			case VERY_LOW: return 0;
			case LOW: return 225;
			case MODERATE: return 195;
			case HIGH: return 195;
			case EXTREME: return 135;
			default: return 225;
		}
	}

	/**
	 * Moves the mouse off screen with a default 100% chance.
	 * This method will always move the mouse off screen when called.
	 */
	public void moveOffScreen() {
		// Always move the mouse off screen (default behavior)
		moveOffScreen(100.0); // Calls the overloaded method with a 100% chance
	}

	/**
	 * Moves the mouse off screen based on a given percentage chance.
	 *
	 * @param chancePercentage the chance (in percentage) to move the mouse off screen.
	 *                         This value should be between 0.0 and 100.0 (inclusive).
	 *                         Note: This parameter should not be a fractional value between
	 *                         0.0 and 0.99; use values representing a whole percentage (e.g., 25.0, 50.0).
	 */
	public void moveOffScreen(double chancePercentage) {
		if (chancePercentage >= 100 || Rs2Random.dicePercentage(chancePercentage)) {
			// Move off screen if the chance is met
			int horizontal = random.nextBoolean() ? -1 : client.getCanvasWidth() + 1;
			int vertical = random.nextBoolean() ? -1 : client.getCanvasHeight() + 1;

			boolean exitHorizontally = random.nextBoolean();
			if (exitHorizontally) {
				moveTo(horizontal, random.nextInt(0, client.getCanvasHeight() + 1));
			} else {
				moveTo(random.nextInt(0, client.getCanvasWidth() + 1), vertical);
			}
		}
	}

	// Move to a random point on the screen
	public void moveRandom() {
		moveTo(random.nextInt(0, client.getCanvasWidth() + 1), random.nextInt(0, client.getCanvasHeight() + 1));
	}

	private class SystemCallsImpl implements SystemCalls {
		@Override
		public long currentTimeMillis() {
			return System.currentTimeMillis();
		}

		@Override
		public void sleep(long time) {
			Global.sleep((int) time);
		}

		@Override
		public Dimension getScreenSize() {
			return Microbot.getClient().getCanvas().getSize();
		}

		@Override
		public void setMousePosition(int x, int y) {
			Microbot.getMouse().move(x, y);

		}
	}
}
