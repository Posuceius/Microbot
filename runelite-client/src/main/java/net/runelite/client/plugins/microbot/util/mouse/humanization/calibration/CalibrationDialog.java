package net.runelite.client.plugins.microbot.util.mouse.humanization.calibration;

import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Modal Swing dialog that presents a dot-clicking calibration session to the user.
 * Displays a series of green target dots at varying distances across the canvas and
 * records the user's mouse trajectory, timing, and click accuracy for each trial.
 * After all {@value #TOTAL_TRIALS} trials are completed the dialog auto-closes and
 * the caller may retrieve the recorded data via {@link #getCompletedTrials()}.
 *
 * <p>The dialog distributes trials across four distance bands so that the resulting
 * {@link CalibrationTrial} data covers the full range of movement distances used
 * during normal bot operation. Trials within each band are shuffled before display
 * so the user cannot anticipate the next dot position.
 *
 * <p>Usage example:
 * <pre>
 *     CalibrationDialog dialog = new CalibrationDialog(parentComponent);
 *     dialog.setVisible(true); // blocks until dialog closes (modal)
 *     List&lt;CalibrationTrial&gt; trials = dialog.getCompletedTrials();
 *     if (trials != null) {
 *         // process calibration data
 *     }
 * </pre>
 */
@Slf4j
public class CalibrationDialog extends JDialog
{
	private static final int DIALOG_WIDTH = 800;
	private static final int DIALOG_HEIGHT = 600;
	private static final int DOT_RADIUS = 15;
	private static final int EDGE_MARGIN = 30;
	private static final int TOTAL_TRIALS = 24;

	// 6 distances per band (4 bands x 6 = 24 trials)
	private static final int[][] TRIAL_DISTANCES = {
		{30, 50, 70, 40, 60, 80},       // Band 0: short (0-100px)
		{120, 150, 200, 180, 250, 130},  // Band 1: medium (100-300px)
		{320, 400, 500, 350, 450, 550},  // Band 2: long (300-600px)
		{620, 700, 750, 650, 680, 720}   // Band 3: very long (600+px)
	};

	private final CalibrationRecorder recorder;
	private final List<Integer> shuffledDistances;
	private int currentTrialIndex;
	private Point currentDotCenter;
	private boolean calibrationComplete;
	private boolean cancelled;
	private final JPanel canvasPanel;
	private javax.swing.Timer closeTimer;

	/**
	 * Constructs and initialises the calibration dialog. The dialog is modal and
	 * will block the calling thread until it is closed. Call {@link #setVisible(boolean)}
	 * with {@code true} to begin the session.
	 *
	 * @param parent the component to center the dialog over, or {@code null} to center
	 *               on the screen
	 */
	/**
	 * Resolves the owner {@link Window} for the dialog from the given parent component.
	 * Returns the nearest ancestor window if the parent is non-null, otherwise {@code null}.
	 * This helper exists so that the result can be passed to the {@link JDialog} super
	 * constructor, which must be the first statement in the constructor body.
	 *
	 * @param parent the component whose ancestor window should be used, or {@code null}
	 * @return the ancestor window, or {@code null} if parent is {@code null}
	 */
	private static Window resolveOwnerWindow(Component parent)
	{
		if (parent == null)
		{
			return null;
		}
		return SwingUtilities.getWindowAncestor(parent);
	}

	public CalibrationDialog(Component parent)
	{
		super(resolveOwnerWindow(parent), "Mouse Calibration", ModalityType.APPLICATION_MODAL);

		setSize(DIALOG_WIDTH, DIALOG_HEIGHT);
		setLocationRelativeTo(parent);
		setResizable(false);
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

		addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosing(WindowEvent windowEvent)
			{
				cancelCalibration();
			}

			@Override
			public void windowOpened(WindowEvent windowEvent)
			{
				SwingUtilities.invokeLater(() -> placeNextDot());
			}
		});

		recorder = new CalibrationRecorder();

		shuffledDistances = new ArrayList<>();
		for (int[] band : TRIAL_DISTANCES)
		{
			for (int distance : band)
			{
				shuffledDistances.add(distance);
			}
		}
		Collections.shuffle(shuffledDistances);

		currentTrialIndex = 0;
		calibrationComplete = false;
		cancelled = false;

		// Build the canvas panel with custom painting
		canvasPanel = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics graphics)
			{
				super.paintComponent(graphics);
				Graphics2D g2d = (Graphics2D) graphics;
				g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

				// Instructions at top
				g2d.setColor(Color.WHITE);
				g2d.setFont(new Font("SansSerif", Font.BOLD, 16));
				String title = calibrationComplete ? "Calibration Complete!" : "Click each green dot naturally";
				FontMetrics fontMetrics = g2d.getFontMetrics();
				g2d.drawString(title, (getWidth() - fontMetrics.stringWidth(title)) / 2, 30);

				// Progress
				g2d.setFont(new Font("SansSerif", Font.PLAIN, 14));
				String progress = calibrationComplete
					? "All " + TOTAL_TRIALS + " trials recorded"
					: "Trial " + (currentTrialIndex + 1) + " of " + TOTAL_TRIALS;
				g2d.drawString(progress, (getWidth() - g2d.getFontMetrics().stringWidth(progress)) / 2, 55);

				// Draw the green dot (if not complete)
				if (!calibrationComplete && currentDotCenter != null)
				{
					// Outer green circle
					g2d.setColor(new Color(0, 200, 0));
					g2d.fillOval(
						currentDotCenter.x - DOT_RADIUS,
						currentDotCenter.y - DOT_RADIUS,
						DOT_RADIUS * 2,
						DOT_RADIUS * 2
					);

					// White center point
					g2d.setColor(Color.WHITE);
					int centerDotSize = 3;
					g2d.fillOval(
						currentDotCenter.x - centerDotSize,
						currentDotCenter.y - centerDotSize,
						centerDotSize * 2,
						centerDotSize * 2
					);
				}
			}
		};

		canvasPanel.setBackground(new Color(30, 30, 30));
		canvasPanel.setFocusable(true);

		// Add recorder as listener first so it processes events before the completion checker
		canvasPanel.addMouseListener(recorder);
		canvasPanel.addMouseMotionListener(recorder);

		// Add completion checker AFTER recorder so recorder processes the event first
		canvasPanel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseReleased(MouseEvent event)
			{
				if (recorder.getCompletedTrialCount() > currentTrialIndex)
				{
					currentTrialIndex++;
					log.info("Trial {} of {} completed", currentTrialIndex, TOTAL_TRIALS);
					placeNextDot();
				}
			}
		});

		// Build the button panel at the bottom
		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttonPanel.setBackground(new Color(40, 40, 40));

		JButton cancelButton = new JButton("Cancel");
		cancelButton.setBackground(new Color(50, 50, 50));
		cancelButton.setForeground(Color.WHITE);
		cancelButton.addActionListener(actionEvent -> cancelCalibration());

		JButton restartButton = new JButton("Restart");
		restartButton.setBackground(new Color(50, 50, 50));
		restartButton.setForeground(Color.WHITE);
		restartButton.addActionListener(actionEvent -> restartCalibration());

		buttonPanel.add(restartButton);
		buttonPanel.add(cancelButton);

		setLayout(new BorderLayout());
		add(canvasPanel, BorderLayout.CENTER);
		add(buttonPanel, BorderLayout.SOUTH);

		log.info("CalibrationDialog: initialised with {} trials across {} distance bands",
			TOTAL_TRIALS, TRIAL_DISTANCES.length);

		canvasPanel.requestFocusInWindow();
	}

	/**
	 * Places the next target dot on the canvas at the distance prescribed by the
	 * shuffled trial list. When all trials have been completed the canvas is
	 * repainted to show the completion message and the dialog auto-closes after
	 * a short delay so the user can see the message.
	 */
	private void placeNextDot()
	{
		if (currentTrialIndex >= TOTAL_TRIALS)
		{
			calibrationComplete = true;
			log.info("CalibrationDialog: all {} trials complete - dialog will close shortly", TOTAL_TRIALS);
			canvasPanel.repaint();
			closeTimer = new javax.swing.Timer(1500, event -> dispose());
			closeTimer.setRepeats(false);
			closeTimer.start();
			return;
		}

		int targetDistance = shuffledDistances.get(currentTrialIndex);

		Point mouseOnScreen = MouseInfo.getPointerInfo().getLocation();
		SwingUtilities.convertPointFromScreen(mouseOnScreen, canvasPanel);
		Point startPosition = mouseOnScreen;

		currentDotCenter = calculateDotPosition(startPosition, targetDistance);

		recorder.startTrial(startPosition, currentDotCenter, DOT_RADIUS);

		log.info("CalibrationDialog: placed trial {}/{} dot at {},{} distance={}px",
			currentTrialIndex + 1, TOTAL_TRIALS,
			currentDotCenter.x, currentDotCenter.y, targetDistance);

		canvasPanel.repaint();
	}

	/**
	 * Calculates the position for a target dot that is exactly {@code targetDistance}
	 * pixels from {@code startPosition} at a random angle. Up to 20 angles are tried
	 * to find one where the dot fits entirely within the canvas bounds. If no suitable
	 * angle is found the result is clamped to the nearest in-bounds position.
	 *
	 * @param startPosition the position from which the distance is measured
	 * @param targetDistance the desired distance in pixels from start to dot center
	 * @return the calculated dot center position, guaranteed to be within canvas bounds
	 */
	private Point calculateDotPosition(Point startPosition, int targetDistance)
	{
		ThreadLocalRandom random = ThreadLocalRandom.current();

		int minimumBound = EDGE_MARGIN + DOT_RADIUS;
		int maximumX = DIALOG_WIDTH - EDGE_MARGIN - DOT_RADIUS;
		int maximumY = DIALOG_HEIGHT - EDGE_MARGIN - DOT_RADIUS - 70; // Account for button panel and header

		for (int attempt = 0; attempt < 20; attempt++)
		{
			double angle = random.nextDouble() * 2 * Math.PI;
			int dotX = (int) (startPosition.x + targetDistance * Math.cos(angle));
			int dotY = (int) (startPosition.y + targetDistance * Math.sin(angle));

			if (dotX >= minimumBound && dotX <= maximumX
				&& dotY >= minimumBound + 60 && dotY <= maximumY)
			{
				return new Point(dotX, dotY);
			}
		}

		// Fallback: clamp to bounds
		double fallbackAngle = random.nextDouble() * 2 * Math.PI;
		int clampedX = (int) (startPosition.x + targetDistance * Math.cos(fallbackAngle));
		int clampedY = (int) (startPosition.y + targetDistance * Math.sin(fallbackAngle));
		clampedX = Math.max(minimumBound, Math.min(clampedX, maximumX));
		clampedY = Math.max(minimumBound + 60, Math.min(clampedY, maximumY));

		log.info("CalibrationDialog: dot position clamped to bounds at {},{} for distance={}px",
			clampedX, clampedY, targetDistance);

		return new Point(clampedX, clampedY);
	}

	/**
	 * Cancels the calibration session, marks the session as cancelled, and closes
	 * the dialog. {@link #getCompletedTrials()} will return {@code null} after a
	 * cancellation.
	 */
	private void cancelCalibration()
	{
		log.info("CalibrationDialog: session cancelled by user after {}/{} trials",
			currentTrialIndex, TOTAL_TRIALS);
		cancelled = true;
		dispose();
	}

	/**
	 * Resets the calibration session to its initial state, re-shuffles the trial
	 * distances, and places the first dot again. Any previously recorded trial data
	 * is discarded.
	 */
	private void restartCalibration()
	{
		log.info("CalibrationDialog: session restarted - discarding {} completed trials",
			recorder.getCompletedTrialCount());
		if (closeTimer != null)
		{
			closeTimer.stop();
			closeTimer = null;
		}
		recorder.reset();
		currentTrialIndex = 0;
		calibrationComplete = false;
		Collections.shuffle(shuffledDistances);
		placeNextDot();
	}

	/**
	 * Returns the list of completed calibration trials after the dialog has closed.
	 * Returns {@code null} if the session was cancelled, if the dialog was closed
	 * before all trials were completed, or if the session has not yet finished.
	 *
	 * @return an unmodifiable list of completed trials, or {@code null} if the
	 *         session did not complete successfully
	 */
	public List<CalibrationTrial> getCompletedTrials()
	{
		if (cancelled || !calibrationComplete)
		{
			return null;
		}
		return recorder.getCompletedTrials();
	}
}
