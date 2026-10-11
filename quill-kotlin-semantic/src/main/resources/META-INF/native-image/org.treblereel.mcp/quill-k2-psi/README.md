Additional metadata for Quill's PSI-backed K2 frontend (useFirLT=false).
The upstream Kotlin compiler metadata primarily covers its LightTree frontend.
Collected on GraalVM CE 25.0.1 with Kotlin 2.4.21 across native E2E cases,
including Java source roots, JDK classes, nested types and invalid Kotlin.

Regenerate after building the JVM distribution:

    python3 scripts/quill_kotlin_native_e2e.py \
      --java-jar quill-app/target/quill-app-0.1-SNAPSHOT-all.jar \
      --agent-output /tmp/quill-k2-agent

Copy /tmp/quill-k2-agent/reachability-metadata.json here, and verify the
native executable with scripts/quill_kotlin_native_e2e.py before committing.

reflect-config.json additionally registers all Kotlin PSI constructors taking
ASTNode. The parser invokes these reflectively for arbitrary Kotlin syntax;
tracing a finite fixture cannot cover every node. Regenerate this file with:

    java --class-path quill-app/target/quill-app-0.1-SNAPSHOT-all.jar \
      scripts/QuillKotlinPsiMetadata.java > reflect-config.json
