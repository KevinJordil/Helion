package ch.kevinjordil.helion.store

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the failure that took the app down on launch: a migration was written and its
 * schema exported, but it was never added to the list Room is built with. Room then
 * refused to open an already-upgraded database and the process died before anything
 * reached the screen.
 *
 * No test then in existence covered it -- each migration was exercised on its own, so
 * every one passed while the set as a whole had a hole in it. The exported schema files
 * are the reference here rather than the `@Database` annotation, which Room does not
 * retain at runtime: a schema file exists for exactly the versions the database has ever
 * declared, so a schema with no migration leading to it is the bug itself.
 */
class MigrationCoverageTest {

    private val schemaVersions: List<Int> =
        File("schemas/ch.kevinjordil.helion.store.HelionDatabase")
            .listFiles { f -> f.extension == "json" }
            .orEmpty()
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            .sorted()

    private val declaredVersion: Int get() = schemaVersions.last()

    @Test
    fun `the exported schemas were found`() {
        assertTrue("no exported schema files -- has room.schemaLocation moved?", schemaVersions.isNotEmpty())
        assertEquals("schema versions should run 1..n with no gaps", (1..declaredVersion).toList(), schemaVersions)
    }

    @Test
    fun `the shipped migrations form an unbroken path to the newest exported schema`() {
        val steps = HELION_MIGRATIONS.map { it.startVersion to it.endVersion }.toSet()
        val missing = (1 until declaredVersion).filterNot { (it to it + 1) in steps }
        assertTrue(
            "no migration registered for ${missing.map { "$it->${it + 1}" }} " +
                "(newest exported schema is $declaredVersion) -- add it to HELION_MIGRATIONS",
            missing.isEmpty(),
        )
    }

    @Test
    fun `no migration reaches past the newest exported schema`() {
        val beyond = HELION_MIGRATIONS.filter { it.endVersion > declaredVersion }
        assertTrue(
            "migrations end above the newest exported schema $declaredVersion: " +
                beyond.map { "${it.startVersion}->${it.endVersion}" },
            beyond.isEmpty(),
        )
    }

    @Test
    fun `each migration advances exactly one version`() {
        HELION_MIGRATIONS.forEach {
            assertEquals(
                "migration ${it.startVersion}->${it.endVersion} skips a version",
                it.startVersion + 1,
                it.endVersion,
            )
        }
    }
}
