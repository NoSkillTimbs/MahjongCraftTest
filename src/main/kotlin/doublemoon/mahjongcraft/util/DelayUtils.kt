package doublemoon.mahjongcraft.util

import doublemoon.mahjongcraft.scheduler.client.ClientScheduler
import doublemoon.mahjongcraft.scheduler.server.ServerScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReference
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment

/**
 * 用來在 [CoroutineScope] 之中,對 伺服器主線程 上使用 [delay]
 */
suspend fun delayOnServer(timeMills: Long) {
    var completed = false
    ServerScheduler.scheduleDelayAction(delay = timeMills) { completed = true }
    while (!completed) delay(10)
}

/**
 * 將 [action] 切換到伺服器主線程執行並等待完成。
 */
suspend fun <T> runOnServerThread(action: () -> T): T {
    val result = AtomicReference<Result<T>?>()
    ServerScheduler.scheduleDelayAction(delay = 0) {
        result.set(runCatching(action))
    }
    while (result.get() == null) {
        delay(1)
    }
    return result.get()!!.getOrThrow()
}

/**
 * 用來在 [CoroutineScope] 之中,對 客戶端主線程 上使用 [delay]
 */
@Environment(EnvType.CLIENT)
suspend fun delayOnClient(timeMills: Long) {
    var completed = false
    ClientScheduler.scheduleDelayAction(delay = timeMills) { completed = true }
    while (!completed) delay(10)
}