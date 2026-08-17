package com.illusion.bookofanswers.content

import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import java.io.Closeable

/**
 * 加载好的书本场景：实体本身，以及（可选的）内置动画控制器。
 *
 * [animator] 为 null 表示模型没有可用的内置动画，此时上层走「无动画模式」降级：
 * 书静置，触碰直接换答案。
 */
class BookScene(
    val entity: Entity,
    val animator: BookAnimator?,
) : Closeable {

    /**
     * 释放动画资源。
     *
     * [AnimationPlaybackController][com.pico.spatial.core.ecs.animation.AnimationPlaybackController]
     * 归 [BookAnimator] 所有，只由它 close —— 本类从不直接持有 controller，所以不存在重复
     * close。[BookAnimator.close] 自带 `closed` 闸，因此本方法多调一次也安全
     * （`stopAllAnimations` 本身幂等）。
     *
     * 实体的生命周期归把它加进 content 的那一层（Task 9）管，这里不销毁。
     */
    override fun close() {
        animator?.close()
        entity.stopAllAnimations()
    }
}

private const val TAG = "BookScene"
private const val BOOK_ASSET = "asset://book.usdz"

/**
 * 书本在 volume 内的落位与朝向。
 *
 * **这两个数目前是待定标的占位值，不是实测值。** 已验证的事实只有一条：模型默认朝向是
 * 书脊侧对观察者，所以必须转过来。具体角度和高度靠 Task 9 Step 6 对着设备截图调 ——
 * 不要试图解析地推导它们，看着不对就改这里。
 */
private val BOOK_POSITION = Vector3(0f, -0.1f, 0f)
private val BOOK_ORIENTATION = EulerAngles(-60f, 180f, 0f)

/**
 * 加载书本模型、落位、挂碰撞体与可交互组件，并（若模型带动画）建好 [BookAnimator]。
 *
 * 加载失败返回 `null`；模型没有动画资源时返回的 [BookScene] 的 `animator` 为 `null`，
 * 上层走无动画降级，**不是失败**。
 *
 * @param scope **必须是主线程受限的 scope**（Task 9 传 `rememberCoroutineScope()`）。
 *   [BookAnimator] 不做 dispatcher 切换，直接在这个 scope 上投递完成回调，而下游
 *   `BookState.phase` 是无同步的普通 `var` 且做 check-then-act。这条约束当前只有文档
 *   保证，没有运行时断言 —— 换调用点时务必确认。见 [BookAnimator] 的 KDoc。
 *
 * 本函数自身也必须在主线程上调用：用到的 ECS API 都标了 `@MainThread`。
 *
 * 模型不缩放：原生包围盒实测 0.03 × 0.21 × 0.29 m，已经是真实书本尺寸。
 */
suspend fun loadBookScene(scope: CoroutineScope): BookScene? {
    val entity = try {
        Entity.loadSuspend(uriString = BOOK_ASSET)
    } catch (c: CancellationException) {
        // 必须先于 Throwable 捕获并原样抛出：loadSuspend 是挂起调用，Task 9 的
        // DisposableEffect 可能在加载途中销毁 volume，取消就是从这里冒出来的。
        // 把它并进下面的错误分支，等于既往 logcat 里刷一条假的 E，又吞掉了协程
        // 机制赖以收尾的取消信号。
        throw c
    } catch (t: Throwable) {
        Log.e(TAG, "failed to load $BOOK_ASSET", t)
        return null
    }

    entity.components[TransformComponent::class.java]?.apply {
        setPosition(BOOK_POSITION)
        setEulerAngles(BOOK_ORIENTATION)
    }

    // 碰撞体用包围盒而非 mesh：文档明确 mesh collider 开销高得多，
    // 而书本外形本就接近盒子，精度差异用户不可感知。
    //
    // 尺寸取自**合着**的姿态，摊开后书会变大；若设备上摊开的书边缘戳不到，
    // 改成按摊开帧重算尺寸，而不是换成 mesh collider。
    val bounds = entity.getVisualBounds(entity, recursive = true, enabledOnly = false)

    // createBox 出来的盒子以实体原点为心，而 bounds 是实体局部坐标系下的（relativeTo =
    // entity），碰撞形状也在同一坐标系里，所以非零的 bounds.center 就是碰撞体相对可见
    // 网格的偏移量。用 offsetByTranslation 把它补回去 —— center 为零时该调用是恒等的，
    // 所以这不是过度设计。
    val boxShape = ShapeResource.createBox(bounds.size)
    val bookShape = try {
        boxShape.offsetByTranslation(bounds.center)
    } finally {
        // offsetByTranslation 返回的是**新**资源，原始 box 从此没有任何 entity 使用，
        // 不 close 就是文档里那条「局部作用域创建但无人使用」的泄漏。放 finally 里，
        // 抛异常的路径也照样释放。
        //
        // 这不会让 bookShape 失效：文档明确 close() 只是撤销持久化、在无人引用时立即
        // 释放，且「不影响已经在使用该资源的用户」。
        boxShape.close()
    }
    entity.components.set(
        CollisionComponent(
            collisionShape = listOf(bookShape),
            physicsMaterial = PhysicsMaterialResource(),
        )
    )
    entity.components.set(InteractableComponent())

    val meshEntity = entity.findSkinnedMeshEntity().firstOrNull()
    val resource = meshEntity?.getAnimationResources()?.firstOrNull()
    val animator = if (meshEntity != null && resource != null) {
        // 不调 setSpeed：按作者标定的速度播。快慢是设备上看着定的事（Task 10），
        // 现在没有依据，也不为此留旋钮。
        BookAnimator(meshEntity.playAnimation(resource), scope)
    } else {
        null
    }

    if (animator == null) {
        Log.w(TAG, "no animation resource on book model — falling back to still mode")
    } else {
        // 这里调 showClosed() 是安全的：它会作废在跑序列的未投递回调，但此刻刚构造完，
        // 什么都没在播，也不可能有触碰进来。播放途中调它会把状态机永久卡住，见
        // BookAnimator.showClosed 的 KDoc。
        animator.showClosed()
    }

    // center 留在日志里：碰撞体偏移已经补过了，但这条能告诉设备上的人当时的偏移到底
    // 是零还是真有值。
    Log.i(
        TAG,
        "book loaded, bounds=${bounds.size}, center=${bounds.center}, animated=${animator != null}",
    )
    return BookScene(entity, animator)
}
