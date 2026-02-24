package com.heypixel.heypixelmod.cc.mizore.client.utils.bypass;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class InjectionLogger {

    private static final String LOG_DIR = "mizore" + File.separator + "log";
    private static final String LOG_FILE = "inject.log";
    private static final boolean DEBUG = Boolean.getBoolean("mizore.debug");
    private static volatile FileWriter writer;
    private static volatile boolean initLogged;
    private static volatile String injectMode;
    private static volatile String loaderPath;
    private static volatile String payloadPath;

    private InjectionLogger() {}

    private static File getLogFile() {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isEmpty()) userHome = ".";
        File dir = new File(userHome, LOG_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, LOG_FILE);
    }

    public static String getLogPath() {
        return getLogFile().getAbsolutePath();
    }

    public static synchronized void init() {
        if (writer != null) return;
        try {
            File logFile = getLogFile();
            writer = new FileWriter(logFile, StandardCharsets.UTF_8, false);
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
            String header =
                    "[INJECT] Session started\n" +
                    "time=" + time + "\n" +
                    "java=" + System.getProperty("java.version") + "\n" +
                    "os=" + System.getProperty("os.name") + " " + System.getProperty("os.arch") + "\n" +
                    "mode=" + (injectMode == null ? "unknown" : injectMode) + "\n" +
                    "loader_path=" + (loaderPath == null ? "unknown" : loaderPath) + "\n" +
                    "payload_path=" + (payloadPath == null ? System.getProperty("mizore.client.payload_path", "unknown") : payloadPath) + "\n" +
                    "log_file=" + logFile.getAbsolutePath() + "\n\n";
            writer.write(header);
            writer.flush();
            if (!initLogged) {
                initLogged = true;
                System.err.println("[mizore] Injection log enabled: " + logFile.getAbsolutePath());
            }
        } catch (IOException ignored) {
        }
    }

    public static synchronized void init(String mode, String loaderJarPath, String payloadJarPath) {
        if (writer != null) return;
        injectMode = mode;
        loaderPath = loaderJarPath;
        payloadPath = payloadJarPath;
        init();
    }

    private static synchronized void ensureWriter() {
        if (writer == null) {
            init();
        }
    }

    private static synchronized void shutdown() {
        try {
            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }
        } catch (IOException ignored) {
        }
    }

    private static void logInternal(String tag, String msg) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
        String line = "[" + timestamp + "][" + tag + "] " + msg + System.lineSeparator();
        try {
            ensureWriter();
            if (writer != null) {
                writer.write(line);
                writer.flush();
            }
        } catch (IOException ignored) {
        }
        if (DEBUG) {
            System.err.print(line);
        }
    }

    public static void log(String tag, String message) {
        logInternal(tag, message);
    }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(InjectionLogger::shutdown, "mizore-inject-log-shutdown"));
    }
}
