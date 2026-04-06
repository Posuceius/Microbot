package net.runelite.client.plugins.microbot.util.tick;

import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.EventBus;

import javax.inject.Singleton;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Core singleton that manages the tick lifecycle for the tick-synchronized execution system.
 * <p>
 * Subscribes to {@link GameTick} events at low priority (-100) so that all other subscribers
 * have already processed the tick before this dispatcher runs. On each tick it:
 * <ol>
 *   <li>Notifies all registered {@link TickListener} instances.</li>
 *   <li>Evaluates and signals completed {@link TickWaiter} sleep primitives.</li>
 * </ol>
 * <p>
 * All callbacks run on the client thread. Implementations must not block the client thread.
 */
@Slf4j
@Singleton
public class TickDispatcher
{
	private final Client client;
	private final EventBus eventBus;

	private final CopyOnWriteArrayList<TickListener> listeners;
	private final ConcurrentLinkedQueue<TickWaiter> waiters;

	private volatile int currentTick;

	/** Subscriber handle retained so we can unregister on shutdown if needed. */
	private final EventBus.Subscriber gameTickSubscriber;
	private final EventBus.Subscriber gameStateChangedSubscriber;

	@Inject
	public TickDispatcher(EventBus eventBus, Client client)
	{
		this.eventBus = eventBus;
		this.client = client;
		this.listeners = new CopyOnWriteArrayList<>();
		this.waiters = new ConcurrentLinkedQueue<>();
		this.currentTick = 0;

		this.gameTickSubscriber = eventBus.register(GameTick.class, this::handleGameTick, -100f);
		this.gameStateChangedSubscriber = eventBus.register(GameStateChanged.class, this::handleGameStateChanged, 0f);

		log.info("TickDispatcher initialized and subscribed to GameTick (priority=-100) and GameStateChanged (priority=0)");
	}

	// -------------------------------------------------------------------------
	// Event handlers
	// -------------------------------------------------------------------------

	/**
	 * Called on the client thread once per game tick, after all higher-priority subscribers.
	 * Dispatches to listeners, evaluates one-shot actions, and signals completed waiters.
	 */
	private void handleGameTick(GameTick event)
	{
		currentTick = client.getTickCount();
		long startNanos = System.nanoTime();

		log.debug("TickDispatcher dispatching tick {} (listeners={}, waiters={})",
			currentTick, listeners.size(), waiters.size());

		// Phase 1: notify listeners
		for (TickListener listener : listeners)
		{
			try
			{
				listener.onGameTick(currentTick);
			}
			catch (Exception listenerException)
			{
				log.error("TickDispatcher caught exception from TickListener {} on tick {}",
					listener.getClass().getSimpleName(), currentTick, listenerException);
			}
		}

		// Phase 2: invoke post-tick hooks on listeners
		for (TickListener listener : listeners)
		{
			try
			{
				listener.onPostTick(currentTick);
			}
			catch (Exception postTickException)
			{
				log.error("TickDispatcher caught exception from TickListener.onPostTick {} on tick {}",
					listener.getClass().getSimpleName(), currentTick, postTickException);
			}
		}

		// Phase 3: evaluate and signal completed waiters
		int waitersSignaled = 0;
		Iterator<TickWaiter> waiterIterator = waiters.iterator();
		while (waiterIterator.hasNext())
		{
			TickWaiter waiter = waiterIterator.next();
			if (waiter.evaluateAndSignal(currentTick))
			{
				waiterIterator.remove();
				waitersSignaled++;
			}
		}
		if (waitersSignaled > 0)
		{
			log.debug("TickDispatcher signaled and removed {} completed TickWaiter(s) on tick {}", waitersSignaled, currentTick);
		}

		// Performance warning threshold
		long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
		if (elapsedMs > 5)
		{
			log.warn("TickDispatcher tick processing took {}ms (listeners={}, waiters={})",
				elapsedMs, listeners.size(), waiters.size());
		}
	}

	/**
	 * Called on the client thread when the game state changes. On logout, orphaned waiters are
	 * cleared. Waiters that are still blocked will time out via their wall-clock deadline - the
	 * clear here prevents the dispatcher from holding references.
	 */
	private void handleGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGIN_SCREEN)
		{
			return;
		}

		int waiterCount = waiters.size();
		for (TickWaiter waiter : waiters)
		{
			waiter.cancel();
		}
		waiters.clear();

		log.info("TickDispatcher cleaning up on logout: cancelled and cleared {} orphaned waiter(s)", waiterCount);
	}

	// -------------------------------------------------------------------------
	// Public API
	// -------------------------------------------------------------------------

	/**
	 * Registers a {@link TickListener} to receive {@code onGameTick} and {@code onPostTick}
	 * callbacks on every game tick. Duplicate registrations are silently ignored by
	 * {@link CopyOnWriteArrayList#addIfAbsent}.
	 *
	 * @param listener the listener to register; must not be null
	 */
	public void addListener(TickListener listener)
	{
		listeners.addIfAbsent(listener);
		log.debug("TickDispatcher added TickListener: {}", listener.getClass().getSimpleName());
	}

	/**
	 * Removes a previously registered {@link TickListener}. No-op if the listener was not registered.
	 *
	 * @param listener the listener to remove; must not be null
	 */
	public void removeListener(TickListener listener)
	{
		boolean removed = listeners.remove(listener);
		if (removed)
		{
			log.debug("TickDispatcher removed TickListener: {}", listener.getClass().getSimpleName());
		}
		else
		{
			log.debug("TickDispatcher removeListener called for unregistered listener: {}", listener.getClass().getSimpleName());
		}
	}

	/**
	 * Creates a new {@link TickWaiter}, registers it with the dispatcher, and returns it so
	 * the calling script thread can block on {@link TickWaiter#await()}.
	 * <p>
	 * The waiter is automatically removed from the dispatcher once its condition is met,
	 * its tick limit is reached, or its wall-clock deadline expires.
	 *
	 * @param condition the condition to evaluate on each tick; {@code null} for an unconditional wait
	 * @param maxTicks maximum number of ticks to wait before timing out; 0 means rely solely on the wall-clock timeout
	 * @param wallClockTimeoutMs wall-clock timeout in milliseconds as a safety net
	 * @return a new {@link TickWaiter} that can be awaited on the script thread
	 */
	public TickWaiter registerWait(BooleanSupplier condition, int maxTicks, long wallClockTimeoutMs)
	{
		int registrationTick = client.getTickCount();
		TickWaiter waiter = new TickWaiter(condition, maxTicks, wallClockTimeoutMs, registrationTick);
		waiters.add(waiter);
		log.debug("TickDispatcher registered TickWaiter (startTick={}, maxTicks={}, wallClockTimeoutMs={}, total pending={})",
			registrationTick, maxTicks, wallClockTimeoutMs, waiters.size());
		return waiter;
	}

	/**
	 * Returns the tick count from the most recently processed {@link GameTick} event.
	 * This value is updated at the start of each {@code handleGameTick} call.
	 *
	 * @return the current game tick count, or 0 before the first tick has been processed
	 */
	public int getCurrentTick()
	{
		return currentTick;
	}
}
