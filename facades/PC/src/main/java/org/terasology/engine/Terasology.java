// Copyright 2020 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.config.Config;
import org.terasology.config.SystemConfig;
import org.terasology.crashreporter.CrashReporter;
import org.terasology.engine.modes.StateLoading;
import org.terasology.engine.modes.StateMainMenu;
import org.terasology.engine.paths.PathManager;
import org.terasology.engine.subsystem.EngineSubsystem;
import org.terasology.engine.subsystem.common.ConfigurationSubsystem;
import org.terasology.engine.subsystem.common.ThreadManager;
import org.terasology.engine.subsystem.common.hibernation.HibernationSubsystem;
import org.terasology.engine.subsystem.config.BindsSubsystem;
import org.terasology.engine.subsystem.discordrpc.DiscordRPCSubSystem;
import org.terasology.engine.subsystem.headless.HeadlessAudio;
import org.terasology.engine.subsystem.headless.HeadlessGraphics;
import org.terasology.engine.subsystem.headless.HeadlessInput;
import org.terasology.engine.subsystem.headless.HeadlessTimer;
import org.terasology.engine.subsystem.headless.mode.HeadlessStateChangeListener;
import org.terasology.engine.subsystem.headless.mode.StateHeadlessSetup;
import org.terasology.engine.subsystem.lwjgl.LwjglAudio;
import org.terasology.engine.subsystem.lwjgl.LwjglGraphics;
import org.terasology.engine.subsystem.lwjgl.LwjglInput;
import org.terasology.engine.subsystem.lwjgl.LwjglTimer;
import org.terasology.engine.subsystem.openvr.OpenVRInput;
import org.terasology.game.GameManifest;
import org.terasology.network.NetworkMode;
import org.terasology.rendering.nui.layers.mainMenu.savedGames.GameInfo;
import org.terasology.rendering.nui.layers.mainMenu.savedGames.GameProvider;
import org.terasology.splash.SplashScreen;
import org.terasology.splash.SplashScreenBuilder;
import picocli.CommandLine;
import picocli.CommandLine.Option;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Class providing the main() method for launching Terasology as a PC app.
 * <br><br>
 * Through the following launch arguments default locations to store logs and game saves can be overridden, by using the
 * current directory or a specified one as the home directory. Furthermore, Terasology can be launched headless, to save
 * resources while acting as a server or to run in an environment with no graphics, audio or input support. Additional
 * arguments are available to reload the latest game on startup and to disable crash reporting.
 * <p>
 * Available launch arguments:
 *
 * <table summary="Launch arguments">
 * <tbody>
 * <tr><td>-homedir</td><td>Use the current directory as the home directory.</td></tr>
 * <tr><td>-homedir=path</td><td>Use the specified path as the home directory.</td></tr>
 * <tr><td>-headless</td><td>Start headless.</td></tr>
 * <tr><td>-loadlastgame</td><td>Load the latest game on startup.</td></tr>
 * <tr><td>-createlastgame</td><td>Recreates the world of the latest game with a new save file on startup.</td></tr>
 * <tr><td>-noSaveGames</td><td>Disable writing of save games.</td></tr>
 * <tr><td>-noCrashReport</td><td>Disable crash reporting.</td></tr>
 * <tr><td>-noSound</td><td>Disable sound.</td></tr>
 * <tr><td>-noSplash</td><td>Disable splash screen.</td></tr>
 * <tr><td>-serverPort=xxxxx</td><td>Change the server port.</td></tr>
 * </tbody>
 * </table>
 * <p>
 * When used via command line an usage help and some examples can be obtained via:
 * <br><br>
 * terasology -help    or    terasology /?
 *
 */

@CommandLine.Command(name = "terasology")
public final class Terasology implements Callable<Integer> {

    @SuppressWarnings("unused")
    @Option(names = {"--help", "-help", "/help", "-h", "/h", "-?", "/?"}, usageHelp = true, description = "show help")
    private boolean helpRequested;

