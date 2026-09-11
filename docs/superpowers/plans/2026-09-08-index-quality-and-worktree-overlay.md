# Quill Index Quality and Worktree Overlay Plan

**Goal:** сделать ответы Quill правдивыми относительно текущего checkout, дополнить статический граф ссылками из тел методов и стабилизировать разрешение `path <-> FQCN` для текущих, generated и исторических сущностей.

**Источник:** quality report после использования Quill на `crysknife`. Репозиторий `crysknife` не является целью изменений; регрессии должны воспроизводиться на автономном fixture внутри Joker/Quill.

## Подтверждённые причины

| Наблюдение | Текущая причина в Quill | Приоритет |
|---|---|---|
| Dirty service descriptor не виден | `MetaEnvelope.isStale()` сравнивает только `HEAD`; `computeStateFingerprint()` хеширует build metadata, `.class` и classpath, но не текущие source/resource/untracked files; `GitAnalyzer` читает только commits | P1 |
| Constructor-only dependency даёт `fan_in=0` | `BeanResolver.addNonBeanDependencies()` читает только field/method signatures, пропускает bean classes и не анализирует bytecode instructions; Jandex не предоставляет тела методов | P1 |
| В hotspots остаются удалённые пути | `GitAnalyzer` агрегирует все пути из истории; `IndexReader.findHotspots()` не проверяет состояние пути; схема не хранит `current/deleted/historical` | P1 |
| Hotspot path нельзя передать в class tools | `resolveClass()` принимает только exact FQCN или short class name, хотя описание `get_file_history` обещает class или file; исторический путь связывается с `class_id` только при exact match с текущим source path | P1 |
| `fan_in=377`, но grep находит 35 файлов | `countDependents()` использует `COUNT(*)`, однако API называет результат количеством classes; таблицы не хранят origin/status, поэтому generated/orphan output невозможно отделить | P2, после исправления graph |
| Annotation-processing сценарии не защищены | Нет fixture с несколькими processing rounds, service descriptor, constructor-only edge, удалённым class output и dirty/untracked worktree | P1 quality gate |

## Целевая модель

Quill должен различать три слоя данных:

1. **Indexed structure** — классы и dependency edges из конкретного build snapshot.
2. **Current worktree overlay** — tracked modified/deleted и untracked файлы, вычисляемые относительно текущего `HEAD`.
3. **Git history** — commits и пути, которые могут больше не существовать.

Смешивать эти слои в одно число или один неразмеченный список нельзя. Каждый MCP-ответ сохраняет существующие поля для совместимости и добавляет provenance/status.

---

## Task 1 — P1: единый каталог файлов и schema v2

**Компоненты:**

- создать `quill-core/.../core/FileInventory.java`;
- создать модели `FileRecord`, `EntityAlias` и enum/строковые значения `origin`, `lifecycle`;
- изменить `QuillDatabase`, `IndexWriter`, `IndexReader`;
- изменить `ClassRecord`/таблицу `classes`, добавив стабильную ссылку `file_id`;
- изменить `ProjectInitializer` и discovery Maven/Gradle source roots.

**Предлагаемая схема:**

- `files(id, project_path, repository_path, kind, origin, lifecycle, worktree_status)`;
- unique normalized `project_path` для текущего project root;
- `classes.file_id`, `classes.output_origin`, `classes.lifecycle`;
- `entity_aliases(entity_type, entity_id, alias_type, alias, lifecycle)` для FQCN, short name, current path и historical path;
- `git_file_stats.file_id` и `git_commit_files.file_id`; исходный path в commit record оставить как immutable historical evidence.

**Правила классификации:**

- `origin=source` — `src/main/java` и дополнительные source roots из Maven/Gradle model;
- `origin=generated` — configured generated source/output roots;
- `origin=resource` — в том числе `META-INF/services/*`;
- `origin=orphan_output` — `.class` существует, но Quill не может подтвердить текущий source/generated producer;
- `lifecycle=current|deleted|historical`;
- пути хранятся с `/`, отдельно project-relative и repository-relative; absolute path используется только на границе filesystem API.

**Критерии приёмки:**

- один Java type и его source path разрешаются в один entity независимо от separator ОС;
- subproject внутри monorepo не смешивает project-relative и repository-relative path;
- generated classes получают `origin=generated`, а не `source=null` без объяснения;
- orphan/stale class outputs исключены из current graph по умолчанию и доступны только диагностически;
- schema version повышена; старый index получает понятное требование re-index (текущий механизм rebuild можно сохранить).

---

## Task 2 — P1: freshness и live worktree overlay

**Компоненты:**

- создать `quill-core/.../core/WorktreeInspector.java` поверх JGit status;
- расширить `GitAnalyzer.GitAnalysisResult` и index metadata;
- изменить `ProjectInitializer.computeStateFingerprint()`;
- изменить `MetaEnvelope`, `StatusCommand`, `QuillTools.getHotspots()`, overview и risk response.

