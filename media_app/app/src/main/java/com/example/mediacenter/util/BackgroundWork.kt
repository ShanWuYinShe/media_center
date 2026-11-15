package com.example.mediacenter.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 进程级后台作用域:用于点击回调 / onDispose 这类既不能挂起、又不能阻塞主线程的持久化写入。
 *
 * 为什么需要:观看历史与断点续播的 SharedPreferences 读写包含 JSON 编解码,放在主线程会在
 * 低端 TV 盒子上造成掉帧;而 onDispose/onClick 没有可安全挂起的协程作用域
 * (rememberCoroutineScope 随页面离开被取消)。这里用应用级 IO 作用域兜底,
 * 页面退出后写入仍能完成。
 */
object BackgroundWork {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
