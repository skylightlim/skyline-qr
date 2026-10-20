// The renderer and the SkylineQrCode composable. Apps depend on this; it brings the core along.
plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  `maven-publish`
}

android {
  namespace = "com.skylightlim.skylineqr.compose"
  compileSdk = 36
  defaultConfig {
    minSdk = 26
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
  }
  publishing {
    singleVariant("release") {
      withSourcesJar()
    }
  }
}

afterEvaluate {
  publishing {
    publications {
      create<MavenPublication>("release") {
        from(components["release"])
        artifactId = "skylineqr-compose"
      }
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  api(project(":skylineqr-core"))
  implementation(libs.androidx.webgpu)
  implementation(libs.kotlinx.coroutines.android)

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  api(libs.androidx.compose.ui) // the public API takes a Modifier
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.lifecycle.runtime.compose)

  testImplementation(libs.junit)

  // On-device tests: render real frames on a real GPU and scan them.
  androidTestImplementation(composeBom)
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  // Espresso before 3.7 calls InputManager.getInstance(), which Android 17 removed.
  androidTestImplementation(libs.androidx.test.espresso.core)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.zxing.core)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}
