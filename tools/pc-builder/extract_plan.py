#!/usr/bin/env python3
"""Turns a finished DriftDroid build into the PC builder's toolkit for one platform.

The downloads ship no game code. Everything in a build that is NOT derived from the game (the
runtime, aurora, Dawn, SDL and the other libraries) is copied here as finished object files and
archives; everything that IS derived from the game (sources under generated/ and build/mods/, made
by the translator from the player's own disc) is described as compile groups with the exact
compiler arguments this build used. The builder (DriftDroidBuilder) then translates the player's
disc, compiles those groups and replays the archive and link steps recorded here.

usage: extract_plan.py --platform android|switch --repo R --build-dir B --out OUT
                       --toolchain-root T [--pgo FILE] TARGET...

Placeholders in the recorded arguments, filled in by the builder:
  {REPO}   the toolkit's copy of the repository files the game code needs (headers, maps)
  {WORK}   the build directory on the player's PC (prebuilt files are copied into it first)
  {TC}     the compiler toolchain root (the NDK's LLVM folder, or the devkitPro folder)
  {PGO}    the toolkit's profile folder
  {SRC} {OBJ}  per-file values inside a compile group
  @group:<id>  expands to every object of that compile group, in file-name order
"""
import argparse
import json
import os
import re
import shlex
import shutil
import subprocess
import sys


def run_ninja_commands(build_dir, targets):
    out = subprocess.run(["ninja", "-t", "commands", *targets], cwd=build_dir, capture_output=True,
                         text=True, check=True).stdout
    seen, lines = set(), []
    for line in out.splitlines():
        if line and line not in seen:
            seen.add(line)
            lines.append(line)
    return lines


def split_subcommands(line):
    """A ninja command line as argv lists, without the ': &&' no-op and 'cd X &&' prefixes."""
    parts = [p.strip() for p in line.split(" && ")]
    commands, cwd = [], None
    for part in parts:
        if part in (":", ""):
            continue
        argv = shlex.split(part)
        if argv[0] == "cd" and len(argv) == 2:
            cwd = argv[1]
            continue
        commands.append((cwd, argv))
    return commands


