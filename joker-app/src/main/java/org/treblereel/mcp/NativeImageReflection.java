package org.treblereel.mcp;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.treblereel.mcp.model.*;

@RegisterForReflection(targets = {
        ClassRecord.class,
        BeanRecord.class,
        InjectionPointRecord.class,
        DependencyRecord.class,
        MetaEnvelope.class,
        GitFileStats.class,
        GitCommitRecord.class,
        GitCommitFile.class,
        CoChangeRecord.class
})
public class NativeImageReflection {
}
