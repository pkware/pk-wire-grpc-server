import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin

buildscript {
  dependencies {
    classpath(libs.pluginz.dokka)
    classpath(libs.pluginz.binaryCompatibilityValidator)
    classpath(libs.pluginz.kotlin)
    classpath(libs.pluginz.kotlinSerialization)
    classpath(libs.pluginz.shadow)
    classpath(libs.pluginz.spotless)
    classpath(libs.protobuf.gradlePlugin)
    classpath(libs.vanniktechPublishPlugin)
    classpath(libs.pluginz.buildConfig)
    classpath(libs.guava)
    classpath(libs.asm)

    classpath(libs.wire.gradlePlugin)
    classpath(libs.wire.runtime)
    classpath("com.squareup.wiregrpcserver:server-generator")
    classpath("com.squareup.wiregrpcserver.build:gradle-plugin")
  }

  repositories {
    mavenCentral()
    gradlePluginPortal()
    google()
  }
}

allprojects {
  group = project.property("GROUP") as String
  version = project.property("VERSION_NAME") as String

  repositories {
    mavenCentral()
    google()
  }
}

// `:server-generator` is published from inside the `build-support` included build (see
// `build-support/build.gradle.kts`), so it never receives its own `publish` task here. Reach it
// explicitly so a single root `./gradlew publish` covers both `:server` and `:server-generator`.
tasks.register("publish") {
  dependsOn(gradle.includedBuild("build-support").task(":server-generator:publish"))
}
