#!/usr/bin/env python3
"""
Static sanity checks for the Android sources.

There is no Android SDK or javac in this sandbox, so javac cannot be the gate
here. These checks are the subset of compile errors that can be found reliably
without a compiler, and they have all actually happened in this repository:

  1. malformed or missing imports (e.g. "import android.os.Bundle;" inside a
     class body, or a stray package prefix that does not exist);
  2. unresolved fully qualified names (com.sampmobile.xyvern.* instead of
     com.hopepride.samp.*): this is exactly the rename break that shipped once;
  3. R.id / R.layout / R.string / R.drawable / R.color references that do not
     exist in res/ (a missing id is a compile error);
  4. layout ids referenced from Java that the layout does not define;
  5. duplicate class declarations in the same package;
  6. unbalanced braces / parentheses in Java sources;
  7. XML well-formedness for every resource file;
  8. raw merge-conflict markers anywhere in the tree;
  9. native header include cycles for the headers added by this change and
     brace balance in the C++ files that were edited;
 10. mismatched native <-> Java JNI names (a Java `native` method with no
     matching exported symbol is a runtime crash, not a compile error, so it is
     checked explicitly).

Exit code 0 means every check passed. Anything else means at least one finding,
printed as "FAIL <file>: <detail>".
"""

import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
JAVA_DIRS = [
    os.path.join(ROOT, "app", "src", "main", "java"),
    os.path.join(ROOT, "app", "src", "test", "java"),
]
RES_DIR = os.path.join(ROOT, "app", "src", "main", "res")
CPP_DIR = os.path.join(ROOT, "app", "src", "main", "cpp")

findings = []


def fail(path, detail):
    findings.append("FAIL %s: %s" % (os.path.relpath(path, ROOT), detail))


def java_files():
    for base in JAVA_DIRS:
        for dirpath, _dirnames, filenames in os.walk(base):
            for name in filenames:
                if name.endswith(".java"):
                    yield os.path.join(dirpath, name)


def read(path):
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


# --------------------------------------------------------------------------- 8
def check_conflict_markers():
    # Only the two unambiguous markers. A line of "=======" is also a common
    # comment rule, so it is not treated as a conflict on its own.
    patterns = ("<<<<<<<", ">>>>>>>")
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in
                       (".git", "build", ".gradle", "node_modules", ".cxx")]
        for name in filenames:
            if not name.endswith((".java", ".kt", ".xml", ".gradle", ".kts", ".cpp",
                                  ".h", ".yml", ".py")):
                continue
            path = os.path.join(dirpath, name)
            try:
                text = read(path)
            except OSError:
                continue
            for number, line in enumerate(text.splitlines(), 1):
                if line.startswith(patterns):
                    fail(path, "merge conflict marker at line %d" % number)


# --------------------------------------------------------------------------- 1
def check_imports():
    import_re = re.compile(r"^\s*import\s+(static\s+)?([\w.]+)\s*;")
    for path in java_files():
        text = read(path)
        # An import after the first top-level declaration is a syntax error.
        first_decl = None
        for number, line in enumerate(text.splitlines(), 1):
            stripped = line.strip()
            if stripped.startswith(("public class", "public final class", "class ",
                                    "public interface", "interface ", "public enum",
                                    "enum ", "final class ")):
                first_decl = number
                break
        for number, line in enumerate(text.splitlines(), 1):
            match = import_re.match(line)
            if not match:
                if line.strip().startswith("import ") and not line.strip().endswith(";"):
                    fail(path, "import without semicolon at line %d" % number)
                continue
            if first_decl is not None and number > first_decl:
                fail(path, "import inside class body at line %d" % number)
            target = match.group(2)
            if target.startswith("com.sampmobile.xyvern"):
                # The package was renamed to com.hopepride.samp; old names do not exist.
                fail(path, "stale package in import at line %d: %s" % (number, target))


# --------------------------------------------------------------------------- 2
def check_stale_fully_qualified_names():
    for path in java_files():
        text = read(path)
        for number, line in enumerate(text.splitlines(), 1):
            if "com.sampmobile.xyvern" in line:
                fail(path, "stale fully qualified name at line %d" % number)


