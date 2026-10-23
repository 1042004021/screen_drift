# KernelSU 实验模块 0.1.0

## 0.1.0-beta 发布

补充Magisk/APatch独立包，共用原DEX、配置和服务脚本，不修改漂移算法与安装条件。Magisk添加加载设备已有util_functions.sh的META-INF入口；APatch采用APModule根目录结构。仅完成包结构/脚本检查，未实机验证这两种框架。三包ID相同，按实际框架选一种安装。使用`python3 package_module.py --target kernelsu|magisk|apatch`分别打包。

模块版本0.1.0-beta，versionCode=2，安装包为`screen-drift-kernelsu-0.1.0-beta.zip`。保留原设备限制和参数，不改显示算法。用户确认过动态实验手感，但本Beta未重新安装到手机，开机、触摸映射、功耗及异常恢复未完整验收。发布安装包与SHA256校验；下方实验版文件名及状态属于历史记录。

## 产物

`dist/screen-drift-0.1.0-experimental.zip`

已经完成 Java/D8 构建、离线算法/控制器检查、shell 语法检查和 ZIP 完整性检查。**尚未刷入、未进行开机启动验证，不是稳定版。**

## 行为

- KernelSU late_start service，等待系统完成启动后开始监督。
- 无界面，无 Hook，无 APK，无 system 挂载，无 SELinux 策略变更。
- 使用已由用户确认手感的参数：gain=6000、frequency=2.4、damping=0.8、verticalGain=1.5、limit=110、fps=120。
- 亮屏且投影处于唯一物理屏的原始全幅竖屏时自动启动；熄屏/旋转由后端停止并尝试恢复，再等待合适状态重新启动。
- 每段最多 600 秒并保留独立看护，完成后短暂停顿再开下一段，避免无看护的无限会话。不是无缝永久运行。
- 初始化失败后停止重试，避免紧密崩溃循环；查看 runtime 日志。
- 停用/卸载后要求重启，不承诺即时停止。未加入即时卸载钩子。
- 模块运行目录通过 `-Dscreen.drift.home` 传入，与电脑实验目录隔离。

## 限制

- 安装检查仅限 Android API 36、SM8750、物理尺寸 1200×2670；这些检查不能证明所有满足条件的 ROM 都兼容。
- 触摸对齐、横屏/熄屏恢复、进程卡死恢复、开机自启、功耗均未完整验收；不要作为已经验证的能力宣传。
- 退出仍有 Killed/137 已知问题，用户已接受暂不阻塞实验模块。
- 后端平移造成黑边与裁切，不读取或录制屏幕。

## 使用与回退

先停止电脑端实验，再在 KernelSU 管理器中手动选择 ZIP 安装，重启生效。当前开发过程没有自动刷入手机。

首次启动建议保留 USB 调试和电脑连接，先确认可以进入 KernelSU 管理器。异常时禁用模块并重启。若界面无法操作，需使用设备/KernelSU 支持的安全模式或已有救援方式禁用模块；不应假定电脑断连时总能直接恢复。

日志在 `/data/adb/modules/screen_drift/runtime/` 中的 worker.log、watchdog.log、supervisor.log。仅记录诊断汇总，不记录传感器轨迹或用户应用内容。

## 构建

```sh
sh build.sh
python3 package_module.py
```

关键实现：module/service.sh（启动及重试）、module/customize.sh（安装条件）、src/DriftDaemon.java（动态后端及隔离运行路径）、package_module.py（打包及检查）。
