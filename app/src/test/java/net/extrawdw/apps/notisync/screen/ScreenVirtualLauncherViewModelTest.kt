package net.extrawdw.apps.notisync.screen

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenVirtualLauncherViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val original = listOf(VirtualLauncherApp("com.example.chat", "Chat", "Chat"))
    private val updated = listOf(VirtualLauncherApp("com.example.browser", "Browser", "Browser"))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun `recreated activity reuses both in-flight load and cached catalog`() = runTest(dispatcher) {
        var loads = 0
        val result = CompletableDeferred<List<VirtualLauncherApp>>()
        val factory = viewModelFactory { initializer {
            ScreenVirtualLauncherViewModel { loads++; result.await() }
        } }
        val first = ViewModelProvider(store, factory)[ScreenVirtualLauncherViewModel::class.java]
        first.ensureLoaded(Locale.ENGLISH)
        runCurrent()

        val recreated = ViewModelProvider(store, factory)[ScreenVirtualLauncherViewModel::class.java]
        repeat(10) { recreated.ensureLoaded(Locale.ENGLISH) }
        runCurrent()
        assertSame(first, recreated)
        assertEquals(1, loads)

        result.complete(original)
        runCurrent()
        repeat(10) { recreated.ensureLoaded(Locale.ENGLISH) }
        runCurrent()
        assertSame(original, recreated.apps.value)
        assertEquals(1, loads)
    }

    @Test fun `package changes retain visible apps and coalesce without publishing an obsolete scan`() = runTest(dispatcher) {
        var loads = 0
        val results = Channel<List<VirtualLauncherApp>>(Channel.UNLIMITED)
        val model = model { loads++; results.receive() }
        model.ensureLoaded(Locale.ENGLISH)
        results.send(original)
        runCurrent()

        model.invalidate()
        runCurrent()
        assertSame(original, model.apps.value)
        repeat(10) { model.invalidate() }
        runCurrent()
        assertEquals(2, loads)

        results.send(emptyList())
        runCurrent()
        assertSame(original, model.apps.value)
        assertEquals(3, loads)
        results.send(updated)
        runCurrent()
        assertSame(updated, model.apps.value)
        assertEquals(3, loads)
    }

    @Test fun `language change during initial load discards old labels and sorting`() = runTest(dispatcher) {
        val locales = mutableListOf<Locale>()
        val results = Channel<List<VirtualLauncherApp>>(Channel.UNLIMITED)
        val model = model { locales += it; results.receive() }
        model.ensureLoaded(Locale.ENGLISH)
        runCurrent()
        model.ensureLoaded(Locale.CHINESE)
        results.send(original)
        runCurrent()
        assertNull(model.apps.value)
        assertEquals(listOf(Locale.ENGLISH, Locale.CHINESE), locales)

        results.send(updated)
        runCurrent()
        assertSame(updated, model.apps.value)
        model.ensureLoaded(Locale.CHINESE)
        runCurrent()
        assertEquals(2, locales.size)
    }

    @Test fun `failed refresh preserves cached apps and retries on resume`() = runTest(dispatcher) {
        var next: List<VirtualLauncherApp>? = original
        var loads = 0
        val model = model { loads++; next }
        model.ensureLoaded(Locale.ENGLISH)
        runCurrent()

        next = null
        model.invalidate()
        runCurrent()
        assertSame(original, model.apps.value)
        assertEquals(2, loads)

        next = updated
        model.ensureLoaded(Locale.ENGLISH)
        runCurrent()
        assertSame(updated, model.apps.value)
        assertEquals(3, loads)
    }

    @Test fun `ending launcher cancels pending scan and releases package observer`() = runTest(dispatcher) {
        var cancelled = false
        var closed = false
        val result = CompletableDeferred<List<VirtualLauncherApp>>()
        val model = model {
            try { result.await() } finally { cancelled = true }
        }
        model.addCloseable { closed = true }
        model.ensureLoaded(Locale.ENGLISH)
        runCurrent()
        store.clear()
        runCurrent()

        assertEquals(true, cancelled)
        assertEquals(true, closed)
        assertNull(model.apps.value)
    }

    private fun model(loader: suspend (Locale) -> List<VirtualLauncherApp>?): ScreenVirtualLauncherViewModel =
        ViewModelProvider(store, viewModelFactory { initializer { ScreenVirtualLauncherViewModel(loader) } })[
            ScreenVirtualLauncherViewModel::class.java]
}
