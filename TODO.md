# TODO

- [x] Поддержка Git submodules в Quill: независимые индексы и границы репозиториев.
  - Обнаружение gitlinks через локальный Git, включая вложенные и неинициализированные submodules; глубина обхода ограничена 8, превышение помечается явно.
  - Отдельные ревизии и индексы; классы child не попадают в индекс parent. Dirty JVM-файлы child инвалидируют структурный контекст parent.
  - JVM и native: `init`, incremental `update`, workspace init/refresh и Git status проверены локальной составной фикстурой.
  - Quill не делает fetch/clone/checkout/update submodule: пользователь сначала инициализирует и собирает child.

- [ ] После завершения интеграции измерить эффективность всего набора на независимых эталонах; платные прогоны ожидают отдельной команды пользователя.
- [ ] Проверить доставку hook-контекста установленными CLI в сырых модельных запросах; сейчас проверены локальные stdin/stdout, конфигурация и жизненный цикл cache.
## Эффективность токенов — план по прогону cryostat v3 (2026-11-01)

Исходные данные: `benchmarks/results/cryostat-full-native-current-v3-quill-only/`, сборка `33f9120f3`,
сравнение с историческим `without_quill` из `cryostat-full-native-coverage-v1`.

| | Точность | ALL | uncached+output |
|---|---|---|---|
| Codex | 100% → 100% | 8.69M → 9.39M (0.93x) | 1.82M → 1.81M (1.00x) |
| Claude | 47% → 57% | 5.19M → 4.96M (1.05x) | 652k → 887k (0.74x) |

Ответы Quill: 416 вызовов, 2.62M символов (≈650k токенов) на 87 сессий.

**Про цель 4x.** У Codex фиксированный порог ≈20k uncached на сессию (системный промпт, каталог,
prewarm ≈12k) в обоих плечах при медиане 40k: на коротких вопросах предел ≈2x, что бы ни делал
Quill. Claude без Quill тратит медиану 9k на вопрос, с Quill 17k. 4x достижимо только на задачах,
где без Quill агент читает много кода (CQ-045, CQ-050, patch/linked). Предлагаемые цели для этого
набора: точность не ниже, Claude uncached+output ≥1.3x, Codex ≥1.2x, ALL ≥1.3x у обоих;
4x считать отдельно на подмножестве «тяжёлых» задач.

### Фаза 0. Измерение без платных прогонов
- [x] Офлайн-реплей: `scripts/quill_tool_replay.py`, базовая линия и команда —
  [benchmarks/analysis/tool-replay-cryostat-v3](benchmarks/analysis/tool-replay-cryostat-v3/README.md).
  Бинарник v3 воспроизводит записанные ответы точно (2,617,749 символов, 0 регрессий).
  Каждое изменение ниже проверять этим реплеем до платного прогона.
- [x] Порог в реплее: `symbol_details_larger_than_source` (сейчас 53 из 58 вызовов).

### Фаза 1. Размер ответов (оценка экономии по v3; токены ≈ символы/4)
1. [x] `get_symbol_details` (Claude: 52 вызова, 50% всех символов; в 53 из 58 вызовов ответ больше
   исходника — 170k токенов против 91k). `members` = 541k символов, ≈820 на член:
   убрать per-member `symbol_id` (выводится из класса + kind/name/descriptor), `location` с
   `line:null`, `jvm_descriptor`/`jvm_name`/`source_name`, равные `descriptor`/`name`,
   `language`, дубль `annotation_details`/`annotations`, пустые массивы. Члены — строками
   `members_columns`. Для класса меньше ~2k токенов со свежим хешем отдавать исходник вместо
   списка членов (агент всё равно читает файл: CQ-037 сделал Read параллельно). ≈ −90k токенов.
   - [x] Члены без повторов идентичности класса (реплей: 682k → 311k символов, x0.456;
     `get_context` x0.882; ответов больше исходника 53 → 32).
   - [x] Java-файл < 2k токенов со свежим хешем отдаётся как `source_text` вместо списка членов
     (без `member_kind`/`member_offset`). Реплей и тест ещё не прогнаны.
2. [ ] `_meta` (≈600 символов в каждом ответе, 175k = 6%; 15% у `list_beans`): при свежем индексе
   `{"index_id","fresh":true}`; подробности — только при stale/dirty-структуре. Сейчас полная форма
   выдаётся при `worktree_dirty` без структурных изменений. ≈ −40k.
3. [ ] Пустые/null поля (121k, 4%) — общий проход сериализации, кроме полей-доказательств
   (`*_proven:false`, `has_more`). ≈ −28k.
