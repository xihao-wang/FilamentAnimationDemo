# Filament Animation Demo

一个使用 Kotlin、Jetpack Compose 与 Google Filament 实现的 Android 3D 动画技术作业。

应用加载 Warrior GLB 角色，循环播放“Walk → Sword Attack → Walk”动画，并通过 crossfade 平滑过渡。Bonus 部分使用 ARCore 检测现实环境中的水平面，用户点击平面后可将角色放置到对应 Anchor 上。

## 已实现功能

- 使用 Filament `gltfio` 加载并渲染 `warrior.glb`
- 按动画名称查找 Walk 和 Sword Attack，不依赖固定 index
- Walk 播放约 3 秒后切换到完整的 Sword Attack，再返回 Walk
- Walk 与 Attack 之间进行 crossfade
- 根据模型 bounding box 自动调整普通 Viewer 的取景
- 支持普通 Viewer 的触摸旋转与缩放
- 白色背景、逐帧清屏及多方向照明
- 普通 3D Viewer 与 AR 模式相互独立
- ARCore 摄像头画面与水平面检测
- 点击有效平面创建 Anchor，再次点击可重新放置角色
- AR 模式复用相同的动画状态机和 GLB 资源
- AR 不可用时安全返回普通 Viewer
- Camera、Tracking Failure 和 Plane 数量诊断信息

## 技术栈

- Kotlin / Jetpack Compose
- Google Filament 1.75.1
- ARCore 1.56.0
- GLB / glTF 2.0
- Gradle Kotlin DSL

## AI Coding Prompt 概览

以下内容是本项目从 0 到 1 开发过程中提交给 AI Coding Agent 的 Prompt 概括。为便于作业评审，保留每个阶段的目标、约束和验证要求，省略重复上下文与具体调试对话。

### 1. 检查项目并搭建最小 Filament 场景

> 检查现有 Android 项目结构、Gradle、Kotlin 和 Android SDK 配置，选择兼容的 Filament 依赖。设计一个简单的 GLB 渲染架构，在不添加 ARCore 和不进行无关重构的前提下，实现最小可运行的 Filament Viewer，并运行 `:app:assembleDebug` 验证。

### 2. 寻找并准备人物模型

> 寻找公开可靠、许可证允许的人物 GLB/glTF 模型。角色最好持剑，并且至少包含 Walk 和 Sword Attack 动画。说明模型来源、License、动画列表、Filament 兼容性及是否需要转换；确认前不要删除已有测试模型。

最终采用 Quaternius LowPoly RPG Characters 中的 Warrior，并保留 `fox.glb` 作为备用资源。

### 3. 加载 Warrior 并验证 Walk

> 将 Viewer 的模型切换为 `warrior.glb`，列出所有 animation clip 的名称和 index。通过名称查找 Walk，优先精确匹配 `CharacterArmature|Walk`，不要硬编码 index。循环播放 Walk，每帧调用 `applyAnimation()` 和 `updateBoneMatrices()`，并根据 bounding box 自动取景，保持触摸控制不变。

### 4. 实现 Walk 与 Sword Attack 状态机

> 使用明确的 `WALK` 和 `ATTACK` 状态。启动后先播放 Walk 约 3 秒，再从头完整播放一次 Sword Attack，然后返回 Walk 并循环。通过动画名称查找 clip；Walk 时间使用 duration modulo，Attack 不循环。记录当前状态、状态开始时间和动画时间，并在状态切换时输出日志。

### 5. 添加动画 Crossfade

> 在不改变既有状态机行为的前提下，为 Walk 与 Sword Attack 的双向切换加入短时间 crossfade，使动作衔接更自然，同时继续正确更新骨骼矩阵。

### 6. 修复普通 Viewer 的画面问题

> 将背景改为白色；确保每帧正确清理颜色缓冲，消除历史帧残影；改善角色照明，使人物从正面、侧面和背面观察时都能看到盔甲与剑的细节。优先增加环境光/间接光，再使用合理的 key、fill 和 rim light。

### 7. 设计并实现独立 AR Bonus

> 保留已经完成的普通 Filament Viewer，在独立 AR 模式中显示摄像头、检测水平面、响应用户点击、创建 Anchor，并把 Warrior 放置到 Anchor 世界坐标。再次点击其他平面时允许重新放置。复用现有 GLB 和动画状态机，不修改模型，不重新实现 Walk → Sword Attack 逻辑。

### 8. 处理 ARCore 不可用的情况

> 当设备不支持 AR、ARCore 服务不可用、相机不可用或权限被拒绝时，安全返回普通动画 Viewer，不能崩溃或直接回到桌面。检查 Activity 和 Filament 资源生命周期，避免重复释放原生资源。

### 9. 增加 AR Plane 诊断信息

> 暂不绘制平面网格，只增加最小诊断：显示并记录 Camera trackingState、trackingFailureReason、Plane 总数、TRACKING 数量、水平向上数量和可用水平面数量。日志只在状态或数量变化时输出，并检查 PlaneFindingMode、Session 生命周期、`session.update()` 与 `setDisplayGeometry()`。

### 10. 真机定位 Plane Detection 较慢的问题

> 安装 Debug APK 到真实设备并通过 adb logcat 观察 Camera 和 Plane 状态变化。不要放宽错误的 HitTest 条件；如果应用过滤没有问题，明确区分 ARCore 环境识别瓶颈与代码问题。根据证据做最小优化，并保留真实 Plane Anchor 放置要求。

根据真机日志，在保持 `PlaneFindingMode.HORIZONTAL` 的同时启用 `FocusMode.AUTO`，改善视觉特征获取。

### 11. 修复 Anchor 创建后模型不可见

> 沿着 Anchor 创建、Anchor 矩阵传递、模型加入 Scene、模型变换和 AR Camera 矩阵链路排查。保留 HitTest、Anchor、动画和普通 Viewer，只修复导致已放置模型不可见的明确问题。

最终禁用了 AR `ModelViewer` 的默认 orbit camera manipulator，避免其在 `render()` 时覆盖 ARCore Camera pose。

### 12. 构建、真机验证与 Git 交付

> 每个阶段运行 `:app:assembleDebug`。可用时安装到 adb 设备，通过日志和实际画面验证动画、Plane、Anchor 与 Warrior。检查 Git 状态和文件大小，只提交任务相关文件，并推送到远程仓库。

## 构建与运行

```bash
./gradlew :app:assembleDebug
```

普通 Viewer 可在 Android Emulator 或实体设备运行。AR Mode 需要支持 ARCore 的 Android 实体设备、Google Play Services for AR 和相机权限。

## 模型资源

Warrior 模型来源与授权信息见：

- `app/src/main/assets/models/ATTRIBUTION.md`
- `app/src/main/assets/models/WARRIOR_LICENSE.txt`

