package net.extrawdw.notisync.desktop

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopProcessOwnerResolverTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun linuxReportsEffectiveUidRatherThanRealOrSavedUid() {
        val proc = temporaryFolder.newFolder("proc").toPath()
        Files.createDirectories(proc.resolve("42"))
        Files.writeString(proc.resolve("42/status"), "Name:\ttest\nUid:\t1000\t0\t1000\t0\n")
        val resolver = DesktopProcessOwnerResolver(osName = "Linux", procRoot = proc, posixUsername = { "user-$it" })
        assertEquals(DesktopProcessOwner("user-0", uid = 0), resolver.resolve(42))
        assertNull(resolver.resolve(43))
        Files.writeString(proc.resolve("42/status"), "Uid:\t1000\tbad\t1000\t0\n")
        assertNull(resolver.resolve(42))
    }

    @Test
    fun unavailableAccountNameDoesNotDiscardUidAndDeniedProcessDoesNotThrow() {
        val resolver = DesktopProcessOwnerResolver(
            osName = "Mac OS X", macUid = { if (it == 42L) 501 else error("denied") },
            posixUsername = { error("unknown account") },
        )
        assertEquals(DesktopProcessOwner(uid = 501), resolver.resolve(42))
        assertNull(resolver.resolve(43))
    }

    @Test
    fun windowsPreservesSidWithoutAnAccountName() {
        val owner = DesktopProcessOwner(sid = "S-1-5-21-100-200-300-1001")
        assertEquals(owner, DesktopProcessOwnerResolver(osName = "Windows 11", windowsOwner = { owner }).resolve(42))
        assertNull(DesktopProcessOwnerResolver(osName = "Windows 11", windowsOwner = { error("denied") }).resolve(42))
    }

    @Test
    fun macRootProcessOwnerIsAvailableAcrossTheUserBoundary() {
        assumeTrue(System.getProperty("os.name").contains("mac", ignoreCase = true))
        // launchd always belongs to root; the full BSD-info query denies non-root callers.
        assertEquals(DesktopProcessOwner(username = "root", uid = 0), DesktopProcessOwnerResolver().resolve(1))
    }

    @Test
    fun liveCurrentProcessReportsItsOwnAccount() {
        val owner = requireNotNull(DesktopProcessOwnerResolver().resolve(ProcessHandle.current().pid()))
        assertNotNull(owner.username)
        if (System.getProperty("os.name").contains("windows", ignoreCase = true)) {
            assertTrue(requireNotNull(owner.sid).startsWith("S-1-"))
            assertNull(owner.uid)
        } else {
            val uid = ProcessBuilder("id", "-u").start().inputStream.bufferedReader().use { it.readText().trim().toLong() }
            assertEquals(uid, owner.uid)
            assertNull(owner.sid)
        }
    }
}
