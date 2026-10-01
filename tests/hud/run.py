from pathlib import Path
import subprocess, os
os.chdir(Path(__file__).resolve().parents[2])
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
names=['kotlin-compiler-embeddable-2.1.10.jar','kotlin-stdlib-2.1.10.jar','kotlin-script-runtime-2.1.10.jar','kotlin-daemon-embeddable-2.1.10.jar','trove4j-1.0.20200330.jar','annotations-13.0.jar','kotlinx-coroutines-core-jvm-1.6.4.jar']
cp=';'.join(str(next(cache.rglob(n))) for n in names)
java=str(Path(os.environ['JAVA_HOME'])/'bin/java.exe') if os.environ.get('JAVA_HOME') else str(Path.home()/'.jdks/jbr-21.0.11/bin/java.exe')
r=Path('app/src/main/java/com/aliothmoon/maahotta/vision')
p=Path('tests/hud')
out=Path('build/hud-verification');out.mkdir(parents=True,exist_ok=True)
subprocess.run([java,'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(out/'test.jar'),str(p/'Graphics.kt'),str(p/'HudCheck.kt'),str(r/'AccountScreenDetector.kt'),str(r/'GameScreenDetector.kt'),str(r/'TemplateMatcher.kt'),str(r/'WelfareNavigationDetector.kt'),str(r/'BygoneScreenDetector.kt')],check=True)
subprocess.run([java,'-cp',str(out/'test.jar')+';'+cp,'HudCheckKt'],check=True)
