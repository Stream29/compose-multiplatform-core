/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.interaction

import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.UIKitInstrumentedTest
import androidx.compose.ui.test.assertVisibleInContainer
import androidx.compose.ui.test.findFocusedUITextInput
import androidx.compose.ui.test.findNodeWithLabel
import androidx.compose.ui.test.findNodeWithTag
import androidx.compose.ui.test.findNodeWithTagOrNull
import androidx.compose.ui.test.firstNodeOrNull
import androidx.compose.ui.test.runUIKitInstrumentedTest
import androidx.compose.ui.test.utils.findFirstDescendant
import androidx.compose.ui.test.utils.hold
import androidx.compose.ui.test.utils.isLoupeView
import androidx.compose.ui.test.utils.up
import androidx.compose.ui.test.waitForContextMenu
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.OSVersion
import org.jetbrains.skiko.available
import platform.UIKit.UIPasteboard

class SelectionContainerInteractionTest {

    @Test
    fun testSelectionContainer_LongPressSelectsWord() = runUIKitInstrumentedTest {
        val selectionState = SelectionState()

        setSelectionContainerContent(state = selectionState, text = SingleWord)

        longPressAndReleaseAfterLoupe(Tag)

        waitUntil("SelectionContainer should select the word after long press") {
            selectionState.selectedText() == SingleWord
        }

        assertEquals(SingleWord, selectionState.selectedText())
        assertTrue(
            selectionState.selectedTexts.isNotEmpty(),
            "Expected selectedTexts to be populated after long press selection.",
        )
    }

    @Test
    fun testSelectionContainer_DoubleTapSelectsWord() = runUIKitInstrumentedTest {
        val selectionState = SelectionState()

        setSelectionContainerContent(state = selectionState, text = SingleWord)

        focusThenDoubleTap()
        waitUntil("SelectionContainer should select the word after double tap") {
            selectionState.selectedText() == SingleWord
        }

        assertEquals(SingleWord, selectionState.selectedText())
        assertTrue(
            selectionState.selectedTexts.isNotEmpty(),
            "Expected selectedTexts to be populated after double tap selection.",
        )
    }

    @Test
    fun testSelectionContainer_LongPressDragExtendsSelectionAcrossLines() =
        runUIKitInstrumentedTest {
            val selectionState = SelectionState()

            setSelectionContainerContent(
                state = selectionState,
                text = MultiLineText,
                contentWidth = 160.dp,
            )

            longPressAndDrag(
                startTag = Tag,
                endTag = Tag,
                startXFraction = 0.12f,
                startYFraction = 0.25f,
                endXFraction = 0.80f,
                endYFraction = 0.75f,
            )

            waitUntil("SelectionContainer should extend the selection across lines") {
                val selectedText = selectionState.selectedText()
                selectedText.startsWith(SingleWord) &&
                    selectedText.contains("\n") &&
                    selectedText.substringAfter('\n').isNotEmpty()
            }

            val selectedText = selectionState.selectedText()
            assertTrue(
                selectedText.startsWith(SingleWord),
                "Expected selection to start from the first line, but got: $selectedText",
            )
            assertTrue(
                selectedText.contains("\n"),
                "Expected selection to cross a line boundary, but got: $selectedText",
            )
            assertTrue(
                selectedText.substringAfter('\n').isNotEmpty(),
                "Expected selection to include content from the second line, but got: $selectedText",
            )
        }

