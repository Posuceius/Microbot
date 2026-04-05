package net.runelite.client.plugins.microbot.util.mouse.humanization.calibration;

/**
 * Immutable data class representing a single mouse position sample recorded
 * during a calibration trial. Each sample captures where the mouse was at a
 * specific point in time, forming the raw trajectory data used to derive
 * movement profiles.
 */
public class CalibrationSample
{
	private final int x;
	private final int y;
	private final long timestampMs;

	/**
	 * Constructs a new CalibrationSample with the given position and timestamp.
	 *
	 * @param x the mouse X coordinate in screen pixels
	 * @param y the mouse Y coordinate in screen pixels
	 * @param timestampMs the system time in milliseconds when this sample was recorded
	 */
	public CalibrationSample(int x, int y, long timestampMs)
	{
		this.x = x;
		this.y = y;
		this.timestampMs = timestampMs;
	}

	/**
	 * Returns the mouse X coordinate at the time this sample was recorded.
	 *
	 * @return the X position in screen pixels
	 */
	public int getX()
	{
		return x;
	}

	/**
	 * Returns the mouse Y coordinate at the time this sample was recorded.
	 *
	 * @return the Y position in screen pixels
	 */
	public int getY()
	{
		return y;
	}

	/**
	 * Returns the system time in milliseconds when this sample was recorded.
	 *
	 * @return the timestamp in milliseconds
	 */
	public long getTimestampMs()
	{
		return timestampMs;
	}
}
