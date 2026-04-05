package net.runelite.client.plugins.microbot.util.mouse.humanization.calibration;

import lombok.extern.slf4j.Slf4j;

import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records mouse movement and click events during a calibration session and
 * assembles them into {@link CalibrationTrial} objects. Each trial begins when
 * {@link #startTrial} is called and ends automatically when the user clicks
 * within the target radius. Attach this recorder to the calibration component
 * via {@link java.awt.Component#addMouseListener} and
 * {@link java.awt.Component#addMouseMotionListener}.
 */
@Slf4j
public class CalibrationRecorder implements MouseListener, MouseMotionListener
{
	private Point currentStartPosition;
	private Point currentTargetPosition;
	private int currentTargetRadius;
	private List<CalibrationSample> currentSamples;
	private long currentMouseDownTimestampMs;
	private Point currentClickPosition;
	private boolean recording;
	private final List<CalibrationTrial> completedTrials;

	/**
	 * Constructs a new CalibrationRecorder in a non-recording, empty state.
	 */
	public CalibrationRecorder()
	{
		this.currentSamples = new ArrayList<>();
		this.completedTrials = new ArrayList<>();
		this.recording = false;
	}

	/**
	 * Begins a new calibration trial. Clears any samples from the previous trial
	 * and sets the recorder into active recording mode. Mouse movement and click
	 * events will be captured until the user clicks within the target radius.
	 *
	 * @param startPosition the current mouse position at the moment the trial starts
	 * @param targetPosition the center of the target dot to be clicked
	 * @param targetRadius the radius of the target dot in pixels
	 */
	public void startTrial(Point startPosition, Point targetPosition, int targetRadius)
	{
		this.currentStartPosition = startPosition;
		this.currentTargetPosition = targetPosition;
		this.currentTargetRadius = targetRadius;
		this.currentSamples = new ArrayList<>();
		this.recording = true;
		log.info("CalibrationRecorder: trial started - start={},{} target={},{} radius={}",
			startPosition.x, startPosition.y,
			targetPosition.x, targetPosition.y,
			targetRadius);
	}

	/**
	 * Returns an unmodifiable view of all trials completed so far in this session.
	 *
	 * @return an unmodifiable list of completed trials in order of completion
	 */
	public List<CalibrationTrial> getCompletedTrials()
	{
		return Collections.unmodifiableList(completedTrials);
	}

	/**
	 * Returns the number of trials that have been successfully completed in this session.
	 *
	 * @return the number of completed trials
	 */
	public int getCompletedTrialCount()
	{
		return completedTrials.size();
	}

	/**
	 * Resets the recorder to its initial state. Clears all completed trials and
	 * any in-progress trial data, and stops recording.
	 */
	public void reset()
	{
		completedTrials.clear();
		currentSamples.clear();
		recording = false;
		log.info("CalibrationRecorder: session reset, all trial data cleared");
	}

	/**
	 * Records the current mouse position as a trajectory sample if a trial is active.
	 * Called by the AWT event dispatch thread whenever the mouse moves without a button held.
	 *
	 * @param mouseEvent the mouse motion event from AWT
	 */
	@Override
	public void mouseMoved(MouseEvent mouseEvent)
	{
		if (!recording)
		{
			return;
		}
		CalibrationSample sample = new CalibrationSample(
			mouseEvent.getX(),
			mouseEvent.getY(),
			System.currentTimeMillis()
		);
		currentSamples.add(sample);
		log.debug("CalibrationRecorder: sample recorded at {},{} - total samples this trial={}",
			mouseEvent.getX(), mouseEvent.getY(), currentSamples.size());
	}

	/**
	 * Records the current mouse position as a trajectory sample if a trial is active.
	 * Treated identically to {@link #mouseMoved} to capture any dragging that may
	 * occur as the user depresses the mouse button.
	 *
	 * @param mouseEvent the mouse motion event from AWT
	 */
	@Override
	public void mouseDragged(MouseEvent mouseEvent)
	{
		if (!recording)
		{
			return;
		}
		CalibrationSample sample = new CalibrationSample(
			mouseEvent.getX(),
			mouseEvent.getY(),
			System.currentTimeMillis()
		);
		currentSamples.add(sample);
		log.debug("CalibrationRecorder: drag sample recorded at {},{} - total samples this trial={}",
			mouseEvent.getX(), mouseEvent.getY(), currentSamples.size());
	}

	/**
	 * Records the mouse-down timestamp and click position when the user presses a
	 * mouse button during an active trial.
	 *
	 * @param mouseEvent the mouse event from AWT
	 */
	@Override
	public void mousePressed(MouseEvent mouseEvent)
	{
		if (!recording)
		{
			return;
		}
		currentMouseDownTimestampMs = System.currentTimeMillis();
		currentClickPosition = new Point(mouseEvent.getX(), mouseEvent.getY());
		log.debug("CalibrationRecorder: mouse pressed at {},{} timestampMs={}",
			mouseEvent.getX(), mouseEvent.getY(), currentMouseDownTimestampMs);
	}

	/**
	 * Completes the current trial if the mouse was released within the target radius.
	 * If the release position is outside the target, the trial remains active and
	 * the user must click again. When a trial is completed it is added to the
	 * completed trials list and recording is stopped.
	 *
	 * @param mouseEvent the mouse event from AWT
	 */
	@Override
	public void mouseReleased(MouseEvent mouseEvent)
	{
		if (!recording)
		{
			return;
		}

		double distanceFromTarget = currentClickPosition.distance(currentTargetPosition);
		boolean clickLandedOnTarget = distanceFromTarget <= currentTargetRadius;

		if (!clickLandedOnTarget)
		{
			log.debug("CalibrationRecorder: click missed target - distance={} radius={}, trial still active",
				String.format("%.2f", distanceFromTarget), currentTargetRadius);
			return;
		}

		long mouseUpTimestampMs = System.currentTimeMillis();

		CalibrationTrial completedTrial = new CalibrationTrial(
			currentStartPosition,
			currentTargetPosition,
			currentTargetRadius,
			currentSamples,
			currentMouseDownTimestampMs,
			mouseUpTimestampMs,
			currentClickPosition
		);

		completedTrials.add(completedTrial);
		recording = false;

		log.info("CalibrationRecorder: trial completed - sampleCount={} distancePx={} distanceBand={} movementDurationMs={} clickAt={},{}",
			completedTrial.getSamples().size(),
			String.format("%.1f", completedTrial.getDistance()),
			completedTrial.getDistanceBand(),
			completedTrial.getMovementDurationMs(),
			currentClickPosition.x, currentClickPosition.y);
	}

	/**
	 * Required by {@link MouseListener}. No action taken.
	 *
	 * @param mouseEvent the mouse event from AWT
	 */
	@Override
	public void mouseClicked(MouseEvent mouseEvent)
	{
	}

	/**
	 * Required by {@link MouseListener}. No action taken.
	 *
	 * @param mouseEvent the mouse event from AWT
	 */
	@Override
	public void mouseEntered(MouseEvent mouseEvent)
	{
	}

	/**
	 * Required by {@link MouseListener}. No action taken.
	 *
	 * @param mouseEvent the mouse event from AWT
	 */
	@Override
	public void mouseExited(MouseEvent mouseEvent)
	{
	}
}
