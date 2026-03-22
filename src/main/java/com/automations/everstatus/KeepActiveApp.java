package com.automations.everstatus;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.awt.Robot;
import java.awt.GraphicsEnvironment;
import java.awt.event.KeyEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;

@Service
public class KeepActiveApp {

    private static final Logger log = LoggerFactory.getLogger(KeepActiveApp.class);

    @Autowired
    private SleepPreventionService sleepPreventionService;

    private Label statusLabel;
    private Label timeRemainingLabel;
    private Instant startTime;
    private Robot robot;
    private LocalDateTime endDateTime;
    private Timer timer;
    private volatile boolean shouldStop  = false;
    private volatile int     keyPressCount = 0;   // total F13 presses in the current session
    private Button startButton;
    private Button stopButton;
    private Spinner hourSpinner;
    private Spinner minuteSpinner;
    private Combo amPmCombo;
    private Combo durationCombo;
    private Display display;
    private Shell shell;
    private Color greenColor;
    private Color redColor;
    private Color greyColor;
    private Color mutedTextColor;
    private Color darkTextColor;
    // Near-black foreground used on coloured buttons (stop, OK in password dialog).
    // Kept as a field so it is created once and disposed with the shell — not leaked
    // every time the user clicks Start/Stop or opens the password dialog.
    private Color darkFgColor;
    // Dark-green foreground for the Start button label.
    private Color darkGreenFgColor;

