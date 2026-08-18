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

    private var closed = false

    /**
     * 释放动画资源**并销毁实体**。
     *
     * [AnimationPlaybackController][com.pico.spatial.core.ecs.animation.AnimationPlaybackController]
     * 归 [BookAnimator] 所有，只由它 close —— 本类从不直接持有 controller，所以不存在重复
     * close。（`stopAllAnimations` 大概推测是幂等的，但 api-reference 只写了「停止该实体上所有
     * 正在播放的动画」，没有对重复调用表态，所以别把幂等当成有据可查的事实。）
     *
     * **顺序不能颠倒：先 close animator，再 destroy 实体。** animator 持有的 controller 来自
     * `meshEntity.playAnimation(...)`，而 meshEntity 是本实体的子节点 —— `destroy(recursively =
     * true)` 会把它一起销毁，之后再 `controller.close()` 就是对已销毁的宿主动手。
     *
     * 实体的销毁必须在这里做。曾经的注释把它推给「把实体加进 content 的那一层」，但没有任何
     * 一层真的调过 `Entity.destroy()`；而迟到加载路径上实体根本没进过 content，连容器拆除都
     * 兜不到它 —— 那就是一整个 ~4 MB 模型的泄漏。
     *
     * `closed` 闸保证重复 close 安全：`destroy` 对已销毁实体只会返回 false，但也没必要去赌，
     * 而且这条闸让本方法维持了原先「多调一次也安全」的契约。
     */
    override fun close() {
        if (closed) return
        closed = true
        animator?.close()
        entity.stopAllAnimations()
        // destroy(recursively = true) 是默认值，写出来是为了让「连子节点一起收」这件事显式。
        val destroyed = entity.destroy(recursively = true)
        if (!destroyed) Log.w(TAG, "Entity.destroy() returned false for the book entity")
    }
}

private const val TAG = "BookScene"
private const val BOOK_ASSET = "asset://book.usdz"

/**
 * 书本**视觉中心**在容器内的落点。设备定标值，改它只影响书摆在哪。
 *
 * 注意这不是实体的 position —— 模型原点不在视觉中心上，且中心还随开合移动，
 * 实体位置要按当前开合程度把偏移补回去，见 [poseFor]。
 */
private val BOOK_CENTER = Vector3(0f, -0.2f, 0.3f)

/**
 * 模型缩放。
 *
 * 原始模型合上时是 0.423 × 0.084 × 0.593 m（离线用 UsdSkel 解算蒙皮后顶点量的），
 * 按书脊长 0.593 → 0.29 定出 0.489，缩放后是 0.207 × 0.041 × 0.29 m，真实书本尺寸。
 */
private const val BOOK_SCALE = 0.489f

/** 绕 Y 轴的朝向。0 表示不做额外旋转；书面朝上，靠这个值转到正对使用者。 */
private const val BOOK_YAW = 0f

/**
 * 合上与摊开时，网格视觉中心相对实体原点的偏移（**模型自身单位**，未乘 [BOOK_SCALE]）。
 *
 * 离线实测（`UsdSkel` 解算蒙皮后顶点，取包围盒中心）：
 *
 * | 状态 | 帧 | 中心 | 尺寸 |
 * |---|---|---|---|
 * | 合上 | 65      | (0.0027, 0.0441, 0) | 0.423 × 0.084 × 0.593 |
 * | 摊开 | 161–490 | (−0.2394, 0.0372, 0) | 0.907 × 0.071 × 0.593 |
 *
 * 关键是 x：书摊开时中心**横移了 0.24（模型单位）**，因为封面绕书脊转 90° 把整体甩向
 * 一侧。不补偿的话书会在开合过程中明显横着滑走。
 *
 * 两个姿态在 y 方向都很薄（0.084 / 0.071），也就是说这本书**本来就是躺着开合的**，
 * 不需要像上一个模型那样用 roll 插值把它按平 —— 那套补偿已经整段删掉。
 *
 * 为什么必须离线量：运行时 `getVisualBounds()` 不反映骨骼形变，播到哪一帧都返回同一组数。
 */
private val CENTER_CLOSED = Vector3(0.0027f, 0.0441f, 0f)
private val CENTER_OPEN = Vector3(-0.2394f, 0.0372f, 0f)

/**
 * 触碰区的尺寸与中心偏移（米，实体局部坐标系，已含 [BOOK_SCALE]）。
 *
 * 碰撞体只跟随实体变换、不跟随骨骼动画，所以它必须是一个**同时覆盖合上与摊开两个姿态**
 * 的静态盒子。按两个姿态的包围盒求并集算出来的：
 *
 * | 轴 | 并集区间 | 尺寸 | 中心 |
 * |---|---|---|---|
 * | x | [−0.3388, 0.1047] | 0.444 | −0.117 |
 * | y | [ 0.0008, 0.0421] | 0.041 |  0.021 |
 * | z | [−0.1450, 0.1450] | 0.290 |  0.000 |
 *
 * y 方向放宽到 0.10：书本身只有 4 cm 厚，但指尖是从上方靠近的，留一点余量让接触判定
 * 早一点成立，手感上不会「明明碰到了却没反应」。x/z 只留很小余量 —— 这是**指尖直接触碰**
 * 的判定区，做太大就变成戳空气也触发。
 *
 * 上一个模型必须用正方体，因为实体 roll 在开合过程中会转 90°、非立方盒子会歪；这本书
 * 躺着开合、实体不旋转，所以可以贴合真实形状。
 */
