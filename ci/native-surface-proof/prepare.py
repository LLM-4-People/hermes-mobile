"""Attach a lab application ID and native verification driver to this disposable checkout."""
from pathlib import Path
import re
import shutil

proof = Path(__file__).parent
test = Path("app/src/androidTest")
for source in proof.glob("*.java"):
    package = re.search(r"^package ([\w.]+);", source.read_text(), re.MULTILINE).group(1)
    target = test / "java" / package.replace(".", "/") / source.name
    if target.exists():
        raise RuntimeError(f"Refusing to overwrite {target}")
    shutil.copyfile(source, target)
manifest = test / "AndroidManifest.xml"
entry = '<instrumentation android:name="org.llm4people.hermes.journey.PolishCaptureRunner" android:targetPackage="${hermesApplicationId}" />'
manifest.write_text(manifest.read_text().replace("<application>", entry + "\n    <application>"))
gradle = Path("app/build.gradle")
src = gradle.read_text()
for field in ["applicationId = ", "manifestPlaceholders = [hermesApplicationId: "]:
    old = field + "namespace"
    assert src.count(old) == 1
    src = src.replace(old, field + "'org.llm4people.polishprobe'")
gradle.write_text(src)
