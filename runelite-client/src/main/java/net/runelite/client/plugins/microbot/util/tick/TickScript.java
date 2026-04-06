package net.runelite.client.plugins.microbot.util.tick;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;

import java.util.function.BooleanSupplier;

/**
 * Base class for tick-driven automation scripts.
 * <p>
 * Extends {@link Script} and implements {@link TickListener} to provide a tick-synchronized
 * programming model. Instead of running logic in a polling loop with {@code sleep()} calls,
 * subclasses override {@link #onTick()} and react to each game tick on the client thread.
 * <p>
 * Lifecycle:
 * <ol>
 *   <li>Call {@link #startTick()} to register with the {@link TickDispatcher} and begin receiving ticks.</li>
 *   <li>Override {@link #onTick()} to implement per-tick logic. Must be fast and non-blocking.</li>
 *   <li>Use the proactive helpers ({@link #onNextTick}, {@link #whenReady}, {@link #afterTicks}) to
 *       schedule one-shot actions on upcoming ticks.</li>
 *   <li>Use the reactive helpers ({@link #reactThen}, {@link #reactWhen}) to schedule actions with
 *       a human-like gaussian reaction delay.</li>
 *   <li>Call {@link #stopTick()} or {@link #shutdown()} to stop receiving ticks.</li>
 * </ol>
 */
@Slf4j
public abstract class TickScript extends Script implements TickListener {

	/** Whether this script is currently registered with the {@link TickDispatcher}. */
	private volatile boolean tickActive = false;

	// -------------------------------------------------------------------------
	// Abstract contract
	// -------------------------------------------------------------------------

	/**
	 * Called on each game tick, on the client thread.
	 * <p>
	 * Implementations must be fast and non-blocking. No sleeping, no I/O, no long-running
	 * computations. Use the action helpers ({@link #onNextTick}, {@link #whenReady},
	 * {@link #reactWhen}, etc.) to defer work to subsequent ticks or off-thread executors.
	 */
	protected abstract void onTick();

	// -------------------------------------------------------------------------
	// Lifecycle
	// -------------------------------------------------------------------------

	/**
	 * Registers this script with the {@link TickDispatcher} to start receiving {@link #onTick()}
	 * callbacks once per game tick.
	 * <p>
	 * Call this from your plugin's {@code startUp()} or your script's {@code run()} method.
	 * Returns {@code true} for convenience so it can be returned directly from {@code run()}.
	 *
	 * @return {@code true} if registration succeeded; {@code false} if the dispatcher is unavailable
	 */
	public boolean startTick() {
		TickDispatcher dispatcher = Microbot.getTickDispatcher();
		if (dispatcher == null) {
			log.error("TickScript.startTick() called but TickDispatcher is not available");
			return false;
		}
		dispatcher.addListener(this);
		tickActive = true;
		log.info("TickScript {} started tick-driven execution", getClass().getSimpleName());
		return true;
	}

	/**
	 * Unregisters this script from the {@link TickDispatcher}, stopping tick callbacks.
	 * Safe to call even if the script was never started or has already been stopped.
	 */
	public void stopTick() {
		TickDispatcher dispatcher = Microbot.getTickDispatcher();
		if (dispatcher != null) {
			dispatcher.removeListener(this);
		}
		tickActive = false;
		log.info("TickScript {} stopped tick-driven execution", getClass().getSimpleName());
	}

	/**
	 * Returns whether this script is actively registered with the {@link TickDispatcher}
	 * and receiving {@link #onTick()} callbacks.
	 *
	 * @return {@code true} if tick callbacks are active
	 */
	public boolean isTickActive() {
		return tickActive;
	}

	// -------------------------------------------------------------------------
	// TickListener implementation
	// -------------------------------------------------------------------------

	/**
	 * Invoked by the {@link TickDispatcher} on every game tick, on the client thread.
	 * Delegates to {@link #onTick()} after passing the {@link #shouldExecuteTick()} guard.
	 *
	 * @param tickCount the current game tick count
	 */
	@Override
	public final void onGameTick(int tickCount) {
		if (!shouldExecuteTick()) return;
		try {
			onTick();
		} catch (Exception exception) {
			log.error("TickScript {} encountered an error in onTick() on tick {}",
				getClass().getSimpleName(), tickCount, exception);
		}
	}

