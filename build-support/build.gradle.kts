import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.plugins.signing.SigningExtension
import org.gradle.plugins.signing.SigningPlugin
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File
import java.net.URI
import java.util.Properties

buildscript {
  dependencies {
    classpath(libs.pluginz.kotlin)
    classpath(libs.vanniktechPublishPlugin)
    classpath(libs.pluginz.dokka)
    classpath(libs.pluginz.buildConfig)
    classpath(libs.pluginz.spotless)
    classpath(libs.pluginz.kotlinSerialization)
    classpath(libs.pluginz.shadow)
    classpath(libs.pluginz.buildConfig)
    classpath(libs.guava)
  }

  repositories {
    mavenCentral()
    gradlePluginPortal()
    google()
  }
}

plugins {
  `kotlin-dsl`
  `java-gradle-plugin`
  kotlin("jvm") version libs.versions.kotlin
}

repositories {
  mavenCentral()
  google()
  gradlePluginPortal()
}

dependencies {
  compileOnly(libs.kotlin.gradleApi)
  implementation(libs.pluginz.binaryCompatibilityValidator)
  implementation(libs.pluginz.kotlin)
  implementation(libs.vanniktechPublishPlugin)
  implementation(libs.pluginz.dokka)
  implementation(libs.kotlin.serialization)
  implementation(libs.pluginz.buildConfig)
  implementation(libs.pluginz.spotless)
  implementation(libs.pluginz.kotlinSerialization)
  implementation(libs.pluginz.shadow)
  implementation(libs.pluginz.buildConfig)
  implementation(libs.guava)

  // Expose the generated version catalog API to the plugin.
  implementation(files(libs::class.java.superclass.protectionDomain.codeSource.location))
}

gradlePlugin {
  plugins {
    create("wireGrpcServerBuild") {
      id = "com.squareup.wiregrpcserver.build"
      displayName = "Wire gRPC Server Build plugin"
      description = "Gradle plugin for Wire gRPC Server build things"
      implementationClass = "com.squareup.wiregrpcserver.buildsupport.WireGrpcServerBuildPlugin"
    }
  }
}

allprojects {
  repositories {
    mavenCentral()
    google()
  }

  val javaVersion = if (project.path == ":") JavaVersion.VERSION_17 else JavaVersion.VERSION_11

  plugins.withId("java") {
    configure<JavaPluginExtension> {
      withSourcesJar()
      sourceCompatibility = javaVersion
      targetCompatibility = javaVersion
    }
  }

  tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
      jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString()))
      freeCompilerArgs.add("-Xno-optimized-callable-references")
      freeCompilerArgs.add("-Xjvm-default=all")
      // https://kotlinlang.org/docs/whatsnew13.html#progressive-mode
      freeCompilerArgs.add("-progressive")
    }
  }
}

