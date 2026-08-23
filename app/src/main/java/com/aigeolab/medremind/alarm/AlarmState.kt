package com.aigeolab.medremind.alarm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which dose slot, if any, is ringing right now.
 *
 * In-process only: it exists so the full-screen alarm screen can close itself the moment the
 * service falls silent, rather than lingering on screen between escalation attempts.
 */
object AlarmState {

    private val _ringingGroupId = MutableStateFlow<Long?>(null)
    val ringingGroupId: StateFlow<Long?> = _ringingGroupId.asStateFlow()

    fun setRinging(groupId: Long?) {
        _ringingGroupId.value = groupId
    }
}
