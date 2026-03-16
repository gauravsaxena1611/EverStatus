package com.automations.everstatus;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.GridData;
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

    @Autowired
    private SleepPreventionService sleepPreventionService;

    private Label statusLabel;
    private Label timeRemainingLabel;
    private Instant startTime;
    private Robot robot;
    private LocalDateTime endDateTime;
    private Timer timer;
    private volatile boolean shouldStop = false;
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

    public void execute() {

        System.setProperty("java.awt.headless", "false");

        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Running in headless mode.");
        }

        try {
            robot = new Robot();
        } catch (Exception e) {
            System.err.println("Failed to initialize Robot for key presses. Error: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        display = new Display();
        shell = new Shell(display, SWT.CLOSE | SWT.TITLE | SWT.MIN);
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
        durationCombo.select(5); // Default to 2 hours 30 minutes
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
        amPmCombo.addListener(SWT.Selection, event -> updatePreviewStatus());

        // Add listener to update end time when duration is selected
        durationCombo.addListener(SWT.Selection, event -> {
            updateEndTimeFromDuration();
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
        greenColor = new Color(display, new RGB(166, 227, 161));  // Soft green
        startButton.setBackground(greenColor);
        startButton.setForeground(new Color(display, new RGB(27, 94, 32)));

        // Stop Button - Modern design
        stopButton = new Button(buttonsContainer, SWT.PUSH);
        stopButton.setText("STOP");
        GridData stopBtnData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        stopBtnData.heightHint = 38;
        stopButton.setLayoutData(stopBtnData);
        stopButton.setFont(buttonFont);
        redColor = new Color(display, new RGB(243, 139, 168));  // Soft pink/red
        greyColor = new Color(display, new RGB(88, 91, 112));   // Muted dark grey
        stopButton.setBackground(greyColor);
        stopButton.setForeground(mutedTextColor);
        stopButton.setEnabled(false);

        // BOTTOM STATUS BAR - Sleek dark design
        Composite statusBar = new Composite(shell, SWT.NONE);
        GridData statusBarData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        statusBarData.heightHint = 60;
        statusBar.setLayoutData(statusBarData);
        Color statusBarBg = new Color(display, new RGB(24, 24, 37)); // Very dark background
        statusBar.setBackground(statusBarBg);
        GridLayout statusBarLayout = new GridLayout(1, false);
        statusBarLayout.marginHeight = 14;
        statusBarLayout.marginWidth = 30;
        statusBarLayout.verticalSpacing = 6;
        statusBar.setLayout(statusBarLayout);

        // Status Label
        statusLabel = new Label(statusBar, SWT.NONE);
        statusLabel.setText("Ready to start");
        statusLabel.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        statusLabel.setBackground(statusBarBg);
        statusLabel.setForeground(accentColorDark);
        Font statusFont = new Font(display, "Segoe UI", 11, SWT.BOLD);
        statusLabel.setFont(statusFont);

        // Time Remaining Label
        timeRemainingLabel = new Label(statusBar, SWT.NONE);
        timeRemainingLabel.setText("--");
        timeRemainingLabel.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        timeRemainingLabel.setBackground(statusBarBg);
        timeRemainingLabel.setForeground(mutedTextColor);
        Font statusSubFont = new Font(display, "Segoe UI", 9, SWT.NORMAL);
        timeRemainingLabel.setFont(statusSubFont);

        // Initialize preview status with current selections
        updatePreviewStatus();

        // Start button listener
        startButton.addListener(SWT.Selection, event -> {
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
            stopButton.setEnabled(true);
            stopButton.setBackground(redColor);
            stopButton.setForeground(new Color(display, new RGB(27, 27, 27)));
            hourSpinner.setEnabled(false);
            minuteSpinner.setEnabled(false);
            amPmCombo.setEnabled(false);
            durationCombo.setEnabled(false);

            startTimer();
        });

        // Stop button listener
        stopButton.addListener(SWT.Selection, event -> {
            stopApplication();
        });

        // Handle DPI changes when moving between monitors
        final int[] lastDpi = {display.getDPI().x};
        shell.addListener(SWT.Move, event -> {
            int currentDpi = display.getDPI().x;
            if (currentDpi != lastDpi[0]) {
                lastDpi[0] = currentDpi;
                shell.layout(true, true);
                shell.pack();
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
        });

        // Pack the shell to calculate preferred size based on content
        shell.pack();

        // Get the computed size and ensure minimum dimensions
        org.eclipse.swt.graphics.Point size = shell.getSize();
        int minWidth = 400;
        int minHeight = 480;
        int finalWidth = Math.max(size.x, minWidth);
        int finalHeight = Math.max(size.y, minHeight);
        shell.setSize(finalWidth, finalHeight);
        shell.setMinimumSize(minWidth, minHeight);

        // Center the shell on the primary monitor
        org.eclipse.swt.graphics.Rectangle displayBounds = display.getPrimaryMonitor().getBounds();
        int x = displayBounds.x + (displayBounds.width - finalWidth) / 2;
        int y = displayBounds.y + (displayBounds.height - finalHeight) / 2;
        shell.setLocation(x, y);

        shell.open();

        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }

        shouldStop = true;
        if (timer != null) {
            timer.cancel();
        }
        display.dispose();
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
        sleepPreventionService.enable();
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

            if (remaining.isNegative() || remaining.isZero()) {
                timeRemainingLabel.setText("Time remaining: 0h 0m");
            } else {
                long hours = remaining.toHours();
                long minutes = remaining.toMinutesPart();
                timeRemainingLabel.setText(String.format("Time remaining: %dh %dm", hours, minutes));
            }
        }
    }

    private void checkEndTime() {
        if (endDateTime != null && !shouldStop) {
            LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
            if (now.isAfter(endDateTime) || now.isEqual(endDateTime)) {
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
        shouldStop = true;
        if (timer != null) {
            timer.cancel();
        }
        sleepPreventionService.disable();

        statusLabel.setText("Stopped");
        timeRemainingLabel.setText("Session ended by user");

        startButton.setEnabled(true);
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
            return;
        }
        try {
            // Use F15 key which is non-intrusive (doesn't affect most applications)
            robot.keyPress(KeyEvent.VK_F15);
            robot.keyRelease(KeyEvent.VK_F15);
        } catch (Exception e) {
            System.err.println("Error simulating key press: " + e.getMessage());
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
            // Ignore errors during preview update
        }
    }
}
