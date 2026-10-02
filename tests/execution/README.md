# 执行策略离线检查

在仓库根目录运行 `python tests/execution/run.py`。检查直接编译生产代码中的
`TaskAttemptRunner`、`TaskSafetyState`、`AccountSession`、`AccountIdentity`、`CaptureReadiness` 和 `RunJournal`，无需 Android SDK、
模拟器或游戏。它也运行 `RunJournalCheck`，再用 Python 独立解析生成的 JSONL。

加载截图检查覆盖首次有效截图后的连续空帧与恢复、固定截止时间、嵌套等待范围、异常与取消后的策略恢复；日常任务仍保持截图不可用时停止、不重放动作。

运行器只读取本地已存在的 Java 和 Kotlin 库，不运行 Gradle，不下载依赖。需要 Python 3.9+
和已安装的 JDK（验证使用 JDK 21）。默认查找用户 Gradle 缓存中的 Kotlin 2.1.10 或 2.0.21、
Kotlin 标准库及脚本运行库、Trove、JetBrains annotations 和 Coroutines 1.6.4。缺少缓存时会
报错，可以用以下环境变量指定已有工具和输出位置：

- `MAH_TEST_OUTPUT_DIR`：输出根目录，下面创建 `execution-verification`；测试 JAR、JSONL 和
  Java 临时目录都放在其中。未设置时使用仓库的 `build` 目录。
- `MAH_TEST_JAVA_HOME`：指定已安装的 JDK 目录，优先于 `JAVA_HOME`。否则查找已有的
  `~/.jdks/jbr-21.0.11`，再查找 PATH 中的 Java。
- `MAH_TEST_KOTLIN_LIB_DIR`：包含完整编译器及其依赖 JAR 的本地目录，可使用已有 Kotlin 或
  Gradle 安装的 `lib` 目录。运行器将该目录中的 JAR 加入 classpath。
- `MAH_TEST_KOTLIN_CLASSPATH`：直接指定已有 JAR 的完整 classpath，优先于库目录和默认缓存；
  Windows 用分号分隔，其他系统用冒号分隔。

例如在 PowerShell 中将所有测试输出放到 G 盘：

```powershell
$env:MAH_TEST_OUTPUT_DIR = 'G:\MAA-Hotta-tests'
$env:MAH_TEST_JAVA_HOME = 'C:\path\to\jdk-21'
python tests/execution/run.py
```

输出通过提示表示离线策略检查完成；游戏识别、实际账号登录和 Android 权限仍需真机验证。