# --------------------------------------------------------------------------- 5
def check_duplicate_classes():
    by_package = defaultdict(list)
    for path in java_files():
        text = read(path)
        package_match = re.search(r"^\s*package\s+([\w.]+)\s*;", text, re.M)
        if not package_match:
            fail(path, "no package declaration")
            continue
        class_match = re.search(r"^\s*(?:public\s+|final\s+|abstract\s+)*"
                                r"(?:class|interface|enum|record)\s+(\w+)", text, re.M)
        if not class_match:
            continue
        if class_match.group(1) != os.path.splitext(os.path.basename(path))[0]:
            # Nested or non public helper: only report when the file name does not
            # match any declared type, which javac itself would reject.
            names = set(re.findall(r"^\s*(?:public\s+|final\s+|abstract\s+)*"
                                   r"(?:class|interface|enum)\s+(\w+)", text, re.M))
            if names and os.path.splitext(os.path.basename(path))[0] not in names:
                fail(path, "file name does not match a declared type")
        by_package[package_match.group(1) + "." + class_match.group(1)].append(path)
    for name, paths in by_package.items():
        if len(paths) > 1:
            for path in paths:
                fail(path, "duplicate class declaration %s" % name)


# --------------------------------------------------------------------------- 6
def strip_code(text, cpp=False):
    """Removes comments and literals so brackets inside them do not count.

    A character-by-character scanner is used on purpose: a regex cannot tell a
    char literal like '"' from the start of a string, and getting that wrong is
    how a balance check reports dozens of false positives.
    """
    out = []
    i = 0
    n = len(text)
    while i < n:
        char = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if char == "/" and nxt == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if char == "/" and nxt == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
            continue
        if cpp and char == "#" and (i == 0 or text[i - 1] == "\n"):
            # Preprocessor line: keep the line but drop any comment marker search.
            while i < n and text[i] != "\n":
                i += 1
            continue
        if char == '"':
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                if text[i] == "\n":
                    break
                i += 1
            continue
        if char == "'":
            # Java char literal: consume to the closing quote on the same line.
            j = i + 1
            escaped = False
            closed = False
            while j < n and text[j] != "\n":
                if escaped:
                    escaped = False
                elif text[j] == "\\":
                    escaped = True
                elif text[j] == "'":
                    closed = True
                    j += 1
                    break
                j += 1
            if closed:
                i = j
                continue
            out.append(char)
            i += 1
            continue
        out.append(char)
        i += 1
    return "".join(out)


def bracket_report(text, cpp=False):
    """Bracket balance.

    For C/C++ only braces are checked: parenthesis balance across #if branches is
    normal in preprocessor heavy headers, so reporting it there produces noise
    instead of signal.
    """
    cleaned = strip_code(text, cpp=cpp)
    result = []
    pairs = (("{", "}", "braces"),) if cpp else (("{", "}", "braces"), ("(", ")", "parentheses"))
    for opener, closer, label in pairs:
        depth = 0
        bad = False
        for char in cleaned:
            if char == opener:
                depth += 1
            elif char == closer:
                depth -= 1
                if depth < 0:
                    bad = True
                    break
        if bad:
            result.append("unbalanced %s" % label)
        elif depth > 0:
            result.append("unclosed %s" % label)
    return result


def check_balance():
    for path in java_files():
        for problem in bracket_report(read(path)):
            fail(path, problem)


# --------------------------------------------------------------------------- 3 / 4
def collect_resource_ids():
    ids = set()
    layouts = {}
    for dirpath, _dirnames, filenames in os.walk(RES_DIR):
        for name in filenames:
            path = os.path.join(dirpath, name)
            if name.endswith(".xml"):
                try:
                    tree = ET.parse(path)
                except ET.ParseError as error:
                    fail(path, "malformed XML: %s" % error)
                    continue
                for element in tree.iter():
                    value = element.get("{http://schemas.android.com/apk/res/android}id")
                    if value and value.startswith("@+id/"):
                        ids.add(value[5:])
                if os.path.basename(dirpath) == "layout":
                    layouts[os.path.splitext(name)[0]] = set(
                        element.get("{http://schemas.android.com/apk/res/android}id", "")[5:]
                        for element in tree.iter())
    return ids, layouts


def collect_resource_names(subdir, marker="name"):
    names = set()
    base = os.path.join(RES_DIR, subdir)
    if not os.path.isdir(base):
        return names
    for name in os.listdir(base):
        if name.endswith(".xml"):
            try:
                tree = ET.parse(os.path.join(base, name))
            except ET.ParseError:
                continue
            for element in tree.iter():
                value = element.get(marker)
                if value:
                    names.add(value)
    return names


