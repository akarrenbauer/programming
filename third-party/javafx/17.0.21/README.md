# JavaFX 17.0.21 notices

The distributable bundles the unmodified `org.openjfx:javafx-base`,
`javafx-graphics`, and `javafx-controls` version `17.0.21` artifacts from
[Maven Central](https://repo.maven.apache.org/maven2/org/openjfx/).
The Linux binaries identify their runtime build as `17.0.21+3`.

The five license and notice files in this directory are verbatim copies from
the official [OpenJFX 17 update repository](https://github.com/openjdk/jfx17u),
published tag [`17.0.21+1`](https://github.com/openjdk/jfx17u/tree/17.0.21%2B1),
commit [`611a2b89281a81ff3ff534650244cba2162df1ed`](https://github.com/openjdk/jfx17u/tree/611a2b89281a81ff3ff534650244cba2162df1ed).
The tag and commit were checked using the GitHub API. This identifies the
published source of the notices, not an assertion that the Maven binaries were
built from that exact commit.

| Local file | Upstream path |
| --- | --- |
| `LICENSE` | `LICENSE` (GPLv2 and the Classpath exception) |
| `ADDITIONAL_LICENSE_INFO` | `ADDITIONAL_LICENSE_INFO` |
| `ASSEMBLY_EXCEPTION` | `ASSEMBLY_EXCEPTION` |
| `javafx.graphics/jpeg_fx.md` | `modules/javafx.graphics/src/main/legal/jpeg_fx.md` |
| `javafx.graphics/mesa3d.md` | `modules/javafx.graphics/src/main/legal/mesa3d.md` |

At that revision, `javafx.base` and `javafx.controls` have no additional
`src/main/legal` directory. The graphics directory contains the two notices
listed above. Each downloaded file was verified against its Git blob hash in
the official repository.

Maven copies this directory into `META-INF/licenses/javafx/17.0.21/` in the JAR.
No license files are downloaded during a build. When changing the JavaFX
dependency version or bundled modules, update these notices and their
provenance together. The application's separate MIT license is bundled at
`META-INF/licenses/language-game/LICENSE`.
