package com.example.mediacenter.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.mediacenter.data.AppUpdateInfo
import com.example.mediacenter.data.ContentCategory
import com.example.mediacenter.data.MediaRepository
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.data.ProviderInfo
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.update.UpdateDialogState
import com.example.mediacenter.update.AppUpdateManager
import com.example.mediacenter.update.InstallLaunchResult
import com.example.mediacenter.update.UpdateCheckResult
import com.example.mediacenter.update.UpdateDownloadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val categories: List<ContentCategory> = emptyList(),
    val categoryVideos: Map<String, List<VideoSummary>> = emptyMap(),
    val providers: List<ProviderInfo> = emptyList(),
    val currentProvider: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
)

class HomeViewModel(
    private val repository: MediaRepository = MediaRepositoryHolder.repository,
    private val updateManager: AppUpdateManager? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // App 内更新:检查/下载状态放在 ViewModel——VM 随导航 back stack entry 存活,
    // 离开首页(点开视频/进设置)下载不中断、返回时进度与弹窗状态仍可恢复。
    private val _updateState = MutableStateFlow<UpdateDialogState?>(null)
    val updateState: StateFlow<UpdateDialogState?> = _updateState.asStateFlow()
    // VM 生命周期内只检查一次;下载失败/取消后允许再次检查
    private var updateChecked = false
    private var updateDownloading = false

    // 跟踪当前加载任务:切换 provider / 刷新时取消旧任务,
    // 避免慢的旧请求后完成覆盖新状态(旧 provider 内容配新 currentProvider)。
    private var loadJob: Job? = null

    init {
        loadHome()
        checkUpdate()
    }

    fun loadHome() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val providers = repository.providers()
                _uiState.value = _uiState.value.copy(
                    providers = providers,
                    currentProvider = repository.currentProvider,
                )
                val cats = repository.categories()
                // 并行拉取各分类第一页,避免分类多时首页加载时间线性增长。
                // 单个分类失败(超时/5xx)不拖垮整个首页:该分类显示空 + 其余正常展示,
                // 下拉/重进会整体刷新。
                val videos = coroutineScope {
                    cats.map { cat ->
                        async {
                            cat.id to runCatching { repository.catalogPage(cat.id).items.orEmpty() }
                                .getOrDefault(emptyList())
                        }
                    }.awaitAll().toMap()
                }
                _uiState.value = _uiState.value.copy(categories = cats, categoryVideos = videos, isLoading = false)
            } catch (e: Exception) {
                // 被取消的任务(CancellationException)不当作错误展示
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    /** 切换视频源后重新加载首页内容。 */
    fun selectProvider(provider: String) {
        repository.setProvider(provider)
        loadHome()
    }

    // ── App 内更新 ──

    fun checkUpdate() {
        val manager = updateManager ?: return
        if (updateChecked || updateDownloading) return
        updateChecked = true
        viewModelScope.launch {
            when (val result = manager.check()) {
                is UpdateCheckResult.UpdateAvailable -> {
                    _updateState.value = UpdateDialogState.Available(result.update)
                }
                else -> Unit
            }
        }
    }

    fun startUpdateDownload(info: AppUpdateInfo) {
        val manager = updateManager ?: return
        if (updateDownloading) return
        updateDownloading = true
        _updateState.value = UpdateDialogState.Downloading(info, 0f)
        viewModelScope.launch {
            when (val result = manager.download(info) { progress ->
                _updateState.value = UpdateDialogState.Downloading(info, progress)
            }) {
                is UpdateDownloadResult.Ready -> {
                    _updateState.value = null
                    when (val install = manager.launchInstaller(result.apk)) {
                        is InstallLaunchResult.NeedsPermission -> {
                            // Android 8+ 未开"安装未知应用"权限:引导去系统设置,返回后提示重试。
                            manager.launchInstallPermissionSettings()
                            _updateState.value = UpdateDialogState.Failed(
                                result.update,
                                "请先允许安装未知应用，然后重新下载安装",
                            )
                        }
                        is InstallLaunchResult.Failed -> {
                            _updateState.value = UpdateDialogState.Failed(result.update, install.message)
                        }
                        is InstallLaunchResult.Started -> Unit
                    }
                }
                is UpdateDownloadResult.Failed -> {
                    // 失败后可再次检查/重试
                    updateDownloading = false
                    updateChecked = false
                    _updateState.value = UpdateDialogState.Failed(info, result.message)
                }
            }
        }
    }

    fun dismissUpdate() {
        // 下载中不允许关闭,避免任务与 UI 状态脱节(弹窗侧同样约束)
        if (_updateState.value is UpdateDialogState.Downloading) return
        _updateState.value = null
        updateDownloading = false
        updateChecked = false
    }
}

class HomeViewModelFactory(
    private val updateManager: AppUpdateManager,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HomeViewModel(
            updateManager = updateManager,
        ) as T
    }
}
