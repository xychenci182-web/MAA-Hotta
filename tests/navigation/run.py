from pathlib import Path
import subprocess, os
os.chdir(Path(__file__).resolve().parents[2])
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
names=['kotlin-compiler-embeddable-2.1.10.jar','kotlin-stdlib-2.1.10.jar','kotlin-script-runtime-2.1.10.jar','kotlin-daemon-embeddable-2.1.10.jar','trove4j-1.0.20200330.jar','annotations-13.0.jar','kotlinx-coroutines-core-jvm-1.6.4.jar']
cp=';'.join(str(next(cache.rglob(n))) for n in names)
java=str(Path(os.environ['JAVA_HOME'])/'bin/java.exe') if os.environ.get('JAVA_HOME') else str(Path.home()/'.jdks/jbr-21.0.11/bin/java.exe')
r=Path('app/src/main/java/com/aliothmoon/maahotta/vision')
p=Path('tests/hud')
out=Path(os.environ.get('MAH_TEST_OUTPUT_DIR', 'build'))/'navigation-verification';out.mkdir(parents=True,exist_ok=True)
tmp=out/'tmp';tmp.mkdir(exist_ok=True)
sources = ['AccountScreenDetector', 'AccountTransitionScreenDetector', 'GameScreenDetector', 'TemplateMatcher', 'WelfareNavigationDetector', 'BygoneScreenDetector', 'CheckInScreenDetector', 'SupplyScreenDetector', 'TrialsScreenDetector', 'MailScreenDetector', 'KitchenScreenDetector', 'IslandMerchantScreenDetector', 'GuildScreenDetector', 'RequiredHubScreenDetector', 'NavigationPolicy', 'PageStateDetector']
subprocess.run([java,'-Djava.io.tmpdir='+str(tmp.resolve()),'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(out/'test.jar'),str(p/'Graphics.kt'),'tests/navigation/TrialType.kt','tests/navigation/NavigationCheck.kt']+[str(r/(name+'.kt')) for name in sources],check=True)
subprocess.run([java,'-Djava.io.tmpdir='+str(tmp.resolve()),'-cp',str(out/'test.jar')+';'+cp,'NavigationCheckKt'],check=True)
