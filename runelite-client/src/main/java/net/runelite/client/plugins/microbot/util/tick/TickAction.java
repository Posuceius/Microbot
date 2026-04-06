package net.runelite.client.plugins.microbot.util.tick;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * A one-shot conditional action that fires when a condition becomes true on a game tick.
 * <p>
 * Each {@code TickAction} is registered with a tick dispatcher and evaluated once per tick.
 * When the condition is satisfied (or absent), the associated action is executed and the
 * instance is removed from the dispatcher. If {@code maxTicks} is set and the action has
 * not fired within that window, the instance expires without executing.
 * <p>
 * Two execution modes are supported:
 * <ul>
 *   <li><b>Proactive</b> - the action fires immediately on the client thread when the condition
 *       becomes true. Use for actions that require synchronous client-thread execution.</li>
 *   <li><b>Reactive</b> - the action is scheduled with a gaussian-distributed delay on a
 *       dedicated executor, simulating human reaction time. Use for script-thread actions
 *       that should not run synchronously with the game tick.</li>
 * </ul>
 */
@Slf4j
public class TickAction {

	private static final int REACTIVE_MINIMUM_DELAY_MS = 20;

	private final BooleanSupplier condition;
	private final Runnable action;
	private final int maxTicks;
	private final int startTick;
	private final boolean reactive;
	private final int reactionMeanMs;
	private final int reactionStdDevMs;
	private final ScheduledExecutorService reactiveExecutor;

	private TickAction(
		BooleanSupplier condition,
		Runnable action,
		int maxTicks,
		int startTick,
		boolean reactive,
		int reactionMeanMs,
		int reactionStdDevMs,
		ScheduledExecutorService reactiveExecutor
	) {
		this.condition = condition;
		this.action = action;
		this.maxTicks = maxTicks;
		this.startTick = startTick;
		this.reactive = reactive;
		this.reactionMeanMs = reactionMeanMs;
		this.reactionStdDevMs = reactionStdDevMs;
		this.reactiveExecutor = reactiveExecutor;
	}

	/**
	 * Creates a proactive {@code TickAction} that fires immediately on the client thread
	 * when the condition becomes true.
	 *
	 * @param condition the condition to evaluate each tick; {@code null} means unconditional
	 * @param action the action to execute when the condition is met
	 * @param maxTicks maximum number of ticks before expiry; {@code 0} means no limit
	 * @param startTick the tick count at which this action was registered
	 * @return a new proactive {@code TickAction}
	 */
	public static TickAction proactive(BooleanSupplier condition, Runnable action, int maxTicks, int startTick) {
		return new TickAction(condition, action, maxTicks, startTick, false, 0, 0, null);
	}

	/**
	 * Creates a reactive {@code TickAction} that schedules the action on a script thread
	 * with a gaussian-distributed delay when the condition becomes true.
	 *
	 * @param condition the condition to evaluate each tick; {@code null} means unconditional
	 * @param action the action to execute when the condition is met
	 * @param maxTicks maximum number of ticks before expiry; {@code 0} means no limit
	 * @param startTick the tick count at which this action was registered
	 * @param reactionMeanMs mean reaction delay in milliseconds
	 * @param reactionStdDevMs standard deviation of the reaction delay in milliseconds
	 * @param executor the executor on which to schedule the delayed action
	 * @return a new reactive {@code TickAction}
	 */
	public static TickAction reactive(
		BooleanSupplier condition,
		Runnable action,
		int maxTicks,
		int startTick,
		int reactionMeanMs,
		int reactionStdDevMs,
		ScheduledExecutorService executor
	) {
		return new TickAction(condition, action, maxTicks, startTick, true, reactionMeanMs, reactionStdDevMs, executor);
	}

	/**
	 * Evaluates this action for the given tick. Returns {@code true} when this action should
	 * be removed from the dispatcher's list - either because it fired successfully or because
	 * it has expired. Returns {@code false} if the condition was not yet met and the action
	 * should be retained for the next tick.
	 *
	 * @param currentTick the current game tick count
	 * @return {@code true} if this action is done (fired or expired), {@code false} otherwise
	 */
	public boolean tryFire(int currentTick) {
		if (maxTicks > 0 && (currentTick - startTick) >= maxTicks) {
			log.debug(
				"TickAction expired after {} ticks without firing (startTick={}, currentTick={})",
				maxTicks,
				startTick,
				currentTick
			);
			return true;
		}

		boolean conditionMet = condition == null || condition.getAsBoolean();
		if (!conditionMet) {
			return false;
		}

		if (reactive) {
			long delayMs = Math.max(
				REACTIVE_MINIMUM_DELAY_MS,
				Math.round(Rs2Random.gaussRand(reactionMeanMs, reactionStdDevMs))
			);
			log.debug(
				"TickAction condition met on tick {}; scheduling reactive execution in {}ms",
				currentTick,
				delayMs
			);
			reactiveExecutor.schedule(() -> {
				try {
					log.debug("TickAction executing reactive action after {}ms delay", delayMs);
					action.run();
				} catch (Exception exception) {
					log.error("TickAction encountered an error executing reactive action", exception);
				}
			}, delayMs, TimeUnit.MILLISECONDS);
		} else {
			try {
				log.debug("TickAction condition met on tick {}; executing proactive action", currentTick);
				action.run();
			} catch (Exception exception) {
				log.error("TickAction encountered an error executing proactive action", exception);
			}
		}

		return true;
	}
}
