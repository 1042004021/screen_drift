"""Build an installable module ZIP without contacting or modifying the phone."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import subprocess
import argparse

parser = argparse.ArgumentParser(description="Package Screen Drift for a Root framework")
parser.add_argument('--target', choices=['kernelsu', 'magisk', 'apatch'], default='kernelsu')
target = parser.parse_args().target

base = Path(__file__).resolve().parent
for script in ("service.sh", "customize.sh"):
    subprocess.run(["sh", "-n", str(base / "module" / script)], check=True)
output = base / ("dist/screen-drift-" + target + "-0.1.0-beta.zip")
with ZipFile(output, "w", ZIP_DEFLATED) as archive:
    for path in sorted((base / "module").iterdir()):
        if path.is_file():
            archive.write(path, path.name)
    archive.write(base / "dist/classes.dex", "classes.dex")
    archive.write(base / "drift.properties", "drift.properties")
    if target == 'magisk':
        installer = base / 'magisk/META-INF/com/google/android/update-binary'
        subprocess.run(['sh', '-n', str(installer)], check=True)
        archive.write(installer, 'META-INF/com/google/android/update-binary')
        archive.writestr('META-INF/com/google/android/updater-script', '#MAGISK\n')
with ZipFile(output) as archive:
    assert archive.testzip() is None
    assert {"module.prop", "service.sh", "customize.sh", "classes.dex", "drift.properties"} <= set(archive.namelist())
    if target == 'magisk':
        assert archive.read('META-INF/com/google/android/updater-script') == b'#MAGISK\n'
print(output)
