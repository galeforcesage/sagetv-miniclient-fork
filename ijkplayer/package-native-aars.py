#!/usr/bin/env python3
import argparse
import os
import pathlib
import subprocess
import sys
import tempfile
import zipfile


ABI_CONFIG = {
    "arm64": ("arm64-v8a", "ijkplayer-arm64-0.8.8-SNAPSHOT.aar"),
    "armv7a": ("armeabi-v7a", "ijkplayer-armv7a-0.8.8-SNAPSHOT.aar"),
}
JAVA_AAR = "ijkplayer-java-0.8.8-SNAPSHOT.aar"


def replace_archive_entries(archive, replacements):
    with tempfile.NamedTemporaryFile(
        dir=archive.parent, prefix=archive.name, suffix=".tmp", delete=False
    ) as temporary:
        temporary_path = pathlib.Path(temporary.name)

    try:
        with zipfile.ZipFile(archive, "r") as source, zipfile.ZipFile(
            temporary_path, "w"
        ) as output:
            for entry in source.infolist():
                if entry.filename in replacements:
                    data = replacements.pop(entry.filename).read_bytes()
                else:
                    data = source.read(entry.filename)
                output.writestr(entry, data)

            for entry_name, replacement in replacements.items():
                output.write(
                    replacement,
                    entry_name,
                    compress_type=zipfile.ZIP_DEFLATED,
                )
        temporary_path.replace(archive)
    finally:
        temporary_path.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(
        description="Replace IJK native libraries in the checked-in ABI AARs."
    )
    parser.add_argument("--ijk-root", required=True, type=pathlib.Path)
    parser.add_argument(
        "--libs-dir",
        default=pathlib.Path(__file__).resolve().parents[1] / "libs",
        type=pathlib.Path,
    )
    parser.add_argument(
        "--readelf",
        required=True,
        type=pathlib.Path,
        help="NDK llvm-readelf used for mandatory post-package validation.",
    )
    parser.add_argument(
        "--android-jar",
        required=True,
        type=pathlib.Path,
        help="Android platform android.jar used to compile the updated IJK Java API.",
    )
    parser.add_argument("--javac", default="javac")
    args = parser.parse_args()

    updated_aars = []
    for module, (abi, aar_name) in ABI_CONFIG.items():
        native_dir = (
            args.ijk_root
            / "android"
            / "ijkplayer"
            / f"ijkplayer-{module}"
            / "src"
            / "main"
            / "libs"
            / abi
        )
        aar = args.libs_dir / aar_name
        replacements = {
            f"jni/{abi}/{library}": native_dir / library
            for library in ("libijkffmpeg.so", "libijkplayer.so", "libijksdl.so")
        }
        missing = [str(path) for path in replacements.values() if not path.is_file()]
        if missing:
            raise SystemExit("Missing built native libraries: " + ", ".join(missing))
        replace_archive_entries(aar, replacements)
        updated_aars.append(aar)
        print(f"Updated {aar} from {native_dir}")

    java_aar = args.libs_dir / JAVA_AAR
    java_source = (
        args.ijk_root
        / "android"
        / "ijkplayer"
        / "ijkplayer-java"
        / "src"
        / "main"
        / "java"
        / "tv"
        / "danmaku"
        / "ijk"
        / "media"
        / "player"
        / "IjkMediaPlayer.java"
    )
    if not java_aar.is_file() or not java_source.is_file():
        raise SystemExit(f"Missing IJK Java input: {java_aar} or {java_source}")
    if not args.android_jar.is_file():
        raise SystemExit(f"Missing Android platform jar: {args.android_jar}")

    with tempfile.TemporaryDirectory(prefix="ijk-java-") as temporary:
        temporary_root = pathlib.Path(temporary)
        classes_jar = temporary_root / "classes.jar"
        output_dir = temporary_root / "classes"
        output_dir.mkdir()
        with zipfile.ZipFile(java_aar, "r") as source:
            classes_jar.write_bytes(source.read("classes.jar"))
        subprocess.run(
            [
                args.javac,
                "-source",
                "8",
                "-target",
                "8",
                "-cp",
                f"{classes_jar}{os.pathsep}{args.android_jar}",
                "-d",
                str(output_dir),
                str(java_source),
            ],
            check=True,
        )
        class_replacements = {
            str(path.relative_to(output_dir)).replace("\\", "/"): path
            for path in output_dir.rglob("IjkMediaPlayer*.class")
        }
        replace_archive_entries(classes_jar, class_replacements)
        replace_archive_entries(java_aar, {"classes.jar": classes_jar})
        print(f"Updated {java_aar} from {java_source}")

    verifier = pathlib.Path(__file__).resolve().parents[1] / "tools" / "verify-native-alignment.py"
    result = subprocess.run(
        [
            sys.executable,
            str(verifier),
            "--readelf",
            str(args.readelf),
            *(str(aar) for aar in updated_aars),
        ],
        check=False,
    )
    if result.returncode:
        raise SystemExit("Packaged IJK AAR validation failed")


if __name__ == "__main__":
    main()
