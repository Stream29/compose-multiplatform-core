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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
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

        delay(60000)

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

            val containerFrame = findNodeWithTag(Tag).frame!!
            val startPoint =
                DpOffset(
                    x = containerFrame.left + (containerFrame.right - containerFrame.left) * 0.12f,
                    y = containerFrame.top + (containerFrame.bottom - containerFrame.top) * 0.25f,
                )
            val endPoint =
                DpOffset(
                    x = containerFrame.left + (containerFrame.right - containerFrame.left) * 0.80f,
                    y = containerFrame.top + (containerFrame.bottom - containerFrame.top) * 0.75f,
                )

            val touch = touchDown(startPoint)
            waitUntil("Selection loupe should appear after long press") {
                findFirstDescendant { it.isLoupeView } != null
            }
            touch.hold()
            delay(100)
            touch.dragTo(x = endPoint.x, y = endPoint.y, duration = 0.3.seconds)
            touch.up()

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

    private fun UIKitInstrumentedTest.setSelectionContainerContent(
        state: SelectionState,
        text: String,
        contentWidth: Dp = 320.dp,
    ) {
        setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                SelectionContainer(
                    state = state,
                    modifier = Modifier.align(Alignment.Center).testTag(Tag),
                ) {
                    BasicText(text = text, modifier = Modifier.width(contentWidth))
                }
            }
        }
    }

    private fun UIKitInstrumentedTest.longPressAndReleaseAfterLoupe(tag: String) {
        val touch = findNodeWithTag(tag).touchDown()
        waitUntil("Selection loupe should appear after long press") {
            findFirstDescendant { it.isLoupeView } != null
        }
        touch.up()
    }

    private fun SelectionState.selectedText(): String =
        selectedTexts.joinToString(separator = "") { it.text }

    private companion object {
        private const val Tag = "SelectionContainer"
        private const val SingleWord = "accomplishment"
        private const val MultiLineText = "$SingleWord\nmagnificent"
    }
}
