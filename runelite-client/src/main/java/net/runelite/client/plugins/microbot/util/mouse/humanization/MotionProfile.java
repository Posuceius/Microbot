package net.runelite.client.plugins.microbot.util.mouse.humanization;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Configures a complete motion style with parameters that feed into {@link HumanMouseMovement}.
 *
 * Each profile represents a different user archetype with distinct movement characteristics
 * mapped to {@link ActivityPreset} intensity tiers. The profiles control timing, noise,
 * overshoot behavior, and which velocity flow patterns are available for selection.
 *
 * Profiles are created via static factory methods - the constructor is private to ensure
 * only well-defined presets are used.
 */
public class MotionProfile
{
	private final int baseTimeMs;
	private final double noisinessDivider;
	private final double slopeDivider;
	private final int overshootCount;
	private final int minOvershootMs;
	private final int reactionTimeVariationMs;
	private final List<FlowTemplate.Flow> flows;

	private MotionProfile(
		int baseTimeMs,
		double noisinessDivider,
		double slopeDivider,
		int overshootCount,
		int minOvershootMs,
		int reactionTimeVariationMs,
		List<FlowTemplate.Flow> flows)
	{
		this.baseTimeMs = baseTimeMs;
		this.noisinessDivider = noisinessDivider;
		this.slopeDivider = slopeDivider;
		this.overshootCount = overshootCount;
		this.minOvershootMs = minOvershootMs;
		this.reactionTimeVariationMs = reactionTimeVariationMs;
		this.flows = flows;
	}

	/**
	 * A relaxed, casual mouse movement profile with moderate speed and full flow variety.
	 * Suitable for low-intensity activities like skilling or browsing interfaces.
	 *
	 * @return a MotionProfile configured for casual user behavior
	 */
	public static MotionProfile casualUser()
	{
		return new MotionProfile(
			400,
			2.0,
			10.0,
			4,
			40,
			110,
			FlowTemplate.getFlowsForIntensity(true)
		);
	}

	/**
	 * A standard gamer profile with balanced speed and full flow variety.
	 * Suitable for general gameplay with moderate interaction rates.
	 *
	 * @return a MotionProfile configured for normal gamer behavior
	 */
	public static MotionProfile normalGamer()
	{
		return new MotionProfile(
			175,
			2.0,
			10.0,
			4,
			40,
			100,
			FlowTemplate.getFlowsForIntensity(true)
		);
	}

	/**
	 * A fast gamer profile with quick movements and reduced flow variety.
	 * Excludes erratic flows to avoid unrealistic pauses during rapid interactions.
	 * Suitable for combat or high-APM activities.
	 *
	 * @return a MotionProfile configured for fast gamer behavior
	 */
	public static MotionProfile fastGamer()
	{
		return new MotionProfile(
			145,
			2.0,
			10.0,
			3,
			130,
			100,
			FlowTemplate.getFlowsForIntensity(false)
		);
	}

	/**
	 * An extremely fast gamer profile with minimal overshoots and tight reaction times.
	 * Uses only smooth, intense flows for rapid precision clicking.
	 * Suitable for tick-perfect activities or PvP.
	 *
	 * @return a MotionProfile configured for super fast gamer behavior
	 */
	public static MotionProfile superFastGamer()
	{
		return new MotionProfile(
			105,
			2.0,
			10.0,
			2,
			100,
			90,
			FlowTemplate.getFlowsForIntensity(false)
		);
	}

	/**
	 * A slow, deliberate mouse movement profile with high noise and full flow variety.
	 * Mimics an inexperienced or cautious user with longer movement times.
	 * Suitable for AFK-style activities or very relaxed botting profiles.
	 *
	 * @return a MotionProfile configured for slow user behavior
	 */
	public static MotionProfile slowUser()
	{
		return new MotionProfile(
			1000,
			1.6,
			9.0,
			3,
			40,
			110,
			FlowTemplate.getFlowsForIntensity(true)
		);
	}

	/**
	 * Returns a randomly selected flow from this profile's available flow templates.
	 *
	 * @return a random {@link FlowTemplate.Flow} from the configured flow list
	 */
	public FlowTemplate.Flow getRandomFlow()
	{
		int randomIndex = ThreadLocalRandom.current().nextInt(flows.size());
		return flows.get(randomIndex);
	}

	public int getBaseTimeMs()
	{
		return baseTimeMs;
	}

	public double getNoisinessDivider()
	{
		return noisinessDivider;
	}

	public double getSlopeDivider()
	{
		return slopeDivider;
	}

	public int getOvershootCount()
	{
		return overshootCount;
	}

	public int getMinOvershootMs()
	{
		return minOvershootMs;
	}

	public int getReactionTimeVariationMs()
	{
		return reactionTimeVariationMs;
	}

	public List<FlowTemplate.Flow> getFlows()
	{
		return flows;
	}

	/**
	 * Returns a copy of this profile with a different base movement time.
	 * Used to apply fatigue adjustments without mutating the original profile.
	 *
	 * @param baseTimeMs the adjusted base time in milliseconds
	 * @return a new MotionProfile with the modified base time
	 */
	public MotionProfile withBaseTimeMs(int baseTimeMs)
	{
		return new MotionProfile(
			baseTimeMs, this.noisinessDivider, this.slopeDivider,
			this.overshootCount, this.minOvershootMs,
			this.reactionTimeVariationMs, this.flows);
	}

	/**
	 * Returns a copy of this profile with a different overshoot count.
	 * Used to disable overshoots when simulateMistakes is off.
	 *
	 * @param overshootCount the number of overshoots (0 to disable)
	 * @return a new MotionProfile with the modified overshoot count
	 */
	public MotionProfile withOvershootCount(int overshootCount)
	{
		return new MotionProfile(
			this.baseTimeMs, this.noisinessDivider, this.slopeDivider,
			overshootCount, this.minOvershootMs,
			this.reactionTimeVariationMs, this.flows);
	}
}
