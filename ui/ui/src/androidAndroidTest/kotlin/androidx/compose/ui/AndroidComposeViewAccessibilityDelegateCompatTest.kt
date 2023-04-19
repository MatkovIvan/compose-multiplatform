
/*
 * Copyright 2020 The Android Open Source Project
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

package androidx.compose.ui

import android.os.Build
import android.text.SpannableString
import android.view.ViewGroup
import android.view.ViewStructure
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAndroidRect
import androidx.compose.ui.node.InnerNodeCoordinator
import androidx.compose.ui.node.LayoutNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.platform.AndroidComposeView
import androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat
import androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat.SemanticsNodeCopy
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.SemanticsNodeWithAdjustedBounds
import androidx.compose.ui.platform.getAllUncoveredSemanticsNodesToMap
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.EmptySemanticsModifierNodeElement
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.copyText
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.cutText
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.pasteText
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.setSelection
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.textSelectionRange
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.test.TestActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.IntRect
import androidx.core.view.ViewCompat
import androidx.core.view.ViewStructureCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import androidx.core.view.contentcapture.ContentCaptureSessionCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.FlakyTest
import androidx.test.filters.MediumTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatcher
import org.mockito.ArgumentMatchers

@MediumTest
@RunWith(AndroidJUnit4::class)
class AndroidComposeViewAccessibilityDelegateCompatTest {
    @get:Rule
    val rule = createAndroidComposeRule<TestActivity>()

    private lateinit var accessibilityDelegate: AndroidComposeViewAccessibilityDelegateCompat
    private lateinit var container: ViewGroup
    private lateinit var androidComposeView: AndroidComposeView
    private lateinit var info: AccessibilityNodeInfoCompat

    private lateinit var contentCaptureSessionCompat: ContentCaptureSessionCompat
    private lateinit var viewStructureCompat: ViewStructureCompat

    @Before
    fun setup() {
        // Use uiAutomation to enable accessibility manager.
        InstrumentationRegistry.getInstrumentation().uiAutomation
        rule.activityRule.scenario.onActivity {
            androidComposeView = AndroidComposeView(
                it,
                Executors.newFixedThreadPool(3).asCoroutineDispatcher()
            )
            container = spy(FrameLayout(it)) {
                on {
                    onRequestSendAccessibilityEvent(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any()
                    )
                } doReturn false
            }
            container.addView(androidComposeView)
            accessibilityDelegate = AndroidComposeViewAccessibilityDelegateCompat(
                androidComposeView
            )
            accessibilityDelegate.accessibilityForceEnabledForTesting = true
        }
        info = AccessibilityNodeInfoCompat.obtain()
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun testPopulateAccessibilityNodeInfoProperties_general() {
        val clickActionLabel = "click"
        val dismissActionLabel = "dismiss"
        val expandActionLabel = "expand"
        val collapseActionLabel = "collapse"
        val stateDescription = "checked"
        val resourceName = "myResourceName"
        val semanticsNode = createSemanticsNodeWithProperties(1, true) {
            this.stateDescription = stateDescription
            testTag = resourceName
            testTagsAsResourceId = true
            heading()
            onClick(clickActionLabel) { true }
            dismiss(dismissActionLabel) { true }
            expand(expandActionLabel) { true }
            collapse(collapseActionLabel) { true }
        }
        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)
        assertEquals("android.view.View", info.className)
        assertTrue(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_CLICK,
                    clickActionLabel
                )
            )
        )
        assertTrue(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_DISMISS,
                    dismissActionLabel
                )
            )
        )
        assertTrue(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_EXPAND,
                    expandActionLabel
                )
            )
        )
        assertTrue(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_COLLAPSE,
                    collapseActionLabel
                )
            )
        )
        val stateDescriptionResult = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                info.unwrap().stateDescription
            }
            Build.VERSION.SDK_INT >= 19 -> {
                info.extras.getCharSequence(
                    "androidx.view.accessibility.AccessibilityNodeInfoCompat.STATE_DESCRIPTION_KEY"
                )
            }
            else -> {
                null
            }
        }
        assertEquals(stateDescription, stateDescriptionResult)
        assertEquals(resourceName, info.viewIdResourceName)
        assertTrue(info.isHeading)
        assertTrue(info.isClickable)
        assertTrue(info.isVisibleToUser)
        assertTrue(info.isImportantForAccessibility)
    }

    @Test
    fun testPopulateAccessibilityNodeInfoProperties_screenReaderFocusable_mergingDescendants() {
        val node = createSemanticsNodeWithProperties(1, true) {}

        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, node)

        assertTrue(info.isScreenReaderFocusable)
    }

    @Test
    fun testPopulateAccessibilityNodeInfoProperties_screenReaderFocusable_notMergingDescendants() {
        val node = createSemanticsNodeWithProperties(1, false) {}

        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, node)

        assertFalse(info.isScreenReaderFocusable)
    }

    @Test
    fun testPopulateAccessibilityNodeInfoProperties_screenReaderFocusable_speakable() {
        val node = createSemanticsNodeWithProperties(1, false) {
            text = AnnotatedString("Example text")
        }

        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, node)

        assertTrue(info.isScreenReaderFocusable)
    }

    @Test
    fun testPopulateAccessibilityNodeInfoProperties_disabled() {
        rule.setContent {
            LocalClipboardManager.current.setText(AnnotatedString("test"))
        }

        val semanticsNode = createSemanticsNodeWithProperties(1, true) {
            disabled()
            editableText = AnnotatedString("text")
            horizontalScrollAxisRange = ScrollAxisRange({ 0f }, { 5f })
            onClick { true }
            onLongClick { true }
            copyText { true }
            pasteText { true }
            cutText { true }
            setText { true }
            setSelection { _, _, _ -> true }
            dismiss { true }
            expand { true }
            collapse { true }
        }
        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)
        assertTrue(info.isClickable)
        assertFalse(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK
            )
        )
        assertTrue(info.isLongClickable)
        assertFalse(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK
            )
        )
        assertTrue(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_COPY
            )
        )
        assertFalse(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_PASTE
            )
        )
        assertFalse(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CUT
            )
        )
        assertFalse(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SET_TEXT
            )
        )
        // This is the default ACTION_SET_SELECTION.
        assertTrue(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SET_SELECTION
            )
        )
        assertFalse(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_DISMISS
            )
        )
        assertFalse(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_EXPAND
            )
        )
        assertFalse(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_COLLAPSE
            )
        )
        assertFalse(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD
            )
        )
        assertFalse(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_RIGHT
            )
        )
        assertFalse(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_PAGE_UP
            )
        )
        assertFalse(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_PAGE_DOWN
            )
        )
        assertFalse(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_PAGE_LEFT
            )
        )
        assertFalse(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_PAGE_RIGHT
            )
        )
    }

    @Test
    fun testPopulateAccessibilityNodeInfoProperties_buttonRole() {
        val semanticsNode = createSemanticsNodeWithProperties(1, true) {
            role = Role.Button
        }
        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)
        assertEquals("android.widget.Button", info.className)
    }

    @Test
    fun testPopulateAccessibilityNodeInfoProperties_switchRole() {
        val semanticsNode = createSemanticsNodeWithProperties(1, true) {
            role = Role.Switch
        }
        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)
        assertEquals("android.view.View", info.className)
    }

    @Test
    fun testPopulateAccessibilityNodeInfoProperties_switchRoleDescription() {
        val semanticsNode = createSemanticsNodeWithProperties(1, true) {
            role = Role.Switch
        }
        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)
        assertEquals("Switch", info.roleDescription)
    }

    @Test
    fun testPopulateAccessibilityNodeInfoProperties_checkBoxRole() {
        val semanticsNode = createSemanticsNodeWithProperties(1, true) {
            role = Role.Checkbox
        }
        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)
        assertEquals("android.widget.CheckBox", info.className)
    }

    @Test
    fun testPopulateAccessibilityNodeInfoProperties_radioButtonRole() {
        val semanticsNode = createSemanticsNodeWithProperties(1, true) {
            role = Role.RadioButton
        }
        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)
        assertEquals("android.widget.RadioButton", info.className)
    }

    @Test
    fun testPopulateAccessibilityNodeInfoProperties_tabRole() {
        val semanticsNode = createSemanticsNodeWithProperties(1, true) {
            role = Role.Tab
        }
        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)
        assertEquals("Tab", info.roleDescription)
    }

    @Test
    fun testPopulateAccessibilityNodeInfoProperties_imageRole() {
        val semanticsNode = createSemanticsNodeWithProperties(1, true) {
            role = Role.Image
        }
        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)
        assertEquals("android.widget.ImageView", info.className)
    }

    @Test
    fun nodeWithTextAndLayoutResult_className_textView() {
        val semanticsNode = createSemanticsNodeWithProperties(1, true) {
            text = AnnotatedString("")
        }

        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)
        assertEquals("android.widget.TextView", info.className)
    }

    @Test
    fun testPopulateAccessibilityNodeInfoProperties_liveRegion() {
        var semanticsNode = createSemanticsNodeWithProperties(1, true) {
            liveRegion = LiveRegionMode.Polite
        }
        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)
        assertEquals(ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE, info.liveRegion)

        info = AccessibilityNodeInfoCompat.obtain()
        semanticsNode = createSemanticsNodeWithProperties(1, true) {
            liveRegion = LiveRegionMode.Assertive
        }
        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)
        assertEquals(ViewCompat.ACCESSIBILITY_LIVE_REGION_ASSERTIVE, info.liveRegion)
    }

    @Test
    fun testPopulateAccessibilityNodeInfoProperties_SeekBar() {
        val setProgressActionLabel = "setProgress"
        val semanticsNode = createSemanticsNodeWithProperties(1, true) {
            progressBarRangeInfo = ProgressBarRangeInfo(0.5f, 0f..1f, 6)
            setProgress(setProgressActionLabel) { true }
        }
        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)
        assertEquals("android.widget.SeekBar", info.className)
        assertEquals(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
            info.rangeInfo.type
        )
        assertEquals(0.5f, info.rangeInfo.current)
        assertEquals(0f, info.rangeInfo.min)
        assertEquals(1f, info.rangeInfo.max)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            assertTrue(
                containsAction(
                    info,
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        android.R.id.accessibilityActionSetProgress,
                        setProgressActionLabel
                    )
                )
            )
        }
    }

    @Test
    fun testPopulateAccessibilityNodeInfoProperties_textField() {
        val setSelectionActionLabel = "setSelection"
        val setTextActionLabel = "setText"
        val text = "hello"
        val semanticsNode = createSemanticsNodeWithProperties(1, true) {
            this.editableText = AnnotatedString(text)
            this.textSelectionRange = TextRange(1)
            this.focused = true
            getTextLayoutResult { true }
            setText(setTextActionLabel) { true }
            setSelection(setSelectionActionLabel) { _, _, _ -> true }
        }
        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)
        assertEquals("android.widget.EditText", info.className)
        assertEquals(SpannableString(text), info.text)
        assertTrue(info.isFocusable)
        assertTrue(info.isFocused)
        assertTrue(info.isEditable)
        assertTrue(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_SET_SELECTION,
                    setSelectionActionLabel
                )
            )
        )
        assertTrue(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_SET_TEXT,
                    setTextActionLabel
                )
            )
        )
        assertTrue(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat
                    .ACTION_NEXT_AT_MOVEMENT_GRANULARITY
            )
        )
        assertTrue(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat
                    .ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
            )
        )
        if (Build.VERSION.SDK_INT >= 26) {

            assertThat(info.unwrap().availableExtraData)
                .containsExactly(
                    "androidx.compose.ui.semantics.id",
                    AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
            )
        }
    }

    @Test
    fun testMovementGranularities_textField_focused() {
        val semanticsNode = createSemanticsNodeWithProperties(1, true) {
            this.editableText = AnnotatedString("text")
            this.textSelectionRange = TextRange(1)
            this.focused = true
            getTextLayoutResult { true }
            setText { true }
            setSelection { _, _, _ -> true }
        }
        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)

        assertEquals(
            AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER or
                AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_WORD or
                AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_PARAGRAPH or
                AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_LINE or
                AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_PAGE,
            info.movementGranularities
        )
    }

    @Test
    fun testMovementGranularities_textField_notFocused() {
        val semanticsNode = createSemanticsNodeWithProperties(1, true) {
            this.editableText = AnnotatedString("text")
            this.textSelectionRange = TextRange(1)
            getTextLayoutResult { true }
            setText { true }
            setSelection { _, _, _ -> true }
        }
        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)

        assertEquals(
            AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER or
                AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_WORD or
                AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_PARAGRAPH,
            info.movementGranularities
        )
    }

    @Test
    fun testPopulateAccessibilityNodeInfoProperties_setContentInvalid_customDescription() {
        val errorDescription = "Invalid format"
        val semanticsNode = createSemanticsNodeWithProperties(1, true) {
            error(errorDescription)
        }

        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)

        assertTrue(info.isContentInvalid)
        assertEquals(errorDescription, info.error)
    }

    @Test
    fun testPopulateAccessibilityNodeInfoProperties_setContentInvalid_emptyDescription() {
        val semanticsNode = createSemanticsNodeWithProperties(1, true) {
            error("")
        }

        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)

        assertTrue(info.isContentInvalid)
        assertTrue(info.error.isEmpty())
    }

    @Test
    fun test_PasteAction_ifFocused() {
        rule.setContent {
            LocalClipboardManager.current.setText(AnnotatedString("test"))
        }

        val semanticsNode = createSemanticsNodeWithProperties(1, true) {
            focused = true
            pasteText {
                true
            }
        }
        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)

        assertTrue(info.isFocused)
        assertTrue(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_PASTE,
                    null
                )
            )
        )
    }

    @Test
    fun test_noPasteAction_ifUnfocused() {
        rule.setContent {
            LocalClipboardManager.current.setText(AnnotatedString("test"))
        }

        val semanticsNode = createSemanticsNodeWithProperties(1, true) {
            pasteText {
                true
            }
        }
        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)

        assertFalse(info.isFocused)
        assertFalse(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_PASTE,
                    null
                )
            )
        )
    }

    @Test
    fun testActionCanBeNull() {
        val actionLabel = "send"
        val semanticsNode = createSemanticsNodeWithProperties(1, true) {
            onClick(label = actionLabel, action = null)
        }
        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)

        // When action is null here, should we still think it is clickable? Should we add the action
        // to AccessibilityNodeInfo?
        assertTrue(info.isClickable)
        assertTrue(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_CLICK,
                    actionLabel
                )
            )
        )
    }

    @Test
    @FlakyTest(bugId = 195287742)
    fun sendScrollEvent_byStateObservation() {
        var scrollValue by mutableStateOf(0f, structuralEqualityPolicy())
        var scrollMaxValue by mutableStateOf(100f, structuralEqualityPolicy())

        val semanticsNode = createSemanticsNodeWithProperties(1, false) {
            verticalScrollAxisRange = ScrollAxisRange({ scrollValue }, { scrollMaxValue })
        }

        accessibilityDelegate.previousSemanticsNodes[1] =
            SemanticsNodeCopy(
                semanticsNode,
                mapOf()
            )
        val newNodes = mutableMapOf<Int, SemanticsNodeWithAdjustedBounds>()
        newNodes[1] = SemanticsNodeWithAdjustedBounds(
            semanticsNode,
            android.graphics.Rect()
        )

        try {
            accessibilityDelegate.view.snapshotObserver.startObserving()

            accessibilityDelegate.sendSemanticsPropertyChangeEvents(newNodes)

            Snapshot.notifyObjectsInitialized()
            scrollValue = 1f
            Snapshot.sendApplyNotifications()
        } finally {
            accessibilityDelegate.view.snapshotObserver.stopObserving()
        }

        verify(container, times(1)).requestSendAccessibilityEvent(
            eq(androidComposeView),
            argThat(
                ArgumentMatcher {
                    it.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                        it.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
                }
            )
        )

        verify(container, times(1)).requestSendAccessibilityEvent(
            eq(androidComposeView),
            argThat(
                ArgumentMatcher {
                    it.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
                        it.scrollY == 1 &&
                        it.maxScrollY == 100 &&
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            it.scrollDeltaY == 1
                        } else {
                            true
                        }
                }
            )
        )
    }

    @Test
    fun sendWindowContentChangeUndefinedEventByDefault_whenPropertyAdded() {
        val oldSemanticsNode = createSemanticsNodeWithProperties(1, false) {}
        accessibilityDelegate.previousSemanticsNodes[1] =
            SemanticsNodeCopy(
                oldSemanticsNode,
                mapOf()
            )
        val newNodes = mutableMapOf<Int, SemanticsNodeWithAdjustedBounds>()
        newNodes[1] = createSemanticsNodeWithAdjustedBoundsWithProperties(1, false) {
            disabled()
        }
        accessibilityDelegate.sendSemanticsPropertyChangeEvents(newNodes)

        verify(container, times(1)).requestSendAccessibilityEvent(
            eq(androidComposeView),
            argThat(
                ArgumentMatcher {
                    it.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                        it.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED
                }
            )
        )
    }

    @Test
    fun sendWindowContentChangeUndefinedEventByDefault_whenPropertyRemoved() {
        val oldSemanticsNode = createSemanticsNodeWithProperties(1, false) {
            disabled()
        }
        accessibilityDelegate.previousSemanticsNodes[1] =
            SemanticsNodeCopy(
                oldSemanticsNode,
                mapOf()
            )
        val newNodes = mutableMapOf<Int, SemanticsNodeWithAdjustedBounds>()
        newNodes[1] = createSemanticsNodeWithAdjustedBoundsWithProperties(1, false) {}
        accessibilityDelegate.sendSemanticsPropertyChangeEvents(newNodes)

        verify(container, times(1)).requestSendAccessibilityEvent(
            eq(androidComposeView),
            argThat(
                ArgumentMatcher {
                    it.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                        it.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED
                }
            )
        )
    }

    @Test
    fun sendWindowContentChangeUndefinedEventByDefault_onlyOnce_whenMultiplePropertiesChange() {
        val oldSemanticsNode = createSemanticsNodeWithProperties(1, false) {
            disabled()
        }
        accessibilityDelegate.previousSemanticsNodes[1] =
            SemanticsNodeCopy(
                oldSemanticsNode,
                mapOf()
            )
        val newNodes = mutableMapOf<Int, SemanticsNodeWithAdjustedBounds>()
        newNodes[1] = createSemanticsNodeWithAdjustedBoundsWithProperties(1, false) {
            onClick { true }
        }
        accessibilityDelegate.sendSemanticsPropertyChangeEvents(newNodes)

        verify(container, times(1)).requestSendAccessibilityEvent(
            eq(androidComposeView),
            argThat(
                ArgumentMatcher {
                    it.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                        it.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED
                }
            )
        )
    }

    @Test
    fun sendWindowContentChangeUndefinedEventByDefault_standardActionWithTheSameLabel() {
        val label = "label"
        val oldSemanticsNode = createSemanticsNodeWithProperties(1, false) {
            onClick(label = label) { true }
        }
        accessibilityDelegate.previousSemanticsNodes[1] =
            SemanticsNodeCopy(
                oldSemanticsNode,
                mapOf()
            )
        val newNodes = mutableMapOf<Int, SemanticsNodeWithAdjustedBounds>()
        newNodes[1] = createSemanticsNodeWithAdjustedBoundsWithProperties(1, false) {
            onClick(label = label) { true }
        }
        accessibilityDelegate.sendSemanticsPropertyChangeEvents(newNodes)

        verify(container, never()).requestSendAccessibilityEvent(
            eq(androidComposeView),
            argThat(
                ArgumentMatcher {
                    it.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                        it.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED
                }
            )
        )
    }

    @Test
    fun sendWindowContentChangeUndefinedEventByDefault_standardActionWithDifferentLabels() {
        val labelOld = "labelOld"
        val labelNew = "labelNew"
        val oldSemanticsNode = createSemanticsNodeWithProperties(1, false) {
            onClick(label = labelOld) { true }
        }
        accessibilityDelegate.previousSemanticsNodes[1] =
            SemanticsNodeCopy(
                oldSemanticsNode,
                mapOf()
            )
        val newNodes = mutableMapOf<Int, SemanticsNodeWithAdjustedBounds>()
        newNodes[1] = createSemanticsNodeWithAdjustedBoundsWithProperties(1, false) {
            onClick(label = labelNew) { true }
        }
        accessibilityDelegate.sendSemanticsPropertyChangeEvents(newNodes)

        verify(container, times(1)).requestSendAccessibilityEvent(
            eq(androidComposeView),
            argThat(
                ArgumentMatcher {
                    it.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                        it.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED
                }
            )
        )
    }

    @Test
    fun sendWindowContentChangeUndefinedEventByDefault_customActionWithTheSameLabel() {
        val label = "label"
        val oldSemanticsNode = createSemanticsNodeWithProperties(1, false) {
            customActions = listOf(CustomAccessibilityAction(label) { true })
        }
        accessibilityDelegate.previousSemanticsNodes[1] =
            SemanticsNodeCopy(
                oldSemanticsNode,
                mapOf()
            )
        val newNodes = mutableMapOf<Int, SemanticsNodeWithAdjustedBounds>()
        newNodes[1] = createSemanticsNodeWithAdjustedBoundsWithProperties(1, false) {
            customActions = listOf(CustomAccessibilityAction(label) { true })
        }
        accessibilityDelegate.sendSemanticsPropertyChangeEvents(newNodes)

        verify(container, never()).requestSendAccessibilityEvent(
            eq(androidComposeView),
            argThat(
                ArgumentMatcher {
                    it.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                        it.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED
                }
            )
        )
    }

    @Test
    fun sendWindowContentChangeUndefinedEventByDefault_customActionWithDifferentLabels() {
        val labelOld = "labelOld"
        val labelNew = "labelNew"
        val oldSemanticsNode = createSemanticsNodeWithProperties(1, false) {
            customActions = listOf(CustomAccessibilityAction(labelOld) { true })
        }
        accessibilityDelegate.previousSemanticsNodes[1] =
            SemanticsNodeCopy(
                oldSemanticsNode,
                mapOf()
            )
        val newNodes = mutableMapOf<Int, SemanticsNodeWithAdjustedBounds>()
        newNodes[1] = createSemanticsNodeWithAdjustedBoundsWithProperties(1, false) {
            customActions = listOf(CustomAccessibilityAction(labelNew) { true })
        }
        accessibilityDelegate.sendSemanticsPropertyChangeEvents(newNodes)

        verify(container, times(1)).requestSendAccessibilityEvent(
            eq(androidComposeView),
            argThat(
                ArgumentMatcher {
                    it.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                        it.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED
                }
            )
        )
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun testUncoveredNodes_notPlacedNodes_notIncluded() {
        val nodes = SemanticsOwner(
            LayoutNode().also {
                it.modifier = EmptySemanticsModifierNodeElement
            }
        ).getAllUncoveredSemanticsNodesToMap()
        assertEquals(0, nodes.size)
    }

    @Test
    fun testUncoveredNodes_zeroBoundsRoot_included() {
        val nodes = SemanticsOwner(androidComposeView.root).getAllUncoveredSemanticsNodesToMap()

        assertEquals(1, nodes.size)
        assertEquals(AccessibilityNodeProviderCompat.HOST_VIEW_ID, nodes.keys.first())
        assertEquals(
            IntRect.Zero.toAndroidRect(),
            nodes[AccessibilityNodeProviderCompat.HOST_VIEW_ID]!!.adjustedBounds
        )
    }

    @Test
    fun testContentDescriptionCastSuccess() {
        val oldSemanticsNode = createSemanticsNodeWithProperties(1, true) {
        }
        accessibilityDelegate.previousSemanticsNodes[1] =
            SemanticsNodeCopy(
                oldSemanticsNode,
                mapOf()
            )
        val newNodes = mutableMapOf<Int, SemanticsNodeWithAdjustedBounds>()
        newNodes[1] = createSemanticsNodeWithAdjustedBoundsWithProperties(1, true) {
            this.contentDescription = "Hello" // To trigger content description casting
        }
        accessibilityDelegate.sendSemanticsPropertyChangeEvents(newNodes)
    }

    @Test
    fun canScroll_returnsFalse_whenPositionInvalid() {
        val semanticsNode = createSemanticsNodeWithAdjustedBoundsWithProperties(
            id = 1,
            mergeDescendants = true
        ) {
            horizontalScrollAxisRange = ScrollAxisRange(
                value = { 0f },
                maxValue = { 1f },
                reverseScrolling = false
            )
        }.apply {
            adjustedBounds.set(0, 0, 100, 100)
        }

        assertFalse(
            accessibilityDelegate.canScroll(
                currentSemanticsNodes = listOf(semanticsNode),
                vertical = false,
                direction = 1,
                position = Offset.Unspecified
            )
        )
        assertFalse(
            accessibilityDelegate.canScroll(
                currentSemanticsNodes = listOf(semanticsNode),
                vertical = false,
                direction = -1,
                position = Offset.Unspecified
            )
        )
        assertFalse(
            accessibilityDelegate.canScroll(
                currentSemanticsNodes = listOf(semanticsNode),
                vertical = false,
                direction = 0,
                position = Offset.Unspecified
            )
        )
    }

    @Test
    fun canScroll_returnsTrue_whenHorizontalScrollableNotAtLimit() {
        val semanticsNode = createSemanticsNodeWithAdjustedBoundsWithProperties(
            id = 1,
            mergeDescendants = true
        ) {
            horizontalScrollAxisRange = ScrollAxisRange(
                value = { 0.5f },
                maxValue = { 1f },
                reverseScrolling = false
            )
        }.apply {
            adjustedBounds.set(0, 0, 100, 100)
        }

        // Should be scrollable in both directions.
        assertTrue(
            accessibilityDelegate.canScroll(
                currentSemanticsNodes = listOf(semanticsNode),
                vertical = false,
                direction = 1,
                position = Offset(50f, 50f)
            )
        )
        assertTrue(
            accessibilityDelegate.canScroll(
                currentSemanticsNodes = listOf(semanticsNode),
                vertical = false,
                direction = 0,
                position = Offset(50f, 50f)
            )
        )
        assertTrue(
            accessibilityDelegate.canScroll(
                currentSemanticsNodes = listOf(semanticsNode),
                vertical = false,
                direction = -1,
                position = Offset(50f, 50f)
            )
        )
    }

    @Test
    fun canScroll_returnsTrue_whenVerticalScrollableNotAtLimit() {
        val semanticsNode = createSemanticsNodeWithAdjustedBoundsWithProperties(
            id = 1,
            mergeDescendants = true
        ) {
            verticalScrollAxisRange = ScrollAxisRange(
                value = { 0.5f },
                maxValue = { 1f },
                reverseScrolling = false
            )
        }.apply {
            adjustedBounds.set(0, 0, 100, 100)
        }

        // Should be scrollable in both directions.
        assertTrue(
            accessibilityDelegate.canScroll(
                currentSemanticsNodes = listOf(semanticsNode),
                vertical = true,
                direction = -1,
                position = Offset(50f, 50f)
            )
        )
        assertTrue(
            accessibilityDelegate.canScroll(
                currentSemanticsNodes = listOf(semanticsNode),
                vertical = true,
                direction = 0,
                position = Offset(50f, 50f)
            )
        )
        assertTrue(
            accessibilityDelegate.canScroll(
                currentSemanticsNodes = listOf(semanticsNode),
                vertical = true,
                direction = 1,
                position = Offset(50f, 50f)
            )
        )
    }

    @Test
    fun canScroll_returnsFalse_whenHorizontalScrollable_whenScrolledRightAndAtLimit() {
        val semanticsNode = createSemanticsNodeWithAdjustedBoundsWithProperties(
            id = 1,
            mergeDescendants = true
        ) {
            horizontalScrollAxisRange = ScrollAxisRange(
                value = { 1f },
                maxValue = { 1f },
                reverseScrolling = false
            )
        }.apply {
            adjustedBounds.set(0, 0, 100, 100)
        }

        assertFalse(
            accessibilityDelegate.canScroll(
                currentSemanticsNodes = listOf(semanticsNode),
                vertical = false,
                direction = 1,
                position = Offset(50f, 50f)
            )
        )
        assertFalse(
            accessibilityDelegate.canScroll(
                currentSemanticsNodes = listOf(semanticsNode),
                vertical = false,
                direction = 0,
                position = Offset(50f, 50f)
            )
        )
    }

    @Test
    fun canScroll_returnsFalse_whenHorizontalScrollable_whenScrolledLeftAndAtLimit() {
        val semanticsNode = createSemanticsNodeWithAdjustedBoundsWithProperties(
            id = 1,
            mergeDescendants = true
        ) {
            horizontalScrollAxisRange = ScrollAxisRange(
                value = { 0f },
                maxValue = { 1f },
                reverseScrolling = false
            )
        }.apply {
            adjustedBounds.set(0, 0, 100, 100)
        }

        assertFalse(
            accessibilityDelegate.canScroll(
                currentSemanticsNodes = listOf(semanticsNode),
                vertical = false,
                direction = -1,
                position = Offset(50f, 50f)
            )
        )
    }

    @Test
    fun canScroll_returnsFalse_whenVerticalScrollable_whenScrolledDownAndAtLimit() {
        val semanticsNode = createSemanticsNodeWithAdjustedBoundsWithProperties(
            id = 1,
            mergeDescendants = true
        ) {
            verticalScrollAxisRange = ScrollAxisRange(
                value = { 1f },
                maxValue = { 1f },
                reverseScrolling = false
            )
        }.apply {
            adjustedBounds.set(0, 0, 100, 100)
        }

        assertFalse(
            accessibilityDelegate.canScroll(
                currentSemanticsNodes = listOf(semanticsNode),
                vertical = true,
                direction = 1,
                position = Offset(50f, 50f)
            )
        )
        assertFalse(
            accessibilityDelegate.canScroll(
                currentSemanticsNodes = listOf(semanticsNode),
                vertical = true,
                direction = 0,
                position = Offset(50f, 50f)
            )
        )
    }

    @Test
    fun canScroll_returnsFalse_whenVerticalScrollable_whenScrolledUpAndAtLimit() {
        val semanticsNode = createSemanticsNodeWithAdjustedBoundsWithProperties(
            id = 1,
            mergeDescendants = true
        ) {
            verticalScrollAxisRange = ScrollAxisRange(
                value = { 0f },
                maxValue = { 1f },
                reverseScrolling = false
            )
        }.apply {
            adjustedBounds.set(0, 0, 100, 100)
        }

        assertFalse(
            accessibilityDelegate.canScroll(
                currentSemanticsNodes = listOf(semanticsNode),
                vertical = true,
                direction = -1,
                position = Offset(50f, 50f)
            )
        )
    }

    @Test
    fun canScroll_respectsReverseDirection() {
        val semanticsNode = createSemanticsNodeWithAdjustedBoundsWithProperties(
            id = 1,
            mergeDescendants = true
        ) {
            horizontalScrollAxisRange = ScrollAxisRange(
                value = { 0f },
                maxValue = { 1f },
                reverseScrolling = true
            )
        }.apply {
            adjustedBounds.set(0, 0, 100, 100)
        }

        assertTrue(
            accessibilityDelegate.canScroll(
                currentSemanticsNodes = listOf(semanticsNode),
                vertical = false,
                // Scroll left, even though value is 0.
                direction = -1,
                position = Offset(50f, 50f)
            )
        )
    }

    @Test
    fun canScroll_returnsFalse_forVertical_whenScrollableIsHorizontal() {
        val semanticsNode = createSemanticsNodeWithAdjustedBoundsWithProperties(
            id = 1,
            mergeDescendants = true
        ) {
            horizontalScrollAxisRange = ScrollAxisRange(
                value = { 0.5f },
                maxValue = { 1f },
                reverseScrolling = true
            )
        }.apply {
            adjustedBounds.set(0, 0, 100, 100)
        }

        assertFalse(
            accessibilityDelegate.canScroll(
                currentSemanticsNodes = listOf(semanticsNode),
                vertical = true,
                direction = 1,
                position = Offset(50f, 50f)
            )
        )
    }

    @Test
    fun canScroll_returnsFalse_whenTouchIsOutsideBounds() {
        val semanticsNode = createSemanticsNodeWithAdjustedBoundsWithProperties(
            id = 1,
            mergeDescendants = true
        ) {
            horizontalScrollAxisRange = ScrollAxisRange(
                value = { 0.5f },
                maxValue = { 1f },
                reverseScrolling = true
            )
        }.apply {
            adjustedBounds.set(0, 0, 50, 50)
        }

        assertFalse(
            accessibilityDelegate.canScroll(
                currentSemanticsNodes = listOf(semanticsNode),
                vertical = false,
                direction = 1,
                position = Offset(100f, 100f)
            )
        )
    }

    @Test
    fun passwordVisibilityToggle_fromInvisibleToVisible_doNotSendTextChangeEvent() {
        sendTextSemanticsChangeEvent(oldNodePassword = true, newNodePassword = false)

        verify(container, never()).requestSendAccessibilityEvent(
            eq(androidComposeView),
            argThat(
                ArgumentMatcher {
                    it.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                }
            )
        )
    }

    @Test
    fun passwordVisibilityToggle_fromVisibleToInvisible_doNotSendTextChangeEvent() {
        sendTextSemanticsChangeEvent(oldNodePassword = false, newNodePassword = true)

        verify(container, never()).requestSendAccessibilityEvent(
            eq(androidComposeView),
            argThat(
                ArgumentMatcher {
                    it.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                }
            )
        )
    }

    @Test
    fun passwordVisibilityToggle_fromInvisibleToVisible_sendTwoSelectionEvents() {
        sendTextSemanticsChangeEvent(oldNodePassword = true, newNodePassword = false)

        verify(container, times(2)).requestSendAccessibilityEvent(
            eq(androidComposeView),
            argThat(
                ArgumentMatcher {
                    it.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
                }
            )
        )
    }

    @Test
    fun passwordVisibilityToggle_fromVisibleToInvisible_sendTwoSelectionEvents() {
        sendTextSemanticsChangeEvent(oldNodePassword = false, newNodePassword = true)

        verify(container, times(2)).requestSendAccessibilityEvent(
            eq(androidComposeView),
            argThat(
                ArgumentMatcher {
                    it.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
                }
            )
        )
    }

    @Test
    fun textChanged_sendTextChangeEvent() {
        sendTextSemanticsChangeEvent(oldNodePassword = false, newNodePassword = false)

        verify(container, times(1)).requestSendAccessibilityEvent(
            eq(androidComposeView),
            argThat(
                ArgumentMatcher {
                    it.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                }
            )
        )
    }

    @Test
    fun textChanged_passwordNode_sendTextChangeEvent() {
        sendTextSemanticsChangeEvent(oldNodePassword = true, newNodePassword = true)

        verify(container, times(1)).requestSendAccessibilityEvent(
            eq(androidComposeView),
            argThat(
                ArgumentMatcher {
                    it.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                }
            )
        )
    }

    @SdkSuppress(minSdkVersion = 29)
    private fun setUpContentCapture() {
        contentCaptureSessionCompat = mock()
        viewStructureCompat = mock()
        val viewStructure: ViewStructure = mock()

        whenever(contentCaptureSessionCompat.newVirtualViewStructure(any(), any()))
            .thenReturn(viewStructureCompat)
        whenever(viewStructureCompat.toViewStructure()).thenReturn(viewStructure)
        accessibilityDelegate.contentCaptureSession = contentCaptureSessionCompat
        accessibilityDelegate.currentSemanticsNodes
        accessibilityDelegate.contentCaptureForceEnabledForTesting = true
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun testSendContentCaptureSemanticsStructureChangeEvents_appeared() {
        setUpContentCapture()

        val nodeId = 12
        val oldNode = createSemanticsNodeWithChildren(nodeId, emptyList())
        val oldNodeCopy = SemanticsNodeCopy(oldNode, mapOf())
        accessibilityDelegate.previousSemanticsNodes[nodeId] = oldNodeCopy

        val newNodeId1 = 10
        val newNodeId2 = 11
        val newNodeId3 = 12
        val newNode1 = createSemanticsNodeWithChildren(newNodeId1, emptyList()) {
            text = AnnotatedString("foo")
        }
        val newNode2 = createSemanticsNodeWithChildren(newNodeId2, emptyList()) {
            text = AnnotatedString("bar")
        }
        val newNode3 = createSemanticsNodeWithChildren(newNodeId3, listOf(newNode1, newNode2))
        accessibilityDelegate.currentSemanticsNodes = mapOf(
            newNodeId1 to SemanticsNodeWithAdjustedBounds(newNode1, android.graphics.Rect()),
            newNodeId2 to SemanticsNodeWithAdjustedBounds(newNode2, android.graphics.Rect()),
            newNodeId3 to SemanticsNodeWithAdjustedBounds(newNode3, android.graphics.Rect()),
        )

        accessibilityDelegate.sendContentCaptureSemanticsStructureChangeEvents(
            newNode3, oldNodeCopy)

        val captor = argumentCaptor<CharSequence>()
        verify(viewStructureCompat, times(2)).setText(captor.capture())
        assertEquals(captor.firstValue, "foo")
        assertEquals(captor.secondValue, "bar")

        assertThat(accessibilityDelegate.bufferedContentCaptureDisappearedNodes)
            .isEmpty()
        assertThat(accessibilityDelegate.bufferedContentCaptureAppearedNodes.keys)
            .containsExactly(newNodeId1, newNodeId2)
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun testSendContentCaptureSemanticsStructureChangeEvents_disappeared() {
        setUpContentCapture()
        val nodeId1 = 10
        val nodeId2 = 11
        val nodeId3 = 12
        val oldNode1 = createSemanticsNodeWithChildren(nodeId1, emptyList())
        val oldNode2 = createSemanticsNodeWithChildren(nodeId2, emptyList())
        val oldNode3 = createSemanticsNodeWithChildren(nodeId3, listOf(oldNode1, oldNode2))
        val oldNodeCopy1 = SemanticsNodeCopy(oldNode1, mapOf())
        val oldNodeCopy2 = SemanticsNodeCopy(oldNode2, mapOf())
        val oldNodeCopy3 = SemanticsNodeCopy(oldNode3, mapOf())
        accessibilityDelegate.previousSemanticsNodes[nodeId1] = oldNodeCopy1
        accessibilityDelegate.previousSemanticsNodes[nodeId2] = oldNodeCopy2
        accessibilityDelegate.previousSemanticsNodes[nodeId3] = oldNodeCopy3

        val newNodeId1 = 12
        val newNode1 = createSemanticsNodeWithChildren(newNodeId1, emptyList())
        accessibilityDelegate.currentSemanticsNodes = mapOf(
            newNodeId1 to SemanticsNodeWithAdjustedBounds(newNode1, android.graphics.Rect()),
        )

        accessibilityDelegate.sendContentCaptureSemanticsStructureChangeEvents(
            newNode1, oldNodeCopy3)

        assertThat(accessibilityDelegate.bufferedContentCaptureAppearedNodes).isEmpty()
        assertThat(accessibilityDelegate.bufferedContentCaptureDisappearedNodes)
            .containsExactly(nodeId1, nodeId2)
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun testSendContentCaptureSemanticsStructureChangeEvents_appearedAndDisappeared() {
        setUpContentCapture()
        val nodeId1 = 10
        val nodeId2 = 11
        val nodeId3 = 12
        val oldNode1 = createSemanticsNodeWithChildren(nodeId1, emptyList())
        val oldNode2 = createSemanticsNodeWithChildren(nodeId2, emptyList())
        val oldNode3 = createSemanticsNodeWithChildren(nodeId3, listOf(oldNode1, oldNode2))
        val oldNodeCopy1 = SemanticsNodeCopy(oldNode1, mapOf())
        val oldNodeCopy2 = SemanticsNodeCopy(oldNode2, mapOf())
        val oldNodeCopy3 = SemanticsNodeCopy(oldNode3, mapOf())
        accessibilityDelegate.previousSemanticsNodes[nodeId1] = oldNodeCopy1
        accessibilityDelegate.previousSemanticsNodes[nodeId2] = oldNodeCopy2
        accessibilityDelegate.previousSemanticsNodes[nodeId3] = oldNodeCopy3

        val newNodeId1 = 13
        val newNodeId2 = 14
        val newNodeId3 = 12
        val newNode1 = createSemanticsNodeWithChildren(newNodeId1, emptyList())
        val newNode2 = createSemanticsNodeWithChildren(newNodeId2, emptyList())
        val newNode3 = createSemanticsNodeWithChildren(newNodeId3, listOf(newNode1, newNode2))
        accessibilityDelegate.currentSemanticsNodes = mapOf(
            newNodeId1 to SemanticsNodeWithAdjustedBounds(newNode1, android.graphics.Rect()),
            newNodeId2 to SemanticsNodeWithAdjustedBounds(newNode2, android.graphics.Rect()),
            newNodeId3 to SemanticsNodeWithAdjustedBounds(newNode3, android.graphics.Rect()),
        )

        accessibilityDelegate.sendContentCaptureSemanticsStructureChangeEvents(
            newNode3, oldNodeCopy3)

        assertThat(accessibilityDelegate.bufferedContentCaptureAppearedNodes.keys)
            .containsExactly(newNodeId1, newNodeId2)
        assertThat(accessibilityDelegate.bufferedContentCaptureDisappearedNodes)
            .containsExactly(nodeId1, nodeId2)
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun testSendContentCaptureSemanticsStructureChangeEvents_sameNodeAppearedThenDisappeared() {
        setUpContentCapture()
        val nodeId1 = 10
        val oldNode1 = createSemanticsNodeWithChildren(nodeId1, emptyList())
        val oldNodeCopy1 =
            SemanticsNodeCopy(oldNode1, mapOf())
        accessibilityDelegate.previousSemanticsNodes[nodeId1] = oldNodeCopy1

        val newNodeId1 = 11
        val newNodeId2 = 10
        val newNode1 = createSemanticsNodeWithChildren(newNodeId1, emptyList())
        val newNode2 = createSemanticsNodeWithChildren(newNodeId2, listOf(newNode1))
        accessibilityDelegate.currentSemanticsNodes = mapOf(
            newNodeId1 to SemanticsNodeWithAdjustedBounds(newNode1, android.graphics.Rect()),
            newNodeId2 to SemanticsNodeWithAdjustedBounds(newNode2, android.graphics.Rect()),
        )

        accessibilityDelegate.sendContentCaptureSemanticsStructureChangeEvents(
            newNode2, oldNodeCopy1)

        assertThat(accessibilityDelegate.bufferedContentCaptureAppearedNodes.keys)
            .containsExactly(newNodeId1)
        assertThat(accessibilityDelegate.bufferedContentCaptureDisappearedNodes).isEmpty()

        val newNodeCopy1 = SemanticsNodeCopy(newNode1, mapOf())
        val newNodeCopy2 = SemanticsNodeCopy(newNode2, mapOf())
        accessibilityDelegate.previousSemanticsNodes[newNodeId1] = newNodeCopy1
        accessibilityDelegate.previousSemanticsNodes[newNodeId2] = newNodeCopy2
        accessibilityDelegate.currentSemanticsNodes = mapOf(
            nodeId1 to SemanticsNodeWithAdjustedBounds(oldNode1, android.graphics.Rect()),
        )

        accessibilityDelegate.sendContentCaptureSemanticsStructureChangeEvents(
            oldNode1, newNodeCopy2)

        assertThat(accessibilityDelegate.bufferedContentCaptureAppearedNodes).isEmpty()
        assertThat(accessibilityDelegate.bufferedContentCaptureDisappearedNodes).isEmpty()
    }

    private fun sendTextSemanticsChangeEvent(oldNodePassword: Boolean, newNodePassword: Boolean) {
        val nodeId = 1
        val oldTextNode = createSemanticsNodeWithProperties(nodeId, true) {
            setText { true }
            if (oldNodePassword) password()
            textSelectionRange = TextRange(4)
            editableText = AnnotatedString(
                when {
                    oldNodePassword && !newNodePassword -> "****"
                    !oldNodePassword && newNodePassword -> "1234"
                    !oldNodePassword && !newNodePassword -> "1234"
                    else -> "1234"
                }
            )
        }
        accessibilityDelegate.previousSemanticsNodes[nodeId] =
            SemanticsNodeCopy(oldTextNode, mapOf())

        val newTextNode = createSemanticsNodeWithAdjustedBoundsWithProperties(nodeId, true) {
            setText { true }
            if (newNodePassword) password()
            textSelectionRange = TextRange(4)
            editableText = AnnotatedString(
                when {
                    oldNodePassword && !newNodePassword -> "1234"
                    !oldNodePassword && newNodePassword -> "****"
                    !oldNodePassword && !newNodePassword -> "1235"
                    else -> "1235"
                }
            )
        }
        accessibilityDelegate.sendSemanticsPropertyChangeEvents(mapOf(nodeId to newTextNode))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun createSemanticsNodeWithProperties(
        id: Int,
        mergeDescendants: Boolean,
        properties: (SemanticsPropertyReceiver.() -> Unit)
    ): SemanticsNode {
        val layoutNode = LayoutNode(semanticsId = id)
        val nodeCoordinator = InnerNodeCoordinator(layoutNode)
        val modifierNode = object : SemanticsModifierNode, Modifier.Node() {
            override val semanticsConfiguration = SemanticsConfiguration().also {
                it.isMergingSemanticsOfDescendants = mergeDescendants
                it.properties()
            }
        }
        modifierNode.updateCoordinator(nodeCoordinator)
        return SemanticsNode(
            modifierNode,
            true,
            layoutNode
        )
    }

    private fun createSemanticsNodeWithChildren(
        id: Int,
        children: List<SemanticsNode>,
        properties: (SemanticsPropertyReceiver.() -> Unit) = {}
    ): SemanticsNode {
        val layoutNode = LayoutNode(semanticsId = id)
        layoutNode.zSortedChildren.addAll(children.map { it.layoutNode })
        val nodeCoordinator = InnerNodeCoordinator(layoutNode)
        val modifierNode = object : SemanticsModifierNode, Modifier.Node() {
            override val semanticsConfiguration = SemanticsConfiguration().also {
                it.properties()
            }
        }
        modifierNode.updateCoordinator(nodeCoordinator)

        val semanticsNode = SemanticsNode(modifierNode, true, layoutNode)
        layoutNode.modifier = Modifier.semantics {
            properties()
        }

        return semanticsNode
    }

    private fun createSemanticsNodeWithAdjustedBoundsWithProperties(
        id: Int,
        mergeDescendants: Boolean,
        properties: (SemanticsPropertyReceiver.() -> Unit)
    ): SemanticsNodeWithAdjustedBounds {
        return SemanticsNodeWithAdjustedBounds(
            createSemanticsNodeWithProperties(id, mergeDescendants, properties),
            android.graphics.Rect()
        )
    }

    private fun containsAction(
        info: AccessibilityNodeInfoCompat,
        action: AccessibilityNodeInfoCompat.AccessibilityActionCompat
    ): Boolean {
        for (a in info.actionList) {
            if (a.id == action.id && a.label == action.label) {
                return true
            }
        }
        return false
    }
}