	// -------------------------------------------------------------------------
	// Guard method
	// -------------------------------------------------------------------------

	/**
	 * Evaluates whether {@link #onTick()} should execute for the current tick.
	 * <p>
	 * Returns {@code false} (skip this tick) when any of the following are true:
	 * <ul>
	 *   <li>The player is not logged in.</li>
	 *   <li>All scripts are globally paused.</li>
	 *   <li>A blocking event is currently executing.</li>
	 *   <li>The current thread has been interrupted.</li>
	 * </ul>
	 *
	 * @return {@code true} if it is safe to execute tick logic; {@code false} otherwise
	 */
	protected boolean shouldExecuteTick() {
		if (!Microbot.isLoggedIn()) return false;
		if (Microbot.pauseAllScripts.get()) return false;
		if (Microbot.getBlockingEventManager().isBlocking()) return false;
		if (Thread.currentThread().isInterrupted()) return false;
		return true;
	}

	// -------------------------------------------------------------------------
	// Proactive action helpers
	// -------------------------------------------------------------------------

	/**
	 * Queues an action to run on the very next game tick.
	 * <p>
	 * The action fires immediately on the client thread (proactive) when the next tick arrives.
	 *
	 * @param action the action to execute on the next tick
	 */
	protected void onNextTick(Runnable action) {
		TickDispatcher dispatcher = Microbot.getTickDispatcher();
		if (dispatcher == null) return;
		int registrationTick = dispatcher.getCurrentTick();
		dispatcher.addAction(TickAction.proactive(
			() -> dispatcher.getCurrentTick() > registrationTick,
			action,
			2,
			registrationTick
		));
	}

	/**
	 * Queues an action to fire on the first tick where the given condition is {@code true}.
	 * The action fires immediately on the client thread (proactive/anticipatory).
	 * Defaults to a maximum of 20 ticks (~12 seconds) before the action expires unfired.
	 *
	 * @param condition the condition to evaluate each tick
	 * @param action the action to execute when the condition is met
	 */
	protected void whenReady(BooleanSupplier condition, Runnable action) {
		whenReady(condition, action, 20);
	}

	/**
	 * Queues an action to fire on the first tick where the given condition is {@code true},
	 * with a custom expiry window.
	 *
	 * @param condition the condition to evaluate each tick
	 * @param action the action to execute when the condition is met
	 * @param maxTicks maximum number of ticks to wait before the action expires unfired
	 */
	protected void whenReady(BooleanSupplier condition, Runnable action, int maxTicks) {
		TickDispatcher dispatcher = Microbot.getTickDispatcher();
		if (dispatcher == null) return;
		dispatcher.addAction(TickAction.proactive(condition, action, maxTicks, dispatcher.getCurrentTick()));
	}

	/**
	 * Queues an action to fire exactly {@code count} ticks from now.
	 * The action fires immediately on the client thread (proactive) when the target tick arrives.
	 *
	 * @param count the number of ticks to wait before firing
	 * @param action the action to execute at the target tick
	 */
	protected void afterTicks(int count, Runnable action) {
		TickDispatcher dispatcher = Microbot.getTickDispatcher();
		if (dispatcher == null) return;
		int registrationTick = dispatcher.getCurrentTick();
		int targetTick = registrationTick + count;
		dispatcher.addAction(TickAction.proactive(
			() -> dispatcher.getCurrentTick() >= targetTick,
			action,
			count + 1,
			registrationTick
		));
	}

	// -------------------------------------------------------------------------
	// Reactive action helpers
	// -------------------------------------------------------------------------

	/**
	 * Schedules an action to run unconditionally after a human-like gaussian reaction delay.
	 * Uses the script's default reaction profile ({@link #getReactionMeanMs()} and
	 * {@link #getReactionStdDevMs()}).
	 *
	 * @param action the action to execute after the reaction delay
	 */
	protected void reactThen(Runnable action) {
		reactThen(action, getReactionMeanMs(), getReactionStdDevMs());
	}

