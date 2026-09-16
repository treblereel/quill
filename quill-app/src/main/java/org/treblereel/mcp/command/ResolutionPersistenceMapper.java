package org.treblereel.mcp.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.core.BeanResolver;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.DependencyRecord;
import org.treblereel.mcp.model.InjectionPointRecord;

/** Maps resolver-local identifiers onto the stable identifiers persisted in SQLite. */
final class ResolutionPersistenceMapper {

    private ResolutionPersistenceMapper() {}

    static ProjectInitializer.PersistedResolution remap(
            BeanResolver.ResolutionResult resolution, Map<Integer, Integer> classIds) {
        Map<Integer, Integer> beanIds = new LinkedHashMap<>();
        List<BeanRecord> beans = new ArrayList<>();
        for (BeanRecord bean : resolution.beans()) {
            Integer classId = classIds.get(bean.classId());
            if (classId == null) continue;
            if (bean.declaringClassId() != null && !classIds.containsKey(bean.declaringClassId())) {
                continue;
            }

            int newId = beans.size() + 1;
            beanIds.put(bean.id(), newId);
            beans.add(new BeanRecord(
                    newId, classId, bean.kind(), bean.scope(), bean.qualifiers(), bean.stereotypes(),
                    bean.isAlternative(), bean.priority(), bean.profiles(),
                    bean.declaringClassId() != null ? classIds.get(bean.declaringClassId()) : null,
                    bean.memberName(), bean.beanTypes()));
        }

        Map<Integer, Integer> injectionPointIds = new LinkedHashMap<>();
        List<InjectionPointRecord> injectionPoints = new ArrayList<>();
        for (InjectionPointRecord injectionPoint : resolution.injectionPoints()) {
            Integer ownerBeanId = beanIds.get(injectionPoint.beanId());
            if (ownerBeanId == null) continue;

            int newId = injectionPoints.size() + 1;
            injectionPointIds.put(injectionPoint.id(), newId);
            injectionPoints.add(new InjectionPointRecord(
                    newId, ownerBeanId, injectionPoint.kind(), injectionPoint.targetType(),
                    injectionPoint.qualifiers(), injectionPoint.fieldName(),
                    injectionPoint.resolvedBeanId() != null
                            ? beanIds.get(injectionPoint.resolvedBeanId()) : null,
                    injectionPoint.isAmbiguous()));
        }

        List<DependencyRecord> dependencies = resolution.dependencies().stream()
                .filter(dependency -> classIds.containsKey(dependency.fromClassId())
                        && classIds.containsKey(dependency.toClassId()))
                .filter(dependency -> dependency.injectionPointId() == null
                        || injectionPointIds.containsKey(dependency.injectionPointId()))
                .map(dependency -> new DependencyRecord(
                        classIds.get(dependency.fromClassId()),
                        classIds.get(dependency.toClassId()), dependency.kind(),
                        dependency.injectionPointId() != null
                                ? injectionPointIds.get(dependency.injectionPointId()) : null))
                .toList();

        return new ProjectInitializer.PersistedResolution(beans, injectionPoints, dependencies);
    }

    static ProjectInitializer.PersistedResolution merge(
            List<ProjectInitializer.PersistedResolution> parts, boolean mixedFrameworks) {
        List<BeanRecord> beans = new ArrayList<>();
        List<InjectionPointRecord> injectionPoints = new ArrayList<>();
        List<DependencyRecord> dependencies = new ArrayList<>();

        for (ProjectInitializer.PersistedResolution part : parts) {
            Map<Integer, Integer> beanIds = new HashMap<>();
            for (BeanRecord bean : part.beans()) {
                int id = beans.size() + 1;
                beanIds.put(bean.id(), id);
                beans.add(new BeanRecord(id, bean.classId(), bean.kind(), bean.scope(),
                        bean.qualifiers(), bean.stereotypes(), bean.isAlternative(),
                        bean.priority(), bean.profiles(), bean.declaringClassId(),
                        bean.memberName(), bean.beanTypes()));
            }

            Map<Integer, Integer> injectionPointIds = new HashMap<>();
            for (InjectionPointRecord injectionPoint : part.injectionPoints()) {
                Integer beanId = beanIds.get(injectionPoint.beanId());
                if (beanId == null) continue;
                int id = injectionPoints.size() + 1;
                injectionPointIds.put(injectionPoint.id(), id);
                injectionPoints.add(new InjectionPointRecord(id, beanId,
                        injectionPoint.kind(), injectionPoint.targetType(),
                        injectionPoint.qualifiers(), injectionPoint.fieldName(),
                        injectionPoint.resolvedBeanId() != null
                                ? beanIds.get(injectionPoint.resolvedBeanId()) : null,
                        injectionPoint.isAmbiguous()));
            }

            for (DependencyRecord dependency : part.dependencies()) {
                if (mixedFrameworks && "CLASS_REFERENCE".equals(dependency.kind())) continue;
                Integer injectionPointId = dependency.injectionPointId() != null
                        ? injectionPointIds.get(dependency.injectionPointId()) : null;
                if (dependency.injectionPointId() != null && injectionPointId == null) continue;
                dependencies.add(new DependencyRecord(dependency.fromClassId(),
                        dependency.toClassId(), dependency.kind(), injectionPointId,
                        dependency.occurrenceCount()));
            }
        }
        return new ProjectInitializer.PersistedResolution(
                List.copyOf(beans), List.copyOf(injectionPoints), List.copyOf(dependencies));
    }
}
