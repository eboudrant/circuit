// Copyright (C) 2023 Slack Technologies, LLC
// SPDX-License-Identifier: Apache-2.0
package com.slack.circuitx.gesturenavigation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slack.circuit.backstack.rememberSaveableBackStack
import com.slack.circuit.foundation.CircuitCompositionLocals
import com.slack.circuit.foundation.NavigableCircuitContent
import com.slack.circuit.foundation.rememberCircuitNavigator
import com.slack.circuit.internal.test.TestContentTags.TAG_COUNT
import com.slack.circuit.internal.test.TestContentTags.TAG_GO_NEXT
import com.slack.circuit.internal.test.TestContentTags.TAG_INCREASE_COUNT
import com.slack.circuit.internal.test.TestContentTags.TAG_LABEL
import com.slack.circuit.internal.test.TestContentTags.TAG_POP
import com.slack.circuit.internal.test.TestCountPresenter.RememberType
import com.slack.circuit.internal.test.TestScreen
import com.slack.circuit.internal.test.createTestCircuit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@Config(minSdk = 34)
@RunWith(AndroidJUnit4::class)
class GestureNavigationRetainedStateTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun retainedStateScopedToBackstackWithKeysAndBackSwipes() {
    retainedStateScopedToBackstack(true) {
      composeTestRule.activityRule.scenario.performBackSwipeGesture()
    }
  }

  @Test
  fun retainedStateScopedToBackstackWithoutKeysAndBackSwipes() {
    retainedStateScopedToBackstack(false) {
      composeTestRule.activityRule.scenario.performBackSwipeGesture()
    }
  }

  @Test
  fun retainedStateScopedToBackstackWithKeysAndBackPress() {
    retainedStateScopedToBackstack(true) {
      composeTestRule.onTopNavigationRecordNodeWithTag(TAG_POP).performClick()
    }
  }

  @Test
  fun retainedStateScopedToBackstackWithoutKeysAndBackPress() {
    retainedStateScopedToBackstack(false) {
      composeTestRule.onTopNavigationRecordNodeWithTag(TAG_POP).performClick()
    }
  }

  private fun retainedStateScopedToBackstack(useKeys: Boolean, pop: () -> Unit) {
    composeTestRule.run {
      val circuit = createTestCircuit(useKeys = useKeys, rememberType = RememberType.Retained)

      setContent {
        CircuitCompositionLocals(circuit) {
          val backstack = rememberSaveableBackStack { push(TestScreen.ScreenA) }
          val navigator =
            rememberCircuitNavigator(
              backstack = backstack,
              onRootPop = {}, // no-op for tests
            )
          NavigableCircuitContent(
            navigator = navigator,
            backstack = backstack,
            decoration = GestureNavigationDecoration(onBackInvoked = navigator::pop),
          )
        }
      }

      // Current: Screen A. Increase count to 1
      onTopNavigationRecordNodeWithTag(TAG_LABEL).assertTextEquals("A")
      onTopNavigationRecordNodeWithTag(TAG_COUNT).assertTextEquals("0")
      onTopNavigationRecordNodeWithTag(TAG_INCREASE_COUNT).performClick()
      onTopNavigationRecordNodeWithTag(TAG_COUNT).assertTextEquals("1")

      // Navigate to Screen B. Increase count to 1
      onTopNavigationRecordNodeWithTag(TAG_GO_NEXT).performClick()
      onTopNavigationRecordNodeWithTag(TAG_LABEL).assertTextEquals("B")
      onTopNavigationRecordNodeWithTag(TAG_COUNT).assertTextEquals("0")
      onTopNavigationRecordNodeWithTag(TAG_INCREASE_COUNT).performClick()
      onTopNavigationRecordNodeWithTag(TAG_COUNT).assertTextEquals("1")

      // Navigate to Screen C. Increase count to 1
      onTopNavigationRecordNodeWithTag(TAG_GO_NEXT).performClick()
      onTopNavigationRecordNodeWithTag(TAG_LABEL).assertTextEquals("C")
      onTopNavigationRecordNodeWithTag(TAG_COUNT).assertTextEquals("0")
      onTopNavigationRecordNodeWithTag(TAG_INCREASE_COUNT).performClick()
      onTopNavigationRecordNodeWithTag(TAG_COUNT).assertTextEquals("1")

      // Pop to Screen B. Increase count from 1 to 2.
      pop()
      onTopNavigationRecordNodeWithTag(TAG_LABEL).assertTextEquals("B")
      onTopNavigationRecordNodeWithTag(TAG_COUNT).assertTextEquals("1")
      onTopNavigationRecordNodeWithTag(TAG_INCREASE_COUNT).performClick()
      onTopNavigationRecordNodeWithTag(TAG_COUNT).assertTextEquals("2")

      // Navigate to Screen C. Assert that it's state was not retained
      onTopNavigationRecordNodeWithTag(TAG_GO_NEXT).performClick()
      onTopNavigationRecordNodeWithTag(TAG_LABEL).assertTextEquals("C")
      onTopNavigationRecordNodeWithTag(TAG_COUNT).assertTextEquals("0")

      // Pop to Screen B. Assert that it's state was retained
      pop()
      onTopNavigationRecordNodeWithTag(TAG_LABEL).assertTextEquals("B")
      onTopNavigationRecordNodeWithTag(TAG_COUNT).assertTextEquals("2")

      // Pop to Screen A. Assert that it's state was retained
      pop()
      onTopNavigationRecordNodeWithTag(TAG_LABEL).assertTextEquals("A")
      onTopNavigationRecordNodeWithTag(TAG_COUNT).assertTextEquals("1")

      // Navigate to Screen B. Assert that it's state was not retained
      onTopNavigationRecordNodeWithTag(TAG_GO_NEXT).performClick()
      onTopNavigationRecordNodeWithTag(TAG_LABEL).assertTextEquals("B")
      onTopNavigationRecordNodeWithTag(TAG_COUNT).assertTextEquals("0")
    }
  }
}
