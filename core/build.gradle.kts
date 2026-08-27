val pekkoVersion: String by extra
val pekkoScalaSuffix: String by extra
val jacksonVersion: String by extra

plugins {
    `java-library`
    application
}

dependencies {
    // Pekko typed actors — the loop runs as a typed actor system.
    api("org.apache.pekko:pekko-actor-typed${pekkoScalaSuffix}:${pekkoVersion}")
    implementation("org.apache.pekko:pekko-serialization-jackson${pekkoScalaSuffix}:${pekkoVersion}")

    // Jackson — build/parse the OpenAI-compatible chat JSON for the :8200 drive.
    // jackson-databind comes transitively via pekko-serialization-jackson; pin it explicit.
    implementation("com.fasterxml.jackson.core:jackson-databind:${jacksonVersion}")

    // Apache Lucene — read the framework-knowledge Library index (BM25 + HNSW dense).
    // 10.4.0 matches the on-disk codec (Lucene104) of ~/.codezaiku/ocean/library.
    implementation("org.apache.lucene:lucene-core:10.4.0")
    implementation("org.apache.lucene:lucene-analysis-common:10.4.0")
    implementation("org.apache.lucene:lucene-queryparser:10.4.0")

    // HTTP client is java.net.http (JDK built-in) — no dependency.

    testImplementation("org.apache.pekko:pekko-actor-testkit-typed${pekkoScalaSuffix}:${pekkoVersion}")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Until now `:core:test` was NO-SOURCE — there was no test source set at all, so "tests pass"
// meant nothing. The first tests are the deterministic, security-relevant ones: path confinement
// and diff-anchored comment positioning. Both are pure functions over inputs and need no model.
tasks.test {
    useJUnitPlatform()
    testLogging { events("failed") }

    // Some tests assert the DOCS agree with the code (README examples dispatch, usage() matches the
    // arm chain). Gradle cannot see that from the classpath, so a doc-only edit left the task
    // UP-TO-DATE and replayed a stale pass — the tests looked green against a README they had never
    // read. Declaring the inputs is what makes those assertions mean anything.
    // BOTH layouts, for the same reason as `distributions` below: these docs are under docs/public/
    // here and at the root of the exported tree. Naming only one means the guard is silently inert
    // in the other — which is the tree the export's own test run uses, so the staleness this exists
    // to catch would go uncaught exactly where nobody is watching.
    inputs.files(fileTree(rootDir.resolve("docs/public")) { include("*.md") },
                 fileTree(rootDir) { include("*.md") })
        .withPropertyName("publicDocs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(rootDir.resolve(".github")), fileTree(rootDir.resolve("packaging")),
                 fileTree(rootDir.resolve("scripts")))
        .withPropertyName("shippedText")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // Constructing a FamiliarLoop reads the drive's context window, and that resolution THROWS when
    // no model server answers — which is the normal state during a unit-test run. An explicit value
    // short-circuits it, so a test can build a loop offline. No test depends on the resolution
    // itself; anything that did would set its own value.
    environment("CODEZAIKU_CTX", "8192")
}

application {
    mainClass.set("org.codezaiku.FamiliarMain")
    // The launcher is `codezaiku`, not the gradle module name — `bin/core` is not a command
    // anyone would type, and it is what installDist produced by default.
    applicationName = "codezaiku"
    applicationDefaultJvmArgs = listOf(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        // Lucene calls a restricted Linker method, and without this the JVM prints four WARNING
        // lines before anything else. They are harmless and they are also the FIRST thing on
        // stderr, so a caller quoting stderr to explain a failure quotes these instead of the
        // reason. Granting the access silences them at the source rather than filtering later.
        "--enable-native-access=ALL-UNNAMED",
    )
}

// The knowledge library ships as DATA beside the code, not inside the jar: cards are content that is
// edited, linted and contributed to. They must travel with the distribution or an install silently has
// no fix cards and no knowledge packs (org.codezaiku.Install resolves them relative to the dist root).
/** The first of these that exists — see the note in `distributions` below. */
fun docFile(name: String): File =
    listOf(rootProject.file(name), rootProject.file("docs/public/$name")).firstOrNull { it.isFile }
        ?: error("neither $name nor docs/public/$name exists — the distribution would ship without it")

distributions {
    main {
        contents {
            from(rootProject.file("ops-knowledge")) { into("ops-knowledge") }
            from(rootProject.file("knowledge-packs")) { into("knowledge-packs") }
            // README and LICENSE sit under docs/public/ in the private tree and at the ROOT of the
            // exported one, because the export promotes the landing-page docs. Gradle SILENTLY skips
            // a `from()` whose path does not exist, so the public build produced a tarball with no
            // LICENSE in it and nothing complained — 0.1.0 shipped that way. Take whichever layout
            // this tree has; packaging/deb/build-deb.sh carries the same helper for the same reason.
            from(docFile("README.md")) { into("") }
            from(docFile("LICENSE")) { into("") }
        }
    }
}

// Print the runtime classpath (used by bench runners to launch FamiliarMain without gradle startup cost).
tasks.register("printCp") {
    val cp = sourceSets["main"].runtimeClasspath
    doLast { println(cp.asPath) }
}
