package net.extrawdw.apps.notisync.sshkeyprovider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.testsupport.RoomStorageTestContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DesktopApplicationStorageTest {
    private val context = RoomStorageTestContext(InstrumentationRegistry.getInstrumentation().targetContext, "desktop-applications")
    private var database: OperationalDatabase? = null
    private val sourceFile get() = File(context.cacheDir, "desktop-application-test-source.png")

    @Before
    fun create() {
        context.deleteDatabase(OperationalDatabase.DATABASE_NAME)
        database = OperationalDatabase.create(context)
    }

    @After
    fun cleanup() {
        database?.close()
        context.deleteDatabase(OperationalDatabase.DATABASE_NAME)
        sourceFile.delete()
    }

    @Test
    fun importedIconSurvivesSourceDeletionAndDatabaseReopeningWithItsOverride() = runBlocking {
        val bitmap = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.argb(160, 30, 90, 210))
        sourceFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val token = DesktopApplicationIconDrafts.import(context, Uri.fromFile(sourceFile))
        val icon = DesktopApplicationIconDrafts.read(context, token)
        assertEquals("RIFF", String(icon, 0, 4, Charsets.US_ASCII))
        assertEquals("WEBP", String(icon, 8, 4, Charsets.US_ASCII))
        BitmapFactory.decodeByteArray(icon, 0, icon.size).also {
            assertEquals(512, it.width)
            assertEquals(256, it.height)
            assertTrue(it.hasAlpha())
            assertEquals(160, Color.alpha(it.getPixel(20, 20)))
            it.recycle()
        }
        val repository = DesktopApplicationRepository(database!!.desktopApplications())
        repository.save(
            KnownDesktopApplication(
                "git", "自定义 Git", 999, DesktopApplicationLineageTraversal.CANDIDATE,
                setOf("git", "Git.app"), setOf("/opt/tools/"),
            ), icon, "git",
        )
        sourceFile.delete()
        DesktopApplicationIconDrafts.delete(context, token)
        database!!.close()
        database = OperationalDatabase.create(context)
        val reopened = DesktopApplicationRepository(database!!.desktopApplications())
        assertArrayEquals(icon, reopened.snapshot.value.icon("git"))
        val entry = reopened.snapshot.value.registry.applications.single { it.id == "git" }
        assertEquals("自定义 Git", entry.displayName)
        assertEquals(setOf("/opt/tools/"), entry.acceptedPaths)

        reopened.save(KnownDesktopApplication("my-git", "Mine", 1000, acceptedNames = setOf("git")), icon, "git")
        assertEquals(listOf("my-git"), database!!.desktopApplications().entries().map { it.id })
        assertNull(reopened.snapshot.value.icon("git"))
        reopened.delete("my-git")
        assertTrue(database!!.desktopApplications().entries().isEmpty())
    }

    @Test
    fun losslessEncodingPreservesPixelsAndRejectsUnsupportedData() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(42, 105, 190))
        val png = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        val icon = DesktopApplicationIconDrafts.normalize(png)
        val decoded = BitmapFactory.decodeByteArray(icon, 0, icon.size)
        assertEquals(bitmap.getPixel(3, 3), decoded.getPixel(3, 3))
        assertEquals(8, decoded.width) // Small sources are never upscaled.
        decoded.recycle()
        bitmap.recycle()
        assertThrows(Exception::class.java) { DesktopApplicationIconDrafts.normalize(byteArrayOf(1, 2, 3)) }
    }
}
