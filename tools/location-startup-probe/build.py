"""Build the standalone Android location startup diagnostic APK."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parent
SDK = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
           or (Path(os.environ.get("LOCALAPPDATA", Path.home())) / "Android/Sdk"))
BUILD_TOOLS = SDK / "build-tools" / os.environ.get("BUILD_TOOLS_VERSION", "37.0.0")
JAVA = Path(os.environ.get("JAVA_HOME") or (Path.home() / ".gradle/jdks/eclipse_adoptium-21-amd64-windows.2"))
ANDROID_JAR = SDK / "platforms/android-36/android.jar"
EXEC_SUFFIX = ".exe" if os.name == "nt" else ""
SCRIPT_SUFFIX = ".bat" if os.name == "nt" else ""


def run(*args):
    result = subprocess.run([str(arg) for arg in args], cwd=ROOT,
                            env={**os.environ, "JAVA_HOME": str(JAVA)}, capture_output=True)
    if result.returncode:
        raise RuntimeError((result.stdout + result.stderr).decode("utf-8", "replace"))


def main():
    output = ROOT / "build"
    output.mkdir(exist_ok=True)
    unsigned_output = os.environ.get("PROBE_UNSIGNED") == "1"
    artifact = output / ("location-startup-probe-unsigned.apk" if unsigned_output else "location-startup-probe.apk")
    with tempfile.TemporaryDirectory(prefix="stage-", dir=output) as directory:
        stage = Path(directory)
        classes = stage / "classes"
        dex = stage / "dex"
        classes.mkdir()
        dex.mkdir()
        run(JAVA / ("bin/javac" + EXEC_SUFFIX), "--release", "17", "-encoding", "UTF-8", "-cp", ANDROID_JAR,
            "-d", classes, *sorted(ROOT.glob("*.java")))
        run(BUILD_TOOLS / ("d8" + SCRIPT_SUFFIX), "--lib", ANDROID_JAR, "--min-api", "31", "--output", dex,
            *sorted(classes.rglob("*.class")))
        unsigned = stage / "unsigned.apk"
        run(BUILD_TOOLS / ("aapt2" + EXEC_SUFFIX), "link", "--manifest", ROOT / "AndroidManifest.xml", "-I", ANDROID_JAR,
            "-o", unsigned)
        with zipfile.ZipFile(unsigned, "a") as apk:
            apk.write(dex / "classes.dex", "classes.dex")
        aligned = stage / "aligned.apk"
        run(BUILD_TOOLS / ("zipalign" + EXEC_SUFFIX), "-f", "4", unsigned, aligned)
        if unsigned_output:
            aligned.replace(artifact)
        else:
            configured_key = os.environ.get("LOCATION_STARTUP_PROBE_KEYSTORE")
            key = Path(configured_key) if configured_key else Path.home() / ".android/debug.keystore"
            if not configured_key and not key.is_file():
                key = stage / "debug.keystore"
                run(JAVA / ("bin/keytool" + EXEC_SUFFIX), "-genkeypair", "-keystore", key, "-storetype", "JKS",
                    "-storepass", "android", "-keypass", "android", "-alias", "androiddebugkey", "-keyalg", "RSA",
                    "-keysize", "2048", "-validity", "10000", "-dname", "CN=Android Debug,O=Android,C=US")
            signed = stage / "signed.apk"
            run(BUILD_TOOLS / ("apksigner" + SCRIPT_SUFFIX), "sign", "--ks", key,
                "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android", "--key-pass", "pass:android",
                "--out", signed, aligned)
            run(BUILD_TOOLS / ("apksigner" + SCRIPT_SUFFIX), "verify", signed)
            signed.replace(artifact)
    print(json.dumps({"apk": str(artifact), "artifact_signed": not unsigned_output,
                      "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest()}))


if __name__ == "__main__":
    main()