    @Option(names = "--headless", description = "Start headless")
    private boolean isHeadless;

    @Option(names = "--crash-report", defaultValue = "true", negatable = true, description = "Enable crash reporting")
    private boolean crashReportEnabled;

    @Option(names = "--sound", defaultValue = "true", negatable = true, description = "Enable sound")
    private boolean soundEnabled;

    @Option(names = "--splash", defaultValue = "true", negatable = true, description = "Enable splash screen")
    private boolean splashEnabled;

    @Option(names = "--load-last-game", description = "Load the latest game on startup")
    private boolean loadLastGame;

    @Option(names = "--create-last-game", description = "Recreates the world of the latest game with a new save file on startup")
    private boolean createLastGame;

    @Option(names = "--permissive-security")
    private boolean permissiveSecurity;

    @Option(names = "--save-games", defaultValue = "true", negatable = true, description = "Enable new save games")
    private boolean saveGamesEnabled;

    @Option(names = "--server-port", description = "Change the server port")
    private Integer serverPort;

    @Option(names = "--override-default-config", description = "Override default config")
    private Path overrideConfigPath;

    @Option(names = "--homedir", description = "Path to home directory")
    private Path homeDir;

    @CommandLine.Spec CommandLine.Model.CommandSpec spec;

    private static final Logger logger = LoggerFactory.getLogger(Terasology.class);

    private Terasology() {
    }

    public static void main(String[] args) {
        new CommandLine(new Terasology()).execute(args);
    }

    @Override
    public Integer call() throws IOException {
        handleLaunchArguments();
        setupLogging();

        SplashScreen splashScreen;
        if (splashEnabled) {
            CountDownLatch splashInitLatch = new CountDownLatch(1);
            GLFWSplashScreen glfwSplash = new GLFWSplashScreen(splashInitLatch);
            Thread thread = new Thread(glfwSplash, "splashscreen-loop");
            thread.setDaemon(true);
            thread.start();
            try {
                // wait splash initialize... we will lose some post messages otherwise.
                splashInitLatch.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // ignore
            }
            splashScreen = glfwSplash;
        } else {
            splashScreen = SplashScreenBuilder.createStub();
        }
        splashScreen.post("Java Runtime " + System.getProperty("java.version") + " loaded");

        try {
            TerasologyEngineBuilder builder = new TerasologyEngineBuilder();
            populateSubsystems(builder);
            TerasologyEngine engine = builder.build();
            engine.subscribe(newStatus -> {
                if (newStatus == StandardGameStatus.RUNNING) {
                    splashScreen.close();
                } else {
                    splashScreen.post(newStatus.getDescription());
                }
            });

            if (isHeadless) {
                engine.subscribeToStateChange(new HeadlessStateChangeListener(engine));
                engine.run(new StateHeadlessSetup());
            } else if (loadLastGame) {
                engine.initialize(); //initialize the managers first
                engine.getFromEngineContext(ThreadManager.class).submitTask("loadGame", () -> {
                    GameManifest gameManifest = getLatestGameManifest();
                    if (gameManifest != null) {
                        engine.changeState(new StateLoading(gameManifest, NetworkMode.NONE));
                    }
                });
            } else {
                if (createLastGame) {
                    engine.initialize();
                    engine.getFromEngineContext(ThreadManager.class).submitTask("createLastGame", () -> {
                        GameManifest gameManifest = getLatestGameManifest();
                        if (gameManifest != null) {
                            String title = gameManifest.getTitle();
                            if (!title.startsWith("New Created")) { //if first time run
                                gameManifest.setTitle("New Created " + title + " 1");
                            } else { //if not first time run
                                gameManifest.setTitle(getNewTitle(title));
                            }
                            engine.changeState(new StateLoading(gameManifest, NetworkMode.NONE));
                        }
                    });
                }

                engine.run(new StateMainMenu());
            }
        } catch (Throwable e) {
            // also catch Errors such as UnsatisfiedLink, NoSuchMethodError, etc.
            splashScreen.close();
            reportException(e);
        }

        return 0;
    }