    @Test
    fun testSelectionContainer_LongPressDragExtendsSelectionAcrossTextChildren() =
        runUIKitInstrumentedTest {
            val selectionState = SelectionState()

            setSelectionContainerContent(state = selectionState) {
                Column {
                    BasicText(
                        text = FirstChildText,
                        modifier = Modifier.width(SelectableTextWidth).testTag(FirstTextTag),
                    )
                    BasicText(
                        text = SecondChildText,
                        modifier = Modifier.width(SelectableTextWidth).testTag(SecondTextTag),
                    )
                }
            }

            longPressAndDrag(
                startTag = FirstTextTag,
                endTag = SecondTextTag,
                startXFraction = 0.02f,
                endXFraction = 0.98f,
            )

            waitUntil("SelectionContainer should extend selection across text children") {
                selectionState.selectedTexts.map { it.text } ==
                    listOf(FirstChildText, SecondChildText)
            }

            assertEquals(
                listOf(FirstChildText, SecondChildText),
                selectionState.selectedTexts.map { it.text },
            )
            assertEquals(FirstChildText + SecondChildText, selectionState.selectedText())
        }

    @Test
    fun testSelectionContainer_LongPressDragSkipsDisableSelectionSubtree() =
        runUIKitInstrumentedTest {
            val selectionState = SelectionState()

            setSelectionContainerContent(state = selectionState) {
                Column {
                    BasicText(
                        text = FirstChildText,
                        modifier = Modifier.width(SelectableTextWidth).testTag(FirstTextTag),
                    )
                    DisableSelection {
                        BasicText(
                            text = DisabledChildText,
                            modifier = Modifier.width(SelectableTextWidth).testTag(DisabledTextTag),
                        )
                    }
                    BasicText(
                        text = ThirdChildText,
                        modifier = Modifier.width(SelectableTextWidth).testTag(ThirdTextTag),
                    )
                }
            }

            longPressAndDrag(
                startTag = FirstTextTag,
                endTag = ThirdTextTag,
                startXFraction = 0.02f,
                endXFraction = 0.98f,
            )

            waitUntil("SelectionContainer should skip DisableSelection content during drag") {
                selectionState.selectedTexts.map { it.text } ==
                    listOf(FirstChildText, ThirdChildText)
            }

            assertEquals(
                listOf(FirstChildText, ThirdChildText),
                selectionState.selectedTexts.map { it.text },
            )
            assertEquals(FirstChildText + ThirdChildText, selectionState.selectedText())
            assertTrue(
                selectionState.selectedTexts.none { it.text.contains(DisabledChildText) },
                "Expected disabled subtree text to be omitted from selectedTexts.",
            )
        }

    @Test
    fun testSelectionContainer_SelectedTextsReflectMultiTextSelection() = runUIKitInstrumentedTest {
        val selectionState = SelectionState()

        setSelectionContainerContent(state = selectionState) {
            Column {
                BasicText(
                    text = StateFirstText,
                    modifier = Modifier.width(SelectableTextWidth).testTag(FirstTextTag),
                )
                BasicText(
                    text = StateSecondText,
                    modifier = Modifier.width(SelectableTextWidth).testTag(SecondTextTag),
                )
            }
        }

        awaitNodeLaidOut(Tag)

        selectionState.select(TextRange(StateCrossTextRangeStart, StateCrossTextRangeEnd))

        waitUntil("SelectionState.selectedTexts should expose selected fragments in visual order") {
            selectionState.selectedTexts.map { it.text } ==
                listOf(StateCrossTextExpectedFirstFragment, StateCrossTextExpectedSecondFragment)
        }

        assertEquals(
            listOf(StateCrossTextExpectedFirstFragment, StateCrossTextExpectedSecondFragment),
            selectionState.selectedTexts.map { it.text },
        )
        assertEquals(StateCrossTextExpectedText, selectionState.selectedText())
    }

