package com.unciv.bridge.android;

import com.unciv.bridge.AndroidBridge;

import org.godotengine.godot.Godot;
import org.godotengine.godot.plugin.GodotPlugin;
import org.godotengine.godot.plugin.UsedByGodot;

/**
 * The UncivBridge Godot plugin: lets the Quest app start Unciv's engine (the bridge) inside itself.
 * GDScript: Engine.get_singleton("UncivBridge").startBridge(assetsDir, homeDir, port)
 */
public class UncivBridgePlugin extends GodotPlugin {
    public UncivBridgePlugin(Godot godot) {
        super(godot);
    }

    @Override
    public String getPluginName() {
        return "UncivBridge";
    }

    /** Starts the bridge on 127.0.0.1:[port]; [assetsDir] is Unciv's unpacked android/assets, [homeDir] holds .unciv-vr. */
    @UsedByGodot
    public boolean startBridge(String assetsDir, String homeDir, int port) {
        return AndroidBridge.start(assetsDir, homeDir, port);
    }

    /** Why the bridge didn't start (or stopped), if it didn't. */
    @UsedByGodot
    public String lastError() {
        return AndroidBridge.INSTANCE.getLastError();
    }

    /** What the bridge is doing right now ("loading Unciv's rules", "listening"...). */
    @UsedByGodot
    public String stage() {
        return AndroidBridge.INSTANCE.getStage();
    }

    /** Whether the bridge thread is running. */
    @UsedByGodot
    public boolean isRunning() {
        return AndroidBridge.INSTANCE.getStarted();
    }
}
