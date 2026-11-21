// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.kotlin.serialization) apply false
}
// JitPack builds tagged releases as com.github.skylightlim.skyline-qr:<module>:<tag>.
subprojects {
  group = "com.github.skylightlim.skyline-qr"
  version = rootProject.version
}

// The Kotlin plugin fetches Node.js (Wasm tests) and Binaryen (the website's optimiser) from their
// own sites by adding repositories to the project, which settings' FAIL_ON_PROJECT_REPOS forbids.
// Settings declares those two repositories instead, so the plugin is told not to add its own.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
allprojects {
  plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec>().downloadBaseUrl.set(null as String?)
  }
  plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec>().downloadBaseUrl.set(null as String?)
  }
  plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec>().downloadBaseUrl.set(null as String?)
  }
}
