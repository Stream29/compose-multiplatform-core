@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package androidx.compose.ui.platform.accessibility

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReusableContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import kotlinx.browser.document
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.skiko.wasm.onWasmReady
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ComposeWebSemanticsCompatibilityTest {
    @Test
    fun clickableControlsKeepTheirExplicitRoleAndExportCurrentCheckedOrSelectedState() = runTest {
        withContext(Dispatchers.Default) {
            ready()
            val controlRole = mutableStateOf(Role.Checkbox)
            val state = mutableStateOf(ToggleableState.Off)
            val selection = mutableStateOf(false)
            val revision = mutableIntStateOf(0)
            val root = RasterOwner {
                Layout(content = {}, modifier = Modifier.testTag("control").semantics {
                    role = controlRole.value
                    contentDescription = "revision ${revision.intValue}"
                    when (controlRole.value) {
                        Role.Checkbox, Role.Switch -> toggleableState = state.value
                        Role.RadioButton, Role.Tab -> selected = selection.value
                        else -> Unit
                    }
                    onClick { true }
                }) { _, _ -> layout(160, 40) {} }
            }
            val host = host()
            val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val listener = ComposeWebSemanticsListener(scope, host)
            try {
                listener.onSemanticsOwnerAppended(root.owner)
                await { host.querySelector("#control") != null }
                val element = host.querySelector("#control") as HTMLElement
                suspend fun synchronize() {
                    revision.intValue++
                    root.render()
                    listener.onSemanticsChange(root.owner)
                    await { element.getAttribute("aria-label") == "revision ${revision.intValue}" }
                }
                assertEquals("checkbox", element.getAttribute("role"))
                assertEquals("false", element.getAttribute("aria-checked"))
                state.value = ToggleableState.On
                synchronize()
                assertEquals("true", element.getAttribute("aria-checked"))
                state.value = ToggleableState.Indeterminate
                synchronize()
                assertEquals("mixed", element.getAttribute("aria-checked"))
                controlRole.value = Role.Switch
                state.value = ToggleableState.Off
                synchronize()
                assertEquals("switch", element.getAttribute("role"))
                assertEquals("false", element.getAttribute("aria-checked"))
                state.value = ToggleableState.On
                synchronize()
                assertEquals("true", element.getAttribute("aria-checked"))
                state.value = ToggleableState.Indeterminate
                synchronize()
                assertEquals("false", element.getAttribute("aria-checked"))
                controlRole.value = Role.RadioButton
                selection.value = true
                synchronize()
                assertEquals("radio", element.getAttribute("role"))
                assertEquals("true", element.getAttribute("aria-checked"))
                selection.value = false
                synchronize()
                assertEquals("false", element.getAttribute("aria-checked"))
                controlRole.value = Role.Tab
                selection.value = true
                synchronize()
                assertEquals("tab", element.getAttribute("role"))
                assertEquals("true", element.getAttribute("aria-selected"))
                assertNull(element.getAttribute("aria-checked"))
                selection.value = false
                synchronize()
                assertEquals("false", element.getAttribute("aria-selected"))
                controlRole.value = Role.Button
                synchronize()
                assertEquals("button", element.getAttribute("role"))
                assertNull(element.getAttribute("aria-checked"))
                assertNull(element.getAttribute("aria-selected"))
            } finally {
                scope.cancel()
                root.close()
                host.remove()
            }
        }
    }

    @Test
    fun reusedLayoutCannotRetargetAnOldDomActionToANewSemanticsIdentity() = runTest {
        withContext(Dispatchers.Default) {
            ready()
            val identity = mutableIntStateOf(0)
            val calls = mutableListOf<Int>()
            val root = RasterOwner {
                ReusableContent(identity.intValue) {
                    val current = identity.intValue
                    probe("reused", "identity $current", action = { calls += current })
                }
            }
            val host = host()
            val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val listener = ComposeWebSemanticsListener(scope, host)
            try {
                fun currentNode() = root.owner.getAllSemanticsNodes(mergingEnabled = false).single {
                    it.config.getOrNull(SemanticsProperties.TestTag) == "reused"
                }
                listener.onSemanticsOwnerAppended(root.owner)
                await { host.querySelector("#reused") != null }
                val retained = host.querySelector("#reused") as HTMLElement
                val before = currentNode()
                retained.click()
                assertEquals(listOf(0), calls)
                identity.intValue = 1
                root.render()
                val after = currentNode()
                assertSame(before.layoutNode, after.layoutNode, "Exercise real layout reuse, not replacement.")
                assertNotEquals(before.id, after.id)
                retained.click()
                assertEquals(listOf(0), calls, "Old DOM must not retarget to the reused row before synchronization.")
                listener.onSemanticsChange(root.owner)
                await { (host.querySelector("#reused") as? HTMLElement)?.getAttribute("aria-label") == "identity 1" }
                val current = host.querySelector("#reused") as HTMLElement
                assertNotSame(retained, current)
                current.click()
                assertEquals(listOf(0, 1), calls)
            } finally {
                scope.cancel()
                root.close()
                host.remove()
            }
        }
    }

    @Test
    fun nestedOwnersRestoreAndLastRemovalClearsTheMirror() = runTest {
        withContext(Dispatchers.Default) {
            ready()
            val root = RasterOwner { probe("root") }
            val popup = RasterOwner { probe("popup") }
            val nested = RasterOwner { probe("nested") }
            val host = host()
            val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val listener = ComposeWebSemanticsListener(scope, host)
            try {
                listener.onSemanticsOwnerAppended(root.owner)
                await { host.querySelector("#root") != null }
                listener.onSemanticsOwnerAppended(popup.owner)
                await { host.querySelector("#popup") != null && host.querySelector("#root") == null }
                listener.onSemanticsOwnerAppended(nested.owner)
                await { host.querySelector("#nested") != null && host.querySelector("#popup") == null }
                listener.onSemanticsOwnerRemoved(popup.owner)
                listener.onSemanticsOwnerRemoved(nested.owner)
                await { host.querySelector("#root") != null && host.querySelector("#nested") == null }
                listener.onSemanticsOwnerRemoved(root.owner)
                await { host.childElementCount == 0 }
            } finally {
                scope.cancel()
                nested.close(); popup.close(); root.close()
                host.remove()
            }
        }
    }

    @Test
    fun retainedDomUsesCurrentActionAndDisabledAdmissionRatherThanCapturedConfig() = runTest {
        withContext(Dispatchers.Default) {
            ready()
            val revision = mutableIntStateOf(1)
            val enabled = mutableStateOf(true)
            val clickable = mutableStateOf(false)
            val exposed = mutableStateOf(true)
            val calls = mutableListOf<Int>()
            val root = RasterOwner {
                val current = revision.intValue
                probe("action", "revision $current", enabled.value,
                    if (clickable.value) ({ calls += current }) else null, exposed.value)
            }
            val popup = RasterOwner { probe("popup") }
            val host = host()
            val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val listener = ComposeWebSemanticsListener(scope, host)
            try {
                listener.onSemanticsOwnerAppended(root.owner)
                await { host.querySelector("#action") != null }
                val retained = host.querySelector("#action") as HTMLElement
                retained.click()
                assertEquals(emptyList(), calls)

                suspend fun synchronize(value: Int, beforeMirror: (() -> Unit)? = null) {
                    revision.intValue = value
                    root.render()
                    beforeMirror?.invoke()
                    listener.onSemanticsChange(root.owner)
                    await { retained.getAttribute("aria-label") == "revision $value" }
                    assertSame(retained, host.querySelector("#action"))
                }

                clickable.value = true
                synchronize(2)
                retained.click()
                assertEquals(listOf(2), calls, "A node can gain an action without being recreated.")
                synchronize(3) { retained.click() }
                assertEquals(listOf(2, 3), calls, "Dispatch must resolve the new callback.")
                enabled.value = false
                synchronize(4) {
                    retained.click()
                    assertEquals(listOf(2, 3), calls, "Disabled admission cannot wait for the debounced DOM mirror.")
                }
                assertEquals("true", retained.getAttribute("aria-disabled"))
                retained.click()
                assertEquals(listOf(2, 3), calls)
                enabled.value = true
                synchronize(5)
                assertNull(retained.getAttribute("aria-disabled"))
                retained.click()
                assertEquals(listOf(2, 3, 5), calls)
                clickable.value = false
                synchronize(6) {
                    retained.click()
                    assertEquals(listOf(2, 3, 5), calls, "A removed action is rejected before DOM synchronization.")
                }
                retained.click()
                assertEquals(listOf(2, 3, 5), calls)

                clickable.value = true
                synchronize(7)
                listener.onSemanticsOwnerAppended(popup.owner)
                retained.click()
                assertEquals(listOf(2, 3, 5), calls, "An unsynchronized owner switch rejects old DOM actions.")
                await { host.querySelector("#popup") != null }
                listener.onSemanticsOwnerRemoved(popup.owner)
                await { host.querySelector("#action") != null }
                (host.querySelector("#action") as HTMLElement).click()
                assertEquals(listOf(2, 3, 5, 7), calls)
                val restored = host.querySelector("#action") as HTMLElement
                exposed.value = false
                root.render()
                restored.click()
                assertEquals(listOf(2, 3, 5, 7), calls, "Removing all semantics from an attached layout rejects its retained DOM.")
                listener.onSemanticsChange(root.owner)
                await { host.querySelector("#action") == null }
                listener.onSemanticsOwnerRemoved(root.owner)
                retained.click()
                assertEquals(listOf(2, 3, 5, 7), calls)
                await { host.childElementCount == 0 }
            } finally {
                scope.cancel()
                popup.close(); root.close()
                host.remove()
            }
        }
    }
}