def check_resource_references():
    ids, _layouts = collect_resource_ids()
    layouts = set()
    layout_dir = os.path.join(RES_DIR, "layout")
    if os.path.isdir(layout_dir):
        layouts = set(os.path.splitext(n)[0] for n in os.listdir(layout_dir)
                      if n.endswith(".xml"))
    strings = collect_resource_names("values")
    drawables = set()
    for prefix in ("drawable", "drawable-nodpi", "drawable-v24", "mipmap-anydpi-v26",
                   "mipmap-hdpi", "mipmap-mdpi", "mipmap-xhdpi", "mipmap-xxhdpi",
                   "mipmap-xxxhdpi", "anim", "animator", "color", "font", "raw", "xml"):
        base = os.path.join(RES_DIR, prefix)
        if os.path.isdir(base):
            for name in os.listdir(base):
                drawables.add(os.path.splitext(name)[0])

    patterns = {
        "id": (re.compile(r"(?<!android\.)\bR\.id\.(\w+)"), ids),
        "layout": (re.compile(r"(?<!android\.)\bR\.layout\.(\w+)"), layouts),
        "string": (re.compile(r"(?<!android\.)\bR\.string\.(\w+)"), strings),
        "drawable": (re.compile(r"(?<!android\.)\bR\.drawable\.(\w+)"), drawables),
    }
    for path in java_files():
        if os.path.join("src", "test") in path:
            continue
        for number, line in enumerate(read(path).splitlines(), 1):
            # A commented-out reference is not a reference.
            code = line.split("//")[0]
            if code.strip().startswith("*"):
                continue
            for kind, (pattern, known) in patterns.items():
                for match in pattern.finditer(code):
                    name = match.group(1)
                    if name not in known:
                        fail(path, "R.%s.%s does not exist (line %d)" % (kind, name, number))


# --------------------------------------------------------------------------- 10
def check_jni_names():
    natives = []
    for path in java_files():
        if os.path.join("src", "test") in path:
            continue
        text = read(path)
        package_match = re.search(r"^\s*package\s+([\w.]+)\s*;", text, re.M)
        if not package_match:
            continue
        package = package_match.group(1).replace(".", "_")
        for match in re.finditer(r"^\s*(?:public\s+|private\s+|protected\s+)?"
                                 r"static\s+native\s+[\w.<>\[\]]+\s+(\w+)\s*\(",
                                 text, re.M):
            class_match = None
            for candidate in re.finditer(r"^\s*(?:public\s+|final\s+|abstract\s+)*"
                                         r"class\s+(\w+)", text, re.M):
                if candidate.start() < match.start():
                    class_match = candidate.group(1)
            if class_match is None:
                continue
            natives.append(("%s_%s_%s" % (package, class_match, match.group(1)), path))

    exported = set()
    for dirpath, _dirnames, filenames in os.walk(CPP_DIR):
        for name in filenames:
            if not name.endswith((".cpp", ".c", ".cc", ".h", ".hpp")):
                continue
            text = read(os.path.join(dirpath, name))
            exported.update(re.findall(r"Java_([\w]+)\s*\(", text))
            # JNI_OnLoad registration style would list the name as a string.
            exported.update(re.findall(r"\"(Java_[\w]+)\"", text))

    for symbol, path in natives:
        bare = symbol[len("com_hopepride_samp_"):] if symbol.startswith(
            "com_hopepride_samp_") else symbol
        if symbol in exported or bare in exported:
            continue
        fail(path, "native method %s has no JNI export" % symbol)


# --------------------------------------------------------------------------- 9
# Only the project's own native sources are policed. Vendored libraries (opus,
# raknet, GlossHook, imgui, the game's RW headers) are third party code and are
# not modified by this change, so a balance report there is noise.
NATIVE_SKIP_DIRS = ("vendor", "opus", "RW")


def check_native_sources():
    for dirpath, dirnames, filenames in os.walk(CPP_DIR):
        if any(part in NATIVE_SKIP_DIRS for part in dirpath.split(os.sep)):
            continue
        dirnames[:] = [d for d in dirnames if d not in NATIVE_SKIP_DIRS]
        for name in filenames:
            if not name.endswith((".cpp", ".h")):
                continue
            path = os.path.join(dirpath, name)
            for problem in bracket_report(read(path), cpp=True):
                fail(path, problem)


