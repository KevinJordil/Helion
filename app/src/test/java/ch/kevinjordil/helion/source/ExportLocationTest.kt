package ch.kevinjordil.helion.source

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExportLocationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val location = ExportLocation(context)

    @Test
    fun `no location is configured initially`() {
        assertNull(location.uri)
    }

    @Test
    fun `the chosen location survives a new instance`() {
        location.uri = "content://example/export"
        val reloaded = ExportLocation(ApplicationProvider.getApplicationContext())
        assertEquals("content://example/export", reloaded.uri)
    }

    @Test
    fun `copying without a configured location yields null`() {
        assertNull(location.copyToCache())
    }

    @Test
    fun `copying a configured and readable location returns a cached copy with the same content`() {
        val source = File(context.cacheDir, "source-export.db")
        source.writeBytes(byteArrayOf(1, 2, 3, 4))
        location.uri = Uri.fromFile(source).toString()

        val path = location.copyToCache()

        assertEquals(File(context.cacheDir, "gadgetbridge-export.db").absolutePath, path)
        assertEquals(listOf<Byte>(1, 2, 3, 4), File(path!!).readBytes().toList())
    }

    @Test
    fun `a failed copy leaves the previously cached export intact`() {
        val source = File(context.cacheDir, "source-export.db")
        source.writeBytes(byteArrayOf(1, 2, 3, 4))
        location.uri = Uri.fromFile(source).toString()
        val cached = File(location.copyToCache()!!)

        // The location breaks between passes: the cached copy must not be replaced by a
        // truncated or empty file, because the next read would then fail on a file that
        // used to be perfectly good.
        location.uri = Uri.fromFile(File(context.cacheDir, "vanished.db")).toString()
        assertThrows(ExportUnavailableException::class.java) { location.copyToCache() }

        assertEquals(listOf<Byte>(1, 2, 3, 4), cached.readBytes().toList())
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun `copying a configured but missing location throws ExportUnavailableException, not null`() {
        val missing = File(context.cacheDir, "does-not-exist.db")
        location.uri = Uri.fromFile(missing).toString()

        // Distinguishable from "nothing configured": a caller must not be able to tell
        // this apart from the not-configured-yet case, since the UI needs to show a
        // different message for each.
        assertThrows(ExportUnavailableException::class.java) { location.copyToCache() }
    }
}
