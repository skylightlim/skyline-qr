// Phone-as-NFC-tag sharing, tag reading and writing, saved cards and the QR scanner.
// The protocol code (ndef/, card/, tag/) is plain Kotlin and is tested on the JVM.
plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
  `maven-publish`
}

android {
  namespace = "com.skylightlim.skylineqr.nfc"
  compileSdk = 36
  defaultConfig {
    minSdk = 26
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
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
        artifactId = "skylineqr-nfc"
      }
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  api(libs.kotlinx.coroutines.android)
  api(libs.androidx.activity)
  api(libs.androidx.lifecycle.runtime.ktx)
  api(libs.androidx.camera.core)
  implementation(libs.androidx.core.ktx)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  // The bundled model: works offline and without Google Play services.
  implementation(libs.mlkit.barcode.scanning)

  testImplementation(libs.junit)
}
