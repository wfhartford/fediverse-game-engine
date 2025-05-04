import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm") version "2.1.10"
}

group = "ca.cutterslade.fedigame"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
  maven {
    url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
  }
}

dependencies {
  testImplementation(kotlin("test"))
  implementation("social.bigbone:bigbone:2.0.0-SNAPSHOT")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
  implementation("com.typesafe:config:1.4.3")
  implementation("io.arrow-kt:arrow-core:2.1.0")
  implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

  runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

  val kotestVersion = "5.9.1"
  testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
  testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
  testImplementation("io.kotest:kotest-property:$kotestVersion")
}

tasks.test {
  useJUnitPlatform()
}
kotlin {
  jvmToolchain(21)
}
tasks.withType<KotlinCompile> {
  compilerOptions {
    freeCompilerArgs.set(listOf("-Xwhen-guards"))
  }
}