private suspend fun ready() {
    val ready = CompletableDeferred<Unit>()
    onWasmReady { ready.complete(Unit) }
    withTimeout(10_000) { ready.await() }
}

private fun host(): HTMLElement = (document.createElement("div") as HTMLElement).also {
    document.body!!.appendChild(it)
}

private suspend fun await(condition: () -> Boolean) {
    withTimeout(10_000) { while (!condition()) delay(10) }
}

private class RasterOwner(content: @Composable () -> Unit) : AutoCloseable {
    private val scene = ImageComposeScene(320, 200, coroutineContext = Dispatchers.Unconfined, content = content)
    private var frame = 0L
    init { render() }
    val owner get() = scene.semanticsOwners.single()
    fun render() {
        Snapshot.sendApplyNotifications()
        repeat(3) { scene.render(++frame * 16_666_667).close() }
    }
    override fun close() = scene.close()
}

@Composable
private fun probe(
    id: String, label: String = id, enabled: Boolean = true, action: (() -> Unit)? = null, exposed: Boolean = true,
) {
    val modifier = if (exposed) Modifier.testTag(id).semantics {
        contentDescription = label
        role = Role.Button
        if (!enabled) disabled()
        if (action != null) onClick { action(); true }
    } else Modifier
    Layout(content = {}, modifier = modifier) { _, _ -> layout(160, 40) {} }
}