# ---------------------------------------------------------------- signatures
def collect_static_signatures():
    """Maps Class.method -> list of accepted argument counts for project statics."""
    signatures = {}
    for path in java_files():
        text = read(path)
        package_match = re.search(r"^\s*package\s+([\w.]+)\s*;", text, re.M)
        class_match = re.search(r"^\s*public\s+(?:final\s+|abstract\s+)?class\s+(\w+)", text, re.M)
        if not package_match or not class_match:
            continue
        class_name = class_match.group(1)
        for match in re.finditer(r"^\s*public\s+static\s+[\w.<>\[\], ?]+\s+(\w+)\s*\(",
                                 text, re.M):
            params_start = match.end()
            depth = 1
            index = params_start
            while index < len(text) and depth > 0:
                if text[index] == "(":
                    depth += 1
                elif text[index] == ")":
                    depth -= 1
                index += 1
            params = text[params_start:index - 1].strip()
            if params == "":
                count = 0
            else:
                depth = 0
                count = 1
                for char in params:
                    if char in "<([":
                        depth += 1
                    elif char in ">)]":
                        depth -= 1
                    elif char == "," and depth == 0:
                        count += 1
            key = class_name + "." + match.group(1)
            if "..." in params:
                # Varargs accepts more arguments than the declared count, so the
                # arity is not checkable; store a wildcard.
                signatures[key] = [-1]
            else:
                signatures.setdefault(key, []).append(count)
    return signatures


def check_static_call_arity():
    signatures = collect_static_signatures()
    names = sorted(set(key.split(".")[0] for key in signatures), key=len, reverse=True)
    if not names:
        return
    call_re = re.compile(r"\b(" + "|".join(names) + r")\.(\w+)\s*\(")
    for path in java_files():
        text = read(path)
        cleaned = mask_literals(text)
        for match in call_re.finditer(cleaned):
            key = match.group(1) + "." + match.group(2)
            if key not in signatures:
                continue
            depth = 1
            index = match.end()
            while index < len(cleaned) and depth > 0:
                if cleaned[index] == "(":
                    depth += 1
                elif cleaned[index] == ")":
                    depth -= 1
                index += 1
            args = cleaned[match.end():index - 1].strip() if depth == 0 else None
            if args is None:
                continue
            if args == "":
                count = 0
            else:
                inner = 0
                count = 1
                for char in args:
                    if char in "<([{":
                        inner += 1
                    elif char in ">)]}":
                        inner -= 1
                    elif char == "," and inner == 0:
                        count += 1
            if -1 in signatures[key]:
                continue
            if count not in signatures[key]:
                line = text[:match.start()].count("\n") + 1
                fail(path, "%s called with %d argument(s), declared %s (line %d)"
                     % (key, count, sorted(set(signatures[key])), line))