    @Test
    fun testSelectionContainer_SelectAllSelectsEntireContainer() = runUIKitInstrumentedTest {
        val selectionState = SelectionState()

        setSelectionContainerContent(state = selectionState) {
            Column {
                BasicText(
                    text = StateFirstText,
                    modifier = Modifier.width(SelectableTextWidth).testTag(FirstTextTag),
                )
                BasicText(
                    text = StateSecondText,
                    modifier = Modifier.width(SelectableTextWidth).testTag(SecondTextTag),
                )
            }
        }

        awaitNodeLaidOut(Tag)

        selectionState.selectAll()

        waitUntil("SelectionContainer should select all selectable content after selectAll()") {
            selectionState.selectedTexts.map { it.text } == listOf(StateFirstText, StateSecondText)
        }

        assertEquals(
            listOf(StateFirstText, StateSecondText),
            selectionState.selectedTexts.map { it.text },
        )
        assertEquals(StateCombinedText, selectionState.selectedText())
    }

    @Test
    fun testSelectionContainer_ClearClearsSelection() = runUIKitInstrumentedTest {
        val selectionState = SelectionState()

        setSelectionContainerContent(state = selectionState) {
            Column {
                BasicText(
                    text = StateFirstText,
                    modifier = Modifier.width(SelectableTextWidth).testTag(FirstTextTag),
                )
                BasicText(
                    text = StateSecondText,
                    modifier = Modifier.width(SelectableTextWidth).testTag(SecondTextTag),
                )
            }
        }

        awaitNodeLaidOut(Tag)

        selectionState.selectAll()
        waitUntil("SelectionContainer should have an active selection before clear()") {
            selectionState.selectedTexts.isNotEmpty()
        }

        selectionState.clear()

        waitUntil("SelectionContainer should clear the selection after clear()") {
            selectionState.selectedTexts.isEmpty()
        }

        assertTrue(
            selectionState.selectedTexts.isEmpty(),
            "Expected selectedTexts to be empty after clear().",
        )
        assertEquals("", selectionState.selectedText())
    }

    @Test
    fun testSelectionContainer_SelectRangeSelectsExpectedText() = runUIKitInstrumentedTest {
        val selectionState = SelectionState()

        setSelectionContainerContent(state = selectionState, text = SingleWord)

        awaitNodeLaidOut(Tag)

        selectionState.select(TextRange(SingleTextRangeStart, SingleTextRangeEnd))

        waitUntil("SelectionContainer should select the requested range after select(range)") {
            selectionState.selectedText() == SingleTextExpectedSelection
        }

        assertEquals(
            listOf(SingleTextExpectedSelection),
            selectionState.selectedTexts.map { it.text },
        )
        assertEquals(SingleTextExpectedSelection, selectionState.selectedText())
    }

    @Test
    fun testSelectionContainer_CopyCopiesExactSelectedText() =
        runSelectionContainerContextMenuTest {
            UIPasteboard.generalPasteboard().string = ClipboardInitialText
            val selectionState = SelectionState()

            setSelectionContainerContent(state = selectionState, text = CopyMenuText)

            awaitNodeLaidOut(Tag)
            openToolbarForLeadingWord(Tag)
            waitUntil("SelectionContainer should create the expected word selection before Copy") {
                selectionState.selectedText() == CopyMenuFirstWord
            }
            findNodeWithLabel("Copy").assertVisibleInContainer()
            tapContextMenuButton("Copy")

            waitUntil("Pasteboard should contain the copied SelectionContainer text") {
                UIPasteboard.generalPasteboard().string == CopyMenuFirstWord
            }

            assertEquals(CopyMenuFirstWord, UIPasteboard.generalPasteboard().string)
            val selectionAfterCopy = selectionState.selectedText()
            assertTrue(
                selectionAfterCopy.isEmpty() || selectionAfterCopy == CopyMenuFirstWord,
                "Expected SelectionContainer selection to either clear or preserve the copied word after Copy, but was: $selectionAfterCopy",
            )
        }

    @Test
    fun testSelectionContainer_EmptyTextDoesNotOpenMenu() = runSelectionContainerContextMenuTest {
        val selectionState = SelectionState()

        setSelectionContainerContent(state = selectionState, text = "")

        awaitNodeLaidOut(Tag)
        findNodeWithTag(Tag).longPress()
        waitForIdle()
        delay(EmptyTextLongPressSettleDelayMillis)

        assertTrue(
            selectionState.selectedTexts.isEmpty(),
            "Expected empty text content to produce no selection.",
        )
        assertEquals("", selectionState.selectedText())
        assertNoContextMenu()
    }

