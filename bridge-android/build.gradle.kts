// bridge-android: the bridge as a Godot Android plugin, for the standalone Quest app (Unciv VR's client, exported
// to Android). The plugin starts AndroidBridge, which runs the bridge on a background thread inside the app.
//
// The AAR carries the plugin class, plus Unciv's core and the bridge as one jar (bridge:androidJar) in its libs/.
// Their libraries (gdx, ktor, coroutines...) aren't bundled: bridge:androidDeps lists them, and the client's export
// plugin (client/addons/unciv_bridge) hands that list to Godot's Gradle build, which fetches them.
//
// Build: gradlew :bridge-android:assembleRelease  (needs the Android SDK, like Unciv's own android module)

plugins {
    id("com.android.library")
}

android {
    namespace = "com.unciv.bridge.android"
    compileSdk = 36
    defaultConfig {
        minSdk = 29   // Quest 2 and later
    }
    sourceSets {
        getByName("main").apply {
            manifest.srcFile("AndroidManifest.xml")
            java.directories += "src"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

private val bridgeJar = project(":bridge").layout.buildDirectory.file("libs/unciv-bridge-android.jar")

dependencies {
    // Godot's Android library: provided by the app at run time
    compileOnly("org.godotengine:godot:4.7.2.stable")
    // Unciv's core + the bridge, bundled into the AAR
    implementation(files(bridgeJar))
}

tasks.named("preBuild") {
    dependsOn(":bridge:androidJar", ":bridge:androidDeps")
}

// Copies the AAR and the list of libraries it needs into the Godot client, where its export plugin finds them
tasks.register<Copy>("copyToClient") {
    description = "Copies the release AAR and android-deps.txt to ../client/addons/unciv_bridge/bin"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/aar/bridge-android-release.aar")) { rename { "unciv-bridge-android.aar" } }
    from(project(":bridge").layout.buildDirectory.file("libs/android-deps.txt"))
    into(rootProject.file("../client/addons/unciv_bridge/bin"))
}
