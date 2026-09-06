package net.extrawdw.apps.notisync.sshkeyprovider

import org.junit.Assert.*
import org.junit.Test

class DesktopApplicationDraftTest {
    private val draft = DesktopApplicationDraft(
        " custom ", " My app ", " -2147483648 ", DesktopApplicationLineageTraversal.STOP,
        " Tool.app\n\nexe\r\nexe ", "/usr/bin/\n/opt/my app/exe\nC:\\Tools\\exe.exe",
    )

    @Test
    fun trimsAndDeduplicatesLinesWithoutChangingPathSemantics() {
        assertNull(draft.validate(emptySet(), null))
        val app = draft.application()
        assertEquals("custom", app.id)
        assertEquals("My app", app.displayName)
        assertEquals(Int.MIN_VALUE, app.priority)
        assertEquals(DesktopApplicationLineageTraversal.STOP, app.traversal)
        assertEquals(setOf("Tool.app", "exe"), app.acceptedNames)
        assertEquals(setOf("/usr/bin/", "/opt/my app/exe", "C:\\Tools\\exe.exe"), app.acceptedPaths)
        assertTrue(draft.copy(acceptedPaths = "\n ").application().acceptedPaths.isEmpty())
    }

    @Test
    fun rejectsBlankFieldsOverflowRelativePathsAndCollisions() {
        assertEquals(DesktopApplicationDraftError.ID, draft.copy(id = " ").validate(emptySet(), null))
        listOf("2147483648", "-2147483649", "1.5", "-").forEach {
            assertEquals(DesktopApplicationDraftError.PRIORITY, draft.copy(priority = it).validate(emptySet(), null))
        }
        assertEquals(DesktopApplicationDraftError.NAMES, draft.copy(acceptedNames = "\n").validate(emptySet(), null))
        listOf("bin/exe", "~/bin/", "C:exe").forEach {
            assertEquals(DesktopApplicationDraftError.PATHS, draft.copy(acceptedPaths = it).validate(emptySet(), null))
        }
        assertEquals(DesktopApplicationDraftError.DUPLICATE_ID, draft.validate(setOf("custom"), "other"))
        assertNull(draft.validate(setOf("custom"), "custom"))
    }

    @Test
    fun optionalDisplayNameUsesIdForNewEntriesAndPreservesExistingNames() {
        val unnamed = draft.copy(displayName = " \n ")
        assertNull(unnamed.validate(emptySet(), null))
        assertEquals("custom", unnamed.application().displayName)
        assertEquals("Existing name", unnamed.application("Existing name").displayName)
        assertEquals("Existing name", unnamed.copy(id = "renamed-id").application("Existing name").displayName)
        assertEquals("My app", draft.application("Existing name").displayName)
    }
}
