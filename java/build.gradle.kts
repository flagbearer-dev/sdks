import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar

plugins {
    `java-library`
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "dev.flagbearer"
version = "0.1.0"

repositories {
    mavenCentral()
}

// Maven Central needs sources, javadoc, full metadata and signatures. Signing
// switches on only when CI provides the key, so local builds don't need one.
mavenPublishing {
    configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = true))
    publishToMavenCentral(automaticRelease = true)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates("dev.flagbearer", "flagbearer", version.toString())
    pom {
        name = "Flagbearer"
        description = "The Flagbearer server SDK for Java and Kotlin."
        url = "https://docs.flagbearer.dev/sdks/java"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
            }
        }
        developers {
            developer {
                id = "flagbearer"
                name = "Flagbearer"
                url = "https://flagbearer.dev"
            }
        }
        scm {
            url = "https://github.com/flagbearer-dev/sdks"
            connection = "scm:git:https://github.com/flagbearer-dev/sdks.git"
            developerConnection = "scm:git:ssh://git@github.com/flagbearer-dev/sdks.git"
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 11
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.named<JavaCompile>("compileTestJava") {
    options.release = 17
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// Writes the SDK version into the jar, for exposure reports.
tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("dev/flagbearer/version.properties") { expand("version" to version) }
}

// A stable module name for applications on the Java module path.
tasks.jar {
    manifest { attributes("Automatic-Module-Name" to "dev.flagbearer") }
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

// The jar the integration fixture runs against, at a path that doesn't change with
// the version.
val fixtureJar by tasks.registering(Copy::class) {
    from(tasks.jar)
    into(layout.buildDirectory.dir("fixture"))
    rename { "flagbearer.jar" }
}
