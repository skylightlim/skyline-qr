// Pure Kotlin: link identity, the QR matrix and the city planner. No Android, so it tests on the JVM.
plugins {
  alias(libs.plugins.kotlin.jvm)
  `maven-publish`
}

kotlin {
  jvmToolchain(17)
}

java {
  withSourcesJar()
}

publishing {
  publications {
    create<MavenPublication>("release") {
      from(components["java"])
      artifactId = "skylineqr-core"
    }
  }
}

dependencies {
  implementation(libs.zxing.core)

  testImplementation(libs.junit)
}
