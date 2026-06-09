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
import androidx.compose.ui.test.findNodeWithTag
import androidx.compose.ui.test.runUIKitInstrumentedTest
import androidx.compose.ui.test.utils.findFirstDescendant
import androidx.compose.ui.test.utils.hold
import androidx.compose.ui.test.utils.isLoupeView
import androidx.compose.ui.test.utils.up
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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

        findNodeWithTag(Tag).tap()
        delay(500)
        findNodeWithTag(Tag).doubleTap()
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
        delay(100)
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
        private val SelectableTextWidth = 160.dp
    }
}
