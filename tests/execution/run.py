"""Run production execution/journal checks using an already cached Kotlin compiler."""

from pathlib import Path
import json
import os
import shutil
import subprocess


REPOSITORY = Path(__file__).resolve().parents[2]


def cached_classpath() -> list[Path]:
    explicit = os.environ.get("MAH_TEST_KOTLIN_CLASSPATH")
    if explicit:
        return [Path(entry) for entry in explicit.split(os.pathsep)]

    configured_lib = os.environ.get("MAH_TEST_KOTLIN_LIB_DIR")
    if configured_lib:
        libraries = sorted(Path(configured_lib).glob("*.jar"))
        if not any("kotlin-compiler-embeddable" in library.name for library in libraries):
            raise RuntimeError("MAH_TEST_KOTLIN_LIB_DIR has no cached Kotlin compiler")
        return libraries

    cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
    for version in ("2.1.10", "2.0.21"):
        names = [
            f"kotlin-compiler-embeddable-{version}.jar",
            f"kotlin-stdlib-{version}.jar",
            f"kotlin-script-runtime-{version}.jar",
            "trove4j-1.0.20200330.jar",
            "annotations-13.0.jar",
            "kotlinx-coroutines-core-jvm-1.6.4.jar",
        ]
        libraries = [next(cache.rglob(name), None) for name in names] if cache.is_dir() else []
        if libraries and all(libraries):
            for optional in (f"kotlin-reflect-{version}.jar", f"kotlin-daemon-embeddable-{version}.jar"):
                library = next(cache.rglob(optional), None)
                if library:
                    libraries.append(library)
            return libraries

    raise RuntimeError("No cached Kotlin compiler; set MAH_TEST_KOTLIN_LIB_DIR or MAH_TEST_KOTLIN_CLASSPATH")


def java_binary() -> str:
    executable = "java.exe" if os.name == "nt" else "java"
    for home in (
        os.environ.get("MAH_TEST_JAVA_HOME"),
        os.environ.get("JAVA_HOME"),
        str(Path.home() / ".jdks/jbr-21.0.11"),
    ):
        if home and (candidate := Path(home) / "bin" / executable).is_file():
            return str(candidate)
    if candidate := shutil.which("java"):
        return candidate
    raise RuntimeError("No Java runtime; set MAH_TEST_JAVA_HOME to an installed JDK")


def main() -> None:
    output_root = Path(os.environ.get("MAH_TEST_OUTPUT_DIR", str(REPOSITORY / "build"))).resolve()
    output = output_root / "execution-verification"
    temporary = output / "tmp"
    journals = output / "journals"
    temporary.mkdir(parents=True, exist_ok=True)
    journals.mkdir(parents=True, exist_ok=True)
    classpath = os.pathsep.join(str(library) for library in cached_classpath())
    java = java_binary()
    java_options = [f"-Djava.io.tmpdir={temporary}", "-Dfile.encoding=UTF-8"]
    sources = [
        REPOSITORY / "app/src/main/java/com/aliothmoon/maahotta/engine/TaskExecution.kt",
        REPOSITORY / "app/src/main/java/com/aliothmoon/maahotta/engine/RunJournal.kt",
        REPOSITORY / "app/src/main/java/com/aliothmoon/maahotta/engine/AccountIdentity.kt",
        REPOSITORY / "app/src/main/java/com/aliothmoon/maahotta/engine/CaptureReadiness.kt",
        REPOSITORY / "tools/tests/TaskExecutionCheck.kt",
        REPOSITORY / "tools/tests/RunJournalCheck.kt",
    ]
    artifact = output / "test.jar"
    subprocess.run(
        [java, *java_options, "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
         "-no-stdlib", "-no-reflect", "-classpath", classpath, "-d", str(artifact),
         *map(str, sources)],
        cwd=REPOSITORY,
        check=True,
    )
    runtime_classpath = str(artifact) + os.pathsep + classpath
    for check in ("TaskExecutionCheck", "RunJournalCheck"):
        subprocess.run(
            [java, *java_options, "-cp", runtime_classpath,
             f"com.aliothmoon.maahotta.engine.{check}", str(journals)],
            cwd=REPOSITORY,
            check=True,
        )

    rows = [json.loads(line) for journal in journals.glob("run_*.jsonl")
            for line in journal.read_text(encoding="utf-8").splitlines()]
    if len(rows) < 122 or not all(isinstance(row["timestamp"], int) and row["runId"] for row in rows):
        raise AssertionError("Invalid journal evidence")
    print(f"Independent JSON parsing passed for {len(rows)} events; output: {output}")


if __name__ == "__main__":
    main()
