package com.heypixel.heypixelmod.cc.mizore.client.utils.bypass;

import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;

import java.io.IOException;
import java.util.Locale;
public final class JmapExecBypass {

    private JmapExecBypass() {}

    private static final String TOOL_NOT_FOUND_MSG =
            "Cannot run program: CreateProcess error=2, The system cannot find the file specified";

    private static final String[] BLOCKED_TOOLS = {
            "jmap", "jstack", "jcmd", "jhsdb", "jinfo", "jattach"
    };

    public static boolean isBlockedCommand(String[] cmd) {
        if (cmd == null) return false;
        for (String s : cmd) {
            if (s == null) continue;
            String lower = s.toLowerCase(Locale.ROOT);
            for (String tool : BLOCKED_TOOLS) {
                if (lower.contains(tool)) return true;
            }
        }
        return false;
    }

    private static boolean isBlockedCommand(String cmd) {
        if (cmd == null) return false;
        String lower = cmd.toLowerCase(Locale.ROOT);
        for (String tool : BLOCKED_TOOLS) {
            if (lower.contains(tool)) return true;
        }
        return false;
    }

    public static Process hookRuntimeExec(Runtime runtime, String command) throws IOException {
        if (isBlockedCommand(command)) {
            InjectionLogger.log("JmapBypass", "blocked diagnostic tool: " + command);
            throw new IOException(TOOL_NOT_FOUND_MSG);
        }
        if (runtime == null) runtime = Runtime.getRuntime();
        return runtime.exec(command);
    }

    public static Process hookRuntimeExec(Runtime runtime, String[] cmdarray) throws IOException {
        if (isBlockedCommand(cmdarray)) {
            InjectionLogger.log("JmapBypass", "blocked diagnostic tool: " + String.join(" ", cmdarray));
            throw new IOException(TOOL_NOT_FOUND_MSG);
        }
        if (runtime == null) runtime = Runtime.getRuntime();
        return runtime.exec(cmdarray);
    }

    public static Process hookRuntimeExec(Runtime runtime, String[] cmdarray, String[] envp) throws IOException {
        if (isBlockedCommand(cmdarray)) {
            InjectionLogger.log("JmapBypass", "blocked diagnostic tool: " + String.join(" ", cmdarray));
            throw new IOException(TOOL_NOT_FOUND_MSG);
        }
        if (runtime == null) runtime = Runtime.getRuntime();
        return runtime.exec(cmdarray, envp);
    }

    public static Process hookRuntimeExecWithDir(Runtime runtime, String[] cmdarray, String[] envp, java.io.File dir) throws IOException {
        if (isBlockedCommand(cmdarray)) {
            InjectionLogger.log("JmapBypass", "blocked diagnostic tool: " + String.join(" ", cmdarray));
            throw new IOException(TOOL_NOT_FOUND_MSG);
        }
        if (runtime == null) runtime = Runtime.getRuntime();
        return runtime.exec(cmdarray, envp, dir);
    }

    public static Process hookProcessBuilderStart(ProcessBuilder builder) throws IOException {
        java.util.List<String> command = builder.command();
        String[] cmd = command != null ? command.toArray(new String[0]) : null;
        if (isBlockedCommand(cmd)) {
            InjectionLogger.log("JmapBypass", "blocked diagnostic tool: " + command);
            throw new IOException(TOOL_NOT_FOUND_MSG);
        }
        return builder.start();
    }
}
