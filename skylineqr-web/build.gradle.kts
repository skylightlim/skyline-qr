// The website (2026-11-21): the demo app in a browser, as a static site for GitHub Pages.
// Kotlin/Wasm, so the core plans the same city as on the phone; the renderer is the app's, with
// the browser's WebGPU (gpu.js) in place of androidx.webgpu.
//
//   ./gradlew :skylineqr-web:wasmJsBrowserDevelopmentRun        a dev server with the site
//   ./gradlew :skylineqr-web:wasmJsBrowserDistribution          the site, in build/dist/wasmJs/productionExecutable
plugins {
  alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    outputModuleName = "skylineqr-web"
    browser {
      commonWebpackConfig {
        outputFileName = "skylineqr-web.js"
      }
    }
    binaries.executable()
  }

  sourceSets {
    wasmJsMain {
      dependencies {
        implementation(project(":skylineqr-core"))
      }
      // The renderer's platform-free parts, shared with the Android library rather than copied:
      // the WGSL, the camera, the frame layout, the sky's shapes, the morph, fireworks and the tour.
      kotlin.srcDir("../skylineqr-compose/src/main/kotlin")
      kotlin.include(
        "com/skylightlim/skylineqr/web/**",
        "com/skylightlim/skylineqr/render/WebRenderer.kt",
        "com/skylightlim/skylineqr/render/BillboardText.kt",
        "com/skylightlim/skylineqr/render/Billboard.kt",
        "com/skylightlim/skylineqr/render/Camera.kt",
        "com/skylightlim/skylineqr/render/Fireworks.kt",
        "com/skylightlim/skylineqr/render/FrameData.kt",
        "com/skylightlim/skylineqr/render/Mat4.kt",
        "com/skylightlim/skylineqr/render/Morph.kt",
        "com/skylightlim/skylineqr/render/SkyParts.kt",
        "com/skylightlim/skylineqr/render/Timelapse.kt",
        "com/skylightlim/skylineqr/render/Wgsl.kt",
        "com/skylightlim/skylineqr/render/tour/**",
        "com/skylightlim/skylineqr/compose/LongPress.kt",
        "com/skylightlim/skylineqr/compose/SkylineEvent.kt",
      )
    }
  }
}
