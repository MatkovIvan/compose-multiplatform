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

package androidx.compose.ui.test

import org.junit.rules.TestRule

interface AnimationClockTestRule : TestRule {
    /**
     * The ambient animation clock that is provided at the root of the composition tree.
     */
    val clock: TestAnimationClock

    /**
     * Convenience property for calling [`clock.isPaused`][TestAnimationClock.isPaused]
     */
    val isPaused: Boolean

    /**
     * Convenience method for calling [`clock.pauseClock()`][TestAnimationClock.pauseClock]
     */
    fun pauseClock()

    /**
     * Convenience method for calling [`clock.resumeClock()`][TestAnimationClock.resumeClock]
     */
    fun resumeClock() = clock.resumeClock()

    /**
     * Convenience method for calling [`clock.advanceClock()`][TestAnimationClock.advanceClock]
     */
    fun advanceClock(milliseconds: Long) = clock.advanceClock(milliseconds)
}

expect fun createAnimationClockRule(): AnimationClockTestRule