    private static String getNewTitle(String title) {
        String newTitle = title.substring(0, getPositionOfLastDigit(title));
        int fileNumber = getLastNumber(title);
        fileNumber++;
        return (newTitle + " " + fileNumber);
    }

    private static void setupLogging() {
        Path path = PathManager.getInstance().getLogPath();
        if (path == null) {
            path = Paths.get("logs");
        }

        LoggingContext.initialize(path);
    }

    private static void printUsageAndExit() {
        // TODO: Add all this stuff back in to the help text.
//
//        String printUsageFlags = Joiner.on("|").join(PRINT_USAGE_FLAGS);
//
//        List<String> opts = ImmutableList.of(
//                printUsageFlags,
//                USE_CURRENT_DIR_AS_HOME + "|" + USE_SPECIFIED_DIR_AS_HOME + "<path>",
//                START_HEADLESS,
//                LOAD_LAST_GAME,
//                CREATE_LAST_GAME,
//                NO_CRASH_REPORT,
//                NO_SAVE_GAMES,
//                PERMISSIVE_SECURITY,
//                NO_SOUND,
//                NO_SPLASH,
//                OVERRIDE_DEFAULT_CONFIG + "<path>",
//                SERVER_PORT + "<port>");
//
//        StringBuilder optText = new StringBuilder();
//
//        for (String opt : opts) {
//            optText.append(" [").append(opt).append("]");
//        }
//
//        System.out.println("Usage:");
//        System.out.println();
//        System.out.println("    terasology" + optText.toString());
//        System.out.println();
//        System.out.println("By default Terasology saves data such as game saves and logs into subfolders of a platform-specific \"home directory\".");
//        System.out.println("Saving can be explicitly disabled using the \"" + NO_SAVE_GAMES + "\" flag.");
//        System.out.println("Optionally, the user can override the default by using one of the following launch arguments:");
//        System.out.println();
//        System.out.println("    " + USE_CURRENT_DIR_AS_HOME + "        Use the current directory as the home directory.");
//        System.out.println("    " + USE_SPECIFIED_DIR_AS_HOME + "<path> Use the specified directory as the home directory.");
//        System.out.println();
//        System.out.println("It is also possible to start Terasology in headless mode (no graphics), i.e. to act as a server.");
//        System.out.println("For this purpose use the " + START_HEADLESS + " launch argument.");
//        System.out.println();
//        System.out.println("To automatically load the latest game on startup,");
//        System.out.println("use the " + LOAD_LAST_GAME + " launch argument.");
//        System.out.println();
//        System.out.println("To automatically recreate the last game played with a new save file,");
//        System.out.println("use the " + CREATE_LAST_GAME + "launch argument");
//        System.out.println();
//        System.out.println("By default Crash Reporting is enabled.");
//        System.out.println("To disable this feature use the " + NO_CRASH_REPORT + " launch argument.");
//        System.out.println();
//        System.out.println("To disable sound use the " + NO_SOUND + " launch argument (default in headless mode).");
//        System.out.println();
//        System.out.println("To disable the splash screen use the " + NO_SPLASH + " launch argument.");
//        System.out.println();
//        System.out.println("To change the port the server is hosted on use the " + SERVER_PORT + " launch argument.");
//        System.out.println();
//        System.out.println("To override the default generated config (useful for headless server) use the " + OVERRIDE_DEFAULT_CONFIG + " launch argument");
//        System.out.println();
//        System.out.println("Examples:");
//        System.out.println();
//        System.out.println("    Use the current directory as the home directory:");
//        System.out.println("    terasology " + USE_CURRENT_DIR_AS_HOME);
//        System.out.println();
//        System.out.println("    Use \"myPath\" as the home directory:");
//        System.out.println("    terasology " + USE_SPECIFIED_DIR_AS_HOME + "myPath");
//        System.out.println();
//        System.out.println("    Start terasology in headless mode (no graphics) and enforce using the default port:");
//        System.out.println("    terasology " + START_HEADLESS + " " + SERVER_PORT + TerasologyConstants.DEFAULT_PORT);
//        System.out.println();
//        System.out.println("    Load the latest game on startup and disable crash reporting");
//        System.out.println("    terasology " + LOAD_LAST_GAME + " " + NO_CRASH_REPORT);
//        System.out.println();
//        System.out.println("    Don't start Terasology, just print this help:");
//        System.out.println("    terasology " + PRINT_USAGE_FLAGS[1]);
//        System.out.println();
//        System.out.println("Alternatively use our standalone Launcher from: https://github.com/MovingBlocks/TerasologyLauncher/releases");
//        System.out.println();
//
        System.exit(0);
    }

