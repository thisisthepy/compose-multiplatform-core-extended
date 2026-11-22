package org.thisisthepy.compose.adaptive

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayerDependencyAndMaterial3ParityTest {

    private fun designSystemsRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "project.yaml").isFile && File(dir, "designsystem-adaptive").isDirectory) {
                return dir
            }
            val nested = File(dir, "extended/design-systems")
            if (File(nested, "project.yaml").isFile && File(nested, "designsystem-adaptive").isDirectory) {
                return nested
            }
            dir = dir.parentFile
        }
        error("could not locate extended/design-systems above ${System.getProperty("user.dir")}")
    }

    private fun productionDependencies(moduleYaml: File): List<String> {
        val deps = mutableListOf<String>()
        var inDeps = false
        for (raw in moduleYaml.readLines()) {
            val line = raw.trimEnd()
            if (line.startsWith("dependencies:")) {
                inDeps = true
                continue
            }
            if (inDeps) {
                if (line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\t") && !line.startsWith("#")) {
                    inDeps = false
                    continue
                }
                val trimmed = line.trim()
                if (trimmed.startsWith("- ")) {
                    deps += trimmed.removePrefix("- ").trim()
                }
            }
        }
        return deps
    }

    @Test
    fun fr14_11_5_layer1_modules_do_not_depend_on_adaptive_or_each_other() {
        val root = designSystemsRoot()
        val layer1 = listOf(
            "designsystem-material3",
            "designsystem-cupertino",
            "designsystem-fluent",
            "designsystem-gnome",
            "designsystem-breeze",
            "designsystem-deepin",
            "designsystem-liquid-glass",
        )
        for (module in layer1) {
            val yaml = File(root, "$module/module.yaml")
            assertTrue(yaml.isFile, "missing $yaml")
            val deps = productionDependencies(yaml)
            assertFalse(
                deps.any { "designsystem-adaptive" in it },
                "$module must not depend on Layer 2 designsystem-adaptive: $deps",
            )
            for (other in layer1) {
                if (other == module) continue
                if (module == "designsystem-cupertino" && other == "designsystem-liquid-glass") continue
                assertFalse(
                    deps.any { other in it },
                    "$module must not depend on sibling Layer 1 module $other: $deps",
                )
            }
        }
    }

    @Test
    fun fr14_11_material3_reexports_core_public_symbols() {
        val root = designSystemsRoot()
        val m3Yaml = File(root, "designsystem-material3/module.yaml")
        val deps = productionDependencies(m3Yaml)
        assertTrue(
            deps.any { it.contains("compose.fork.material3") && it.contains("exported") },
            "designsystem-material3 must re-export compose.fork.material3: $deps",
        )
        // Verify core androidx.compose.material3 public classes are reachable on the classpath
        // through the re-exported Material 3 adapter module.
        val requiredMaterial3Classes = listOf(
            "androidx.compose.material3.ButtonKt",
            "androidx.compose.material3.TextKt",
            "androidx.compose.material3.TextFieldKt",
            "androidx.compose.material3.CheckboxKt",
            "androidx.compose.material3.RadioButtonKt",
            "androidx.compose.material3.SwitchKt",
            "androidx.compose.material3.SliderKt",
            "androidx.compose.material3.ProgressIndicatorKt",
            "androidx.compose.material3.DividerKt",
            "androidx.compose.material3.CardKt",
            "androidx.compose.material3.SurfaceKt",
            "androidx.compose.material3.ColorScheme",
            "androidx.compose.material3.Typography",
            "androidx.compose.material3.Shapes",
            "androidx.compose.material3.MaterialTheme",
        )
        val missing = requiredMaterial3Classes.filter { name ->
            runCatching { Class.forName(name) }.isFailure
        }
        assertTrue(missing.isEmpty(), "missing exported androidx.compose.material3 symbols: $missing")
    }
}
