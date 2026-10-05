package io.github.msecret.flymefreeform

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 手电筒开关。
 *
 * 用 `CameraManager.setTorchMode` 而不是 Camera2 的完整会话，理由：
 *
 * - **不需要 `CAMERA` 权限**。开关手电筒（torch mode）是相机 API 里唯一不要求相机权限的入口，
 *   否则只为一个小工具去申请相机权限，对这个项目来说太重；
 * - 不占用相机预览通路，退出后相机应用能立刻正常使用。
 *
 * ## 为什么要先读一次状态
 *
 * 手电筒归**系统**管：用户可以从快捷开关、下拉状态栏、甚至另一个应用把它打开或关掉。
 * 早先的实现只在进程里记一个 boolean，于是「系统里已经开着 → 我们以为关着 → 点一下变开」
 * 这种反着的操作就会发生，用户看到的就是「按了没反应，要按两次」。
 *
 * 现在每次点击都先向系统要一次**真实状态**（`registerTorchCallback` 注册后会立刻回调一次
 * 当前值），据此决定这次是开还是关，所以状态永远和系统一致。
 *
 * 读状态要把回调挂在一个 looper 上、再等它回来，因此**本方法会短暂阻塞**（最多
 * [STATE_WAIT_MS]）。调用方在 worker 线程上跑，别放主线程。
 */
object Flashlight {

    /** 最近一次切换后的状态，仅用于给用户回一句「已打开 / 已关闭」。 */
    @Volatile
    private var on: Boolean = false

    val isOn: Boolean get() = on

    /** 读系统真实状态时等多久。超时就用上次已知值，不至于让用户点一下卡住。 */
    private const val STATE_WAIT_MS = 400L

    /**
     * 回调要挂在一个 looper 上，而调用方（worker 线程）正在阻塞等结果，
     * 所以既不能用调用方的 looper，也不能用主线程——单开一条，避免互相等死。
     */
    private val stateThread: HandlerThread by lazy {
        HandlerThread("flyme-torch-state").apply { start() }
    }

    private val stateHandler: Handler by lazy { Handler(stateThread.looper) }

    /** 切换手电筒。返回 null 表示成功，否则是给用户看的失败原因。 */
    fun toggle(context: Context): String? {
        val manager =
            runCatching { context.getSystemService(CameraManager::class.java) }.getOrNull()
                ?: return "这台设备没有可用的相机服务"
        val cameraId = resolveCameraId(manager) ?: return "没有找到带闪光灯的摄像头"

        // 先问系统要真实状态：用户在别处开关过，这里必须跟上。
        val current = readState(manager, cameraId) ?: on
        val target = !current

        return runCatching {
            manager.setTorchMode(cameraId, target)
            on = target
            DebugLog.info("TOOL_FLASHLIGHT", if (target) "已打开" else "已关闭")
            null
        }.getOrElse { error ->
            DebugLog.warn("TOOL_FLASHLIGHT_FAILED", "cameraId=$cameraId on=$current -> $target", error)
            when (error) {
                // 相机会话被别的应用占着（比如相机 App 在前台），这时系统不允许改 torch。
                is CameraAccessException -> "闪光灯被占用（相机可能正在使用）"
                else -> "手电筒切换失败：${error.javaClass.simpleName}"
            }
        }
    }

    private fun resolveCameraId(manager: CameraManager): String? =
        runCatching {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()

    /**
     * 读这个摄像头的闪光灯当前是开还是关。
     *
     * `registerTorchCallback` 注册后系统会**立刻**回调一次现有状态，所以这里注册完等一下就拿到了。
     * 读不到（超时 / 设备不支持回调）时返回 null，调用方退回上次已知值。
     */
    private fun readState(manager: CameraManager, cameraId: String): Boolean? {
        val latch = CountDownLatch(1)
        val holder = AtomicReference<Boolean>()
        val callback =
            object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(id: String, enabled: Boolean) {
                    if (id != cameraId) return
                    holder.set(enabled)
                    latch.countDown()
                }
            }
        val registered =
            runCatching { manager.registerTorchCallback(callback, stateHandler) }.isSuccess
        if (!registered) return null
        return try {
            if (!latch.await(STATE_WAIT_MS, TimeUnit.MILLISECONDS)) null else holder.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            runCatching { manager.unregisterTorchCallback(callback) }
        }
    }
}
