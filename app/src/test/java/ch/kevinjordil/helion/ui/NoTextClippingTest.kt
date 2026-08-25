package ch.kevinjordil.helion.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural regression guard for repeated text-truncation reports (the HRV tile label,
 * then the baseline caption "plus haut que d'habitude"): both were `TextOverflow.Ellipsis`
 * silently dropping characters instead of the text wrapping. Rather than re-auditing every
 * screen by hand each time a string grows, this asserts the *mechanism* cannot exist
 * anywhere in the app: no `Text` composable may request single-line ellipsis truncation.
 * Wrapping (Compose's default) or a deliberately short phrasing are the only allowed fixes
 * for a string that does not fit -- both keep every character visible instead of clipping
 * it away, which is the one thing this test exists to make permanently true.
 */
class NoTextClippingTest {

    @Test
    fun `no source file requests TextOverflow Ellipsis`() {
        val sourceRoot = File("src/main/java")
        check(sourceRoot.exists()) { "expected to find ${sourceRoot.absolutePath} from the module's working directory" }

        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("TextOverflow.Ellipsis") }
            .map { it.path }
            .toList()

        assertTrue(
            "Found TextOverflow.Ellipsis in: $offenders -- wrap the text or shorten the string instead, never clip it.",
            offenders.isEmpty(),
        )
    }
}
