package com.automations.everstatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@SpringBootApplication
public class Application implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(Application.class);

	@Autowired
	private KeepActiveApp keepActiveApp;

	public static void main(String[] args) {
		// ── Step 1: Detect log directory ──────────────────────────────────────────
		// Must happen before SpringApplication.run() so logback-spring.xml picks up
		// the correct FILE appender path when building the distributed app.
		String logDir = detectLogDirectory();
		System.setProperty("everstatus.log.dir", logDir);

		// ── Step 2: Clear previous logs ───────────────────────────────────────────
		// Delete the log files BEFORE the first log.xxx() call so Logback creates
		// fresh files when it initialises.  Each run therefore contains only the
		// logs for that run — no accumulation across sessions.
		// Rolled/dated archives in the same folder are also removed so the logs/
		// directory stays clean.
		clearPreviousLogs(logDir);

		// Print to stdout before logback is fully initialised so it is visible even
		// if the file appender fails to open (e.g. permission denied on the target dir).
		// Use Paths.get() so the separator is correct on every platform
		// (Windows: logs\everstatus.log, macOS/Linux: logs/everstatus.log)
		System.out.println("[EverStatus] Log directory : " + logDir);
		System.out.println("[EverStatus] Log file      : " + Paths.get(logDir, "everstatus.log"));
		System.out.println("[EverStatus] Error log     : " + Paths.get(logDir, "everstatus-errors.log"));

		// ── Step 3: Global uncaught exception handler ────────────────────────────
		// Catches crashes on any thread that does not have its own try/catch.
		// Without this, an uncaught exception on a background thread prints to stderr
		// (invisible in a .app bundle) and disappears — the log file captures it instead.
		Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
			log.error("━━ UNCAUGHT EXCEPTION ━━ thread='{}' — please report with the full log file at: {}",
				thread.getName(), logDir + "/everstatus.log", throwable)
		);

		// ── Step 4: Startup diagnostics ───────────────────────────────────────────
		Runtime rt = Runtime.getRuntime();
		log.info("══════════════════════════════════════════════════════════════");
		log.info("  EverStatus  —  starting up");
		log.info("══════════════════════════════════════════════════════════════");
		log.info("Log directory  : {}", logDir);
		log.info("OS             : {} {}  (arch={})",
			System.getProperty("os.name"),
			System.getProperty("os.version"),
			System.getProperty("os.arch"));
		log.info("Java           : {} ({})  home={}",
			System.getProperty("java.version"),
			System.getProperty("java.vendor"),
			System.getProperty("java.home"));
		log.info("User           : {}  dir={}",
			System.getProperty("user.name"),
			System.getProperty("user.dir"));
		log.info("Memory         : {}MB free / {}MB total / {}MB max",
			rt.freeMemory()  / 1_048_576,
			rt.totalMemory() / 1_048_576,
			rt.maxMemory()   / 1_048_576);
		log.info("CPUs           : {}", rt.availableProcessors());
		log.info("Timezone       : {} (offset={})",
			java.util.TimeZone.getDefault().getID(),
			java.time.ZonedDateTime.now().getOffset());
		logDisplayInfo();
		logTeamsLogPath();
		log.info("──────────────────────────────────────────────────────────────");

		SpringApplication.run(Application.class, args);
	}

	@Override
	public void run(String... args) {
		log.info("Spring context ready — launching UI on thread='{}'", Thread.currentThread().getName());
		try {
			keepActiveApp.execute();
		} catch (Exception e) {
			log.error("Fatal error in keepActiveApp.execute()", e);
			throw e;
		}
		log.info("keepActiveApp.execute() returned — application exiting normally");
	}

	// ─────────────────────────────────────────────────────────────────────────────
	//  Log housekeeping
	// ─────────────────────────────────────────────────────────────────────────────

	/**
	 * Deletes all {@code *.log} files inside the given log directory before
	 * Logback opens them.  Called at the very start of {@code main()} so each
	 * app launch produces a clean, self-contained log for that run only.
	 *
	 * <p>Failures are printed to stdout (Logback is not yet up) but are
	 * non-fatal — the app continues even if old logs cannot be removed.</p>
	 */
	private static void clearPreviousLogs(String logDir) {
		try {
			Path dir = Paths.get(logDir);
			if (!Files.exists(dir)) {
				// Directory will be created by Logback on first write — nothing to clear
				return;
			}
			File[] logFiles = dir.toFile().listFiles(
				(d, name) -> name.endsWith(".log")
			);
			if (logFiles == null || logFiles.length == 0) {
				return;
			}
			int deleted = 0;
			for (File f : logFiles) {
				try {
					if (f.delete()) {
						deleted++;
					} else {
						System.out.println("[EverStatus] WARNING: could not delete old log file: " + f.getAbsolutePath());
					}
				} catch (Exception e) {
					System.out.println("[EverStatus] WARNING: error deleting " + f.getName() + ": " + e.getMessage());
				}
			}
			if (deleted > 0) {
				System.out.println("[EverStatus] Cleared " + deleted + " previous log file(s) from: " + logDir);
			}
		} catch (Exception e) {
			System.out.println("[EverStatus] WARNING: clearPreviousLogs() failed: " + e.getMessage());
		}
	}

	// ─────────────────────────────────────────────────────────────────────────────
	//  Log directory detection
	// ─────────────────────────────────────────────────────────────────────────────

	/**
	 * Returns the directory where log files should be written, based on how the
	 * app is being run:
	 *
	 * <ul>
	 *   <li><b>jpackage macOS .app bundle</b> — {@code java.home} contains
	 *       {@code .app/Contents/runtime}; logs go in a {@code logs/} folder
	 *       next to the {@code .app} bundle (same location the user sees the app).</li>
	 *   <li><b>jpackage Windows install dir</b> — {@code java.home} ends with
	 *       {@code \runtime}; logs go in a {@code logs/} sub-folder inside the
	 *       install directory.</li>
	 *   <li><b>JAR launcher (runEverStatus.sh / .bat)</b> — {@code user.dir} is
	 *       the directory that contains the JAR; logs go in a {@code logs/}
	 *       sub-folder there.</li>
	 *   <li><b>Fallback</b> — {@code ~/Library/Logs/EverStatus} on macOS,
	 *       {@code ~/everstatus-logs} elsewhere.</li>
	 * </ul>
	 */
	private static String detectLogDirectory() {
		String javaHome = System.getProperty("java.home", "");
		String normalised = javaHome.replace('\\', '/');

		// ── jpackage macOS .app bundle ────────────────────────────────────────────
		// java.home = /path/to/EverStatus.app/Contents/runtime/Contents/Home
		if (normalised.contains(".app/Contents/runtime")) {
			try {
				java.nio.file.Path p = Paths.get(javaHome);
				// Walk up to find the .app directory
				while (p != null && !p.getFileName().toString().endsWith(".app")) {
					p = p.getParent();
				}
				if (p != null && p.getParent() != null) {
					// Place logs/ next to the .app so the user finds them easily
					return p.getParent().resolve("logs").toString();
				}
			} catch (Exception ignored) { /* fall through */ }
		}

		// ── jpackage Windows install directory ────────────────────────────────────
		// java.home = C:\Users\...\EverStatus\runtime
		if (normalised.endsWith("/runtime")) {
			try {
				java.nio.file.Path installDir = Paths.get(javaHome).getParent();
				if (installDir != null) {
					return installDir.resolve("logs").toString();
				}
			} catch (Exception ignored) { /* fall through */ }
		}

		// ── JAR launcher (shell / bat script) ─────────────────────────────────────
		// user.dir = the directory the script was launched from (same dir as the JAR)
		String userDir = System.getProperty("user.dir", "");
		if (!userDir.isEmpty() && !userDir.equals("/")) {
			try {
				return Paths.get(userDir).resolve("logs").toString();
			} catch (Exception ignored) { /* fall through */ }
		}

		// ── Fallback ──────────────────────────────────────────────────────────────
		String os = System.getProperty("os.name", "").toLowerCase();
		if (os.contains("mac")) {
			return System.getProperty("user.home") + "/Library/Logs/EverStatus";
		}
		// Paths.get() produces the correct native separator on every platform
		return Paths.get(System.getProperty("user.home"), "everstatus-logs").toString();
	}

	// ─────────────────────────────────────────────────────────────────────────────
	//  Display diagnostics
	// ─────────────────────────────────────────────────────────────────────────────

	/**
	 * Detects and logs the Microsoft Teams log directory so EverStatus and Teams
	 * logs can be cross-referenced. Both log files use ISO 8601 timestamps with
	 * the same timezone offset, making direct comparison possible.
	 */
	private static void logTeamsLogPath() {
		String os = System.getProperty("os.name", "").toLowerCase();
		String home = System.getProperty("user.home", "");
		try {
			java.util.List<Path> candidates = new java.util.ArrayList<>();
			if (os.contains("mac")) {
				// New Teams (2023+)
				candidates.add(Paths.get(home, "Library", "Group Containers",
					"UBF8T346G9.com.microsoft.teams", "Library", "Application Support", "Logs"));
				// Teams Classic
				candidates.add(Paths.get(home, "Library", "Application Support",
					"Microsoft", "Teams", "logs.txt").getParent());
			} else if (os.contains("win")) {
				String appData  = System.getenv("APPDATA");
				String localApp = System.getenv("LOCALAPPDATA");
				// Teams Classic — logs.txt lives directly in this folder
				if (appData != null)
					candidates.add(Paths.get(appData, "Microsoft", "Teams"));
				// New Teams (exe installer)
				if (localApp != null)
					candidates.add(Paths.get(localApp, "Microsoft", "Teams"));
				// New Teams (Microsoft Store / MSIX package)
				if (localApp != null)
					candidates.add(Paths.get(localApp, "Packages",
						"MSTeams_8wekyb3d8bbwe", "LocalCache", "Microsoft", "MSTeams", "Logs"));
			}
			for (Path candidate : candidates) {
				if (Files.exists(candidate)) {
					log.info("Teams logs     : {} (cross-reference with EverStatus timestamps)", candidate);
					return;
				}
			}
			log.debug("Teams logs     : not found at known paths — Teams may not be installed");
		} catch (Exception e) {
			log.debug("Teams logs     : could not detect path — {}", e.getMessage());
		}
	}

	/** Logs the count and resolution of each connected screen. */
	private static void logDisplayInfo() {
		try {
			if (GraphicsEnvironment.isHeadless()) {
				log.warn("Displays       : running in headless mode — no screens detected");
				return;
			}
			GraphicsDevice[] screens = GraphicsEnvironment
				.getLocalGraphicsEnvironment()
				.getScreenDevices();
			log.info("Displays       : {} screen(s) detected", screens.length);
			for (int i = 0; i < screens.length; i++) {
				Rectangle bounds = screens[i].getDefaultConfiguration().getBounds();
				log.info("  Screen[{}]   : {}×{}  id={}",
					i, bounds.width, bounds.height, screens[i].getIDstring());
			}
		} catch (Exception e) {
			log.warn("Displays       : could not enumerate screens — {}", e.getMessage());
		}
	}
}