class Extractor:
    def __init__(self, args):
        self.platform = args.platform
        self.repo = os.path.realpath(args.repo)
        self.build = os.path.realpath(args.build_dir)
        self.out = os.path.realpath(args.out)
        self.tc = os.path.realpath(args.toolchain_root)
        self.pgo = os.path.realpath(args.pgo) if args.pgo else None
        self.targets = args.targets
        self.groups = {}          # id -> dict
        self.obj_to_group = {}    # original object path (build-relative) -> (group id, new path)
        self.game_outputs = set()  # build-relative outputs that contain game code
        self.steps = []
        self.prebuilt = set()
        self.headers = set()
        self.unity_game_sources = {}
        self.external = {}       # toolkit-relative path -> file outside the build folder

    # --- paths ---------------------------------------------------------------------------------

    def rel_build(self, path, cwd=None):
        base = cwd or self.build
        full = os.path.normpath(path if os.path.isabs(path) else os.path.join(base, path))
        if full.startswith(self.build + os.sep):
            return os.path.relpath(full, self.build)
        return None

    def placeholder(self, text):
        """Absolute paths of this machine replaced by placeholders, inside any argument."""
        if self.platform == "switch":
            # libnx and the portlibs ship with the toolkit (copied into {WORK}/dkp), so the game links
            # against exactly the libraries it was built and tested with; only the compiler comes
            # from the player's devkitPro.
            for sub_dir in ("libnx", "portlibs"):
                text = text.replace(os.path.join(self.tc, sub_dir), "{WORK}/dkp/" + sub_dir)
        if self.pgo:
            text = text.replace(self.pgo, "{PGO}/" + os.path.basename(self.pgo))
        text = text.replace(self.tc, "{TC}")
        text = text.replace(self.build + "/", "{WORK}/")
        text = text.replace(self.build, "{WORK}")
        # The runtime CMake refers to the repository both directly and as runtime/..
        text = text.replace(self.repo + "/runtime/..", "{REPO}")
        text = text.replace(self.repo, "{REPO}")
        return text

    def is_game_source(self, src):
        full = os.path.realpath(src)
        if full.startswith(os.path.join(self.repo, "generated") + os.sep):
            return True
        if full.startswith(os.path.join(self.repo, "build", "mods") + os.sep):
            return True
        if full.endswith((".cxx", ".cpp")) and full.startswith(self.build + os.sep) and "Unity" in full:
            with open(full, encoding="utf-8", errors="replace") as f:
                text = f.read()
            if "/generated/" in text or "/build/mods/" in text:
                raise SystemExit(f"unity file {full} includes game sources; exclude them from unity builds")
        return False

    # --- compiles ------------------------------------------------------------------------------

    def compile_template(self, argv, src, obj):
        out, skip = [], 0
        for i, a in enumerate(argv):
            if skip:
                skip -= 1
                continue
            if a in ("-MD", "-MMD"):
                continue
            if a in ("-MT", "-MF", "-MQ"):
                skip = 1
                continue
            if a == "-o":
                out += ["-o", "{OBJ}"]
                skip = 1
                continue
            # The precompiled header is specific to the machine that made it: include the header
            # it was made from instead, which compiles to the same code.
            if a == "-Xclang" and i + 2 < len(argv) and argv[i + 1] == "-include-pch":
                skip = 3
                continue
            if a == "-Xclang" and i + 3 < len(argv) and argv[i + 1] == "-include" and argv[i + 3].endswith("cmake_pch.hxx"):
                out += ["-include", "{REPO}/runtime/include/mkw_pch.h"]
                skip = 3
                continue
            if a == "-include" and i + 1 < len(argv) and argv[i + 1].endswith("cmake_pch.hxx"):
                out += ["-include", "{REPO}/runtime/include/mkw_pch.h"]
                skip = 1
                continue
            if a == "-Winvalid-pch":
                continue
            # Debug information never changes the code and the shipped libraries are stripped:
            # leaving it out makes the player's build faster and much smaller on disk.
            if a in ("-g", "-g1", "-g2", "-g3", "-ggdb"):
                continue
            if os.path.realpath(a) == os.path.realpath(src) if os.path.isabs(a) else False:
                out.append("{SRC}")
                continue
            out.append(self.placeholder(a))
        if "{SRC}" not in out:
            raise SystemExit(f"source not found in compile of {src}")
        return out

    def add_game_compile(self, cwd, argv):
        obj = argv[argv.index("-o") + 1]
        src = argv[argv.index("-c") + 1]
        rel_obj = self.rel_build(obj, cwd)
        src_full = os.path.realpath(src if os.path.isabs(src) else os.path.join(cwd or self.build, src))
        src_rel = os.path.relpath(src_full, self.repo)
        target_dir = rel_obj.split("/")[1] if rel_obj.startswith("CMakeFiles/") else "misc"
        src_dir = os.path.dirname(src_rel)
        # Assembly and C++ in one folder compile with different arguments.
        ext = os.path.splitext(src_rel)[1].lstrip(".")
        group_id = f"{target_dir}:{src_dir}:{ext}"
        template = self.compile_template(argv, src_full, obj)
        group = self.groups.setdefault(group_id, {
            "sourceDir": src_dir,
            "objectDir": f"CMakeFiles/{target_dir}/g/{os.path.basename(src_dir) or 'root'}_{ext}",
            "args": template,
            "files": [],
        })
        if group["args"] != template:
            raise SystemExit(f"compile arguments differ inside group {group_id}:\n{group['args']}\n{template}")
        name = os.path.basename(src_rel)
        group["files"].append(name)
        new_obj = f"{group['objectDir']}/{name}.o"
        self.obj_to_group[rel_obj] = (group_id, new_obj)
        self.game_outputs.add(rel_obj)
        self.collect_headers(rel_obj)

    def collect_headers(self, rel_obj):
        # CMake's Ninja generator keeps dependency lists in .ninja_deps, not in .d files.
        text = subprocess.run(["ninja", "-t", "deps", rel_obj], cwd=self.build, capture_output=True,
                              text=True).stdout
        for token in text.split()[1:]:
            if not token.startswith("/"):
                continue  # "#deps 25, deps mtime ..." bookkeeping
            path = os.path.realpath(token)
            if path.startswith(self.repo + os.sep) and not path.startswith(os.path.join(self.repo, "generated") + os.sep) \
                    and not path.startswith(os.path.join(self.repo, "build", "mods") + os.sep) \
                    and not path.startswith(self.build + os.sep):
                self.headers.add(os.path.relpath(path, self.repo))
            elif path.startswith(self.build + os.sep) and "cmake_pch" not in path:
                raise SystemExit(f"{rel_obj} includes {path} from the build folder, which the toolkit does not ship")

    # --- archives and links --------------------------------------------------------------------

    def expand_inputs(self, argv, cwd):
        """Arguments with game objects replaced by @group references; prebuilt inputs recorded."""
        out, groups_seen = [], []
        for a in argv:
            rel = self.rel_build(a, cwd) if not a.startswith("-") else None
            if rel is not None and rel in self.obj_to_group:
                gid = self.obj_to_group[rel][0]
                if gid not in groups_seen:
                    groups_seen.append(gid)
                    out.append("@group:" + gid)
                continue
            if rel is not None and rel in self.game_outputs:
                out.append(rel)
                continue
            if rel is not None and os.path.isfile(os.path.join(self.build, rel)) and rel.endswith((".o", ".a", ".so", ".obj")):
                self.prebuilt.add(rel)
                out.append(rel)
                continue
            # Archives from outside the build folder (Dawn): shipped in the toolkit's ext/ folder.
            if a.startswith("/") and a.endswith((".a", ".so", ".o")) and os.path.isfile(a) \
                    and not a.startswith(self.tc + "/"):
                rel_ext = "ext/" + os.path.basename(a)
                self.external[rel_ext] = a
                out.append(rel_ext)
                continue
            out.append(self.placeholder(a))
        return out, groups_seen

    def check_group_complete(self, argv, cwd, gid):
        members = {o for o, (g, _) in self.obj_to_group.items() if g == gid}
        used = {self.rel_build(a, cwd) for a in argv if not a.startswith("-")}
        if members - used:
            raise SystemExit(f"a step uses only part of group {gid}")

    def add_tool_step(self, kind, cwd, argv, output):
        expanded, groups = self.expand_inputs(argv, cwd)
        for gid in groups:
            self.check_group_complete(argv, cwd, gid)
        rel_cwd = self.rel_build(cwd) if cwd else ""
        self.steps.append({"kind": kind, "cwd": rel_cwd or "", "args": expanded, "output": output})
        self.game_outputs.add(output)

    # --- main ----------------------------------------------------------------------------------

    def run(self):
        lines = run_ninja_commands(self.build, self.targets)
        for line in lines:
            for cwd, argv in split_subcommands(line):
                tool = os.path.basename(argv[0])
                if "-c" in argv and "-o" in argv:
                    src = argv[argv.index("-c") + 1]
                    src_full = src if os.path.isabs(src) else os.path.join(cwd or self.build, src)
                    if self.is_game_source(src_full):
                        self.add_game_compile(cwd, argv)
                    continue
                if tool in ("cmake",):
                    continue
                if tool.endswith(("-ar", "ar")) and len(argv) > 2 and argv[1] in ("qc", "rcs", "cr", "qcs"):
                    output = self.rel_build(argv[2], cwd)
                    inputs = [self.rel_build(a, cwd) for a in argv[3:]]
                    if any(i in self.obj_to_group or i in self.game_outputs for i in inputs if i):
                        self.add_tool_step("archive", cwd, argv, output)
                    continue
                if tool.endswith(("ranlib",)):
                    output = self.rel_build(argv[-1], cwd)
                    if output in self.game_outputs:
                        self.add_tool_step("ranlib", cwd, argv, output)
                    continue
                if "-o" in argv:
                    output = self.rel_build(argv[argv.index("-o") + 1], cwd)
                    inputs = [self.rel_build(a, cwd) for a in argv if not a.startswith("-")]
                    if any(i in self.obj_to_group or i in self.game_outputs for i in inputs if i):
                        self.add_tool_step("link", cwd, argv, output)
                    continue
                if tool.startswith("python") and "build_combined_android_lib.py" in " ".join(argv):
                    self.steps.append({"kind": "android-combined", "cwd": "", "args": [],
                                       "output": os.path.basename(argv[-1])})
                    self.prebuilt.add("CMakeFiles/mkw_combined_product.dir/src/product/combined_product.cpp.o")
                    continue
                if tool in ("elf2nro", "nacptool"):
                    continue
        for group in self.groups.values():
            group["files"].sort()

    def write(self):
        if os.path.exists(self.out):
            shutil.rmtree(self.out)
        os.makedirs(self.out)
        prebuilt_root = os.path.join(self.out, "prebuilt")
        for rel in sorted(self.prebuilt):
            if rel in self.game_outputs:
                continue
            dst = os.path.join(prebuilt_root, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(os.path.join(self.build, rel), dst)
        for rel, src in sorted(self.external.items()):
            dst = os.path.join(prebuilt_root, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(src, dst)
        if self.platform == "switch":
            dkp = os.path.join(self.out, "prebuilt", "dkp")
            os.makedirs(os.path.join(dkp, "libnx", "lib"), exist_ok=True)
            for f in ("switch.ld", "switch.specs"):
                shutil.copy2(os.path.join(self.tc, "libnx", f), os.path.join(dkp, "libnx", f))
            for f in ("libnx.a", "libdeko3d.a"):
                shutil.copy2(os.path.join(self.tc, "libnx", "lib", f), os.path.join(dkp, "libnx", "lib", f))
            shutil.copytree(os.path.join(self.tc, "portlibs", "switch", "lib"),
                            os.path.join(dkp, "portlibs", "switch", "lib"), symlinks=False)
        plan = {
            "platform": self.platform,
            # switch.specs finds the link script through DEVKITPRO.
            "env": {"DEVKITPRO": "{WORK}/dkp"} if self.platform == "switch" else {},
            "groups": self.groups,
            "steps": self.steps,
            "headers": sorted(self.headers),
        }
        with open(os.path.join(self.out, "plan.json"), "w") as f:
            json.dump(plan, f, indent=1)
        print(f"groups={len(self.groups)} steps={len(self.steps)} prebuilt={len(self.prebuilt)} "
              f"headers={len(self.headers)}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--platform", required=True, choices=["android", "switch"])
    p.add_argument("--repo", required=True)
    p.add_argument("--build-dir", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--toolchain-root", required=True)
    p.add_argument("--pgo")
    p.add_argument("targets", nargs="+")
    args = p.parse_args()
    e = Extractor(args)
    e.run()
    e.write()


if __name__ == "__main__":
    main()
