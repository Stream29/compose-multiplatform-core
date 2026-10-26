#!/usr/bin/env python3
"""Prepare and verify a self-contained, immutable Wasm leaf publication."""
import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

GROUP = "org.jetbrains.compose.ui"
MODULE = "ui-wasm-js"
PATCH_SHA = "ce90975d198b6a09e1633edbb0b886cdd805ca63de96038c59064a51d1013982"
LISTENER_SHA = "ee47fa5d840930be2b3bc9ef30293caa774a872d930c4eb9c9a4496b667f84df"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def prepare(root, version):
    require(re.fullmatch(r"1\.11\.1-kodex\.[0-9a-f]{12}", version), "Invalid immutable version")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    tree = subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=root, text=True).strip()
    require(version.endswith(commit[:12]), "Version does not identify this producer commit")
    require(not subprocess.check_output(
        ["git", "status", "--porcelain", "--untracked-files=normal"], cwd=root,
    ), "Producer must be clean")
    require(sha((root / "semantics.patch").read_bytes()) == PATCH_SHA, "Patch identity changed")
    base = Path(*GROUP.split(".")) / MODULE / version
    source = root / "build/fork-staging" / base
    destination = root / "build/verified-maven" / base
    stem = f"{MODULE}-{version}"
    names = {f"{stem}.{suffix}" for suffix in ("pom", "module", "klib")} | {
        f"{stem}-sources.jar", f"{stem}-kotlin_resources.kotlin_resources.zip",
    }
    require({p.name for p in source.iterdir() if p.is_file() and
             not p.name.endswith((".md5", ".sha1", ".sha256", ".sha512", ".xml"))} == names,
            "Unexpected publication payload")
    payload = {name: (source / name).read_bytes() for name in names}
    pom = ET.fromstring(payload[f"{stem}.pom"])
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    for field, value in (("groupId", GROUP), ("artifactId", MODULE), ("version", version)):
        require(pom.findtext("m:" + field, namespaces=ns) == value, "Unexpected POM identity")
    doc = json.loads(payload[f"{stem}.module"])
    require(doc["formatVersion"] == "1.1", "Unexpected GMM version")
    require(doc["component"]["group"] == GROUP and doc["component"]["version"] == version,
            "Unexpected GMM producer")
    require(doc["component"]["module"] == "ui", "Unexpected generated root identity")
    doc["component"]["module"] = MODULE
    doc["component"].pop("url", None)
    doc.get("createdBy", {}).get("gradle", {}).pop("buildId", None)
    roles = set()
    for variant in doc["variants"]:
        require("available-at" not in variant, "Leaf cannot redirect to an unpublished root")
        attrs = variant["attributes"]
        require(attrs["org.jetbrains.kotlin.platform.type"] == "wasm" and
                attrs["org.jetbrains.kotlin.wasm.target"] == "js", "Unexpected platform")
        usage = attrs["org.gradle.usage"]
        require(usage in ("kotlin-api", "kotlin-runtime", "kotlin-multiplatformresourcesjs"),
                "Unexpected usage")
        if attrs["org.gradle.category"] == "library" and usage in ("kotlin-api", "kotlin-runtime"):
            roles.add(usage)
        for item in variant["files"]:
            url = item["url"]
            require(url in names and item["name"] in {
                url, f"ui-{version}.kotlin_resources.zip",
            }, "Unexpected artifact reference")
            data = payload[url]
            require(item["size"] == len(data), "Artifact size mismatch")
            for algorithm in ("md5", "sha1", "sha256", "sha512"):
                if algorithm in item:
                    require(item[algorithm] == hashlib.new(algorithm, data).hexdigest(),
                            "Artifact digest mismatch")
        for dependency in variant.get("dependencies", []):
            require("-kodex." not in dependency.get("version", {}).get("requires", ""),
                    "Dependency closure must remain official")
    require(roles == {"kotlin-api", "kotlin-runtime"}, "Missing API/runtime variant")
    with zipfile.ZipFile(source / f"{stem}.klib") as klib:
        manifest = klib.read("default/manifest").decode()
        require("unique_name=org.jetbrains.compose.ui\\:ui" in manifest or
                "unique_name=org.jetbrains.compose.ui:ui" in manifest, "KLIB identity changed")
        require("builtins_platform=WASM" in manifest, "Not a Wasm KLIB")
    with zipfile.ZipFile(source / f"{stem}-sources.jar") as sources:
        listener = [n for n in sources.namelist() if n.endswith("/ComposeWebSemanticsListener.kt")]
        require(len(listener) == 1 and sha(sources.read(listener[0])) == LISTENER_SHA,
                "Published listener differs from the reviewed repair")
        for name in ("LICENSE.txt", "KODEX-NOTICE.md"):
            require(f"META-INF/androidx/compose/ui/ui/{name}" in sources.namelist(),
                    "Missing license or modification notice")
    payload[f"{stem}.module"] = (json.dumps(doc, indent=2) + "\n").encode()
    destination.mkdir(parents=True, exist_ok=True)
    for name, data in payload.items():
        (destination / name).write_bytes(data)
    receipt = {"commit": commit, "tree": tree, "version": version, "patchSha256": PATCH_SHA,
               "files": {str(base / name): {"size": len(data), "sha256": sha(data)}
                         for name, data in sorted(payload.items())}}
    (root / "build/publication.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["prepare"])
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    prepare(Path(__file__).resolve().parent, args.version)
