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
    // 两点已知的近似（都留给设备验证，不在这里预先修）：
    //   1. 尺寸取自**合着**的姿态，摊开后书会变大；若设备上摊开的书边缘戳不到，
    //      改成按摊开帧重算尺寸，而不是换成 mesh collider。
    //   2. 盒子以实体原点为心，不是以 bounds.center 为心；日志里带上 center 便于核对
    //      偏移是否大到需要处理。
    val bounds = entity.getVisualBounds(entity, recursive = true, enabledOnly = false)
    entity.components.set(
        CollisionComponent(
            collisionShape = listOf(ShapeResource.createBox(bounds.size)),
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

    Log.i(
        TAG,
        "book loaded, bounds=${bounds.size}, center=${bounds.center}, animated=${animator != null}",
    )
    return BookScene(entity, animator)
}
