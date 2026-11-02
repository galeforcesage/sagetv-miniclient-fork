#!/usr/bin/env python3
import argparse
import pathlib
import subprocess
import sys
import tempfile
import zipfile


ABI_CONFIG = {
    "arm64": ("arm64-v8a", "ijkplayer-arm64-0.8.8-SNAPSHOT.aar"),
    "armv7a": ("armeabi-v7a", "ijkplayer-armv7a-0.8.8-SNAPSHOT.aar"),
}


def replace_native_entries(aar, replacements):
    with tempfile.NamedTemporaryFile(
        dir=aar.parent, prefix=aar.name, suffix=".tmp", delete=False
    ) as temporary:
        temporary_path = pathlib.Path(temporary.name)

    try:
        with zipfile.ZipFile(aar, "r") as source, zipfile.ZipFile(
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
        temporary_path.replace(aar)
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
        replace_native_entries(aar, replacements)
        updated_aars.append(aar)
        print(f"Updated {aar} from {native_dir}")

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
