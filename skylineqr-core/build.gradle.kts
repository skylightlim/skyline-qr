// Pure Kotlin: link identity, the QR matrix and the city planner. No Android, so it tests on the JVM.
// Multiplatform since the website (2026-11-21): the same code builds the same city in the browser
// through Kotlin/Wasm, which keeps Float arithmetic in 32 bits like the JVM (Kotlin/JS does not).
plugins {
  alias(libs.plugins.kotlin.multiplatform)
  `maven-publish`
}

kotlin {
  jvmToolchain(17)
  jvm()
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    // npm names the package after the root project otherwise, and "Skyline QR" has a space.
    outputModuleName = "skylineqr-core"
    // A library for the website; its tests run on Node, not in a browser.
    browser {
      testTask { enabled = false }
    }
    // CrossPlatformCityTest runs here too, on Node: the browser's planner against the phone's.
    nodejs()
  }

  sourceSets {
    commonTest.dependencies {
      implementation(kotlin("test"))
    }
    jvmTest.dependencies {
      implementation(libs.zxing.core)
      implementation(libs.junit)
    }
  }
}
