import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// unciv-bridge: a headless Unciv game engine that a separate frontend (the Godot VR client)
// drives over a local TCP socket, one JSON message per line. See bridge/README.md.

plugins {
    id("kotlin")
}

sourceSets {
    main {
        java.srcDir("src/")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    implementation(project(":core"))
    implementation(libs.gdx)
    implementation(libs.gdx.backend.headless)
    implementation(libs.coroutines.core)
    implementation(libs.kotlin.reflect)
    implementation(libs.ktor.serialization) // brings kotlinx-serialization-json, used for the wire format
}

private val mainClassName = "com.unciv.bridge.BridgeMain"
// Unciv loads its rulesets relative to the working directory, so the bridge must run from android/assets
private val assetsDir = file("../android/assets")

tasks.register<JavaExec>("run") {
    description = "Build and run the bridge on 127.0.0.1:8765 (pass other args with --args=\"--port 9000\")"
    group = "application"
    dependsOn(tasks.getByName("classes"))
    mainClass.set(mainClassName)
    classpath = sourceSets.main.get().runtimeClasspath
    workingDir = assetsDir
    standardInput = System.`in`
    jvmArgs("-Xmx4G")
}

tasks.register<Jar>("dist") {
    description = "Builds a self-contained UncivBridge.jar (still needs to be run from android/assets)"
    group = "build"
    dependsOn(tasks.getByName("classes"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(files(sourceSets.main.get().output.classesDirs))
    from({ configurations.runtimeClasspath.get().resolve().map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    archiveFileName.set("UncivBridge.jar")
    manifest {
        attributes(mapOf("Main-Class" to mainClassName))
    }
}

// The standalone Quest app (bridge-android): Unciv's core and the bridge as one jar, without their libraries...
evaluationDependsOn(":core")
tasks.register<Jar>("androidJar") {
    description = "Unciv's core and the bridge, without libraries: bundled into the Quest app's UncivBridge plugin"
    group = "build"
    dependsOn(tasks.getByName("classes"), ":core:classes")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from(project(":core").extensions.getByType<SourceSetContainer>()["main"].output)
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.kotlin_module")
    archiveFileName.set("unciv-bridge-android.jar")
}

// ...and the libraries they need, as Maven coordinates, for Godot's Android Gradle build to fetch
tasks.register("androidDeps") {
    description = "Writes build/libs/android-deps.txt: the bridge's libraries as group:name:version"
    group = "build"
    val out = layout.buildDirectory.file("libs/android-deps.txt")
    outputs.file(out)
    outputs.upToDateWhen { false }
    doLast {
        val ids = configurations.getByName("runtimeClasspath").incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            .map { "${it.group}:${it.module}:${it.version}" }
            .distinct().sorted()
        val file = out.get().asFile
        file.parentFile.mkdirs()
        file.writeText(ids.joinToString("\n") + "\n")
        println("android-deps.txt: ${ids.size} libraries")
    }
}
