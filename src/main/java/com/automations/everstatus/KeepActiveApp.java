package com.automations.everstatus;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.layout.FillLayout;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Timer;
import java.util.TimerTask;

@Service
public class KeepActiveApp {

    private Text editorText;
    private Instant startTime;
    private Robot robot;

    public void execute() {

        System.setProperty("java.awt.headless", "false");

        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Running in headless mode.");
        }

        try {
            robot = new Robot(); // Initialize the Robot instance
        } catch (Exception e) {
            System.err.println("Failed to initialize Robot for key presses. Error: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        Display display = new Display();
        Shell shell = new Shell(display);
        shell.setText("Ever Status Robot");
        shell.setLayout(new FillLayout());
        shell.setSize(400, 200);

        editorText = new Text(shell, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP);

        startTime = Instant.now();

        // Start a timer task to update uptime every minute
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                display.asyncExec(() -> updateEditorText());
                simulateKeyPress();
            }
        }, 0, 60000); // Run every minute (60000 ms)

        shell.open();

        // Main event loop
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }

        // Clean up
        timer.cancel();
        display.dispose();
    }

    private void updateEditorText() {
        Duration uptime = Duration.between(startTime, Instant.now());
        long hours = uptime.toHours();
        long minutes = uptime.toMinutesPart();

        String uptimeText;
        if (hours > 0) {
            uptimeText = String.format("Uptime: %d hours %d minutes", hours, minutes);
        } else {
            uptimeText = String.format("Uptime: %d minutes", minutes);
        }

        editorText.setText(uptimeText);
    }

    private void simulateKeyPress() {
        try {
            // Simulate a key press and release (e.g., Space bar)
            robot.keyPress(KeyEvent.VK_SPACE);
            robot.keyRelease(KeyEvent.VK_SPACE);
        } catch (Exception e) {
            System.err.println("Error simulating key press: " + e.getMessage());
        }
    }
}
