// Copyright (C) 2022 Slack Technologies, LLC
// SPDX-License-Identifier: Apache-2.0
package com.slack.circuit.retained

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * Remember the value produced by [init].
 *
 * It behaves similarly to [remember], but the stored value will survive configuration changes, such
 * as a screen rotation.
 *
 * You can use it with a value stored inside [androidx.compose.runtime.mutableStateOf].
 *
 * This differs from `rememberSaveable` by not being tied to Android bundles or parcelable. You
 * should take care to ensure that the state computed by [init] does not capture anything that is
 * not save to persist across reconfiguration, such as Navigators. The same caveats of
 * `rememberSaveable` also still apply (i.e. do not retain Android Contexts, Views, etc).
 *
 * However, it does not participate in saved instance state either, so care should be taken to
 * choose the right retention mechanism for your use case. Consider the below two examples.
 *
 * The first case will retain `state` across configuration changes but will _not_ survive process
 * death.
 *
 * ```kotlin
 * @Composable
 * override fun present(): CounterState {
 *   var state by rememberRetained { mutableStateOf(CounterState(0)) }
 *
 *   return CounterState(count) { event ->
 *     when (event) {
 *       is CounterEvent.Increment -> state = state.copy(count = state.count + 1)
 *       is CounterEvent.Decrement -> state = state.copy(count = state.count - 1)
 *     }
 *   }
 * }
 * ```
 *
 * This second case will retain `count` across configuration changes _and_ survive process death.
 * However, it only works with primitives or `Parcelable` state types.
 *
 * ```kotlin
 * @Composable
 * override fun present(): CounterState {
 *   var count by rememberSaveable { mutableStateOf(0) }
 *
 *   return CounterState(count) { event ->
 *     when (event) {
 *       is CounterEvent.Increment -> state = count++
 *       is CounterEvent.Decrement -> state = count--
 *     }
 *   }
 * }
 * ```
 *
 * @param inputs A set of inputs such that, when any of them have changed, will cause the state to
 *   reset and [init] to be rerun
 * @param key An optional key to be used as a key for the saved value. If not provided we use the
 *   automatically generated by the Compose runtime which is unique for the every exact code
 *   location in the composition tree
 * @param init A factory function to create the initial value of this state
 */
@Composable
public fun <T : Any> rememberRetained(vararg inputs: Any?, key: String? = null, init: () -> T): T {
  val registry = LocalRetainedStateRegistryOwner.current
  // Short-circuit no-ops
  if (registry === NoOpRetainedStateRegistry) {
    return when (inputs.size) {
      0 -> remember(init)
      1 -> remember(inputs[0], init)
      2 -> remember(inputs[0], inputs[1], init)
      3 -> remember(inputs[0], inputs[1], inputs[2], init)
      else -> remember(keys = inputs, init)
    }
  }

  // key is the one provided by the user or the one generated by the compose runtime
  val finalKey =
    if (!key.isNullOrEmpty()) {
      key
    } else {
      currentCompositeKeyHash.toString(MaxSupportedRadix)
    }

  // value is restored using the registry or created via [init] lambda
  val value =
    remember(*inputs) {
      val restored = registry.consumeValue(finalKey)
      restored ?: init()
    }

  // we want to use the latest instances of value in the valueProvider lambda
  // without restarting DisposableEffect as it would cause re-registering the provider in
  // the different order. so we use rememberUpdatedState.
  val valueState = rememberUpdatedState(value)

  val canRetain = rememberCanRetainChecker()
  remember(registry, finalKey) {
    val entry = registry.registerValue(finalKey) { valueState.value }
    object : RememberObserver {
      override fun onAbandoned() = unregisterIfNotRetainable()

      override fun onForgotten() = unregisterIfNotRetainable()

      override fun onRemembered() {
        // Do nothing
      }

      fun unregisterIfNotRetainable() {
        if (!canRetain()) {
          entry.unregister()
        }
      }
    }
  }
  @Suppress("UNCHECKED_CAST") return value as T
}

/** The maximum radix available for conversion to and from strings. */
private const val MaxSupportedRadix = 36