    public void execute() {
        log.info("execute() starting on thread='{}' os='{}' java='{}'",
            Thread.currentThread().getName(),
            System.getProperty("os.name"),
            System.getProperty("java.version"));

        System.setProperty("java.awt.headless", "false");

        if (GraphicsEnvironment.isHeadless()) {
            log.error("Running in headless mode — GUI will not be available");
        }

        try {
            robot = new Robot();
            log.debug("java.awt.Robot initialised successfully");
        } catch (Exception e) {
            log.error("Failed to initialise Robot for key simulation", e);
            return;
        }

        log.debug("Creating SWT Display on thread='{}'", Thread.currentThread().getName());
        display = new Display();

        // Wire the native SWT password dialog into the sleep prevention service.
        // This must happen before START is pressed so the credential store has
        // the callback ready before the background thread calls getOrPromptPassword().
        // Using display.syncExec() inside the lambda is safe: the background
        // (sleep-prevention-enable) thread blocks on syncExec while the main thread
        // runs the dialog, then resumes with the result — no AppKit violations.
        log.debug("Registering SWT password dialog provider with SleepPreventionService");
        sleepPreventionService.setPasswordDialogProvider(message -> {
            log.debug("Password dialog provider invoked on thread='{}' — posting to SWT main thread",
                Thread.currentThread().getName());
            String[] result = {null};
            display.syncExec(() -> {
                log.debug("SWT password dialog opening on thread='{}'", Thread.currentThread().getName());
                result[0] = openPasswordDialog(message);
                log.debug("SWT password dialog closed — entered={}", result[0] != null);
            });
            return result[0];
        });

        // Register coverage-change callback so the power monitor can update the status
        // label whenever AC is plugged/unplugged or an external display is connected/removed.
        // The callback is fired on the power-monitor background thread, so we use asyncExec.
        log.debug("Registering coverage-changed callback with SleepPreventionService");
        sleepPreventionService.setCoverageChangedCallback(() -> {
            log.debug("Coverage-changed callback fired on thread='{}' — updating UI",
                Thread.currentThread().getName());
            if (!display.isDisposed()) {
                display.asyncExec(this::updateCoverageLabel);
            }
        });

        shell = new Shell(display, SWT.CLOSE | SWT.TITLE | SWT.MIN | SWT.RESIZE);
        shell.setText("EverStatus");

        // Main layout
        GridLayout mainLayout = new GridLayout(1, false);
        mainLayout.marginWidth = 0;
        mainLayout.marginHeight = 0;
        mainLayout.verticalSpacing = 0;
        shell.setLayout(mainLayout);

        // Sleek Modern Color Palette - Dark Theme
        Color bgColor = new Color(display, new RGB(30, 30, 46));              // Dark background
        Color cardBgColor = new Color(display, new RGB(49, 50, 68));          // Card background
        darkTextColor = new Color(display, new RGB(205, 214, 244));            // Light text
        Color whiteColor = new Color(display, new RGB(255, 255, 255));        // Pure white
        Color accentColor = new Color(display, new RGB(137, 180, 250));       // Sky blue accent
        Color accentColorDark = new Color(display, new RGB(116, 199, 236));   // Lighter blue
        mutedTextColor = new Color(display, new RGB(166, 173, 200));           // Muted text
        Color inputBgColor = new Color(display, new RGB(69, 71, 90));         // Input background

        shell.setBackground(bgColor);

        // Header Section
        Composite titleSection = new Composite(shell, SWT.NONE);
        titleSection.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        titleSection.setBackground(bgColor);
        GridLayout titleLayout = new GridLayout(1, false);
        titleLayout.marginHeight = 25;
        titleLayout.marginWidth = 30;
        titleLayout.verticalSpacing = 6;
        titleSection.setLayout(titleLayout);

        Label titleLabel = new Label(titleSection, SWT.NONE);
        titleLabel.setText("EverStatus");
        titleLabel.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        titleLabel.setBackground(bgColor);
        titleLabel.setForeground(accentColor);
        Font titleFont = new Font(display, "Segoe UI", 28, SWT.BOLD);
        titleLabel.setFont(titleFont);

        Label subtitleLabel = new Label(titleSection, SWT.NONE);
        subtitleLabel.setText("Keep Your System Active");
        subtitleLabel.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        subtitleLabel.setBackground(bgColor);
        subtitleLabel.setForeground(mutedTextColor);
        Font subtitleFont = new Font(display, "Segoe UI", 10, SWT.NORMAL);
        subtitleLabel.setFont(subtitleFont);

        // Main Card Container
        Composite contentArea = new Composite(shell, SWT.NONE);
        GridData contentData = new GridData(SWT.FILL, SWT.FILL, true, true);
        contentArea.setLayoutData(contentData);
        contentArea.setBackground(bgColor);
        GridLayout contentLayout = new GridLayout(1, false);
        contentLayout.marginLeft = 30;
        contentLayout.marginRight = 30;
        contentLayout.marginTop = 0;
        contentLayout.marginBottom = 20;
        contentLayout.horizontalSpacing = 0;
        contentArea.setLayout(contentLayout);

        // Main Card Panel
        Composite mainPanel = new Composite(contentArea, SWT.NONE);
        mainPanel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        mainPanel.setBackground(cardBgColor);
        GridLayout mainPanelLayout = new GridLayout(1, false);
        mainPanelLayout.marginWidth = 25;
        mainPanelLayout.marginHeight = 22;
        mainPanelLayout.verticalSpacing = 18;
        mainPanel.setLayout(mainPanelLayout);

        // Duration Selection Label
        Label durationHeaderLabel = new Label(mainPanel, SWT.NONE);
        durationHeaderLabel.setText("Duration");
        durationHeaderLabel.setBackground(cardBgColor);
        durationHeaderLabel.setForeground(whiteColor);
        Font sectionFont = new Font(display, "Segoe UI", 13, SWT.BOLD);
        durationHeaderLabel.setFont(sectionFont);
        GridData durationHeaderData = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        durationHeaderLabel.setLayoutData(durationHeaderData);

        // Duration Combo
        durationCombo = new Combo(mainPanel, SWT.DROP_DOWN | SWT.READ_ONLY);
        String[] durations = {
            "15 minutes",
            "30 minutes",
            "1 hour",
            "1 hour 30 minutes",
            "2 hours",
            "2 hours 30 minutes",
            "3 hours",
            "4 hours",
            "5 hours",
            "6 hours",
            "8 hours",
            "10 hours",
            "12 hours"
        };
        for (String duration : durations) {
            durationCombo.add(duration);
        }
        durationCombo.select(2); // Default to 1 hour
        GridData durationData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        durationData.heightHint = 36;
        durationCombo.setLayoutData(durationData);
        Font durationFont = new Font(display, "Segoe UI", 11, SWT.NORMAL);
        durationCombo.setFont(durationFont);
        durationCombo.setBackground(inputBgColor);
        durationCombo.setForeground(darkTextColor);

        // Divider
        Label divider1 = new Label(mainPanel, SWT.SEPARATOR | SWT.HORIZONTAL);
        divider1.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        divider1.setBackground(inputBgColor);

        // End Time Label
        Label endTimeHeaderLabel = new Label(mainPanel, SWT.NONE);
        endTimeHeaderLabel.setText("End Time");
        endTimeHeaderLabel.setBackground(cardBgColor);
        endTimeHeaderLabel.setForeground(whiteColor);
        endTimeHeaderLabel.setFont(sectionFont);
        GridData endTimeHeaderData = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        endTimeHeaderLabel.setLayoutData(endTimeHeaderData);

        // Time Picker Container
        Composite timePickerContainer = new Composite(mainPanel, SWT.NONE);
        timePickerContainer.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        timePickerContainer.setBackground(cardBgColor);
        GridLayout timePickerLayout = new GridLayout(5, false);
        timePickerLayout.horizontalSpacing = 10;
        timePickerLayout.verticalSpacing = 0;
        timePickerLayout.marginHeight = 0;
        timePickerContainer.setLayout(timePickerLayout);

        Font numberFont = new Font(display, "Segoe UI", 16, SWT.BOLD);

        // Hour Spinner
        Calendar cal = Calendar.getInstance();
        hourSpinner = new Spinner(timePickerContainer, SWT.BORDER);
        hourSpinner.setMinimum(1);
        hourSpinner.setMaximum(12);
        int hour12 = cal.get(Calendar.HOUR);
        if (hour12 == 0) hour12 = 12;
        hourSpinner.setSelection(hour12);
        GridData hourData = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        hourData.widthHint = 70;
        hourData.heightHint = 40;
        hourSpinner.setLayoutData(hourData);
        hourSpinner.setFont(numberFont);
        hourSpinner.setBackground(inputBgColor);
        hourSpinner.setForeground(whiteColor);
        hourSpinner.setEnabled(false);
        hourSpinner.addListener(SWT.Selection, event -> updatePreviewStatus());

        // Colon separator
        Label colonLabel = new Label(timePickerContainer, SWT.NONE);
        colonLabel.setText(":");
        colonLabel.setBackground(cardBgColor);
        colonLabel.setForeground(accentColorDark);
        Font colonFont = new Font(display, "Segoe UI", 20, SWT.BOLD);
        colonLabel.setFont(colonFont);
        GridData colonData = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        colonData.verticalIndent = -5;
        colonLabel.setLayoutData(colonData);

        // Minute Spinner
        minuteSpinner = new Spinner(timePickerContainer, SWT.BORDER);
        minuteSpinner.setMinimum(0);
        minuteSpinner.setMaximum(59);
        minuteSpinner.setSelection(cal.get(Calendar.MINUTE));
        GridData minuteData = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        minuteData.widthHint = 70;
        minuteData.heightHint = 40;
        minuteSpinner.setLayoutData(minuteData);
        minuteSpinner.setFont(numberFont);
        minuteSpinner.setBackground(inputBgColor);
        minuteSpinner.setForeground(whiteColor);
        minuteSpinner.setEnabled(false);
        minuteSpinner.addListener(SWT.Selection, event -> updatePreviewStatus());

        // Spacer between minute and AM/PM
        Label spacerLabel = new Label(timePickerContainer, SWT.NONE);
        spacerLabel.setText("");
        spacerLabel.setBackground(cardBgColor);
        GridData spacerData = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        spacerData.widthHint = 10;
        spacerLabel.setLayoutData(spacerData);

        // AM/PM Combo
        amPmCombo = new Combo(timePickerContainer, SWT.DROP_DOWN | SWT.READ_ONLY);
        amPmCombo.add("AM");
        amPmCombo.add("PM");
        amPmCombo.select(cal.get(Calendar.AM_PM));
        GridData amPmData = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        amPmData.widthHint = 70;
        amPmData.heightHint = 40;
        amPmCombo.setLayoutData(amPmData);
        Font amPmFont = new Font(display, "Segoe UI", 12, SWT.NORMAL);
        amPmCombo.setFont(amPmFont);
        amPmCombo.setBackground(inputBgColor);
        amPmCombo.setForeground(darkTextColor);
        amPmCombo.setEnabled(false);
        amPmCombo.addListener(SWT.Selection, event -> updatePreviewStatus());

        // Add listener to update end time when duration is selected
        durationCombo.addListener(SWT.Selection, event -> {
            updateEndTimeFromDuration();
            hourSpinner.setEnabled(true);
            minuteSpinner.setEnabled(true);
            amPmCombo.setEnabled(true);
            updatePreviewStatus();
        });

        // Divider
        Label divider2 = new Label(mainPanel, SWT.SEPARATOR | SWT.HORIZONTAL);
        divider2.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        divider2.setBackground(inputBgColor);

        // Buttons Container
        Composite buttonsContainer = new Composite(mainPanel, SWT.NONE);
        GridData buttonsContainerData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        buttonsContainerData.verticalIndent = 10;
        buttonsContainer.setLayoutData(buttonsContainerData);
        buttonsContainer.setBackground(cardBgColor);
        GridLayout buttonsLayout = new GridLayout(2, true);
        buttonsLayout.horizontalSpacing = 15;
        buttonsContainer.setLayout(buttonsLayout);

        // Start Button - Modern sleek design
        startButton = new Button(buttonsContainer, SWT.PUSH);
        startButton.setText("START");
        GridData startBtnData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        startBtnData.heightHint = 38;
        startButton.setLayoutData(startBtnData);
        Font buttonFont = new Font(display, "Segoe UI", 11, SWT.BOLD);
        startButton.setFont(buttonFont);
        greenColor       = new Color(display, new RGB(166, 227, 161));  // Soft green
        darkGreenFgColor = new Color(display, new RGB(27, 94, 32));    // Dark green text on Start
        darkFgColor      = new Color(display, new RGB(27, 27, 27));    // Near-black text on coloured buttons
        startButton.setBackground(greenColor);
        startButton.setForeground(darkGreenFgColor);

        // Stop Button - Modern design
        stopButton = new Button(buttonsContainer, SWT.PUSH);
        stopButton.setText("STOP");
        GridData stopBtnData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        stopBtnData.heightHint = 38;
        stopButton.setLayoutData(stopBtnData);
        stopButton.setFont(buttonFont);
        redColor = new Color(display, new RGB(243, 139, 168));  // Soft pink/red
        greyColor = new Color(display, new RGB(88, 91, 112));   // Muted dark grey
        stopButton.setEnabled(false);
        stopButton.setBackground(greyColor);
        stopButton.setForeground(mutedTextColor);

        // BOTTOM STATUS BAR - Sleek dark design
        Composite statusBar = new Composite(shell, SWT.NONE);
        GridData statusBarData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        statusBarData.heightHint = 60;
        statusBar.setLayoutData(statusBarData);
        Color statusBarBg = new Color(display, new RGB(24, 24, 37)); // Very dark background
        statusBar.setBackground(statusBarBg);
        GridLayout statusBarLayout = new GridLayout(1, false);
        statusBarLayout.marginHeight = 12;
        statusBarLayout.marginLeft = 20;
        statusBarLayout.marginRight = 20;
        statusBarLayout.marginWidth = 0;
        statusBarLayout.verticalSpacing = 4;
        statusBar.setLayout(statusBarLayout);

        // Status Label
        statusLabel = new Label(statusBar, SWT.WRAP);
        statusLabel.setText("Select a duration to begin");
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        statusLabel.setBackground(statusBarBg);
        statusLabel.setForeground(accentColorDark);
        Font statusFont = new Font(display, "Segoe UI", 11, SWT.BOLD);
        statusLabel.setFont(statusFont);

        // Time Remaining Label
        timeRemainingLabel = new Label(statusBar, SWT.WRAP);
        timeRemainingLabel.setText("--");
        timeRemainingLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        timeRemainingLabel.setBackground(statusBarBg);
        timeRemainingLabel.setForeground(mutedTextColor);
        Font statusSubFont = new Font(display, "Segoe UI", 9, SWT.NORMAL);
        timeRemainingLabel.setFont(statusSubFont);

        // Initialize preview status with current selections
        updatePreviewStatus();

        // Start button listener
        startButton.addListener(SWT.Selection, event -> {
            log.info("START button clicked on thread='{}'", Thread.currentThread().getName());
            // If the user never picked a duration (spinners still locked),
            // derive the end time from the duration combo now.
            if (!hourSpinner.isEnabled()) {
                updateEndTimeFromDuration();
                hourSpinner.setEnabled(true);
                minuteSpinner.setEnabled(true);
                amPmCombo.setEnabled(true);
            }

            LocalDateTime now = LocalDateTime.now();
            int hour = hourSpinner.getSelection();
            int minute = minuteSpinner.getSelection();
            boolean isPM = amPmCombo.getSelectionIndex() == 1;

            // Convert 12-hour to 24-hour format
            int hour24 = hour;
            if (isPM && hour != 12) {
                hour24 = hour + 12;
            } else if (!isPM && hour == 12) {
                hour24 = 0;
            }

            // Set end time to today with the selected time
            endDateTime = LocalDateTime.of(now.getYear(), now.getMonth(), now.getDayOfMonth(), hour24, minute, 0);

            // If the end time is in the past, set it for tomorrow
            if (endDateTime.isBefore(now)) {
                endDateTime = endDateTime.plusDays(1);
            }

            startTime = Instant.now();
            shouldStop = false;

            updateStatusDisplay();

            startButton.setEnabled(false);
            startButton.setBackground(greyColor);
            startButton.setForeground(mutedTextColor);
            stopButton.setEnabled(true);
            stopButton.setBackground(redColor);
            stopButton.setForeground(darkFgColor);
            hourSpinner.setEnabled(false);
            minuteSpinner.setEnabled(false);
            amPmCombo.setEnabled(false);
            durationCombo.setEnabled(false);

            keyPressCount = 0;  // reset counter for the new session
            log.info("Session starting — endDateTime={}", endDateTime);
            // Show a placeholder while sleep prevention initialises in the background.
            // On macOS battery without an external display, enable() shows a password
            // dialog — running it here would block the Cocoa main thread and cause
            // macOS to kill the app as unresponsive.
            timeRemainingLabel.setText("Configuring sleep prevention...");
            startTimer();
        });

        // Stop button listener
        stopButton.addListener(SWT.Selection, event -> {
            log.info("STOP button clicked on thread='{}'", Thread.currentThread().getName());
            stopApplication();
        });

        // Shared state for DPI change and resize clamping
        final int   minWidth  = 400;
        final int   minHeight = 480;
        final int[] lastDpi   = {display.getDPI().x};
        final int[] maxSize   = {(int)(minWidth * 1.5), (int)(minHeight * 1.5)};
        final boolean[] reflowing = {false};

        // Reflow helper — deferred so the OS finishes DPI scaling before we measure
        Runnable reflow = () -> {
            if (shell.isDisposed()) return;
            reflowing[0] = true;
            shell.layout(true, true);
            shell.pack();
            org.eclipse.swt.graphics.Point s = shell.getSize();
            int w = Math.max(s.x, minWidth);
            int h = Math.max(s.y, minHeight);
            maxSize[0] = (int)(w * 1.5);
            maxSize[1] = (int)(h * 1.5);
            shell.setMinimumSize(w, h);
            shell.setSize(w, h);
            shell.layout(true, true);
            reflowing[0] = false;
        };

        shell.addListener(SWT.Move, event -> {
            int currentDpi = display.getDPI().x;
            if (currentDpi != lastDpi[0]) {
                lastDpi[0] = currentDpi;
                // 150 ms gives the OS time to finish DPI transition before we reflow
                display.timerExec(150, reflow);
            }
        });

        // Clamp user resize to max 150 % of the default (computed) size
        shell.addListener(SWT.Resize, event -> {
            if (reflowing[0]) return;
            org.eclipse.swt.graphics.Point cur = shell.getSize();
            int clampW = Math.min(cur.x, maxSize[0]);
            int clampH = Math.min(cur.y, maxSize[1]);
            if (clampW != cur.x || clampH != cur.y) {
                reflowing[0] = true;
                shell.setSize(clampW, clampH);
                reflowing[0] = false;
            }
        });

        shell.addListener(SWT.Close, event -> {
            shouldStop = true;
            if (timer != null) {
                timer.cancel();
            }
            sleepPreventionService.disable();
            titleFont.dispose();
            subtitleFont.dispose();
            sectionFont.dispose();
            durationFont.dispose();
            numberFont.dispose();
            colonFont.dispose();
            amPmFont.dispose();
            buttonFont.dispose();
            statusFont.dispose();
            statusSubFont.dispose();
            bgColor.dispose();
            cardBgColor.dispose();
            darkTextColor.dispose();
            accentColor.dispose();
            accentColorDark.dispose();
            mutedTextColor.dispose();
            inputBgColor.dispose();
            statusBarBg.dispose();
            greenColor.dispose();
            redColor.dispose();
            greyColor.dispose();
            darkFgColor.dispose();
            darkGreenFgColor.dispose();
        });

        // Pack the shell to calculate preferred size based on content
        shell.pack();

        // Get the computed size and ensure minimum dimensions
        org.eclipse.swt.graphics.Point size = shell.getSize();
        int finalWidth  = Math.max(size.x, minWidth);
        int finalHeight = Math.max(size.y, minHeight);
        // Now that we know the real default size, set the 150 % cap
        maxSize[0] = (int)(finalWidth  * 1.5);
        maxSize[1] = (int)(finalHeight * 1.5);
        shell.setMinimumSize(finalWidth, finalHeight);
        shell.setSize(finalWidth, finalHeight);

        // Center the shell on the primary monitor
        org.eclipse.swt.graphics.Rectangle displayBounds = display.getPrimaryMonitor().getBounds();
        int x = displayBounds.x + (displayBounds.width - finalWidth) / 2;
        int y = displayBounds.y + (displayBounds.height - finalHeight) / 2;
        shell.setLocation(x, y);

        log.info("Shell opened — entering SWT event loop");
        shell.open();

        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }

