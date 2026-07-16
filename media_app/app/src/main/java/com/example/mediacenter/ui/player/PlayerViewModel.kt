package com.example.mediacenter.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.mediacenter.BuildConfig
import com.example.mediacenter.data.MediaApi
import com.example.mediacenter.data.MediaRepository
import com.example.mediacenter.data.PlaybackResult
import com.example.mediacenter.data.VideoSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerUiState(
    val playbackResult: PlaybackResult? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

class PlayerViewModel(
    private val videoId: String,
    private val repository: MediaRepository = MediaRepository(
        api = MediaApi(BuildConfig.MEDIA_SERVER_URL),
    ),
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init { resolvePlayback() }

    fun resolvePlayback() {
        viewModelScope.launch {
            _uiState.value = PlayerUiState(isLoading = true)
            try {
                val summary = VideoSummary(
                    id = videoId,
                    title = "",
                    description = "",
                    thumbnailUrl = "",
                    durationSeconds = 0,
                )
                val details = repository.details(summary)
                val result = repository.playbackOptions(details)
                _uiState.value = PlayerUiState(playbackResult = result, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = PlayerUiState(isLoading = false, error = e.message)
            }
        }
    }
}

class PlayerViewModelFactory(private val videoId: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return PlayerViewModel(videoId) as T
    }
}
