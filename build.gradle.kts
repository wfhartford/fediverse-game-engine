import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm") version "2.1.21"
  kotlin("plugin.serialization") version "2.1.21"
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
  implementation("com.typesafe:config:1.4.3")
  implementation("io.arrow-kt:arrow-core:2.1.2")
  implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

  runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

  testImplementation(kotlin("test"))

  val kotestVersion = "6.0.0.M4"
  testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
  testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
  testImplementation("io.kotest.extensions:kotest-assertions-arrow:2.0.0")
  testImplementation("io.mockk:mockk:1.14.2")

  val ktorVersion = "3.1.3"
  testImplementation(platform("io.ktor:ktor-bom:$ktorVersion"))
  testImplementation("io.ktor:ktor-server-core")
  testImplementation("io.ktor:ktor-server-netty")
  testImplementation("io.ktor:ktor-server-content-negotiation")
  testImplementation("io.ktor:ktor-client-core")
  testImplementation("io.ktor:ktor-client-cio")
  testImplementation("io.ktor:ktor-serialization-kotlinx-json")
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