    @Test
    fun testSelectionContainer_OpeningMenuDoesNotShowKeyboard() =
        runSelectionContainerContextMenuTest {
            val selectionState = SelectionState()

            setSelectionContainerContent(state = selectionState, text = CopyMenuText)

            awaitNodeLaidOut(Tag)
            focusThenDoubleTap()
            waitForContextMenu()
            waitUntil("SelectionContainer should create a selection before menu open") {
                selectionState.selectedTexts.isNotEmpty()
            }

            findNodeWithLabel("Copy").assertVisibleInContainer()
            assertNull(
                findFocusedUITextInput(),
                "Expected SelectionContainer menu to open without focusing a UITextInput.",
            )
            assertEquals(0.dp, keyboardHeight)
        }

    private fun UIKitInstrumentedTest.setSelectionContainerContent(
        state: SelectionState,
        content: @Composable () -> Unit,
    ) {
        setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                SelectionContainer(
                    state = state,
                    modifier = Modifier.align(Alignment.Center).testTag(Tag),
                ) {
                    content()
                }
            }
        }
    }

    private fun UIKitInstrumentedTest.setSelectionContainerContent(
        state: SelectionState,
        text: String,
        contentWidth: Dp = 320.dp,
    ) {
        setSelectionContainerContent(state = state) {
            BasicText(text = text, modifier = Modifier.width(contentWidth))
        }
    }

    private fun UIKitInstrumentedTest.longPressAndReleaseAfterLoupe(tag: String) {
        val touch = findNodeWithTag(tag).touchDown()
        waitUntil("Selection loupe should appear after long press") {
            findFirstDescendant { it.isLoupeView } != null
        }
        touch.up()
    }

    private fun UIKitInstrumentedTest.awaitNodeLaidOut(tag: String) {
        waitUntil("Node with tag $tag should be laid out") {
            findNodeWithTagOrNull(tag)?.frame != null
        }
    }

    private fun UIKitInstrumentedTest.openToolbarForLeadingWord(tag: String) {
        findNodeWithTag(tag).tap()
        delay(DoubleTapPreparationDelayMillis)
        val tapPoint = pointInNode(tag, xFraction = 0.1f, yFraction = 0.5f)
        tap(tapPoint)
        delay(ManualDoubleTapIntervalDelayMillis)
        tap(tapPoint)
        waitForContextMenu()
    }

    private fun UIKitInstrumentedTest.focusThenDoubleTap() {
        val node = findNodeWithTag(Tag)
        node.tap()
        delay(DoubleTapPreparationDelayMillis)
        node.doubleTap()
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun runSelectionContainerContextMenuTest(testBlock: UIKitInstrumentedTest.() -> Unit) =
        runUIKitInstrumentedTest(params = listOf(false, true)) { newContextMenuEnabled ->
            val previousValue = ComposeFoundationFlags.isNewContextMenuEnabled
            ComposeFoundationFlags.isNewContextMenuEnabled = newContextMenuEnabled
            try {
                testBlock()
            } finally {
                ComposeFoundationFlags.isNewContextMenuEnabled = previousValue
            }
        }

    private fun UIKitInstrumentedTest.longPressAndDrag(
        startTag: String,
        endTag: String,
        startXFraction: Float,
        endXFraction: Float,
        startYFraction: Float = 0.5f,
        endYFraction: Float = 0.5f,
    ) {
        val startPoint = pointInNode(startTag, startXFraction, startYFraction)
        val endPoint = pointInNode(endTag, endXFraction, endYFraction)

        val touch = touchDown(startPoint)
        waitUntil("Selection loupe should appear after long press") {
            findFirstDescendant { it.isLoupeView } != null
        }
        touch.hold()
        delay(LongPressDragSettleDelayMillis)
        touch.dragTo(x = endPoint.x, y = endPoint.y, duration = 0.3.seconds)
        touch.up()
    }

    private fun UIKitInstrumentedTest.pointInNode(
        tag: String,
        xFraction: Float,
        yFraction: Float,
    ): DpOffset {
        val frame = findNodeWithTag(tag).frame!!
        return DpOffset(
            x = frame.left + (frame.right - frame.left) * xFraction,
            y = frame.top + (frame.bottom - frame.top) * yFraction,
        )
    }

    private fun UIKitInstrumentedTest.tapContextMenuButton(label: String) {
        if (available(OS.Ios to OSVersion(16))) {
            findNodeWithLabel(label).tap()
        } else {
            findNodeWithLabel(label)
                .touchDown(useNodeWindow = true)
                .hold()
                .also { delay(LegacyContextMenuButtonHoldDelayMillis) }
                .up()
        }
    }

    private fun UIKitInstrumentedTest.assertNoContextMenu() {
        assertNull(
            firstNodeOrNull { node ->
                node.element?.let { it::class.simpleName } == contextMenuHostClassName
            },
            "Expected no SelectionContainer context menu host to be present.",
        )
    }

    private fun SelectionState.selectedText(): String =
        selectedTexts.joinToString(separator = "") { it.text }

    private companion object {
        private const val Tag = "SelectionContainer"
        private const val FirstTextTag = "SelectionContainerFirstText"
        private const val SecondTextTag = "SelectionContainerSecondText"
        private const val DisabledTextTag = "SelectionContainerDisabledText"
        private const val ThirdTextTag = "SelectionContainerThirdText"
        private const val SingleWord = "accomplishment"
        private const val MultiLineText = "$SingleWord\nmagnificent"
        private const val FirstChildText = "accomplishment"
        private const val SecondChildText = "magnificent"
        private const val DisabledChildText = "hidden"
        private const val ThirdChildText = "remarkable"
        private const val StateFirstText = "Hello "
        private const val StateSecondText = "World"
        private const val StateCombinedText = StateFirstText + StateSecondText
        private const val StateCrossTextRangeStart = 2
        private const val StateCrossTextRangeEnd = 9
        private const val StateCrossTextExpectedFirstFragment = "llo "
        private const val StateCrossTextExpectedSecondFragment = "Wor"
        private const val StateCrossTextExpectedText =
            StateCrossTextExpectedFirstFragment + StateCrossTextExpectedSecondFragment
        private const val ClipboardInitialText = "Clipboard sentinel"
        private const val CopyMenuFirstWord = "copyable"
        private const val CopyMenuText = "$CopyMenuFirstWord second"
        private const val SingleTextRangeStart = 2
        private const val SingleTextRangeEnd = 9
        private const val SingleTextExpectedSelection = "complis"
        // Gives the first tap time to settle so the next doubleTap() is treated as a new gesture.
        private const val DoubleTapPreparationDelayMillis = 500L
        // Lets us observe that long-pressing empty text does not create a late menu or selection.
        private const val EmptyTextLongPressSettleDelayMillis = 500L
        // Keeps the two manual taps close enough for UIKit to recognize them as a double tap.
        private const val ManualDoubleTapIntervalDelayMillis = 50L
        // Gives long-press state a brief moment to settle before starting a drag extension.
        private const val LongPressDragSettleDelayMillis = 100L
        // On iOS < 16, hold the menu item briefly so the separate-window press is registered.
        private const val LegacyContextMenuButtonHoldDelayMillis = 100L
        private val SelectableTextWidth = 160.dp
        private val contextMenuHostClassName =
            if (available(OS.Ios to OSVersion(16))) {
                "_UIEditMenuContainerView"
            } else {
                "UICalloutBar"
            }
    }
}
