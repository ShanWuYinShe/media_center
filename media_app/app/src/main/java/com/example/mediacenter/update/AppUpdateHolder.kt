package com.example.mediacenter.update

import android.content.Context
import com.example.mediacenter.data.AppUpdateInfo
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.ui.update.UpdateDialogState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 进程级更新协调器:首页与设置页共用同一 [AppUpdateManager]、状态流与下载协程。
 *
 * 统一前的重复与缺陷:两个页面各自 new AppUpdateManager(实例级 Mutex 不互斥,
 * 并发下载同一版本会写坏文件——已由进程级 Mutex 兜底),各自持有下载状态——
 * 首页状态随 ViewModel 存活(可后台下载),设置页状态随页面销毁(离开即取消,
 * 半成品被清理)。统一到单例后:全 App 同时最多一个下载;任何页面发起、任何
 * 页面都能观察进度;离开发起页下载照常继续,失败弹窗在任何可见页面重现。
 *
 * downloadInBackground 语义:用户选"后台下载"后进度不再回写弹窗,完成仍拉起
 * 系统安装器,失败弹窗重现。
 */
object AppUpdateHolder {
    @Volatile private var appContext: Context? = null

    /** 由 Application.onCreate 注入,须先于任何更新流程调用。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val manager: AppUpdateManager by lazy {
        AppUpdateManager(
            requireNotNull(appContext) { "AppUpdateHolder.init must be called before first use" },
            MediaRepositoryHolder.updateApi,
        )
    }

    // 进程级应用作用域:下载/安装序列挂在这里,与任何页面生命周期解耦。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _updateState = MutableStateFlow<UpdateDialogState?>(null)
    val updateState: StateFlow<UpdateDialogState?> = _updateState.asStateFlow()

    @Volatile private var updateChecked = false
    @Volatile private var updateDownloading = false
    @Volatile private var updateDialogHiddenWhileDownloading = false

    /**
     * 发起一次检查(自动检查路径:首页进入时)。进程生命周期内只查一次,
     * 失败/取消后允许再次发起。返回是否真正发起。
     */
    fun requestCheck(): Boolean {
        if (updateChecked || updateDownloading) return false
        updateChecked = true
        scope.launch { checkSuspend() }
        return true
    }

    /**
     * 检查更新并同步返回结果;Available/Failed 同时写入 [updateState]
     * (Failed 不弹窗,由调用方决定如何反馈——设置页有明确反馈,首页自动检查静默)。
     */
    suspend fun checkSuspend(): UpdateCheckResult {
        if (updateDownloading) return UpdateCheckResult.NoUpdate
        updateChecked = true
        val result = manager.check()
        if (result is UpdateCheckResult.UpdateAvailable) {
            _updateState.value = UpdateDialogState.Available(result.update)
        } else if (result is UpdateCheckResult.Failed) {
            // 失败回滚:离线/服务器抖动常见,不能让本进程内更新检查永不重试
            updateChecked = false
        }
        return result
    }

    fun startUpdateDownload(info: AppUpdateInfo) {
        if (updateDownloading) return
        updateDownloading = true
        updateDialogHiddenWhileDownloading = false
        _updateState.value = UpdateDialogState.Downloading(info, 0f)
        scope.launch {
            when (val result = manager.download(info) { progress ->
                // 用户已选"后台下载"时不再回写弹窗状态,否则进度回调会把弹窗重新顶出
                if (!updateDialogHiddenWhileDownloading) {
                    _updateState.value = UpdateDialogState.Downloading(info, progress)
                }
            }) {
                is UpdateDownloadResult.Ready -> {
                    _updateState.value = null
                    when (val install = manager.launchInstaller(result.apk)) {
                        is InstallLaunchResult.NeedsPermission -> {
                            // 重置状态机:Failed 弹窗的"重试"不能被下载守卫吞掉
                            updateDownloading = false
                            updateChecked = false
                            manager.launchInstallPermissionSettings()
                            _updateState.value = UpdateDialogState.Failed(
                                result.update,
                                "请先允许安装未知应用，然后重新下载安装",
                            )
                        }
                        is InstallLaunchResult.Failed -> {
                            updateDownloading = false
                            updateChecked = false
                            _updateState.value = UpdateDialogState.Failed(result.update, install.message)
                        }
                        is InstallLaunchResult.Started -> {
                            // 用户取消系统安装器时保留重查/重装入口
                            updateDownloading = false
                            updateChecked = false
                        }
                    }
                }
                is UpdateDownloadResult.Failed -> {
                    updateDownloading = false
                    updateChecked = false
                    _updateState.value = UpdateDialogState.Failed(info, result.message)
                }
            }
        }
    }

    /**
     * 关闭更新弹窗。
     * 非 Downloading:清状态并允许再次检查。
     * Downloading 且 allowDuringDownload=true:仅隐藏弹窗,后台下载继续;
     *   完成后仍拉起安装器,失败弹窗重现。不重置下载守卫(任务还活着)。
     * Downloading 且不允许:保持阻塞行为。
     */
    fun dismissUpdate(allowDuringDownload: Boolean = false) {
        val current = _updateState.value
        if (current is UpdateDialogState.Downloading) {
            if (!allowDuringDownload) return
            updateDialogHiddenWhileDownloading = true
            _updateState.value = null
            return
        }
        _updateState.value = null
        updateDownloading = false
        updateChecked = false
    }
}