// `:server-generator` is a project of this included build (see settings.gradle.kts), so the root
// build's `WireGrpcServerBuildPlugin` (which normally configures publishing via `PROJECT_TO_PUBLISH`)
// can never reach it: this build compiles that plugin, so a project of this build cannot have the
// plugin's output on its buildscript classpath. Publishing is therefore configured directly here,
// duplicating (deliberately, not by oversight) the repositories/signing/POM setup from
// `WireGrpcServerBuildPlugin.kt`'s `publishing()` function.
project(":server-generator") {
  // This included build does NOT inherit the root build's `gradle.properties` (GROUP/VERSION_NAME
  // are absent here). Load it explicitly so server-generator publishes under the exact same
  // coordinates as `server`.
  val rootGradleProperties = Properties().apply {
    file("../gradle.properties").inputStream().use { load(it) }
  }
  val wireGroupId = rootGradleProperties.getProperty("GROUP")!!
  val wireVersion = rootGradleProperties.getProperty("VERSION_NAME")!!

  // `project.projectDir` here is already `<repo-root>/server-generator` (see
  // `settings.gradle.kts`'s `project(":server-generator").projectDir = File("../server-generator")`),
  // so its parent is the repo root. `rootProject.layout.buildDirectory` would instead resolve to
  // `build-support/build`, landing artifacts in the wrong place.
  val repoRootDir = projectDir.parentFile

  group = wireGroupId
  version = wireVersion

  plugins.apply("com.vanniktech.maven.publish")
  plugins.apply("org.jetbrains.dokka")

  val publishing = extensions.getByName("publishing") as PublishingExtension
  publishing.apply {
    repositories {
      maven {
        name = "LocalMaven"
        url = File(repoRootDir, "build/localMaven").toURI()
      }
      maven {
        name = "test"
        url = File(repoRootDir, "build/localMaven").toURI()
      }

      // Want to push to an internal repository for testing?
      // Set the following properties in ~/.gradle/gradle.properties.
      //
      // internalUrl=YOUR_INTERNAL_URL
      // internalUsername=YOUR_USERNAME
      // internalPassword=YOUR_PASSWORD
      //
      // Then run the following command to publish a new internal release:
      //
      // ./gradlew publishAllPublicationsToInternalRepository -DRELEASE_SIGNING_ENABLED=false
      val internalUrl = providers.gradleProperty("internalUrl")
      if (internalUrl.isPresent) {
        maven {
          name = "internal"
          setUrl(internalUrl)
          credentials {
            username = providers.gradleProperty("internalUsername").get()
            password = providers.gradleProperty("internalPassword").get()
          }
        }
      }

      maven {
        name = "mavenCentral"
        url = if (wireVersion.endsWith("-SNAPSHOT")) {
          URI("https://central.sonatype.com/repository/maven-snapshots/")
        } else {
          URI("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2")
        }
        credentials {
          username = findProperty("NEXUS_USERNAME") as String? ?: ""
          password = findProperty("NEXUS_PASSWORD") as String? ?: ""
        }
      }
    }
  }

  val isCiServer = System.getenv().containsKey("CI")
  if (isCiServer) {
    pluginManager.apply(SigningPlugin::class.java)
    extensions.getByType<SigningExtension>().apply {
      useInMemoryPgpKeys(
        findProperty("SIGNING_KEY_ID") as String? ?: "",
        findProperty("SIGNING_KEY") as String? ?: "",
        findProperty("SIGNING_PASSWORD") as String? ?: "",
      )
      sign(extensions.getByType<PublishingExtension>().publications)
    }
  }

  val mavenPublishing = extensions.getByName("mavenPublishing") as MavenPublishBaseExtension
  mavenPublishing.apply {
    coordinates(wireGroupId, project.name, wireVersion)

    pom {
      name.set(project.name)
      description.set("gRPC and protocol buffers for Android, Kotlin, and Java.")
      inceptionYear.set("2017")
      url.set("https://github.com/square/wire-grpc-server/")

      licenses {
        license {
          name.set("Apache-2.0")
          url.set("https://www.apache.org/licenses/LICENSE-2.0")
          distribution.set("repo")
        }
      }

      developers {
        developer {
          id.set("cashapp")
          name.set("CashApp")
          url.set("https://github.com/cashapp")
        }
      }

      scm {
        url.set("https://github.com/square/wire-grpc-server/")
        connection.set("scm:git:https://github.com/square/wire-grpc-server.git")
        developerConnection.set("scm:git:ssh://git@github.com/square/wire-grpc-server.git")
      }
    }
  }

  tasks.withType<DokkaTask>().configureEach {
    outputDirectory.set(File(repoRootDir, "docs/3.x/${project.name}"))
    dokkaSourceSets.configureEach {
      reportUndocumented.set(false)
      skipDeprecated.set(true)
      jdkVersion.set(8)
      perPackageOption {
        matchingRegex.set("com\\.squareup\\.wire.*\\.internal.*")
        suppress.set(true)
      }
      // Document generated code.
      suppressGeneratedFiles.set(false)
    }
  }
}