private val TAP_BOX_SIZE = Vector3(0.47f, 0.10f, 0.32f)
private val TAP_BOX_CENTER = Vector3(-0.117f, 0.021f, 0f)

/**
 * 按开合进度算出实体应有的位置与朝向。`openness` 0 = 合上，1 = 摊开。
 *
 * 位置 = 目标中心 − 缩放后的当前中心偏移，于是书的视觉中心始终钉在 [BOOK_CENTER]，
 * 看起来就是「原地躺着打开」。
 */
private fun poseFor(openness: Float): Pair<Vector3, EulerAngles> {
    val t = openness.coerceIn(0f, 1f)
    val ox = (CENTER_CLOSED.x + (CENTER_OPEN.x - CENTER_CLOSED.x) * t) * BOOK_SCALE
    val oy = (CENTER_CLOSED.y + (CENTER_OPEN.y - CENTER_CLOSED.y) * t) * BOOK_SCALE
    val oz = (CENTER_CLOSED.z + (CENTER_OPEN.z - CENTER_CLOSED.z) * t) * BOOK_SCALE
    val position = Vector3(
        BOOK_CENTER.x - ox,
        BOOK_CENTER.y - oy,
        BOOK_CENTER.z - oz,
    )
    return position to EulerAngles(0f, BOOK_YAW, 0f)
}

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
        setScaleVector(Vector3(BOOK_SCALE, BOOK_SCALE, BOOK_SCALE))
        val (p0, e0) = poseFor(0f)
        setPosition(p0)
        setEulerAngles(e0)
    }

    // 碰撞体挂在书实体上，形状是**正方体**。
    //
    // 为什么是正方体而不是按包围盒量：碰撞体只跟随实体变换，不跟随骨骼动画。书的「摊平」
    // 是动画里的骨骼形变，碰撞体感知不到，所以按合上姿态量出来的薄板（0.028 × 0.205 ×
    // 0.289）在书摊开后仍是一块立着的窄板 —— 实机上只有书正中一条竖缝点得到。而且实体
    // roll 在开合过程中从 -90° 转到 0°，非立方的盒子转过去就歪了。正方体旋转不变。
    //
    // 边长按离线量出的摊开尺寸定：摊开后是 0.441 × 0.029 × 0.289，取 0.48 留一点余量。
    // 它是隐形的，场景里也没有别的可点目标，宁可大一点 —— 点不中比太灵敏难受得多。
    //
    // 也试过把碰撞挪到一个独立的空实体上，实机点不到：裸 Entity 没有 ModelComponent，
    // 输入命中似乎要求实体有可渲染网格。书本身有网格，命中是已验证的。
    val rawShape = ShapeResource.createBox(TAP_BOX_SIZE)
    val tapShape = try {
        rawShape.offsetByTranslation(TAP_BOX_CENTER)
    } finally {
        // offsetByTranslation 返回新资源，原始盒子从此无人使用，不 close 就是泄漏。
        rawShape.close()
    }
    entity.components.set(
        CollisionComponent(
            collisionShape = listOf(tapShape),
            physicsMaterial = PhysicsMaterialResource(),
        )
    )
    entity.components.set(InteractableComponent())

    val meshEntity = entity.findSkinnedMeshEntity().firstOrNull()
    val resource = meshEntity?.getAnimationResources()?.firstOrNull()
    val animator = if (meshEntity != null && resource != null) {
        // 不调 setSpeed：按作者标定的速度播。快慢是设备上看着定的事（Task 10），
        // 现在没有依据，也不为此留旋钮。
        BookAnimator(meshEntity.playAnimation(resource), scope) { openness ->
            // 姿态换算归本文件所有：BookAnimator 只负责播放与报进度，不碰实体。
            val (pos, euler) = poseFor(openness)
            entity.components[TransformComponent::class.java]?.apply {
                setPosition(pos)
                setEulerAngles(euler)
            }
        }
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

    // 触碰区尺寸留在日志里：它是纯手调的值，实机上若「点不中 / 太灵敏」，这条能直接
    // 对上当时装的是哪一组。animated=false 说明降级成了无动画模式。
    Log.i(
        TAG,
        "book loaded, animated=${animator != null}, " +
            "tapBox=$TAP_BOX_SIZE @$TAP_BOX_CENTER, center=$BOOK_CENTER",
    )
    return BookScene(entity, animator)
}
