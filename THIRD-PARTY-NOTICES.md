# Third-party notices

Fornax is licensed under MIT (see `LICENSE`), which lets anyone copy, modify and redistribute it
freely. The distributed mod jar also **contains** software written by other people under a
different licence, and that licence requires its notice to travel with the code. It is recorded
here, and its full text ships in `licenses/`.

The rule: nothing is bundled into the jar without a row in this file naming the project, its
version, its author, its licence, and where to obtain its source. A dependency whose licence cannot
be stated does not get bundled.

## Bundled in the jar

Fabric Loom's `include` feature nests these libraries under `META-INF/jars/` in the built jar. They
are not shaded, relocated, or merged into Fornax's own classes; each one stays a separate jar file
inside the mod's jar.

Loom repackages the nested jars rather than copying them byte-for-byte: it adds a `fabric.mod.json`
to each so the loader recognizes it, which changes the archive's hash. The library code itself is
unmodified, though. Extracting both the original release and the nested copy and comparing them
finds `fabric.mod.json` as the only difference, with every class file identical. That distinction is
what the library's licence turns on, so it is stated precisely rather than approximated.

### night-config — `core` and `toml`, version 3.8.1

| | |
|---|---|
| Project | night-config |
| Author | Guillaume Raffin (TheElectronWill) |
| Version | 3.8.1 (`core` and `toml` modules) |
| Licence | **GNU Lesser General Public License v3.0** |
| Licence text | `licenses/LGPL-3.0.txt`, and `licenses/GPL-3.0.txt` which it incorporates by reference |
| Source | <https://github.com/TheElectronWill/night-config> |
| Where it ships | `META-INF/jars/core-3.8.1.jar`, `META-INF/jars/toml-3.8.1.jar` |
| Declared at | `build.gradle`, the two `include` lines |

**What it does here.** It is the parser for TOML, the configuration-file format Fornax's
shaderpacks are written in. Every file that defines a pack (`pack.toml`, `graph.toml`,
`screens.toml`, `blocks.toml`) is read through it. Nothing else on the classpath (the set of code
the JVM can load at runtime) provides it: Fabric Loader ships no night-config classes, and because
it is a plain Java library rather than a mod, no other mod supplies it either. It has to be bundled,
or Fornax cannot read a pack.

**Why bundling it is compatible with Fornax being MIT.** The LGPL, the licence night-config ships
under, exists to permit exactly this: using a library without forcing the whole program that uses
it under the same licence. Fornax uses night-config as a library; it does not copy, modify, or
derive from its source. Under LGPL-3.0 that makes the mod jar a "Combined Work" (a program built
from parts under different licences), which may carry its own terms provided:

- the use of the library is disclosed, in this file;
- copies of the LGPL and the GPL it incorporates by reference are supplied, in `licenses/`;
- the user can substitute their own build of the library.

That last condition is satisfied by the file layout itself, not by a promise: because Loom nests
the library as its own jar rather than folding its classes into ours, replacing
`META-INF/jars/core-3.8.1.jar` and `toml-3.8.1.jar` with a different build of the same library is a
plain jar swap, and Fornax links against it at runtime with no further changes.

No night-config source has been modified. If it ever is, the modifications themselves fall under
LGPL-3.0 and must be published; at that point this entry needs rewriting, not amending.

## Not bundled: required at runtime, installed by the player

These are declared as dependencies in `fabric.mod.json` and resolved from the player's own mods
folder. Fornax redistributes none of them, so none carries a notice obligation here. They are listed
only so the distinction between "bundled" and "depended on" is on the record.

| Project | Version | Relationship |
|---|---|---|
| Fabric Loader | 0.19.2 | Hard dependency |
| Fabric API | 0.152.1+26.2 | Hard dependency |
| Sodium | mc26.2-0.9.1-fabric | Hard dependency; Fornax hooks its API via mixin and copies none of its source |
| YetAnotherConfigLib (YACL) | 3.9.5+26.2-fabric | Hard dependency, settings UI |
| ModMenu | — | Optional; `compileOnly`, so absence is not an error |

## Not shipped in the jar: repository tooling

`gradle/wrapper/gradle-wrapper.jar` is committed so the build bootstraps without a preinstalled
Gradle. It is part of Gradle (Apache License 2.0, <https://github.com/gradle/gradle>) and is build
tooling only: it is not part of the mod and is not distributed to players.