**Metadata при индексации:**

- `indexed_commit`, `indexed_at`;
- `indexed_worktree_fingerprint`;
- `indexed_worktree_dirty`, counts по modified/deleted/untracked;
- `structure_scope=compiled_snapshot` и явное состояние dependency index.

**Live `_meta`:**

```json
{
  "indexed_commit": "...",
  "current_commit": "...",
  "commit_stale": false,
  "worktree_dirty": true,
  "worktree_changed_files": 3,
  "structure_stale": true,
  "stale_reasons": ["worktree_changed_after_index"]
}
```

**Поведение file/git tools:**

- `find_git_hotspots` получает отдельный `worktree_changes` и помечает совпавшие hotspots через `worktree_status`;
- dirty/untracked файлы показываются до исторического ranking, даже если у них `commit_count=0`;
- список ограничивается параметром/cap и возвращает `total` + `truncated`;
- service descriptors и другие resources являются полноценными file entities;
- structural tools не притворяются, что разобрали непрокомпилированный Java overlay: они выставляют `structure_stale=true` и рекомендуют `quill update --compile`.

**Consistency:** снять commit + worktree fingerprint до индексации и повторить проверку перед atomic publish. Если relevant file изменился во время индексации, staged DB не публиковать.

**Критерии приёмки:**

- изменение порядка строк в `META-INF/services/javax.annotation.processing.Processor` видно без commit;
- untracked source/resource виден в overlay;
- изменение при неизменном `HEAD` приводит к `structure_stale=true`;
- чистый worktree после свежего index даёт все stale flags `false`;
- MCP stdout остаётся только JSON-RPC; диагностические сообщения остаются на stderr.

---

## Task 3 — P1: bytecode dependency scanner для imperative references

**Компоненты:**

- создать `quill-core/.../core/BytecodeDependencyScanner.java`;
- использовать ASM core visitor (предпочтительно) либо эквивалентный проверенный classfile reader;
- интегрировать результат с `BeanResolver`/`SpringResolver` до persistence;
- расширить `DependencyRecord.kind` и обеспечить deduplication.

**Минимально поддерживаемые bytecode references:**

- `NEW` + вызов `<init>` -> одно ребро `CONSTRUCTS`;
- method invocation owner -> `CALLS`;
- field instruction owner -> `FIELD_ACCESS`;
- `CHECKCAST`, `INSTANCEOF`, array/type literal -> `TYPE_USE`;
- descriptors методов/полей, exceptions, method handles и `invokedynamic` bootstrap arguments;
- local variable type при наличии debug metadata — как дополнительный `TYPE_USE`, но не как единственный источник истины.

Сканирование применяется ко всем application classes, включая bean classes. Dependency JAR types не нужно добавлять в внутренний class graph; они продолжают идти в external dependency data.

**Критерии приёмки:**

- `new BeanProcessorTask(...)` создаёт inbound edge к `BeanProcessorTask`, даже если type отсутствует в field/method signature;
- constructor instruction не считается дважды из-за пары `NEW`/`INVOKESPECIAL`;
- одинаковые references внутри одного owner агрегируются предсказуемо: отдельные `edge_count` и unique target relationship;
- существующие CDI/Spring injection edges сохраняются и отличаются по `kind`;
- прирост native binary проверяется против текущего лимита 45 MiB.

---

## Task 4 — P1: единый EntityResolver для path, FQCN и historical aliases

**Компоненты:**

- создать `quill-app/.../mcp/EntityResolver.java`;
- заменить локальный `QuillTools.resolveClass()`;
- добавить queries в `IndexReader` по file id, normalized path, aliases и lifecycle;
- применить resolver в `get_dependencies`, `assess_change_risk`, `get_file_history`, `find_co_changed_files`, `search_classes`.

**Resolution order:**

1. exact current FQCN;
2. exact normalized project/repository path;
3. exact alias/short name, только если результат уникален;
4. historical alias с явной пометкой;
5. ranked candidates по basename, simple class name и package suffix.

**Ошибки становятся структурированными:**

```json
{
  "error": "Entity not found",
  "target": "...",
  "accepted_target_types": ["fqcn", "project_path", "repository_path"],
  "candidates": [
    {"class": "...", "file": "...", "lifecycle": "current", "reason": "same simple name"}
  ]
}
```

**Критерии приёмки:**

- path из `find_git_hotspots` можно без преобразования передать в history/co-change/risk;
- старый путь после rename либо разрешается через alias, либо возвращается как historical file, но не как загадочный `Class not found`;
- ambiguous short name всегда возвращает candidates;
- `search_classes` различает current/generated/orphan и по умолчанию не предлагает deleted/historical class как текущий.

