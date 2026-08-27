// Root build — Pekko 1.4.0, Jackson 2.21.1, Java 21 toolchain, trimmed to what the
// familiar loop's spine needs.
val pekkoVersion = "1.4.0"
val pekkoScalaSuffix = "_2.13"
val jacksonVersion = "2.21.1"

subprojects {
    apply(plugin = "java")

    group = "org.codezaiku"
    version = "0.1.0"

    // 21 is the FLOOR the code needs — the docs, `codezaiku doctor` and the .deb dependency all say
    // "21 or newer". Expressing that as `toolchain { languageVersion = 21 }` did not say it: a Gradle
    // toolchain is an EXACT match, so a machine holding only JDK 25 could not build at all. That is
    // every stock macOS box with current Temurin, and equally a Linux box that skipped 21 — measured
    // on macOS 26.5 / JDK 25, where the build died before compiling a single file. An earlier fix had
    // moved the same exact-pin problem from 25 to 21 rather than removing it.
    //
    // `options.release` is the floor stated properly: compile with WHATEVER JDK is running (Gradle
    // already requires >= 21 to run this build) while emitting Java 21 bytecode and checking the code
    // against the Java 21 API, so a newer JDK cannot silently introduce a dependency on newer APIs.
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
    }

    repositories {
        mavenCentral()
    }

    extra["pekkoVersion"] = pekkoVersion
    extra["pekkoScalaSuffix"] = pekkoScalaSuffix
    extra["jacksonVersion"] = jacksonVersion

    dependencies {
        "implementation"("org.slf4j:slf4j-api:2.0.17")
        "implementation"("ch.qos.logback:logback-classic:1.5.16")

        "testImplementation"("org.junit.jupiter:junit-jupiter:5.12.2")
        "testImplementation"("org.assertj:assertj-core:3.27.3")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
