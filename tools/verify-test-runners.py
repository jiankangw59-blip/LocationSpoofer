"""Both CI test entry points must survive AGP's injection of its configured runner."""
import re
import subprocess
import sys

def declared_runners(tree):
    result = []
    for block in re.findall(r"(?m)^[ \t]*E: instrumentation\b[^\n]*\n((?:[ \t]*A:[^\n]*\n?)*)", tree):
        def attr(name):
            match = re.search(r"(?m)^[ \t]*A: (?:android|http://schemas\.android\.com/apk/res/android):"
                              + name + r'(?:\([^)]*\))?="([^"]+)"', block)
            return match.group(1) if match else None
        result.append((attr("name"), attr("targetPackage")))
    return result

def verify(tree):
    found = declared_runners(tree)
    package = "com.suseoaa.locationspoofer.fork"
    for short in ("SystemFilesInstrumentation", "RadioResourcesInstrumentation"):
        if found.count(("com.vincenthzr.locationspoofer." + short, package)) != 1:
            raise ValueError("Missing or duplicate instrumentation/target pair: " + short)

if __name__ == "__main__":
    aapt2, apk = sys.argv[1:]
    verify(subprocess.check_output([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk], encoding="utf-8"))
    print("Verified both instrumentation runners in the packaged test APK.")
