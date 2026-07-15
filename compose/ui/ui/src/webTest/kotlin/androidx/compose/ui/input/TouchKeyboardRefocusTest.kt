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

package androidx.compose.ui.input

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.OnCanvasTests
import androidx.compose.ui.events.touchEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.w3c.dom.HTMLElement
import org.w3c.dom.pointerevents.PointerEvent as WebPointerEvent
import org.w3c.dom.pointerevents.PointerEventInit

/**
 * Verifies that ComposeWindow's touchend handler re-asserts the DOM focus of the backing
 * text input when (and only when) the software keyboard was requested during the current
 * touch sequence.
 *
 * On iOS Safari 26.3+ a focus() issued during the pointerup dispatch (where Compose handles
 * the tap and starts the text input session) is not reliably honored as a keyboard summon,
 * while a focus() issued during the touchend dispatch of the same tap is. The keyboard itself
 * cannot be observed in these tests; what they pin down is the DOM-focus contract: by the end
 * of touchend the backing input must be focused for keyboard-requesting taps, and must NOT be
 * (re)focused for unrelated ones.
 */
class TouchKeyboardRefocusTest : OnCanvasTests {

    private fun touch(id: Int, x: Int, y: Int) = PointerEventInit(
        pointerId = id,
        clientX = x,
        clientY = y,
        pointerType = "touch",
        cancelable = true,
    )

    /** Dispatches the browser's event sequence for a tap: pointer events before their touch counterparts. */
    private fun tap(id: Int, x: Int, y: Int) {
        dispatchEvents(WebPointerEvent("pointerdown", touch(id, x, y)))
        dispatchEvents(touchEvent("touchstart"))
        dispatchEvents(WebPointerEvent("pointerup", touch(id, x, y)))
        dispatchEvents(touchEvent("touchend"))
    }

    private fun requireBackingField(): HTMLElement {
        val element = getShadowRoot().querySelector(".compose-backing-field")
        assertIs<HTMLElement>(element, "a backing field is expected to exist")
        return element
    }

    @Test
    fun tapRefocusesBackingInputDuringTouchend() = runApplicationTest {
        createComposeWindow {
            BasicTextField(
                state = rememberTextFieldState("hello"),
                modifier = Modifier.fillMaxSize()
            )
        }

        dispatchEvents(WebPointerEvent("pointerdown", touch(1, 50, 50)))
        dispatchEvents(touchEvent("touchstart"))
        dispatchEvents(WebPointerEvent("pointerup", touch(1, 50, 50)))

        // The tap is processed within the pointerup dispatch: it starts the text input
        // session, which creates and focuses the backing input.
        val backingField = requireBackingField()
        assertEquals(
            backingField,
            getShadowRoot().activeElement,
            "backing input should be focused after pointerup"
        )

        // Emulate iOS Safari's state between pointerup and touchend when the pointerup-time
        // keyboard summon was ignored/cancelled (the same DOM state as after the user hides
        // the keyboard with the Done button): the element lost its DOM focus.
        backingField.blur()
        assertNotEquals(backingField, getShadowRoot().activeElement)

        dispatchEvents(touchEvent("touchend"))
        assertEquals(
            backingField,
            getShadowRoot().activeElement,
            "touchend of a keyboard-requesting tap must re-assert the backing input focus"
        )
    }

    @Test
    fun unrelatedTapDoesNotRefocusBackingInput() = runApplicationTest {
        createComposeWindow {
            Column(Modifier.fillMaxSize()) {
                // A tap-consuming, focus-neutral area: `clickable` is not used because on web
                // it requests focus on click, which would end the text input session and make
                // the assertions below vacuous.
                Box(
                    Modifier.size(100.dp, 50.dp).background(Color.LightGray)
                        .pointerInput(Unit) { detectTapGestures { } }
                )
                BasicTextField(
                    state = rememberTextFieldState("hello"),
                    modifier = Modifier.size(100.dp, 50.dp)
                )
            }
        }

        // Focus the text field with a full tap sequence.
        tap(1, 50, 75)
        val backingField = requireBackingField()
        assertEquals(backingField, getShadowRoot().activeElement)

        backingField.blur()

        // Tap the unrelated tap-consuming area: its touchend must not re-focus the backing
        // input (that would re-summon a keyboard the user dismissed).
        tap(2, 50, 25)

        assertTrue(backingField.isConnected, "the text input session should still be active")
        assertNotEquals(
            backingField,
            getShadowRoot().activeElement,
            "touchend of an unrelated tap must not re-focus the backing input"
        )
    }

    @Test
    fun staleShowRequestClearedOnNextTouchSequence() = runApplicationTest {
        val focusRequester = FocusRequester()
        createComposeWindow {
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier.size(100.dp, 50.dp).background(Color.LightGray)
                        .pointerInput(Unit) { detectTapGestures { } }
                )
                BasicTextField(
                    state = rememberTextFieldState("hello"),
                    modifier = Modifier.size(100.dp, 50.dp).focusRequester(focusRequester)
                )
            }
        }

        // Latch a keyboard show request with no touch sequence involved:
        // programmatic focus starts the text input session.
        focusRequester.requestFocus()
        awaitIdle()

        val backingField = requireBackingField()
        backingField.blur()

        // A new touch sequence must drop the stale request: the touchend of a tap on the
        // unrelated tap-consuming area must not re-focus the backing input.
        tap(1, 50, 25)

        assertTrue(backingField.isConnected, "the text input session should still be active")
        assertNotEquals(
            backingField,
            getShadowRoot().activeElement,
            "a stale show request must not make an unrelated tap re-focus the backing input"
        )
    }
}
