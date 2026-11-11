import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    id("com.android.library") version "9.5.1"
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "dev.flagbearer"
version = "1.0.0"

android {
    namespace = "dev.flagbearer.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        // The SDK's own version, sent with flag-check reports.
        buildConfigField("String", "SDK_VERSION", "\"${project.version}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Android ships org.json; unit tests run on the JVM, which doesn't.
    testImplementation("org.json:json:20250517")
    testImplementation("junit:junit:4.13.2")
}

// Maven Central needs sources, full metadata and signatures. Signing switches
// on only when CI provides the key, so local builds don't need one.
mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    publishToMavenCentral(automaticRelease = true)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates("dev.flagbearer", "flagbearer-android", version.toString())
    pom {
        name = "Flagbearer for Android"
        description = "The Flagbearer feature flag SDK for Android apps."
        url = "https://docs.flagbearer.dev/sdks/android"
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