---

## Task 5 — P2: корректная семантика fan-in/fan-out и provenance breakdown

**Компоненты:**

- изменить aggregate queries в `IndexReader`;
- изменить `get_dependencies`, `get_overview`, `assess_change_risk` и tool descriptions;
- добавить модель `DependencyMetrics` или аналогичный DTO.

**Исправления:**

- `fan_in` = `COUNT(DISTINCT from_class_id)`, а не `COUNT(*)`;
- `fan_out` = `COUNT(DISTINCT to_class_id)`;
- отдельно вернуть `incoming_edges`/`outgoing_edges`;
- разбить unique dependents и edges по `origin=source|generated|orphan_output` и lifecycle;
- orphan/deleted entries не участвуют в risk score по умолчанию;
- historical относится к Git/file metrics, а не маскируется под current static dependency;
- описания signal должны говорить "unique classes", если метрика действительно unique.

**Пример:**

```json
{
  "fan_in": 35,
  "incoming_edges": 377,
  "fan_in_breakdown": {
    "source": {"classes": 30, "edges": 91},
    "generated": {"classes": 5, "edges": 286},
    "orphan_output": {"classes": 0, "edges": 0, "excluded": true}
  }
}
```

**Критерии приёмки:**

- два injection/call edges из одного owner дают `fan_in=1`, но `incoming_edges=2`;
- сумма breakdown совпадает с totals;
- risk score использует unique current dependents;
- API поясняет scope каждой метрики и не требует сравнивать число с grep вслепую.

---

## Task 6 — P1 quality gate: annotation-processing regression fixture

**Расположение:** новый автономный Maven multi-module fixture, например `tests/annotation-processing/`; при необходимости добавить маленький Gradle companion test только для discovery parity.

**Fixture должен содержать:**

- два annotation processors, зарегистрированных через `META-INF/services/javax.annotation.processing.Processor`;
- generated type первого round и type, сгенерированный/потреблённый в следующем round;
- application class с единственной ссылкой `new ConstructorOnlyDependency()`;
- tracked Java class, затем удаляемый в temp Git history;
- stale `.class`, оставшийся после удаления source;
- dirty service descriptor и untracked Java/resource file, создаваемые внутри теста.

**Тестовые уровни:**

- unit: `WorktreeInspectorTest`, `BytecodeDependencyScannerTest`, `EntityResolverTest`;
- DB contract: schema/status/origin/alias round trip и distinct metric queries;
- MCP integration: все пять инструментов из отчёта возвращают согласованные entity/status/counts;
- native MCP smoke: тот же JSON contract хотя бы для overlay + constructor-only edge;
- Windows assertions используют normalized `/` в JSON независимо от filesystem separator.

**End-to-end acceptance:**

1. fresh clean build/index не stale;
2. constructor-only dependency имеет `fan_in=1`;
3. generated classes обоих rounds помечены `generated` и разрешаются по FQCN/path;
4. после удаления source stale output не становится current hotspot/class;
5. deleted historical path отсутствует в current hotspots, но доступен с `include_historical=true` и `lifecycle=historical|deleted`;
6. dirty service descriptor и untracked file появляются в worktree overlay без commit;
7. каждый ответ содержит `indexed_commit`, `current_commit` и однозначные stale flags.

---

## Порядок реализации

1. Сначала characterization tests для текущих ошибок и Task 1 (без file identity следующие задачи будут создавать временные несовместимые решения).
2. Task 2 и Task 3 можно выполнять независимо после schema/file inventory.
3. Task 4 после Task 1; подключить сразу ко всем class/file tools.
4. Task 5 после нового graph, иначе придётся дважды менять semantics и fixtures.
5. Task 6 развивается вместе с каждой задачей, а полный native E2E является финальным gate.

## Ограничения и решения

- **Не парсить Java source для имитации полного graph overlay.** Пока dirty source не скомпилирован, Quill сообщает stale structure; после `update --compile` authoritative source — bytecode.
- **Не удалять исторические данные.** Current APIs фильтруют их по умолчанию, history APIs сохраняют с lifecycle/status.
- **Не привязывать тесты к локальному checkout crysknife.** Fixture должен быть детерминированным и работать в CI/Linux/Windows/native.
- **Сохранить additive MCP compatibility.** Старые поля остаются; новые status/provenance поля добавляются. Изменение смысла `fan_in` фиксируется в release notes как исправление ошибки.

## Definition of Done

- все acceptance criteria Tasks 1–6 покрыты автоматическими тестами;
- `mvn verify -DskipITs=false` проходит на Linux/Windows;
- native profile и MCP smoke проходят без превышения 45 MiB;
- README описывает indexed snapshot, live overlay, stale flags и scope fan-in;
- никакие изменения в `crysknife` не требуются.
