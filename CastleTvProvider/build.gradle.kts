import com.lagradost.cloudstream3.gradle.tasks.CompileDexTask

// use an integer for version numbers
version = 49

// The CloudStream gradle plugin's compileDex task dexes ONLY the Kotlin compiler output
// (src/main/java is ignored and jar dependencies never reach classes.dex), so the
// vendored Brotli decoder under src/main/brotli is compiled here with javac and its
// classes are appended to the compileDex input. compileOnly keeps the maven jar on the
// Kotlin compile classpath only; the classes that ship inside the plugin come from
// compileBrotli. See src/main/brotli/org/brotli/dec/LICENSE (MIT).

val brotliClassesDir = layout.buildDirectory.dir("brotli-classes")
val compileBrotli = tasks.register<JavaCompile>("compileBrotli") {
    group = "build"
    description = "Compiles the vendored org.brotli.dec sources for the plugin dex"
    source(fileTree("src/main/brotli"))
    classpath = files()
    destinationDirectory.set(brotliClassesDir)
    sourceCompatibility = "11"
    targetCompatibility = "11"
    javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(17)) })
}

// Append the Brotli classes to whatever the compileDex task dexes
tasks.matching { it.name == "compileDex" }.configureEach {
    dependsOn(compileBrotli)
    (this as CompileDexTask).input.from(brotliClassesDir)
}

android {
    // Root build sets namespace "com.anime"; override so BuildConfig is generated in com.castletv
    namespace = "com.castletv"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        // In the original repo this field was injected from the root build.gradle.kts
        buildConfigField("String", "CASTLE_SUFFIX", "\"T!BgJB\"")
    }
}

dependencies {
    val implementation by configurations
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    // Compile-time only: the decoder classes that ship inside the plugin are built
    // from src/main/brotli by the compileBrotli task above
    compileOnly("org.brotli:dec:0.1.2")
}

cloudstream {
    language = "hi"
    // All of these properties are optional, you can safely remove them

    description = "Castle TV Movies and Series Provider"
    authors = listOf("senkuboy0-cyber")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )

    iconUrl = "https://raw.githubusercontent.com/senkuboy0-cyber/FlixVerse/refs/heads/main/CastleTvProvider/icon.png"
}
