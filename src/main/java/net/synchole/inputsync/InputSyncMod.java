package net.synchole.inputsync;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class InputSyncMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Start network client and install GLFW hooks
        NetworkClient.start();

        // Main sync loop:
        // - leader sends input state
        // - followers apply input state
        // - focus is reported here as well
        ClientTickEvents.END_CLIENT_TICK.register(NetworkClient::onTick);

        System.out.println("[InputSync] Client initialized (sync enabled)");
    }
}

