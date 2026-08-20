package net.extrawdw.apps.notisync.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.data.activity.ActivityRepository
import net.extrawdw.apps.notisync.data.activity.ActivityTimelineAction
import net.extrawdw.apps.notisync.data.activity.ActivityTimelineState
import net.extrawdw.apps.notisync.data.activity.reduceActivityTimeline

/** Screen-level state holder for the durable Activity projection. */
internal class ActivityTimelineViewModel(
    repository: ActivityRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ActivityTimelineState())
    val state: StateFlow<ActivityTimelineState> = mutableState.asStateFlow()

    init {
        mutableState.value = reduceActivityTimeline(mutableState.value, ActivityTimelineAction.Started)
        viewModelScope.launch {
            try {
                repository.observeNewest().collect { events ->
                    mutableState.value = reduceActivityTimeline(
                        mutableState.value,
                        ActivityTimelineAction.Observed(events),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = reduceActivityTimeline(
                    mutableState.value,
                    ActivityTimelineAction.ObservationFailed,
                )
            }
        }
    }

    companion object {
        fun factory(repository: ActivityRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(ActivityTimelineViewModel::class.java)) {
                        "Unsupported Activity ViewModel class"
                    }
                    return ActivityTimelineViewModel(repository) as T
                }
            }
    }
}