	/**
	 * Schedules an action to run unconditionally after a custom gaussian reaction delay.
	 *
	 * @param action the action to execute after the reaction delay
	 * @param meanMs mean reaction delay in milliseconds
	 * @param stdDevMs standard deviation of the reaction delay in milliseconds
	 */
	protected void reactThen(Runnable action, int meanMs, int stdDevMs) {
		TickDispatcher dispatcher = Microbot.getTickDispatcher();
		if (dispatcher == null) return;
		dispatcher.addAction(TickAction.reactive(
			null,
			action,
			1,
			dispatcher.getCurrentTick(),
			meanMs,
			stdDevMs,
			scheduledExecutorService
		));
	}

	/**
	 * Detects a condition on-tick and, once it is true, fires the action after a human-like
	 * gaussian reaction delay off the client thread.
	 * Uses the script's default reaction profile ({@link #getReactionMeanMs()} and
	 * {@link #getReactionStdDevMs()}).
	 * Defaults to a maximum of 20 ticks (~12 seconds) before the action expires unfired.
	 *
	 * @param condition the condition to evaluate each tick
	 * @param action the action to execute after the reaction delay once the condition is met
	 */
	protected void reactWhen(BooleanSupplier condition, Runnable action) {
		reactWhen(condition, action, getReactionMeanMs(), getReactionStdDevMs());
	}

	/**
	 * Detects a condition on-tick and, once it is true, fires the action after a custom
	 * gaussian reaction delay off the client thread.
	 * Defaults to a maximum of 20 ticks (~12 seconds) before the action expires unfired.
	 *
	 * @param condition the condition to evaluate each tick
	 * @param action the action to execute after the reaction delay once the condition is met
	 * @param meanMs mean reaction delay in milliseconds
	 * @param stdDevMs standard deviation of the reaction delay in milliseconds
	 */
	protected void reactWhen(BooleanSupplier condition, Runnable action, int meanMs, int stdDevMs) {
		reactWhen(condition, action, 20, meanMs, stdDevMs);
	}

	/**
	 * Detects a condition on-tick and, once it is true, fires the action after a custom
	 * gaussian reaction delay off the client thread, with a custom expiry window.
	 *
	 * @param condition the condition to evaluate each tick
	 * @param action the action to execute after the reaction delay once the condition is met
	 * @param maxTicks maximum number of ticks to wait before the action expires unfired
	 * @param meanMs mean reaction delay in milliseconds
	 * @param stdDevMs standard deviation of the reaction delay in milliseconds
	 */
	protected void reactWhen(BooleanSupplier condition, Runnable action, int maxTicks, int meanMs, int stdDevMs) {
		TickDispatcher dispatcher = Microbot.getTickDispatcher();
		if (dispatcher == null) return;
		dispatcher.addAction(TickAction.reactive(
			condition,
			action,
			maxTicks,
			dispatcher.getCurrentTick(),
			meanMs,
			stdDevMs,
			scheduledExecutorService
		));
	}

	// -------------------------------------------------------------------------
	// Reaction profile - overridable per script
	// -------------------------------------------------------------------------

	/**
	 * Mean reaction delay in milliseconds used by the reactive action helpers.
	 * Override to customize the reaction profile for a specific script.
	 * Default: 180ms (approximate average human visual reaction time).
	 *
	 * @return mean reaction delay in milliseconds
	 */
	protected int getReactionMeanMs() {
		return 180;
	}

	/**
	 * Standard deviation of the reaction delay in milliseconds used by the reactive action helpers.
	 * Override to customize the reaction profile for a specific script.
	 * Default: 50ms.
	 *
	 * @return standard deviation of the reaction delay in milliseconds
	 */
	protected int getReactionStdDevMs() {
		return 50;
	}

	// -------------------------------------------------------------------------
	// Lifecycle override
	// -------------------------------------------------------------------------

	/**
	 * Stops tick callbacks and then delegates to {@link Script#shutdown()} to cancel any
	 * scheduled executor futures and reset shared state.
	 * Always call {@code super.shutdown()} if overriding in a subclass.
	 */
	@Override
	public void shutdown() {
		stopTick();
		super.shutdown();
	}
}