        log.info("SWT event loop exited — disposing display");
        shouldStop = true;
        if (timer != null) {
            timer.cancel();
        }
        display.dispose();
        log.info("execute() finished");
    }

    /**
     * Shows a native SWT password input dialog on the current (main) thread.
     * Must only be called from the SWT event thread (use display.syncExec from other threads).
     *
     * @param message plain-text message to display (use \n for line breaks)
     * @return the password the user typed, or null if they clicked Cancel
     */
    private String openPasswordDialog(String message) {
        log.debug("openPasswordDialog() on thread='{}'", Thread.currentThread().getName());

        // Use app's dark theme colours so the dialog matches the main window
        Color bgColor    = new Color(display, new RGB(30, 30, 46));
        Color cardColor  = new Color(display, new RGB(49, 50, 68));
        Color textColor  = new Color(display, new RGB(205, 214, 244));
        Color inputColor = new Color(display, new RGB(69, 71, 90));
        Color accentColor = new Color(display, new RGB(137, 180, 250));

        Shell dialog = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        dialog.setText("EverStatus — Admin Access");
        dialog.setBackground(bgColor);

        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 24;
        layout.marginHeight = 20;
        layout.verticalSpacing = 14;
        dialog.setLayout(layout);

        // Message label — convert escaped \n sequences to real newlines
        Label msgLabel = new Label(dialog, SWT.WRAP);
        msgLabel.setText(message.replace("\\n", "\n"));
        msgLabel.setBackground(bgColor);
        msgLabel.setForeground(textColor);
        Font msgFont = new Font(display, "Segoe UI", 11, SWT.NORMAL);
        msgLabel.setFont(msgFont);
        GridData msgData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        msgData.widthHint = 380;
        msgLabel.setLayoutData(msgData);

        // Password input field
        Text passwordField = new Text(dialog, SWT.BORDER | SWT.PASSWORD | SWT.SINGLE);
        passwordField.setBackground(inputColor);
        passwordField.setForeground(textColor);
        Font inputFont = new Font(display, "Segoe UI", 12, SWT.NORMAL);
        passwordField.setFont(inputFont);
        GridData inputData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        inputData.heightHint = 36;
        passwordField.setLayoutData(inputData);

        // Buttons row
        Composite btnRow = new Composite(dialog, SWT.NONE);
        btnRow.setBackground(bgColor);
        btnRow.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        GridLayout btnLayout = new GridLayout(2, true);
        btnLayout.horizontalSpacing = 12;
        btnRow.setLayout(btnLayout);

        Color cancelBgColor = new Color(display, new RGB(88, 91, 112));
        Button cancelBtn = new Button(btnRow, SWT.PUSH);
        cancelBtn.setText("Cancel");
        cancelBtn.setBackground(cancelBgColor);
        cancelBtn.setForeground(textColor);
        Font btnFont = new Font(display, "Segoe UI", 11, SWT.NORMAL);
        cancelBtn.setFont(btnFont);
        GridData btnData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        btnData.widthHint = 100;
        btnData.heightHint = 36;
        cancelBtn.setLayoutData(btnData);

        Color okFgColor = new Color(display, new RGB(27, 27, 27));
        Button okBtn = new Button(btnRow, SWT.PUSH);
        okBtn.setText("OK");
        okBtn.setBackground(accentColor);
        okBtn.setForeground(okFgColor);
        okBtn.setFont(btnFont);
        okBtn.setLayoutData(btnData);
        dialog.setDefaultButton(okBtn);

        // Result holder — populated before dialog closes
        String[] result = {null};

        okBtn.addListener(SWT.Selection, e -> {
            result[0] = passwordField.getText();
            log.debug("OK clicked in password dialog");
            dialog.close();
        });
        cancelBtn.addListener(SWT.Selection, e -> {
            log.debug("Cancel clicked in password dialog");
            dialog.close();
        });
        // Allow pressing Enter to confirm
        passwordField.addListener(SWT.KeyDown, e -> {
            if (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) {
                result[0] = passwordField.getText();
                log.debug("Enter pressed in password dialog");
                dialog.close();
            }
        });

        dialog.pack();

        // Centre over the main shell
        Rectangle parentBounds = shell.getBounds();
        Point size = dialog.getSize();
        dialog.setLocation(
            parentBounds.x + (parentBounds.width  - size.x) / 2,
            parentBounds.y + (parentBounds.height - size.y) / 2
        );

        dialog.open();
        passwordField.setFocus();

        // Inner event loop — drives only this dialog until it is closed
        while (!dialog.isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }

        // Dispose local resources (all Colors created inside this method)
        msgFont.dispose();
        inputFont.dispose();
        btnFont.dispose();
        bgColor.dispose();
        cardColor.dispose();
        textColor.dispose();
        inputColor.dispose();
        accentColor.dispose();
        cancelBgColor.dispose();
        okFgColor.dispose();

        log.debug("openPasswordDialog() returning — entered={}", result[0] != null);
        return result[0];
    }

    private void updateEndTimeFromDuration() {
        if (durationCombo == null || durationCombo.isDisposed()) return;

        int selectionIndex = durationCombo.getSelectionIndex();
        if (selectionIndex < 0) return;

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime newEndTime;

        switch (selectionIndex) {
            case 0: // 15 minutes
                newEndTime = now.plusMinutes(15);
                break;
            case 1: // 30 minutes
                newEndTime = now.plusMinutes(30);
                break;
            case 2: // 1 hour
                newEndTime = now.plusHours(1);
                break;
            case 3: // 1 hour 30 minutes
                newEndTime = now.plusHours(1).plusMinutes(30);
                break;
            case 4: // 2 hours
                newEndTime = now.plusHours(2);
                break;
            case 5: // 2 hours 30 minutes
                newEndTime = now.plusHours(2).plusMinutes(30);
                break;
            case 6: // 3 hours
                newEndTime = now.plusHours(3);
                break;
            case 7: // 4 hours
                newEndTime = now.plusHours(4);
                break;
            case 8: // 5 hours
                newEndTime = now.plusHours(5);
                break;
            case 9: // 6 hours
                newEndTime = now.plusHours(6);
                break;
            case 10: // 8 hours
                newEndTime = now.plusHours(8);
                break;
            case 11: // 10 hours
                newEndTime = now.plusHours(10);
                break;
            case 12: // 12 hours
                newEndTime = now.plusHours(12);
                break;
            default:
                return;
        }

        // Update time pickers
        int hour = newEndTime.getHour();
        int minute = newEndTime.getMinute();

        // Convert to 12-hour format
        boolean isPM = hour >= 12;
        int hour12 = hour % 12;
        if (hour12 == 0) hour12 = 12;

        hourSpinner.setSelection(hour12);
        minuteSpinner.setSelection(minute);
        amPmCombo.select(isPM ? 1 : 0);
    }

    private void startTimer() {
        log.info("startTimer() — launching sleep-prevention-enable background thread");

        // enable() must run on a background thread on macOS:
        // when on battery with no external display it shows an osascript password dialog
        // via p.waitFor(), which would block the Cocoa main thread and cause macOS to
        // terminate the app as unresponsive.  The timer starts immediately; the coverage
        // label is updated via asyncExec once enable() returns.
        new Thread(() -> {
            log.info("sleep-prevention-enable thread started (thread='{}')",
                Thread.currentThread().getName());
            try {
                sleepPreventionService.enable();
            } catch (Exception e) {
                log.error("Unexpected exception in sleepPreventionService.enable()", e);
            }

            SleepPreventionService.Coverage cov = sleepPreventionService.getCoverage();
            log.info("sleepPreventionService.enable() returned — coverage={}", cov);

            if (!display.isDisposed()) {
                display.asyncExec(this::updateCoverageLabel);
            } else {
                log.warn("Display disposed before coverage label could be updated");
            }
        }, "sleep-prevention-enable").start();

        log.debug("Scheduling key-press timer (interval=60000ms, immediate first tick)");
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (!display.isDisposed()) {
                    display.asyncExec(() -> {
                        updateStatusDisplay();
                        checkEndTime();
                    });
                }
                simulateKeyPress();
            }
        }, 0, 60000); // Run every minute
        log.info("Timer scheduled — first key press will fire immediately");
    }

    /**
     * Updates the coverage/status label to reflect the current sleep-prevention coverage.
     * Safe to call from any thread as long as the caller uses display.asyncExec() when
     * not on the SWT main thread.  Must only be called while a session is active.
     */
    private void updateCoverageLabel() {
        if (timeRemainingLabel == null || timeRemainingLabel.isDisposed()) return;
        SleepPreventionService.Coverage cov = sleepPreventionService.getCoverage();
        String text;
        switch (cov) {
            case FULL    -> text = "Full coverage — lid-close sleep blocked";
            case PARTIAL -> text = "Note: lid-close on battery not blocked (admin prompt was skipped)";
            default      -> text = "Sleep prevention active";
        }
        log.info("updateCoverageLabel() — coverage={} label='{}'", cov, text);
        timeRemainingLabel.setText(text);
    }

    private void updateStatusDisplay() {
        if (statusLabel == null || statusLabel.isDisposed() || timeRemainingLabel == null || timeRemainingLabel.isDisposed()) {
            return;
        }

        if (endDateTime != null) {
            DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("hh:mm a");
            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MMM dd, yyyy");
            String endTimeStr = endDateTime.format(timeFormatter);
            String endDateStr = endDateTime.format(dateFormatter);
            statusLabel.setText("Active until " + endTimeStr + " on " + endDateStr);

            LocalDateTime now = LocalDateTime.now();
            Duration remaining = Duration.between(now, endDateTime);

            Duration elapsed = startTime != null ? Duration.between(startTime, Instant.now()) : Duration.ZERO;
            long uptimeH = elapsed.toHours();
            long uptimeM = elapsed.toMinutesPart();

            if (remaining.isNegative() || remaining.isZero()) {
                timeRemainingLabel.setText(String.format("Time remaining: 0h 0m  ·  Uptime: %dh %dm", uptimeH, uptimeM));
            } else {
                long hours = remaining.toHours();
                long minutes = remaining.toMinutesPart();
                timeRemainingLabel.setText(String.format("Time remaining: %dh %dm  ·  Uptime: %dh %dm", hours, minutes, uptimeH, uptimeM));
            }
        }
    }

    private void checkEndTime() {
        if (endDateTime != null && !shouldStop) {
            LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
            if (now.isAfter(endDateTime) || now.isEqual(endDateTime)) {
                log.info("Session end time reached — endDateTime={} now={}", endDateTime, now);
                if (startTime != null) {
                    long uptimeSecs = java.time.Duration.between(startTime, java.time.Instant.now()).getSeconds();
                    log.info("━━ SESSION SUMMARY ━━ uptime={}m {}s  keyPresses={}  coverage={}  reason=endTime",
                        uptimeSecs / 60, uptimeSecs % 60,
                        keyPressCount,
                        sleepPreventionService.getCoverage());
                }
                shouldStop = true;

                if (!display.isDisposed()) {
                    display.asyncExec(() -> {
                        if (!statusLabel.isDisposed()) {
                            statusLabel.setText("Session Complete");
                        }
                        if (!timeRemainingLabel.isDisposed()) {
                            timeRemainingLabel.setText("Closing in 3 seconds...");
                        }
                    });
                }

                if (timer != null) {
                    timer.cancel();
                }
                sleepPreventionService.disable();

                display.timerExec(3000, () -> {
                    if (!display.isDisposed() && !shell.isDisposed()) {
                        shell.close();
                    }
                });
            }
        }
    }

    private void stopApplication() {
        log.info("stopApplication() called on thread='{}'", Thread.currentThread().getName());

        // Session summary — useful for confirming the app ran correctly
        if (startTime != null) {
            long uptimeSecs = java.time.Duration.between(startTime, java.time.Instant.now()).getSeconds();
            log.info("━━ SESSION SUMMARY ━━ uptime={}m {}s  keyPresses={}  coverage={}",
                uptimeSecs / 60, uptimeSecs % 60,
                keyPressCount,
                sleepPreventionService.getCoverage());
        }
        shouldStop = true;
        if (timer != null) {
            timer.cancel();
            log.debug("Timer cancelled");
        }
        log.info("Calling sleepPreventionService.disable()...");
        sleepPreventionService.disable();

        statusLabel.setText("Stopped");
        timeRemainingLabel.setText("Session ended by user");

        startButton.setEnabled(true);
        startButton.setBackground(greenColor);
        startButton.setForeground(darkGreenFgColor);
        stopButton.setEnabled(false);
        stopButton.setBackground(greyColor);
        stopButton.setForeground(mutedTextColor);
        hourSpinner.setEnabled(true);
        minuteSpinner.setEnabled(true);
        amPmCombo.setEnabled(true);
        durationCombo.setEnabled(true);
    }

    private void simulateKeyPress() {
        if (shouldStop) {
            log.debug("simulateKeyPress() skipped — shouldStop=true");
            return;
        }
        try {
            // F13 has no system mapping on macOS or Windows (F15 triggered brightness overlay)
            robot.keyPress(KeyEvent.VK_F13);
            robot.keyRelease(KeyEvent.VK_F13);
            keyPressCount++;
            log.debug("F13 key simulated — count={} thread='{}'", keyPressCount, Thread.currentThread().getName());
        } catch (Exception e) {
            log.error("F13 key simulation FAILED (count={} thread='{}') — Robot may have lost focus or screen locked",
                keyPressCount, Thread.currentThread().getName(), e);
        }
    }

    private void updatePreviewStatus() {
        if (statusLabel == null || statusLabel.isDisposed() || timeRemainingLabel == null || timeRemainingLabel.isDisposed()) {
            return;
        }

        // Don't update preview if already running
        if (!shouldStop && endDateTime != null && startTime != null) {
            return;
        }

        // If the time spinners haven't been unlocked yet, show a prompt
        if (!hourSpinner.isEnabled()) {
            statusLabel.setText("Select a duration to begin");
            timeRemainingLabel.setText("--");
            return;
        }

        try {
            // Get current selections
            LocalDateTime now = LocalDateTime.now();
            int hour = hourSpinner.getSelection();
            int minute = minuteSpinner.getSelection();
            boolean isPM = amPmCombo.getSelectionIndex() == 1;

            // Convert 12-hour to 24-hour format
            int hour24 = hour;
            if (isPM && hour != 12) {
                hour24 = hour + 12;
            } else if (!isPM && hour == 12) {
                hour24 = 0;
            }

            // Calculate the selected end time (today)
            LocalDateTime selectedEndTime = LocalDateTime.of(now.getYear(), now.getMonth(), now.getDayOfMonth(), hour24, minute, 0);

            // If the end time is in the past, assume tomorrow
            if (selectedEndTime.isBefore(now)) {
                selectedEndTime = selectedEndTime.plusDays(1);
            }

            DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("hh:mm a");
            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MMM dd");
            String endTimeStr = selectedEndTime.format(timeFormatter);
            String endDateStr = selectedEndTime.format(dateFormatter);
            statusLabel.setText("Will end at " + endTimeStr + " on " + endDateStr);

            // Calculate time remaining
            Duration remaining = Duration.between(now, selectedEndTime);

            if (remaining.isNegative() || remaining.isZero()) {
                timeRemainingLabel.setText("Duration: 0h 0m");
            } else {
                long hours = remaining.toHours();
                long minutes = remaining.toMinutesPart();
                timeRemainingLabel.setText(String.format("Duration: %dh %dm", hours, minutes));
            }
        } catch (Exception e) {
            log.debug("updatePreviewStatus() suppressed non-fatal exception: {}", e.getMessage());
        }
    }
}
