package tech.illusion.bookofanswers.content

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
     * 全部归 [BookAnimator] 所有（每个蒙皮网格一个），只由它 close —— 本类从不直接持有
     * controller，所以不存在重复
     * close。（`stopAllAnimations` 大概推测是幂等的，但 api-reference 只写了「停止该实体上所有
     * 正在播放的动画」，没有对重复调用表态，所以别把幂等当成有据可查的事实。）
     *
     * **顺序不能颠倒：先 close animator，再 destroy 实体。** animator 持有的各个 controller 来自
     * `meshEntity.playAnimation(...)`，而这些 meshEntity 都是本实体的子节点 —— `destroy(recursively =
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
 * 这个模型合上时实测 0.0279 × 0.2052 × 0.2893 m —— **本来就是真实书本尺寸，不要缩放。**
 * 保留这个常量而不是直接删掉，是因为下面的中心偏移要乘它；换模型时改这一个值即可。
 */
private const val BOOK_SCALE = 1f

/** 绕 Y 轴的朝向。0 = 书脊front-back、页面左右摊开，正对使用者时读起来最自然。 */
private const val BOOK_YAW = 0f

/**
 * 合上与摊开时，网格视觉中心相对实体原点的偏移（**模型自身单位**，未乘 [BOOK_SCALE]）。
 *
 * 离线实测（`UsdSkel` 解算蒙皮后顶点，取包围盒中心；metersPerUnit = 0.01）：
 *
 * | 状态 | 帧 | 尺寸 | 中心 |
 * |---|---|---|---|
 * | 合上 | 5–100   | 0.0279 × 0.2052 × 0.2893 | (0, 0.1011, 0) |
 * | 摊开 | 190–302 | 0.4409 × 0.0291 × 0.2893 | (0, 0.0131, 0) |
 *
 * 关键是 y：实体原点在书**底**，而书摊开后整体塌下去 0.088 m。x/z 两个姿态都是 0，
 * 所以只有 y 需要补偿 —— 但它会被 [poseFor] 里的 roll 旋转带到别的轴上去。
 *
 * 为什么必须离线量：运行时 `getVisualBounds()` 不反映骨骼形变，播到哪一帧都返回同一组数。
 */
private val CENTER_CLOSED = Vector3(0f, 0.1011f, 0f)
private val CENTER_OPEN = Vector3(0f, 0.0131f, 0f)

/**
 * 实体 roll 的两端（度）。**这是让「合上也平放、摊开也平放」成立的唯一办法。**
 *
 * 这个模型的动画自带 90° 的姿态变化：在实体 roll = 0 时，合上的书是**竖着立起来的**
 * （y 高 0.205、x 薄 0.028），而摊开的书是**平的**（y 薄 0.029、x 宽 0.441）。于是：
 *
 * - 恒定 roll = 0   → 摊开好看，合上是立着的（用户明确否掉了「竖起来」）
 * - 恒定 roll = ±90 → 合上好看，摊开变成一片竖立的薄片（这是真机上踩过的坑）
 *
 * 两端拉不到一起，所以只能跟着开合进度**插值**：合上时 +90 把书按平，摊开时回到 0
 * 让动画自己的平躺姿态生效。
 *
 * 取 +90 而不是 −90：模型封面法向是局部 +X，`Rz(+90) · (1,0,0) = (0,1,0)`，
 * 合上时封面朝上 —— 静置态是用户看得最久的一帧，应该看到封面而不是封底。
 */
private const val ROLL_CLOSED = 90f
private const val ROLL_OPEN = 0f

/**
 * 触碰区的尺寸与中心偏移（米，实体局部坐标系，已含 [BOOK_SCALE]）。
 *
 * 碰撞体只跟随实体变换、不跟随骨骼形变，所以必须是一个同时覆盖两个姿态的静态盒子。
 * 按两个姿态在**实体局部空间**的包围盒求并集：
 *
 * | 轴 | 并集区间 | 尺寸 | 中心 |
 * |---|---|---|---|
 * | x | [−0.2205, 0.2205] | 0.441 | 0.000 |
 * | y | [−0.0015, 0.2037] | 0.205 | 0.101 |
 * | z | [−0.1446, 0.1446] | 0.289 | 0.000 |
 *
 * **已知取舍：这个盒子会跟着实体 roll 一起转。** 合上时实体 roll = +90，盒子的 x/y 互换，
 * 于是它在世界 y 方向撑到 0.46 m —— 平放的合上书本上下各多出约 0.23 m 的空盒子。射线与
 * 捏合无所谓，但指尖触碰会偏灵敏（手伸过去可能在碰到书之前就已经在盒子里了）。
 * 上一个模型不需要 roll 插值，所以能用贴合形状的紧盒子；这个模型换不来。真机验收时若
 * 指尖触碰确实偏灵敏，这里是第一个要动的地方。
 */
private val TAP_BOX_SIZE = Vector3(0.46f, 0.22f, 0.31f)
private val TAP_BOX_CENTER = Vector3(0f, 0.1011f, 0f)

/**
 * 按开合进度算出实体应有的位置与朝向。`openness` 0 = 合上，1 = 摊开。
 *
 * 位置的算法是「让视觉中心始终钉在 [BOOK_CENTER]」：
 *
 * ```
 * position = BOOK_CENTER − R · (缩放后的中心偏移)
 * ```
 *
 * `R` 必须参与进来，不能像上一个模型那样直接减偏移量 —— 因为这里实体在开合过程中
 * 真的在转（[ROLL_CLOSED] → [ROLL_OPEN]），偏移向量会跟着转到别的轴上去。
 *
 * `EulerAngles(pitch, yaw, roll)` 是**外旋 ZXY**，即 `M = Ry(yaw) · Rx(pitch) · Rz(roll)`。
 * 本项目 pitch 恒为 0，于是 `M = Ry(yaw) · Rz(roll)`，作用在 `(0, cy, 0)` 上展开得到
 * 下面三行 —— 不是眼估，是把矩阵乘开：
 *
 * ```
 * Rz(θ) · (0, cy, 0)      = (−cy·sinθ,  cy·cosθ, 0)
 * Ry(ψ) · (vx, vy, 0)     = ( vx·cosψ,  vy,      −vx·sinψ)
 * ```
 */
private fun poseFor(openness: Float): Pair<Vector3, EulerAngles> {
    val t = openness.coerceIn(0f, 1f)

    // 中心偏移：x/z 两端都是 0，但仍然按通式插值，换模型时不必回来补这两行。
    val cx = (CENTER_CLOSED.x + (CENTER_OPEN.x - CENTER_CLOSED.x) * t) * BOOK_SCALE
    val cy = (CENTER_CLOSED.y + (CENTER_OPEN.y - CENTER_CLOSED.y) * t) * BOOK_SCALE
    val cz = (CENTER_CLOSED.z + (CENTER_OPEN.z - CENTER_CLOSED.z) * t) * BOOK_SCALE

    val roll = ROLL_CLOSED + (ROLL_OPEN - ROLL_CLOSED) * t
    val rollRad = Math.toRadians(roll.toDouble())
    val yawRad = Math.toRadians(BOOK_YAW.toDouble())
    val sinR = kotlin.math.sin(rollRad).toFloat()
    val cosR = kotlin.math.cos(rollRad).toFloat()
    val sinY = kotlin.math.sin(yawRad).toFloat()
    val cosY = kotlin.math.cos(yawRad).toFloat()

    // Rz(roll) 作用在 (cx, cy, cz) 上
    val vx = cx * cosR - cy * sinR
    val vy = cx * sinR + cy * cosR
    val vz = cz
    // 再叠 Ry(yaw)
    val ox = vx * cosY + vz * sinY
    val oy = vy
    val oz = -vx * sinY + vz * cosY

    val position = Vector3(
        BOOK_CENTER.x - ox,
        BOOK_CENTER.y - oy,
        BOOK_CENTER.z - oz,
    )
    return position to EulerAngles(0f, BOOK_YAW, roll)
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

    // 碰撞体挂在书实体上。形状与取舍见 TAP_BOX_SIZE 的 KDoc。
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

    // 把**所有**蒙皮网格都驱动起来。这个模型只有一个，但上一个模型有三个，只播第一个
    // 会导致只有一层翻出去、其余仍是合着的厚块。按列表处理，换模型时不会再踩。
    val skinnedMeshes = entity.findSkinnedMeshEntity()
    // 不调 setSpeed：按作者标定的速度播。快慢是设备上看着定的事（Task 10），
    // 现在没有依据，也不为此留旋钮。
    val controllers = skinnedMeshes.mapNotNull { mesh ->
        mesh.getAnimationResources().firstOrNull()?.let { mesh.playAnimation(it) }
    }
    val animator = if (controllers.isNotEmpty()) {
        BookAnimator(controllers, scope) { openness ->
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
        Log.w(
            TAG,
            "no animation resource on book model (skinnedMeshes=${skinnedMeshes.size}) " +
                "— falling back to still mode",
        )
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
            "skinnedMeshes=${skinnedMeshes.size}, controllers=${controllers.size}, " +
            "tapBox=$TAP_BOX_SIZE @$TAP_BOX_CENTER, center=$BOOK_CENTER",
    )
    return BookScene(entity, animator)
}
