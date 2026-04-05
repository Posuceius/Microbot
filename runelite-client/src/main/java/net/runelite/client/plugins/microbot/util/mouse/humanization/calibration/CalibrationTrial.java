package net.runelite.client.plugins.microbot.util.mouse.humanization.calibration;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable data class representing one completed dot-click calibration trial.
 * A trial captures the full trajectory from the mouse's starting position to the
 * moment the user clicked a target dot, including all intermediate samples, click
 * timing, and where the click actually landed. Computed metrics such as distance
 * band and movement duration are derived on demand from the stored data.
 */
public class CalibrationTrial
{
	private final Point startPosition;
	private final Point targetPosition;
	private final int targetRadius;
	private final List<CalibrationSample> samples;
	private final long mouseDownTimestampMs;
	private final long mouseUpTimestampMs;
	private final Point clickPosition;

	/**
	 * Constructs a new CalibrationTrial. The provided samples list is defensively
	 * copied so that external modifications cannot affect this immutable record.
	 *
	 * @param startPosition the mouse position when the trial began
	 * @param targetPosition the center of the target dot
	 * @param targetRadius the radius of the target dot in pixels
	 * @param samples the recorded trajectory samples during this trial
	 * @param mouseDownTimestampMs the system time in milliseconds when the mouse button was pressed
	 * @param mouseUpTimestampMs the system time in milliseconds when the mouse button was released
	 * @param clickPosition the actual screen position where the click landed
	 */
	public CalibrationTrial(
		Point startPosition,
		Point targetPosition,
		int targetRadius,
		List<CalibrationSample> samples,
		long mouseDownTimestampMs,
		long mouseUpTimestampMs,
		Point clickPosition)
	{
		this.startPosition = startPosition;
		this.targetPosition = targetPosition;
		this.targetRadius = targetRadius;
		this.samples = new ArrayList<>(samples);
		this.mouseDownTimestampMs = mouseDownTimestampMs;
		this.mouseUpTimestampMs = mouseUpTimestampMs;
		this.clickPosition = clickPosition;
	}

	/**
	 * Returns the mouse position when this trial began.
	 *
	 * @return the starting position
	 */
	public Point getStartPosition()
	{
		return startPosition;
	}

	/**
	 * Returns the center position of the target dot for this trial.
	 *
	 * @return the target position
	 */
	public Point getTargetPosition()
	{
		return targetPosition;
	}

	/**
	 * Returns the radius of the target dot in pixels.
	 *
	 * @return the target radius
	 */
	public int getTargetRadius()
	{
		return targetRadius;
	}

	/**
	 * Returns an unmodifiable view of the trajectory samples recorded during this trial.
	 *
	 * @return an unmodifiable list of samples ordered by recording time
	 */
	public List<CalibrationSample> getSamples()
	{
		return Collections.unmodifiableList(samples);
	}

	/**
	 * Returns the system time in milliseconds when the mouse button was pressed.
	 *
	 * @return the mouse-down timestamp
	 */
	public long getMouseDownTimestampMs()
	{
		return mouseDownTimestampMs;
	}

	/**
	 * Returns the system time in milliseconds when the mouse button was released.
	 *
	 * @return the mouse-up timestamp
	 */
	public long getMouseUpTimestampMs()
	{
		return mouseUpTimestampMs;
	}

	/**
	 * Returns the actual screen position where the click landed during this trial.
	 *
	 * @return the click position
	 */
	public Point getClickPosition()
	{
		return clickPosition;
	}

	/**
	 * Computes the Euclidean distance in pixels from the start position to the
	 * target position. This is the straight-line distance the mouse needed to travel.
	 *
	 * @return the distance in pixels
	 */
	public double getDistance()
	{
		double deltaX = targetPosition.getX() - startPosition.getX();
		double deltaY = targetPosition.getY() - startPosition.getY();
		return Math.sqrt(deltaX * deltaX + deltaY * deltaY);
	}

	/**
	 * Categorizes the straight-line distance from start to target into one of four
	 * bands used to group trials by movement scale during profile analysis:
	 * <ul>
	 *   <li>0 - short range: 0 to 100 pixels</li>
	 *   <li>1 - medium range: 100 to 300 pixels</li>
	 *   <li>2 - long range: 300 to 600 pixels</li>
	 *   <li>3 - very long range: 600 or more pixels</li>
	 * </ul>
	 *
	 * @return the distance band index (0-3)
	 */
	public int getDistanceBand()
	{
		double distance = getDistance();
		if (distance < 100)
		{
			return 0;
		}
		else if (distance < 300)
		{
			return 1;
		}
		else if (distance < 600)
		{
			return 2;
		}
		else
		{
			return 3;
		}
	}

	/**
	 * Computes the total movement duration in milliseconds as the difference between
	 * the timestamp of the last sample and the timestamp of the first sample. Returns
	 * zero if there are fewer than two samples.
	 *
	 * @return movement duration in milliseconds, or 0 if insufficient samples exist
	 */
	public long getMovementDurationMs()
	{
		if (samples.size() < 2)
		{
			return 0;
		}
		long firstTimestamp = samples.get(0).getTimestampMs();
		long lastTimestamp = samples.get(samples.size() - 1).getTimestampMs();
		return lastTimestamp - firstTimestamp;
	}
}