    private void handleLaunchArguments() throws IOException {
        if (homeDir != null) {
            logger.info("homeDir is {}", homeDir);
            PathManager.getInstance().useOverrideHomePath(homeDir);
            // TODO: what is this?
            //   PathManager.getInstance().chooseHomePathManually();
        } else {
            PathManager.getInstance().useDefaultHomePath();
        }

        if (isHeadless) {
            crashReportEnabled = false;
            splashEnabled = false;
        }
        if (!saveGamesEnabled) {
            System.setProperty(SystemConfig.SAVED_GAMES_ENABLED_PROPERTY, "false");
        }
        if (permissiveSecurity) {
            System.setProperty(SystemConfig.PERMISSIVE_SECURITY_ENABLED_PROPERTY, "true");
        }
        if (serverPort != null) {
            System.setProperty(ConfigurationSubsystem.SERVER_PORT_PROPERTY, serverPort.toString());
        }
        if (overrideConfigPath != null) {
            System.setProperty(Config.PROPERTY_OVERRIDE_DEFAULT_CONFIG, overrideConfigPath.toString());
        }
    }

    private void populateSubsystems(TerasologyEngineBuilder builder) {
        if (isHeadless) {
            builder.add(new HeadlessGraphics())
                    .add(new HeadlessTimer())
                    .add(new HeadlessAudio())
                    .add(new HeadlessInput());
        } else {
            EngineSubsystem audio = soundEnabled ? new LwjglAudio() : new HeadlessAudio();
            builder.add(audio)
                    .add(new LwjglGraphics())
                    .add(new LwjglTimer())
                    .add(new LwjglInput())
                    .add(new BindsSubsystem())
                    .add(new OpenVRInput());
            builder.add(new DiscordRPCSubSystem());
        }
        builder.add(new HibernationSubsystem());
    }

    private void reportException(Throwable throwable) {
        Path logPath = LoggingContext.getLoggingPath();

        if (!GraphicsEnvironment.isHeadless() && crashReportEnabled) {
            CrashReporter.report(throwable, logPath);
        } else {
            throwable.printStackTrace();
            System.err.println("For more details, see the log files in " + logPath.toAbsolutePath().normalize());
        }
    }

    private static GameManifest getLatestGameManifest() {
        GameInfo latestGame = null;
        List<GameInfo> savedGames = GameProvider.getSavedGames();
        for (GameInfo savedGame : savedGames) {
            if (latestGame == null || savedGame.getTimestamp().after(latestGame.getTimestamp())) {
                latestGame = savedGame;
            }
        }

        if (latestGame == null) {
            return null;
        }

        return latestGame.getManifest();
    }

    private static int getPositionOfLastDigit(String str) {
        int position;
        for (position = str.length() - 1; position >= 0; --position) {
            char c = str.charAt(position);
            if (!Character.isDigit(c)) {
                break;
            }
        }
        return position + 1;
    }

    private static int getLastNumber(String str) {
        int positionOfLastDigit = getPositionOfLastDigit(str);
        if (positionOfLastDigit == str.length()) {
            // string does not end in digits
            return -1;
        }
        return Integer.parseInt(str.substring(positionOfLastDigit));
    }

}