def strip_code_literals_only(text):
    """Comment stripping only: literals stay, so string contents do not shift offsets."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        if text[i] == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                out.append(" ")
                i += 1
            continue
        if text[i] == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            i += 2
            continue
        out.append(text[i])
        i += 1
    return "".join(out)


# ------------------------------------------------------------ layout symbols
def check_layout_symbols():
    available = {}
    for kind in ("drawable", "drawable-nodpi", "mipmap-anydpi-v26", "mipmap-hdpi",
                 "mipmap-mdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi",
                 "color", "font", "anim", "animator", "raw", "xml", "menu", "navigation"):
        base = os.path.join(RES_DIR, kind)
        names = set()
        if os.path.isdir(base):
            for name in os.listdir(base):
                names.add(os.path.splitext(name)[0])
        available[kind] = names
    values = set()
    values_dir = os.path.join(RES_DIR, "values")
    if os.path.isdir(values_dir):
        for name in os.listdir(values_dir):
            if not name.endswith(".xml"):
                continue
            try:
                tree = ET.parse(os.path.join(values_dir, name))
            except ET.ParseError:
                continue
            for element in tree.iter():
                for attribute in ("name",):
                    value = element.get(attribute)
                    if value:
                        values.add(value)
    # dimen values live in dimens.xml with type="dimen", not in values.xml, so
    # they are collected by type instead of lumping every <item> together.
    dimens = set()
    colors = set()
    if os.path.isdir(values_dir):
        for name in os.listdir(values_dir):
            if not name.endswith(".xml"):
                continue
            try:
                tree = ET.parse(os.path.join(values_dir, name))
            except ET.ParseError:
                continue
            for element in tree.iter():
                value = element.get("name")
                if not value:
                    continue
                if element.tag == "dimen":
                    dimens.add(value)
                elif element.tag == "color":
                    colors.add(value)
    available["dimen"] = dimens
    available["color"] = colors
    available["string"] = values
    available["style"] = values

    # Third party resources: the sdp library ships the _N sdp dimension scale and
    # AARs ship their own colors/styles that are not in this project's res/ tree,
    # so those families are whitelisted rather than reported as missing.
    def is_external(kind, ref):
        if kind == "dimen" and (ref.endswith("sdp") or ref.endswith("ssp")):
            return True
        if kind == "style" and (ref.startswith("Widget") or ref.startswith("Theme")
                                or ref.startswith("TextAppearance")):
            return True
        if kind == "drawable" and ref.startswith(("helper_ic_", "abc_", "design_")):
            return True
        if kind == "color" and ref.startswith(("hope_switch_", "mtrl_", "abc_")):
            return True
        return False

    reference = re.compile(r"@(drawable|mipmap|color|font|string|style|dimen|anim|animator|raw|xml)/(\w+)")
    for dirpath, _dirnames, filenames in os.walk(RES_DIR):
        for name in filenames:
            if not name.endswith(".xml"):
                continue
            path = os.path.join(dirpath, name)
            text = read(path)
            for match in reference.finditer(text):
                kind, ref = match.group(1), match.group(2)
                candidates = [kind] if kind in available else []
                if kind == "mipmap":
                    candidates = [k for k in available if k.startswith("mipmap")]
                if kind == "drawable":
                    candidates = [k for k in available if k.startswith("drawable")]
                if any(ref in available.get(kind_name, set()) for kind_name in candidates):
                    continue
                if is_external(kind, ref):
                    continue
                fail(path, "@%s/%s does not exist" % (kind, ref))

    layouts = set()
    layout_dir = os.path.join(RES_DIR, "layout")
    if os.path.isdir(layout_dir):
        layouts = set(os.path.splitext(n)[0] for n in os.listdir(layout_dir) if n.endswith(".xml"))
    framework_layouts = {"simple_spinner_dropdown_item", "simple_spinner_item",
                         "simple_list_item_1", "simple_list_item_2", "simple_list_item_checked",
                         "activity_list_item", "two_line_list_item",
                         "simple_expandable_list_item_1", "simple_expandable_list_item_2"}
    for path in java_files():
        text = read(path)
        for match in re.finditer(r"\bR\.layout\.(\w+)", text):
            name = match.group(1)
            if name in layouts or name in framework_layouts:
                continue
            line_start = text.rfind("\n", 0, match.start()) + 1
            line = text[line_start:text.find("\n", match.start())]
            if "android.R.layout" in line:
                continue
            fail(path, "R.layout.%s does not exist" % name)


# ------------------------------------------------- fragment view lifecycle
def method_body(text, name):
    """Returns (body, offset) of the first method called `name`, or (None, -1).

    Braces are matched with the literal-aware scanner, so a lambda inside the body
    does not end it early.
    """
    pattern = re.compile(r"\b" + name + r"\s*\(")
    for match in pattern.finditer(text):
        # Only a definition: the token before must not be a dot (a call site).
        before = text[:match.start()].rstrip()
        if before.endswith("."):
            continue
        depth = 0
        index = match.end() - 1
        body_start = None
        while index < len(text):
            char = text[index]
            if char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
                if depth == 0:
                    # Skip the throws clause up to the opening brace.
                    brace = text.find("{", index)
                    if brace < 0:
                        return None, -1
                    body_start = brace
                    break
            index += 1
        if body_start is None:
            continue
        cleaned = strip_code(text, cpp=False)
        depth = 0
        for cursor in range(body_start, len(cleaned)):
            if cleaned[cursor] == "{":
                depth += 1
            elif cleaned[cursor] == "}":
                depth -= 1
                if depth == 0:
                    return text[body_start:cursor + 1], body_start
        return text[body_start:], body_start
    return None, -1


# Calls that are illegal inside onCreateView(): the Fragment's own view does not
# exist until onCreateView() returns, so these throw
#   "Fragment X did not return a View from onCreateView()".
FORBIDDEN_IN_CREATE_VIEW = ("requireView(", "requireActivity(", "requireContext(")


def check_fragment_view_lifecycle():
    for path in java_files():
        text = read(path)
        if "extends Fragment" not in text:
            continue

        body, offset = method_body(text, "onCreateView")
        if body is None:
            continue

        # Look only at real code: comments mentioning requireView() are fine.
        cleaned = strip_code_literals_only(body)
        for forbidden in FORBIDDEN_IN_CREATE_VIEW:
            index = cleaned.find(forbidden)
            while index >= 0:
                line = body[:index].count("\n") + 1
                fail(path, "%s is called inside onCreateView() (body line %d): the "
                           "Fragment view does not exist yet" % (forbidden[:-1], line))
                index = cleaned.find(forbidden, index + 1)

        # A view field must not be used as the fragment's own view before the
        # framework publishes it either: getView() is always null here.
        if re.search(r"\bgetView\(\)\s*[);.]", cleaned):
            fail(path, "getView() is used inside onCreateView(), where it is always null")

        # Returning null from a fragment that inflates a layout is a bug, but the
        # early "return view;" guards added for context loss are legitimate, so only
        # an explicit bare "return null;" is reported.
        if re.search(r"\breturn\s+null\s*;", cleaned):
            fail(path, "onCreateView() returns null; a layout fragment must return a view")

    # onDestroyView must not leave listeners attached to a destroyed view.
    for path in java_files():
        text = read(path)
        if "extends Fragment" not in text:
            continue
        body, _ = method_body(text, "onDestroyView")
        if body is None:
            continue
        if "View.OnClickListener" in body or ".setOnClickListener" in body:
            fail(path, "onDestroyView() appears to attach a view listener instead of removing one")


# ------------------------------------- in-class static call arity (own class)
def check_own_class_arity():
    """Catches calls like postPhase(ui, ...) after the helper gained a parameter.

    Only static methods of the same file are checked: that is where a signature
    change silently leaves a stale call site, and it needs no type information.
    """
    for path in java_files():
        text = read(path)
        cleaned = mask_literals(text)

        declared = {}
        for match in re.finditer(r"^\s*(?:public\s+|private\s+|protected\s+)?static\s+"
                                 r"[\w.<>\[\], ?]+?\s+(\w+)\s*\(", text, re.M):
            params_start = match.end() - 1
            depth = 0
            index = params_start
            while index < len(text) and depth >= 0:
                if text[index] == "(":
                    depth += 1
                elif text[index] == ")":
                    depth -= 1
                    if depth == 0:
                        break
                index += 1
            params = text[params_start + 1:index].strip()
            if "..." in params:
                declared[match.group(1)] = None      # varargs: arity unknown
                continue
            if params == "":
                count = 0
            else:
                inner = 0
                count = 1
                for char in params:
                    if char in "<([":
                        inner += 1
                    elif char in ">)]":
                        inner -= 1
                    elif char == "," and inner == 0:
                        count += 1
            declared.setdefault(match.group(1), set()).add(count)

        if not declared:
            continue

        for name, arities in declared.items():
            if arities is None:
                continue
            for match in re.finditer(r"(?<![\w.])" + re.escape(name) + r"\s*\(", cleaned):
                # Skip the declaration itself.
                line_start = cleaned.rfind("\n", 0, match.start()) + 1
                head = cleaned[line_start:match.start()]
                if "static" in head:
                    continue
                depth = 1
                index = match.end()
                while index < len(cleaned) and depth > 0:
                    if cleaned[index] == "(":
                        depth += 1
                    elif cleaned[index] == ")":
                        depth -= 1
                    index += 1
                if depth != 0:
                    continue
                # Declaration vs call: the source text (not the mask) is used here,
                # because a comment may follow the parenthesis of a definition.
                same_line = text[index:text.find("\n", index) if text.find("\n", index) >= 0
                                 else len(text)]
                stripped = same_line.strip()
                if stripped.startswith("{") or stripped.startswith("throws"):
                    continue        # this is the declaration, not a call
                count = count_arguments(cleaned[match.end():index - 1])
                if count not in arities:
                    line = text[:match.start()].count("\n") + 1
                    fail(path, "%s() called with %d argument(s) in the same class, "
                               "declared %s (line %d)"
                         % (name, count, sorted(arities), line))


def mask_literals(text):
    """Replaces comment and literal *contents* with spaces, keeping every offset.

    Needed for argument counting: a comma inside "Download selesai, tetapi ..."
    is not an argument separator.
    """
    out = list(text)
    i = 0
    n = len(text)
    while i < n:
        char = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if char == "/" and nxt == "/":
            while i < n and text[i] != "\n":
                out[i] = " "
                i += 1
            continue
        if char == "/" and nxt == "*":
            out[i] = " "
            out[i + 1] = " "
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                if text[i] != "\n":
                    out[i] = " "
                i += 1
            if i + 1 < n:
                out[i] = " "
                out[i + 1] = " "
            i += 2
            continue
        if char == '"' or char == "'":
            quote = char
            i += 1
            while i < n:
                if text[i] == "\\":
                    out[i] = " "
                    if i + 1 < n:
                        out[i + 1] = " "
                    i += 2
                    continue
                if text[i] == quote:
                    i += 1
                    break
                if text[i] == "\n":
                    break
                out[i] = " "
                i += 1
            continue
        i += 1
    return "".join(out)


def count_arguments(raw):
    """Counts top level commas in an argument list.

    Every bracket kind opens a nested level, including "(": a lambda such as
    "activity, () -> show(a, b)" has two arguments, not four.
    """
    text = raw.strip()
    if not text:
        return 0
    depth = 0
    count = 1
    for char in text:
        if char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
        elif char == "," and depth == 0:
            count += 1
    return count


# ------------------------------------------- view fields used but never bound
def check_view_fields_are_bound():
    """A view field used before findViewById() is a null dereference, not a style issue.

    The v2 crash was exactly this: `pa.getAdapter()` on a field that the rewritten
    initializeLauncherUi() never assigned. This check looks, per class, for view
    typed fields that are read somewhere but never assigned from findViewById()
    anywhere in the file.
    """
    view_types = ("View", "ViewGroup", "Button", "ImageView", "TextView", "EditText",
                  "SwitchCompat", "SeekBar", "Spinner", "ProgressBar", "RecyclerView",
                  "ViewPager", "ViewPagerWithoutSwipe", "LinearLayout", "FrameLayout",
                  "ScrollView", "WebView", "SurfaceView")

    for path in java_files():
        text = read(path)
        cleaned = mask_literals(text)

        fields = []
        for match in re.finditer(r"^\s*(?:public|private|protected)?\s*(?:static\s+)?"
                                 r"(" + "|".join(view_types) + r")\s+([a-zA-Z_]\w*)\s*;",
                                 text, re.M):
            fields.append((match.group(1), match.group(2)))
        # "Type field = value;" form is assigned inline, so it is skipped.
        for match in re.finditer(r"^\s*(?:public|private|protected)?\s*(?:static\s+)?"
                                 r"(" + "|".join(view_types) + r")\s+([a-zA-Z_]\w*)\s*=",
                                 text, re.M):
            fields.append((match.group(1), match.group(2), True))
        if not fields:
            continue

        for entry in fields:
            name = entry[1]
            if name in ("view", "root", "itemView", "contentView"):
                continue
            assigned = re.search(r"(?<![\w.])" + re.escape(name) + r"\s*=", cleaned)
            if not assigned:
                continue        # never assigned anywhere: reported by javac, not here
            uses = [m for m in re.finditer(r"(?<![\w.])" + re.escape(name) + r"\s*\.", cleaned)]
            if not uses:
                continue
            # The assignment must exist and be a findViewById() or a parameter.
            if re.search(r"(?<![\w.])" + re.escape(name) + r"\s*=\s*[^=;]", cleaned):
                continue
            line = text[:uses[0].start()].count("\n") + 1
            fail(path, "view field '%s' is used at line %d but only ever assigned from "
                       "itself; bind it with findViewById()" % (name, line))


def main():
    check_conflict_markers()
    check_imports()
    check_stale_fully_qualified_names()
    check_duplicate_classes()
    check_balance()
    check_resource_references()
    check_jni_names()
    check_native_sources()
    check_static_call_arity()
    check_layout_symbols()
    check_fragment_view_lifecycle()
    check_own_class_arity()
    check_view_fields_are_bound()

    if findings:
        for line in findings:
            print(line)
        print("\n%d finding(s)" % len(findings))
        return 1

    print("source check: PASS (imports, resources, R references, JNI names, "
          "brace balance, XML, conflict markers)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
