package net.runelite.client.plugins.microbot.util.tick;

/**
 * Callback interface for components that wish to receive notification on each game tick.
 * <p>
 * Implementations are invoked on the client thread as part of the tick-synchronized
 * execution pipeline. This interface is part of the Internal API (Layer 1) and should
 * not be exposed directly to consumer scripts.
 */
public interface TickListener
{
	/**
	 * Called once per game tick, on the client thread, before post-tick processing.
	 *
	 * @param tickCount the number of ticks that have elapsed since the listener was registered
	 */
	void onGameTick(int tickCount);

	/**
	 * Called after all {@link #onGameTick(int)} handlers have been invoked for the current tick.
	 * Provides a hook for any cleanup or follow-up work that must run after primary tick logic.
	 * <p>
	 * The default implementation is a no-op.
	 *
	 * @param tickCount the number of ticks that have elapsed since the listener was registered
	 */
	default void onPostTick(int tickCount)
	{
	}
}
