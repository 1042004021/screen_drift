# 整屏惯性漂移 · Screen Drift

基于 Root 的 Android 整屏惯性漂移实验项目。通过传感器驱动显示投影偏移，实现手机移动时画面随惯性漂移的效果。**独立 Root 模块，无 APK，不依赖 Xposed，不与魔法手指合并。**

## Beta 下载与兼容限制

[下载 v0.1.0-beta](https://github.com/1042004021/screen_drift/releases/tag/v0.1.0-beta)：按Root框架选择 `screen-drift-kernelsu-0.1.0-beta.zip`、`screen-drift-magisk-0.1.0-beta.zip` 或 `screen-drift-apatch-0.1.0-beta.zip`。不要刷入 GitHub 自动生成的 Source code ZIP。附件 `SHA256SUMS` 可校验安装包。三包模块ID均为 `screen_drift`，同一手机只选一种。

**仅针对小米15、SM8750、Android 16 / API36、1200×2670、Flyme 移植 ROM、KernelSU 进行过目标机实验。安装脚本保留 API36、SM8750、物理屏幕尺寸限制；满足这些条件也不保证 ROM 兼容。** Magisk / APatch包已完成脚本及结构检查，未实机验证安装、开机或权限；APatch包为APModule。请通过对应管理器安装，不承诺Recovery刷入。

## 功能与验证边界

- 用户已确认动态实验的漂移手感满意，但不等于本 Beta 模块完成长期验收。
- 默认 gain=6000、frequency=2.4、damping=0.8、verticalGain=1.5、limit=110、fps=120；120fps 为目标而非实测承诺。
- 模块等待开机完成，检查唯一物理屏、原始全幅竖屏和亮屏状态后启动后端。
- 每段最多600秒，配套独立看护，段落间有停顿，不承诺无缝永久运行。
- 设计上旋转、熄屏时停止并尝试恢复；触摸对齐、异常恢复、开机自启、功耗和长期保活尚未完整验收。
- 显示平移会产生黑边及裁切；已知主动退出仍有 Killed/137，根因未彻底解决。
- 不进行 system 挂载或 SELinux 政策变更，不读取、录制屏幕，不保存传感器轨迹或用户应用内容。

## 安装与回退

1. 先停止电脑端漂移实验或其他漂移后端，避免同时运行。
2. 建议保留 USB 调试、电脑连接和适用于设备的救援方式。
3. 在对应Root框架管理器安装 Beta 模块 ZIP，重启生效。
4. 首次使用检查点击位置、滑动、熄屏、旋转及恢复情况，发现异常立即停止使用。

**停用或卸载模块后必须重启，不承诺即时恢复。** 如果界面无法操作，需通过设备 / KernelSU 支持的安全模式或已有救援方法禁用模块；不能假定断开电脑后总能自动恢复。

日志位于 `/data/adb/modules/screen_drift/runtime/` 的 `worker.log`、`watchdog.log`、`supervisor.log`。本次 Beta 只在电脑构建发布，没有给手机安装新包。

## 构建与目录

需要 JDK8、Android SDK Platform34 / Build Tools34.0.0、可运行 D8 的 Java 环境及 Python3。可通过 `ANDROID_HOME`、`JDK_HOME` 指定路径。

```sh
sh build.sh
python3 package_module.py --target kernelsu
python3 package_module.py --target magisk
python3 package_module.py --target apatch
```

构建包含离线物理算法、控制器检查；模块打包检查 shell 语法及 ZIP 完整性，不能替代实机验收。

- `src/`：传感器、物理算法、显示后端与历史探针。
- `module/`：KernelSU 安装条件和开机监督脚本。
- `control.py`：电脑实验控制工具；使用方法见 [USAGE.md](USAGE.md)。
- `drift.properties`：参数配置。
- `MODULE.md`：模块行为、限制及历史验证记录。
- `release-assets/v0.1.0-beta/`：Beta 安装包与校验文件。
- `build/`、`dist/`：本机生成目录，不提交。

## 历史静态探针记录（非当前模块使用说明）

**当前动态实验版入口：见 [USAGE.md](USAGE.md)。源码长期位置为 `/home/elder/Projects/screen-drift`。以下为早期静态探针的历史记录，并非当前动态版本的验收结论。**

个人实验，独立于公司业务工程。尚未实现或执行显示偏移。
更新：第三轮已执行一次静态显示投影偏移，结果见下节；传感器动态漂移仍未实现。

## 当前文件

- `src/DisplayProbe.java`：反射枚举 SurfaceControl、Transaction、DisplayControl 的相关接口。默认不加载额外原生库、不提交事务。
- `build/`：本机 JDK 8 和 Android build-tools 34.0.0 生成的 class/dex。

## 实机结果

- Android 16 / API 36，Flyme 12.6.0.0A 移植包，KernelSU root 已确认。
- 显示 1200×2670；加速度和陀螺仪已注册；SELinux Enforcing。
- 存在 `SurfaceControl.Transaction.setDisplayProjection(IBinder,int,Rect,Rect)`。
- 物理显示枚举位于 `com.android.server.display.DisplayControl`。
- 仅 services.jar 加载 libandroid_servers 时，原生注册因缺少 `com/android/server/DeviceControlService` 中止探针进程。
- 使用完整 SYSTEMSERVERCLASSPATH 后，成功获取一个物理显示 ID 和 token，但独立进程退出时出现 Segmentation fault，根因尚未定位。
- 因原生库生命周期不稳定，暂停显示写入测试。不能把取得 token 当作偏移成功。

## 安全边界与未完成项

没有安装 APK、模块，没有修改分辨率、设置或投影，没有修改 SELinux，没有开机启动项。手机仅推送了 `/data/local/tmp/screen-drift-probe.dex`，没有常驻后端；已覆盖为默认不加载额外原生库的版本。

原生崩溃可能由系统生成诊断记录，本工具未删除系统记录。

后续需先定位原生库装载/退出问题，再实现保存当前投影参数、独立恢复守护、有限时长的微小偏移以及投影/触摸验证。目前没有可用的偏移和恢复工具，不应直接进行显示变换。

## 第二轮进展（暂停于 USB 断开）

- 仅读取本探针对应 tombstone：崩溃栈位于退出阶段 `String16::~String16 -> __cxa_finalize -> exit`。尚未定位导致析构失效的更深层原因。
- 只读实验改用显式 `_exit` 绕过原生析构，获取 token 后未再报告同类 Segmentation fault，但 shell 显示 `Killed`，退出行为仍需调查，不能宣称已彻底解决。
- 新增 `src/ProjectionTest.java`：限本机尺寸、固定 10px/2 秒、finally 恢复；独立 watchdog 7 秒后恢复。退出前显式关闭事务，随后绕过有问题的原生退出清理。
- 新增 `test_projection.py`：指定设备序列号；先验证唯一物理屏、竖屏且原始投影为全幅；保存 baseline，等待独立 watchdog 就绪，再执行偏移并对比前中后投影。
- Java 编译、D8 转换和 Python 语法检查通过；实际推送时 ADB 设备消失，复查 `adb devices -l` 为空。
- **新测试 dex 尚未推送，host 测试脚本未执行，未提交任何显示变换。** 重新连接后必须重新确认设备、Root 和当前投影再测试。
- 以上是实验工具，恢复路径、触摸映射及不同旋转行为均未经实机验证，不用于日常或常驻运行。

## 第三轮：静态偏移实机验证

- USB 重连后重新确认 root、屏幕 Awake、1200×2670、ROTATION_0、全幅原始投影。
- 推送测试 dex，执行 `python3 test_projection.py`。
- 独立 watchdog 输出 READY 后启动测试；采样到 orientedDisplaySpace 的 content 从 `Rect(0, 0, 1200, 2670)` 变为 `Rect(10, 0, 1210, 2670)`，证明显示投影偏移实际被系统接受，不只是提交日志。
- 主进程两秒后输出 MAIN_RESTORED，watchdog 输出 WATCHDOG_RESTORED；最终投影文本与基线完全一致。
- worker/watchdog 仍以 137 退出且 stderr 为 Killed；是在打印恢复完成后，不能视为正常退出。未产生新的 tombstone（最新仍为 tombstone_26），具体退出机制仍需调查。
- SurfaceFlinger PID 前后为 2010；尺寸 1200×2670、密度 520 未变；没有残留探针进程。
- 结果见 `build/baseline.json`、`build/result.json`。没有屏幕截图、没有读取个人应用内容。
- 尚未验证用户视觉观感、实际触摸对齐、旋转/熄屏适配、动态传感器驱动和性能。不得称为可日常使用的完整功能。
- 手机新增 `/data/local/tmp/screen-drift-test.dex`；无安装项、模块、开机项。

## 第四轮：用户要求增大视觉偏移

- 将 `ProjectionTest` 的固定偏移改为向右 100px、保持 4000ms；watchdog 仍为 7 秒恢复。
- 重新 javac、D8 构建、推送后执行 `python3 test_projection.py`，基线检查通过。
- 实测中间投影 content 为 `Rect(100, 0, 1300, 2670)`，最后与原始全幅投影完全一致，`projection_restored=true`。
- 两个进程均打印恢复完成，仍有退出码 137/Killed 的已知问题。没有进行触摸操作或传感器动态测试。
- `build/result.json` 现记录本轮 100px 测试（覆盖前次结果）；当前代码及手机测试 dex 参数均为 100px/4 秒，不是之前的 10px/2 秒。

## 构建

```sh
/usr/lib/jvm/java-8-temurin/bin/javac -d build src/DisplayProbe.java
/home/elder/Android/Sdk/build-tools/34.0.0/d8 --min-api 34 --output build build/DisplayProbe.class
```

实验原生加载现需显式参数 `--experimental-load-native`，默认只枚举方法。结束前已重新构建、推送并验证默认执行正常退出。SurfaceFlinger PID 前后均为 2010，未观察到该服务重启；显示尺寸与密度未变，没有残留探针进程。
