#!/usr/bin/env python3
import argparse
import pathlib
import subprocess
import sys
import tempfile
import zipfile


PAGE_SIZE = 0x4000


def program_headers(readelf: pathlib.Path, shared_object: pathlib.Path):
    result = subprocess.run(
        [str(readelf), "-Wl", str(shared_object)],
        check=True,
        capture_output=True,
        text=True,
    )
    headers = []
    for line in result.stdout.splitlines():
        fields = line.split()
        if len(fields) >= 8 and fields[0] in {"LOAD", "GNU_RELRO"}:
            headers.append(
                {
                    "type": fields[0],
                    "offset": int(fields[1], 16),
                    "vaddr": int(fields[2], 16),
                    "filesz": int(fields[4], 16),
                    "memsz": int(fields[5], 16),
                    "flags": "".join(fields[6:-1]),
                    "align": int(fields[-1], 16),
                }
            )
    return headers


def verify_elf(readelf: pathlib.Path, shared_object: pathlib.Path):
    headers = program_headers(readelf, shared_object)
    loads = [header for header in headers if header["type"] == "LOAD"]
    relros = [header for header in headers if header["type"] == "GNU_RELRO"]
    failures = []

    if not loads:
        failures.append("no LOAD segments")

    for load in loads:
        if load["align"] < PAGE_SIZE:
            failures.append(
                f"LOAD at {load['vaddr']:#x} has alignment {load['align']:#x}"
            )

    for relro in relros:
        relro_end = relro["vaddr"] + relro["memsz"]
        dedicated_load = any(
            load["vaddr"] == relro["vaddr"] and load["memsz"] == relro["memsz"]
            for load in loads
        )
        if not dedicated_load and relro_end % PAGE_SIZE:
            failures.append(
                f"GNU_RELRO end {relro_end:#x} is not 16 KB-aligned"
            )

    return failures


def archive_shared_objects(archive: pathlib.Path, output: pathlib.Path):
    with zipfile.ZipFile(archive) as zipped:
        members = [
            member
            for member in zipped.namelist()
            if member.endswith(".so") and not member.endswith("/")
        ]
        for member in members:
            zipped.extract(member, output)
            yield member, output / member


def filesystem_shared_objects(path: pathlib.Path):
    if path.is_file():
        yield path.name, path
        return
    for shared_object in sorted(path.rglob("*.so")):
        yield str(shared_object.relative_to(path)), shared_object


def main():
    parser = argparse.ArgumentParser(
        description="Verify Android ELF LOAD and GNU_RELRO 16 KB compatibility."
    )
    parser.add_argument(
        "--readelf",
        required=True,
        type=pathlib.Path,
        help="Path to the NDK llvm-readelf executable.",
    )
    parser.add_argument(
        "artifacts",
        nargs="+",
        type=pathlib.Path,
        help="ELF files, directories, APKs, AABs, or AARs to inspect.",
    )
    args = parser.parse_args()

    failed = False
    inspected = 0
    with tempfile.TemporaryDirectory(prefix="native-alignment-") as temporary:
        temporary_root = pathlib.Path(temporary)
        for artifact_index, artifact in enumerate(args.artifacts):
            if not artifact.exists():
                print(f"FAIL {artifact}: does not exist")
                failed = True
                continue

            if artifact.suffix.lower() in {".apk", ".aab", ".aar", ".zip"}:
                extracted = temporary_root / str(artifact_index)
                entries = archive_shared_objects(artifact, extracted)
            else:
                entries = filesystem_shared_objects(artifact)

            artifact_count = 0
            for display_name, shared_object in entries:
                artifact_count += 1
                inspected += 1
                failures = verify_elf(args.readelf, shared_object)
                if failures:
                    failed = True
                    print(f"FAIL {artifact}:{display_name}: {'; '.join(failures)}")
                else:
                    print(f"PASS {artifact}:{display_name}")

            if artifact_count == 0:
                print(f"FAIL {artifact}: contains no shared libraries")
                failed = True

    print(f"Inspected {inspected} shared libraries.")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
