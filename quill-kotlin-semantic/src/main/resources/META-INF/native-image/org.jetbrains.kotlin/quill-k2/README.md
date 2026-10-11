The adjacent reachability-metadata.json is copied without modification from
JetBrains Kotlin v2.4.21, licensed under Apache-2.0:

https://github.com/JetBrains/kotlin/blob/v2.4.21/prepare/compiler-native-image/resources/META-INF/native-image/org/jetbrains/kotlin/kotlin-compiler-embeddable/reachability-metadata.json

Upstream Git blob: 55c4017a80cdb38cc1aa743a4a48bfdedb37ef5f.
Update this metadata alongside the compiler version. Quill's native build uses
the upstream JRT filesystem/charset flags, while preserving Quill's -Os setting.
