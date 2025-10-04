import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm") version "2.2.20"
  kotlin("plugin.serialization") version "2.2.20"
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
  implementation("social.bigbone:bigbone:2.0.0-SNAPSHOT")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
  implementation("com.typesafe:config:1.4.5")
  implementation("io.arrow-kt:arrow-core:2.1.2")
  implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

  api(platform("io.ktor:ktor-bom:3.3.0"))
  implementation("io.ktor:ktor-client-core")
  implementation("io.ktor:ktor-client-cio")
  implementation("io.ktor:ktor-client-content-negotiation")
  implementation("io.ktor:ktor-serialization-kotlinx-json")

  runtimeOnly("ch.qos.logback:logback-classic:1.5.19")

  testImplementation(kotlin("test"))

  testImplementation(platform("io.kotest:kotest-bom:6.0.3"))
  testImplementation("io.kotest:kotest-runner-junit5")
  testImplementation("io.kotest:kotest-assertions-core")
  testImplementation("io.kotest.extensions:kotest-assertions-arrow:2.0.0")
  testImplementation("io.mockk:mockk:1.14.6")

  testImplementation("io.ktor:ktor-server-core")
  testImplementation("io.ktor:ktor-server-netty")
  testImplementation("io.ktor:ktor-server-content-negotiation")
}

tasks.test {
  useJUnitPlatform()
  systemProperty("kotest.framework.classpath.scanning.autoscan.disable", "true")
  jvmArgs("-XX:+EnableDynamicAgentLoading")
}
kotlin {
  jvmToolchain(21)
}
tasks.withType<KotlinCompile> {
  compilerOptions {
    freeCompilerArgs.set(listOf("-Xwhen-guards"))
  }
}
