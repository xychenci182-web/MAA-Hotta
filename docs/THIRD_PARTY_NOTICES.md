# 第三方代码声明

本项目的设备控制思路来自 [Aliothmoon/MAA-Meow](https://github.com/Aliothmoon/MAA-Meow)（AGPL-3.0）。

未搬运 MAA Core、明日方舟任务、虚拟屏 native 桥。触控与截图改为：

- Android AccessibilityService 手势 / `takeScreenshot`
- Shizuku UserService 执行 `input` / `screencap` / `am force-stop`

## 直接依赖

| 组件 | 许可证 |
| --- | --- |
| Shizuku API | Apache-2.0 |
| Timber | Apache-2.0 |
| AndroidX / Compose | Apache-2.0 |
| HiddenApiBypass | Apache-2.0 |

Meow 中源自 scrcpy 的 `third/` 反射封装未整包拷贝；若后续合入，将保留 Apache-2.0 原文，见 `LICENSE-Apache-2.0`。
