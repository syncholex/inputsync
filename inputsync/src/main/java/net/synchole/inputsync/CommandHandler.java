package net.synchole.inputsync;

public final class CommandHandler {
    private static volatile boolean enabled = true;

    private CommandHandler() {}

    public static boolean isEnabled() { return enabled; }

    public static void setEnabled(boolean on) {
        enabled = on;
        System.out.println("[InputSync] Sync " + (enabled ? "ENABLED" : "DISABLED"));
    }

    public static void toggle() { setEnabled(!enabled); }
}

