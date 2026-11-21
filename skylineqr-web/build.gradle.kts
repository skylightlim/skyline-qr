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

// The browser fetches the wasm alongside the page instead of only once the script that asks for it
// has arrived and run, a round trip sooner (2026-11-21): the distribution's index.html gets a
// preload link naming the wasm, whose name is a hash of its contents. The dev server keeps the
// placeholder, a comment.
tasks.named("wasmJsBrowserDistribution") {
  val dist = layout.buildDirectory.dir("dist/wasmJs/productionExecutable")
  doLast {
    val dir = dist.get().asFile
    val wasm = dir.listFiles { file -> file.extension == "wasm" }?.singleOrNull()?.name
      ?: throw GradleException("expected one .wasm file in $dir")
    val index = dir.resolve("index.html")
    val marker = "<!-- wasm preload -->"
    val page = index.readText()
    check(marker in page) { "index.html has no $marker" }
    index.writeText(page.replace(marker, """<link rel="preload" href="$wasm" as="fetch" type="application/wasm" crossorigin>"""))
  }
}