4. [ ] `find_configuration_references` (355k): `declared_source` 12%, `field_read_source` 11%,
   повтор `module`/`sourceSet`/`classId`/`resolution_strategy`/`definition_files` в каждой из
   43 строк wildcard-запроса → `row_defaults`; для wildcard-ключа меньший лимит по умолчанию и
   группировка по префиксу. ≈ −30k.
5. [ ] `list_injection_points` (359k): `provider_evidence` 20% (общий `scope` в defaults,
   повтор artifact/framework), `resolution_trace` 20% с пустыми `applied_rules`/`candidates`,
   пустые `limitations`. ≈ −22k.
6. [ ] `get_call_hierarchy` (297k): `caller`/`callee` 44% — таблица методов один раз, рёбра по
   индексу; `dispatch_targets` 16%. ≈ −22k.
7. [ ] `find_entry_points` (158k, в среднем 39k символов): `observer` 30%, `annotation_values` 16%.
   ≈ −15k.
8. [ ] `list_beans` (208k): `arc_retention` 20% — повторяющийся `note` вынести в defaults,
   сигналы строкой; пустые `profiles`, суффикс `:0` в `source`. ≈ −10k.
9. [ ] `get_project_dependencies` (90k): абсолютный путь `jar` 21% — только по запросу. ≈ −5k.

Итого фаза 1: ≈ −1.1M символов из 2.62M (≈ −42%, ≈ −260k токенов на прогон).

### Фаза 2. Поведение агентов (главный резерв для ALL)
10. [ ] Codex: 69 из 234 вызовов — `search_tools` (+37 `execute_tool`); каждый лишний ход
    пересылает весь контекст. Отдавать Codex основные инструменты напрямую (профиль `core`/
    `di`), `search_tools` — только для редких. В ответе `search_tools` не повторять `guidance`
    (31% ответа, есть в instructions) и сжать `input_schema` (45%).
11. [ ] Повторное чтение: Claude с Quill всё ещё делает 75 Read и 46 Grep (без Quill — 177/183).
    Добавлять в ответ точный диапазон строк и признак «исходник свежий», чтобы Read шёл
    по `offset/limit`, а не по файлу целиком; проверить на реплее CQ-037, CQ-012, CQ-029.
12. [ ] Prefetch-хук: 2.8k символов в каждый промпт Claude — проверить, сколько фактов
    используется, и не отдавать `get_symbol_details`-подобный факт, если тот же вызов
    вероятен следующим.

### Фаза 3. Точность
13. [ ] Разобрать 5 регрессий Claude (CQ-016, CQ-028, CQ-029, CQ-031, CQ-043) и 10 улучшений:
    что в ответе Quill привело к ошибке.
14. [ ] `@Inject Logger`: точка `unknown/NO_CONTEXT_CANDIDATE`, хотя `provider_evidence` нашёл
    `LoggerProducer.getSimpleLogger` с подходящим `@Default`. Единственный подходящий producer
    из зависимости — отдельный статус разрешения, не `unknown`.

15a. [ ] `list_injection_points io.cryostat.Producers` → `NOT_A_BEAN` в 5 сессиях: класс с
    producer-методами без bean-defining аннотации ArC делает `@Dependent` бином
    (`quarkus.arc.auto-producer-methods`, по умолчанию true). Индексировать такой бин.
15b. [x] Найдено реплеем и исправлено: `find_cycles` scope=class падал на вложенном классе с
    владельцем без рёбер; `change_session`/`plan_change`/`verify_change`/`find_impacted_tests`
    с целью `Class#method` требовали граф (CQ-050).
15c. [ ] Реплей: отделить внутренние ошибки (`TOOL_ERROR`) от ответов на неверный запрос
    (`CLASS_NOT_FOUND`, `AMBIGUOUS_METHOD`, неверные аргументы) в `failures`.

### Фаза 4. Платная проверка
15. [ ] Только после фаз 1–2 и реплея: повторить v3 (Quill-плечо, тот же baseline), отчёт по
    тем же метрикам + отдельно по «тяжёлым» задачам.

- [x] Общий офлайн-детектор Codex/Claude: `scripts/quill_response_reuse_audit.py`, [правило и ограничения](benchmarks/response-reuse-policy.md). Проверяет доставку и покрытие, включая отклонённые клиентом попытки; неоднозначные семантические повторы/чтения требуют разбора, а не объявляются автоматически лишними. `review_required` блокирует автоматическое разрешение; исходные ответы/решения не переписываются.
