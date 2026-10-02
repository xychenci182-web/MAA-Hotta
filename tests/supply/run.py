"""Offline supply evidence checks; reuse cached Kotlin dependencies only.

Run from any directory with ``python tests/supply/run.py``. Set
MAH_TEST_OUTPUT_DIR to place compiler output outside the checkout.
"""

from pathlib import Path
import os
import shutil
import subprocess


def cached_classpath() -> str:
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
    names = [
        "kotlin-compiler-embeddable-2.1.10.jar",
        "kotlin-stdlib-2.1.10.jar",
        "kotlin-script-runtime-2.1.10.jar",
        "kotlin-daemon-embeddable-2.1.10.jar",
        "trove4j-1.0.20200330.jar",
        "annotations-13.0.jar",
        "kotlinx-coroutines-core-jvm-1.6.4.jar",
    ]
    jars = []
    for name in names:
        found = next(cache.rglob(name), None)
        if found is None:
            raise SystemExit(f"Missing cached dependency: {name}. This runner does not download dependencies.")
        jars.append(str(found))
    return os.pathsep.join(jars)


def java_executable() -> str:
    binary = "java.exe" if os.name == "nt" else "java"
    if os.environ.get("JAVA_HOME"):
        candidate = Path(os.environ["JAVA_HOME"]) / "bin" / binary
        if not candidate.is_file():
            raise SystemExit(f"JAVA_HOME does not contain a Java executable: {candidate}")
        return str(candidate)
    bundled = Path.home() / ".jdks/jbr-21.0.11/bin" / binary
    if bundled.is_file():
        return str(bundled)
    found = shutil.which(binary)
    if found:
        return found
    raise SystemExit("JDK 21 is required; set JAVA_HOME to an existing JDK installation.")


def main() -> None:
    repo = Path(__file__).resolve().parents[2]
    os.chdir(repo)
    output = Path(os.environ.get("MAH_TEST_OUTPUT_DIR", str(repo / "build"))).resolve() / "supply-verification"
    output.mkdir(parents=True, exist_ok=True)
    temporary = output / "tmp"
    temporary.mkdir(exist_ok=True)
    java_options = [f"-Djava.io.tmpdir={temporary}", "-Dfile.encoding=UTF-8"]
    classpath = cached_classpath()
    java = java_executable()
    vision = repo / "app/src/main/java/com/aliothmoon/maahotta/vision"
    sources = [
        repo / "tests/hud/Graphics.kt",
        repo / "tools/tests/SupplyEvidenceCheck.kt",
        *[vision / name for name in [
            "AccountScreenDetector.kt",
            "GameScreenDetector.kt",
            "TemplateMatcher.kt",
            "WelfareNavigationDetector.kt",
            "SupplyScreenDetector.kt",
            "BygoneScreenDetector.kt",
        ]],
    ]
    jar = output / "supply-evidence-test.jar"
    subprocess.run([
        java, *java_options, "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-classpath", classpath,
        "-d", str(jar), *map(str, sources),
    ], check=True)
    subprocess.run([
        java, *java_options, "-cp", str(jar) + os.pathsep + classpath, "SupplyEvidenceCheckKt",
    ], check=True)


if __name__ == "__main__":
    main()
