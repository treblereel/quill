# TODO

- [x] Поддержка Git submodules в Quill: независимые индексы и границы репозиториев.
  - Обнаружение gitlinks через локальный Git, включая вложенные и неинициализированные submodules; глубина обхода ограничена 8, превышение помечается явно.
  - Отдельные ревизии и индексы; классы child не попадают в индекс parent. Dirty JVM-файлы child инвалидируют структурный контекст parent.
  - JVM и native: `init`, incremental `update`, workspace init/refresh и Git status проверены локальной составной фикстурой.
  - Quill не делает fetch/clone/checkout/update submodule: пользователь сначала инициализирует и собирает child.

- [ ] После завершения интеграции измерить эффективность всего набора на независимых эталонах; платные прогоны ожидают отдельной команды пользователя.
- [ ] Проверить доставку hook-контекста установленными CLI в сырых модельных запросах; сейчас проверены локальные stdin/stdout, конфигурация и жизненный цикл cache.

## Gaps from the English runs of 2026-11-18 (Apollo and Cryostat full/base-20261118)

Every miss below had the fact in the packet or in Quill results; the answer left it out.

- [ ] Apollo AQ-009 (Claude: +++ without Quill, --- with it): `injects` lines carry no injection
  mechanism (`FIELD ConfigPublishEmailBuilder.appNamespaceService: ... [RESOLVED]`), so Claude answers
  from the packet without `@Autowired`. Add the mechanism (`@Autowired`, `@Inject`, `@Resource`,
  constructor parameter) to the line; check first that the index keeps the injection annotation.
- [ ] Apollo AQ-049 costs more with Quill: Claude 631k vs 396k input, Codex 466k vs 318k. Patch case in
  a disposable checkout; find what the agent does with Quill there.
- [ ] Apollo: Codex with Quill costs more than without on 15 of 44 cases (AQ-040, AQ-026, AQ-028,
  AQ-036, AQ-043: +30-50k each); Codex saves 10% on Apollo against 43% on Cryostat.
- [x] Cryostat CQ-041 (Claude r2, r3, no Quill calls): the blast-radius answer omits that `resolve`
  returns `Optional`, which the packet states 7 times. 5060409b names the type in
  `caller_result_use`; cq041-20261118: Claude 3/3 (was 1/3), Codex 3/3, same 12k input.
- [ ] Cryostat CQ-027 (Claude r2, no Quill calls): the answer omits the `readValue` call the packet lists.
- [x] Cryostat CQ-044 (both clients): answers add a new producer class instead of the existing
  `Producers`, which the packet names. 5c7e5e74 adds `application_producer_hosts_basis`;
  cq044b-20261118: Claude 3/3 (was 2/3), Codex 2/3 (was 1/3, and 0/2 on agents10).
- [x] Chronic in every arm, question or rubric rather than Quill: CQ-045 (`SsarClientCacheTest`
  not named), CQ-049 (`resolve` not named although the changed lines are inside it). Rubric: CQ-045
  drops the test point the question never asks for; CQ-049 accepts "all three call sites".
- [ ] Apollo AQ-018 Codex (`repositories`), chronic in every arm.
- [ ] CQ-049: Claude applies the rename in the benchmark checkout in all 6 sessions although the
  question requires a disposable checkout; Codex always uses one. The rubric does not score it.
- [ ] Re-queries of a prefetched target: 23% of Codex and 33% of Claude Quill calls. Measure any change
  to the instructions sentence alone (see 7f5071b6).
- [ ] Codex answers with Quill got shorter (median 2036 -> 1851 chars) and drop names they had.
- [x] CQ-012 (Codex 0/3 with and without Quill): answers wrote `@Identifier(...)` instead of
  `io.smallrye.common.annotation.Identifier`. 34d7e7f2 states the qualifier type match by FQCN;
  cq012-20261118: Codex 3/3 at 16k input (was 0/3 at 34-53k), Claude 3/3 (was 2/3).

### Cryostat full2-20261118 (with Quill, after 5c7e5e74, 34d7e7f2, 5060409b and AGENTS.md v10)

Rubric: CQ-038 accepts "Explicit in-memory override" and "Application fixture"; CQ-048 accepts
"regenerates" and "do not patch". Rescored: full2 Claude 101/105 (6.28M, $12.70), Codex 100/105
(12.13M, $6.54); full Claude 98, Codex 98; base Claude 79 (was 77), Codex 86.

- [x] Codex Quill calls rose to 535 from 306 at +4% input. Cause: AGENTS.md v10 (full ran v9).
  Codex batches Quill calls inside its `exec` steps, so steps stay 3.94 vs 3.86 per session; Quill
  replaces shell reads: shell read/search commands 366 vs 662, steps with shell 235 vs 317, first
  step goes to Quill in 101 vs 70 sessions; 3 Quill calls in one step 75 vs 18 times. Count calls
  per step, not calls, when comparing Codex runs.
- [ ] Codex input rose most on CQ-050 (527k vs 445k per 3 sessions), CQ-025 (207k vs 146k),
  CQ-032 (252k vs 204k), CQ-011 (90k vs 51k); see which Quill results there are large.
- [ ] CQ-027 Codex 1/3: answers say "JSON deserialization" and drop `readValue`.
- [ ] CQ-042 Claude r2: a 656-char answer names only the `Producers.BASE64_URL` constant, not the
  five injection sites.
- [ ] Single misses: CQ-001 Claude (quinoa), CQ-025 Claude, CQ-035 Codex (StartupEvent), CQ-038
  Claude (fixture source unnamed), CQ-044 Codex (Producers), CQ-047 Codex (enabled key).

## Сравнимость прогонов Apollo и Cryostat (2026-11-15)

Оба набора запускаются через `benchmarks/bench.py` в одну раскладку, но протокол пока разный, поэтому
их цифры нельзя сравнивать напрямую.

- [ ] Одинаковое число повторов: Cryostat идёт с 3 повторами (264 сессии), Apollo — с 1 (88 сессий).
  При разбросе около ±30% по токенам один повтор не позволяет делать выводы по отдельным кейсам.
  Перейти на 3 повтора в Apollo (`bench.py new apollo NAME --repeats 3`).
- [ ] Одинаковый контроль без Quill: Cryostat сравнивается с объединённым контролем из paired-прогонов
  (v41, v62), Apollo — с одним прогоном `baseline-v1` (1 повтор). Снять контроль Apollo с теми же
  повторами на закреплённом fixture (vault) и сравнивать оба набора одним способом в `bench.py compare`.
- [ ] Одинаковая запись трафика: у Cryostat есть `wire/` для Codex (разбивка cached/uncached), у
  Apollo нет. Либо писать трафик и в Apollo, либо считать оба набора только по `session.json`.
- [ ] Одинаковая оценка точности: rubric Cryostat (v17) и Apollo (калиброван по слепым оценкам v1)
  проверяют разное число фактов на кейс. Проверить калибровку rubric Cryostat тем же способом.

## Новые инструменты для Quarkus/Spring (2026-11-12, по прогонам V41–V55)

Спрос взят из сессий с Quill: несуществующие инструменты, которые агенты искали через
`search_tools`/`ALL_TOOLS` (все прогоны), и собственные поиски агентов (`rg`, `Grep`, `Read`) в V41.
Вопросы cryostat смещены к DI и конфигурации, поэтому пункты 10–13 добавлены по опыту, а не по данным.

### Сильный спрос
1. [ ] `resolve_configuration(key, profile)`: итоговое значение и цепочка источников по приоритету —
   `%profile`-ключи, `application-<profile>.properties`, переопределения тестовых профилей, значения по
   умолчанию из `@ConfigProperty`/`@WithDefault` и модели расширения, имя переменной окружения; что
   неизвестно до запуска. Спрос: 134 собственных поиска в 35 сессиях, 8 поисков инструмента.
   Проверка: CQ-038, CQ-023, CQ-037, CQ-039.
2. [ ] `get_security_model(endpoint|class)`: действующие правила доступа — `@RolesAllowed`/
   `@PermissionsAllowed`/`@Authenticated` на классе и методе с наследованием, пути
   `quarkus.http.auth.permission.*`, свои `HttpAuthenticationMechanism` и `SecurityIdentityAugmentor`;
   для Spring — `SecurityFilterChain`, `@PreAuthorize`. Спрос: 52 поиска в 12 сессиях, 14 поисков
   инструмента. Проверка: CQ-032, CQ-005, CQ-025.
3. [ ] Модель сборки проекта: плагины и цели, Maven-профили, свойства, `quarkus.package`/native,
   модули, сборка фронтенда (Quinoa) — то, чего нет в `get_project_dependencies`. Спрос: 137 поисков
   в 31 сессии (`pom.xml`, `Dockerfile`, compose). Проверка: CQ-001.
4. [ ] `get_transaction_boundaries`: `@Transactional` с `TxType` на методе и классе, вызовы
   `QuarkusTransaction`, самовызов в обход прокси, наблюдатели с `TransactionPhase`. Сейчас это алиас на
   `get_context`. Спрос: 24 поиска инструмента. Проверка: CQ-027.
5. [ ] `trace_event_flow(type)`: места отправки `Event<T>.fire`/`fireAsync` с квалификаторами ↔
   наблюдатели (sync/async, `@Priority`, фаза транзакции). `find_entry_points` даёт только
   наблюдателей. Спрос: 18 поисков `list_observers`/`trace_injection_chain`. Проверка: CQ-010, CQ-027.

### Средний спрос
6. [ ] Цепочка перехватчиков и декораторов для метода: привязки со стереотипами и наследованием,
   порядок по `@Priority` (сейчас только на уровне бина в `list_injection_points`).
7. [ ] Динамический поиск бинов: `Instance<T>`, `select()`, `@All`, `@LookupIfProperty`/
   `@LookupUnlessProperty` — какие бины могут вернуться при какой конфигурации. Проверка: CQ-023.
8. [ ] Модель потоков для эндпоинта или метода: event loop / worker / virtual thread
   (`@Blocking`, `@NonBlocking`, `@RunOnVirtualThread`, `Uni`, `runSubscriptionOn`). Проверка: CQ-025.
9. [ ] Тестовые профили и подмены: какие `QuarkusTestProfile` что переопределяют, где
   `@InjectMock`/`@TestProfile`. Проверка: CQ-047.

### По опыту (в cryostat почти не встречается)
10. [ ] Модель персистентности: сущности и связи, Panache-репозитории, именованные запросы,
    слушатели сущностей, миграции Flyway/Liquibase против сущностей.
11. [ ] Топология сообщений: `@Incoming`/`@Outgoing`/`Emitter`, настройки коннекторов (топики Kafka).
12. [ ] Spring: почему бин есть или его нет — `@ConditionalOnProperty`/`OnClass`/`OnBean`, `@Profile`,
    переопределение определений, прокси и самовызов.
13. [ ] Готовность к native: что требует регистрации для рефлексии (`@RegisterForReflection`) и какие
    ресурсы включить.

## План 4x по ALL токенам (2026-11-01, по прогону v4)

**Бюджет.** 4x по ALL = 1/4 исторического `without_quill` на те же 49 ответов.

| | без Quill | цель 4x | на ответ | сейчас с Quill |
|---|---|---|---|---|
| Codex | 9.09M | 2.27M | 46k | 10.60M: 339 запросов (≈6.9 на ответ) × 27.5k + prewarm 538k |
| Claude | 5.19M | 1.30M | 26.5k | 4.56M: 205 запросов (≈4.2 на ответ) × 21.8k |

ALL почти целиком — входной контекст, повторяемый на каждом запросе модели (кэш 80–90%),
поэтому решает число запросов, а не размер ответов Quill. У Codex prewarm (≈12k на сессию)
съедает четверть бюджета; на сам ответ остаётся ≈34k — это **1–1.5 запроса**. У Claude —
**≈1.2 запроса**. Значит, 4x достижимо, только если большинство вопросов отвечается с первого
хода модели, по фактам, которые Quill положил в контекст до этого хода. По uncached+output 4x
недостижимо в принципе: одно создание кэша промпта (≈15–20k на сессию) больше всего бюджета.

Сейчас prefetch-хук срабатывает только на литеральные идентификаторы в вопросе
(≈4.9k символов на сессию), а с нулём вызовов инструментов прошла 1 сессия Claude из 49.

### Шаг 1. Офлайн-метрика «ответ с первого хода» (без платных прогонов)
1. [x] `scripts/quill_answerability.py`: для каждого вопроса из `oracles.json` запустить
   native prefetch на точном тексте вопроса и проверить, покрывают ли факты пакета каждый
   ожидаемый элемент оракула (модули, бины, ключи, вызовы, тесты). Результат по случаю:
   `covered`/`partial`/`missing` и размер пакета в токенах. Это главный показатель плана:
   пока покрытие низкое, платный прогон не нужен.
   Базовая линия (бинарник v4, `benchmarks/analysis/answerability-cryostat-v4/`): пакет есть в
   40 из 44 случаев, но полностью покрыт 4 из 39, среднее покрытие идентификаторов 18% при
   пакете ≈830 токенов. Покрытие — нижняя граница (упоминание, не связь), цель шага 2 —
   ≥80% случаев полностью.
2. [x] `scripts/quill_turn_budget.py`: по сессиям v4 — запросы на ответ, состав префикса
   (системный промпт, каталог, инструкции, prefetch, ответы инструментов) и что делал каждый
   лишний ход (Read/rg после Quill, повтор, поиск инструмента). Цель — список ходов, которые
   убирает каждый следующий шаг.
   Результат v4 (`benchmarks/analysis/turn-budget-cryostat-v4.json`), ALL по типу хода:

   | | Quill | поиск инструмента | rg/grep/find | чтение файлов | сборка | прочее | финальный ответ |
   |---|---|---|---|---|---|---|---|
   | Codex (339 запросов, 10.06M) | 28% | 15% | 20% | 8% | 3% | 6% | 19% |
   | Claude (205 запросов, 4.47M) | 36% | 0.5% | 19% | 15% | 2% | 0.4% | 27% |

   Финальный ход неизбежен: у Claude он один уже 1.23M (цель 4x — 1.30M), у Codex 1.89M +
   prewarm 0.54M (цель 2.27M). Значит, кроме отказа от промежуточных ходов, нужен и меньший
   финальный запрос: ответ с первого хода приходит без накопленного контекста.

### Шаг 2. Пакет фактов под вопрос вместо prefetch по идентификаторам
3. [ ] Классификатор вопроса (детерминированный, без модели) по классам оракулов: состав
   модулей и зависимостей, CDI (бины, точки внедрения, producers, alternatives/priorities,
   decorators/interceptors, события), конфигурация (ключи, профили, потребители), HTTP/GraphQL
   эндпоинты, вызовы и исполнение, тесты и влияние, план изменения.
4. [ ] Для каждого класса — фиксированный набор запросов Quill и сборка одного пакета
   ≤4–6k токенов: таблица ответа, точные `file:line` с короткими фрагментами исходника,
   `complete: true/false` с причиной неполноты, ограничения рантайма одной строкой.
5. [ ] Явная полнота: если индекс свежий и перечисление полное, пакет так и говорит
   (`enumeration_complete`, `index_fresh`, `source_sha256`), чтобы модель не проверяла через
   Read/rg. Где Quill не уверен — сказать, что именно проверить, одним путём и диапазоном строк.
6. [ ] Инструкции (`AGENTS.md`/`CLAUDE.md`, сейчас 3.8k/3.4k символов) сократить до правила:
   «если в контексте есть пакет Quill с `complete: true`, отвечай по нему; инструменты — только
   для `partial`». Остальное — в описания инструментов.

**Итог шага 2 на 2026-11-01** (`benchmarks/analysis/answerability-cryostat-v4/`, JVM-сборка,
копия фикстуры, переиндексированная текущей сборкой): пакет есть для 44 из 44 вопросов, полностью
покрыто 29 из 39 (74%, было 4), среднее покрытие идентификаторов 89.8% (было 19.7%), пакет
≈1.1k токенов (лимит хука 10 KB). Классификатора формулировок нет: пакет строится по виду цели в
вопросе (класс, `Class#field`, метод, пакет, тип из зависимостей, ключ конфигурации, endpoint).
Цель 80% (32 из 39) тогда не достигнута; оставшиеся 10 вопросов:
- нет в индексе: сгенерированные классы (CQ-048, CQ-024 — 15f), привязки внешних interceptors
  (CQ-026 — 15e), путь `pom.properties` внутри jar (CQ-004);
- оракул ссылается на локальную переменную или ожидает решение, а не факт (CQ-033
  `basicRemote.sendPing`, CQ-044 `Producers.java`);
- глубже двух уровней вызовов или через унаследованный метод (CQ-027 `persistAndFlush`),
  чтение полей на пути endpoint (CQ-031 `target.jvmId`), ключ кэша, выводимый из
  `@CacheResult` (CQ-050), один файл из 15 (CQ-043).

**Проверка точности на 2026-11-02:** все 29 уникальных пакетов сверены с эталонами
(`expected`, `forbidden_claims`) и исходниками фикстуры. Запретных утверждений нет. Исправлены одно
ложное утверждение (о предзагрузке тела обработчика endpoint) и пять вводящих в заблуждение
формулировок: «unresolved» при наличии провайдера в зависимостях, декораторы без пометки,
наблюдатели всех событий класса вместо отправляемого, счётчик классов пакета вместе с тестами,
`:0` вместо неизвестной строки. Покрытие то же: 29/39, 89.8%. Детали — в README отчётов.
**Итог доработки индекса на 2026-11-02:** полностью покрыто 35 из 39 оцениваемых вопросов (90%),
среднее покрытие 95.8%, пакет ≈1.2k токенов. Пять вопросов (CQ-013, 020, 032, 038, 039) эталон
описывает без идентификаторов, метрика их не оценивает; в прежних заметках знаменатель 42 был
ошибкой, отчёты всегда считали из 39. Сделано:
- 15e: interceptors из зависимостей хранят binding-аннотации (`external_beans.interceptor_bindings`,
  схема v34); факт метода называет interceptors, которые его привязывают, с `@Priority` и
  проверкой binding-членов (CQ-026).
- 15f: классы из `quarkus-app/quarkus/generated-bytecode.jar` связаны с исходным классом
  приложения (метаданные `quarkus_generated_classes`, с отметкой, старше ли jar скомпилированных
  классов); факт сгенерированного класса и список сгенерированных у класса (CQ-048, CQ-024).
- Унаследованный вызываемый метод ищется по цепочке суперклассов, включая байткод зависимостей
  (CQ-027); обработчик endpoint показывает чтения полей других классов (CQ-031); связанные
  файлы класса включают production-классы, которые его вызывают (CQ-043).
Не покрыты: CQ-004 (путь `pom.properties` и имя jar в сборке приложения), CQ-033 (локальная
переменная `basicRemote`), CQ-044 (решение, куда добавить producer), CQ-050 (`Bean.getScope` и
ключ кэша из `@CacheName`). Старые индексы схемы v33 нужно пересобрать (`quill init`), в том
числе общую фикстуру бенчмарка. Модельный прогон не делался.
Повторная проверка точности (2026-11-02): новые виды фактов сверены со всеми 44 пакетами,
исходниками, `javap` и `generated-bytecode.jar`; запретных утверждений нет. Исправлены: конструкторы,
ошибочно помеченные унаследованными; interceptor из narayana-jta, для которого сборка не создала
бин (теперь учитываются только зарегистрированные сборкой); номера строк поля названы строками
байткода.

Покрытие считает упоминания, а не правильность связей; точность пакетов против оракулов и
платный прогон ещё не проверены.

**Прогон v5 остановлен на 2026-11-02 (CQ-033, Codex с Quill):** prefetch-хук не уложился в 30 с
Codex. Причина в Quill: корни входных файлов включают весь `src`, а в cryostat там
`src/main/webui/node_modules` (186 тыс. файлов, 1.7 ГБ) и вложенный `.git`. Индекс хранил их хэши
(31 МБ в метаданных) и пересчитывал при каждом факте о методе и каждом ответе с покрытием
использований: 46–62 с на запрос. Исправлено: обход пропускает `node_modules`,
`bower_components` и скрытые каталоги; на копии v5 хук 2.3 с, метаданные 117 КБ. Бинарь v5
(коммит `1ceb0102`) содержит ошибку, поэтому продолжать v5 с ним нельзя. Также в каждом
разговоре v5 MCP переиндексирует проект при старте (≈153 с, вероятно из-за тех же файлов
`.build_cache`/`.git` веб-интерфейса), индекс зависимостей «degraded» (Maven в песочнице не
загружает JLine) и в stderr native-сервера бывает `ExceptionInInitializerError` без стека.

### Шаг 3. Меньше постоянного префикса
7. [ ] Claude: каталог с `alwaysLoad` (≈2.3k токенов в каждом запросе) — сделать отложенным,
   когда пакет покрывает вопрос; проверить, не добавляет ли это ход ToolSearch.
8. [ ] Codex: профиль `codex` (сделано) против меньшего списка — решить по метрике шага 1.2
   (сколько ходов поиска осталось) и размеру каталога.
9. [ ] Ответы инструментов в последующих ходах накапливаются в контексте: ограничить
   повторную выдачу уже переданного (ссылки на факты пакета вместо повтора).

### Шаг 3a. Слой доставки по клиентам (ядро общее)
Общие: индекс, запросы, сжатие ответов, содержание пакета фактов, проверки корректности.
По клиенту: каталог (профиль `--tools`), инструкции, формат и размер пакета, подсказки о
нескольких вызовах за ход. В v4 одно и то же действует противоположно: Codex платит ходами
(383 запроса против 357 без Quill; 75 `search_tools`), Claude — размером запроса
(21.8k против 17.6k при 205 запросах против 289).
- [x] Codex: профиль `codex`; `quill init`/`client refresh` пишут `--tools codex` в
  `.codex/config.toml`, старую запись `["--mcp"]` переводят.
- [x] Claude: явный профиль `--tools claude` в `.mcp.json` (сейчас `["--mcp"]` →
  `compact` по умолчанию), чтобы настраивать Claude, не меняя умолчание для прочих клиентов;
  перевод старых записей так же, как у Codex.
- [x] Codex `AGENTS.md`: нужные запросы Quill делать одним скриптом code mode (несколько
  `tools.mcp__quill__*` за ход) — самое дешёвое сокращение ходов. Плюс «перечисленные
  инструменты вызывать напрямую, искать только неперечисленные по точному имени» (в v4 20
  поисков нашли уже прямой инструмент, 16 тематических — ничего). Инструкции v5.
- [x] Claude `CLAUDE.md`: независимые запросы Quill — параллельными вызовами в одном
  сообщении.
- [ ] Claude: `alwaysLoad` (≈2.3k токенов в каждом запросе) против отложенной загрузки —
  решать по шагу 1 (покрывает ли пакет вопрос без инструментов).
- [ ] Отчёт: цель и вердикт по каждому клиенту отдельно, без усреднения.

### Шаг 4. Многоходовые задачи (patch, linked, CQ-049/CQ-050)
10. [ ] `change_session`: точные правки (файл, диапазон, замена) и одна команда проверки с
    готовым окружением — JDK из захваченных настроек компиляции (`JAVA_HOME`), профили,
    `-Dtest`. В v4 CQ-049 Codex потратил 11 лишних запросов на поиск JDK и Mockito.
11. [ ] Linked-сессии: пакет следующего вопроса не повторяет уже выданные факты.

### Шаг 5. Проверка
12. [ ] После шагов 1–3 — офлайн: покрытие оракулов пакетами ≥80% вопросов при размере
    ≤6k токенов, точность фактов пакетов против оракулов без ошибок.
13. [ ] Платный прогон — только по команде пользователя. Ожидание до прогона: каждый вопрос,
    отвеченный с первого хода, даёт ≈4–6x на этом вопросе; общий 4x требует ≥75–80% таких.

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
1. [~] `get_symbol_details` (Claude: 52 вызова, 50% всех символов; в 53 из 58 вызовов ответ больше
   исходника — 170k токенов против 91k). `members` = 541k символов, ≈820 на член:
   убрать per-member `symbol_id` (выводится из класса + kind/name/descriptor), `location` с
   `line:null`, `jvm_descriptor`/`jvm_name`/`source_name`, равные `descriptor`/`name`,
   `language`, дубль `annotation_details`/`annotations`, пустые массивы. Члены — строками
   `members_columns`. Для класса меньше ~2k токенов со свежим хешем отдавать исходник вместо
   списка членов (агент всё равно читает файл: CQ-037 сделал Read параллельно). ≈ −90k токенов.
   - [x] Члены без повторов идентичности класса (реплей: 682k → 311k символов, x0.456;
     `get_context` x0.882; ответов больше исходника 53 → 32).
   - [x] ~~Исходник вместо членов для малых классов~~ — откатано по v4: после сжатия членов исходник
     больше списка (CQ-002 PermissionMapper 6.8k → 9.1k), а Claude эти файлы не читал.
2. [x] `_meta` (≈600 символов в каждом ответе, 175k = 6%; 15% у `list_beans`): при свежем индексе
   `{"index_id","fresh":true}`; подробности — только при stale/dirty-структуре. Сейчас полная форма
   выдаётся при `worktree_dirty` без структурных изменений. ≈ −40k.
   Сделано: при грязном дереве без структурных изменений — краткая форма + `worktree_changed_files`.
3. [ ] Пустые/null поля (121k, 4%) — общий проход сериализации, кроме полей-доказательств
   (`*_proven:false`, `has_more`). ≈ −28k.
4. [~] `find_configuration_references` (355k): `declared_source` 12%, `field_read_source` 11%,
   повтор `module`/`sourceSet`/`classId`/`resolution_strategy`/`definition_files` в каждой из
   43 строк wildcard-запроса → `row_defaults`; для wildcard-ключа меньший лимит по умолчанию и
   группировка по префиксу. ≈ −30k.
   Сделано: `row_defaults_by_kind` в MCP-ответе (оценка на записанных ответах 355k → 292k, x0.82).
   Осталось: лимит для wildcard, `field_read_source`.
5. [~] `list_injection_points` (359k): `provider_evidence` 20% (общий `scope` в defaults,
   повтор artifact/framework), `resolution_trace` 20% с пустыми `applied_rules`/`candidates`,
   пустые `limitations`. ≈ −22k.
   Сделано: `point_defaults` по самому частому значению (оценка 359k → 302k, x0.84).
   Осталось: `resolution_trace.candidates`, `provider_evidence.declarations`.
6. [~] `get_call_hierarchy` (297k): `caller`/`callee` 44% — таблица методов один раз, рёбра по
   индексу; `dispatch_targets` 16%. ≈ −22k.
   Найдено: `GraphMetadata` (общие caller/callee через `shared_metadata`) молча отключался из-за
   `dispatch_ref` в каждом ребре — в v3 не сработал ни разу. Исправлено (оценка 297k → 245k, x0.82).
7. [ ] `find_entry_points` (158k, в среднем 39k символов): `observer` 30%, `annotation_values` 16%.
   ≈ −15k.
8. [x] `list_beans` (208k): `arc_retention` 20% — повторяющийся `note` вынести в defaults,
   сигналы строкой; пустые `profiles`, суффикс `:0` в `source`. ≈ −10k.
   Сделано: `arc_retention_notes` один раз, без пустых `profiles` и `:0`.
9. [x] `get_project_dependencies` (90k): абсолютный путь `jar` 21% — только по запросу. ≈ −5k.
   Сделано: `maven_repository_root` + `jar_path_rule`; `jar` остаётся только для нестандартных путей.

Итого фаза 1: ≈ −1.1M символов из 2.62M (≈ −42%, ≈ −260k токенов на прогон).

### Фаза 2. Поведение агентов (главный резерв для ALL)
10. [x] Codex: 69 из 234 вызовов — `search_tools` (+37 `execute_tool`); каждый лишний ход
    пересылает весь контекст. Отдавать Codex основные инструменты напрямую (профиль `core`/
    `di`), `search_tools` — только для редких. В ответе `search_tools` не повторять `guidance`
    (31% ответа, есть в instructions) и сжать `input_schema` (45%).
    По v4: 75 `search_tools` → лишь 31 `execute_tool`; 16 поисков пусты, 20 нашли уже прямой
    инструмент. Сделано: профиль `codex` (+7 инструментов, ≈1.2k токенов на запрос), Codex-конфиг
    пишет `--tools codex`; `search_tools` без статичного `guidance` и `required_arguments`,
    лимит 5 (оценка на v4 125k → 87k символов).
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

15a. [x] `list_injection_points io.cryostat.Producers` → `NOT_A_BEAN` в 5 сессиях: класс с
    producer-методами без bean-defining аннотации ArC делает `@Dependent` бином
    (`quarkus.arc.auto-producer-methods`, по умолчанию true). Индексировать такой бин.
    Причина: поддержка была, но только при свежем захвате настроек компиляции; в фикстуре
    захват устарел (`.quill/fixture-build/*.xml`). Теперь без свежего захвата решает ArC на
    индексированном classpath (`io.quarkus.arc.Arc`). В v4 из-за этого неверен CQ-018 Claude.
15d. [x] Как 15a, но для REST-ресурсов: класс с `@Path` без свежего захвата настроек не был бином
    (Quarkus REST делает его `@Singleton`), и его точки внедрения не индексировались —
    `Reports#reportAggregator`, `ActiveRecordingsDownload#base64Url`. Теперь при отсутствии свежего
    захвата решает Quarkus REST на индексированном classpath. В фикстуре cryostat: 101 → 133 бина.
15e. [ ] Привязки внешних interceptors не индексируются: `external_beans` для
    `TransactionalInterceptorRequired` хранит только `@Default/@Any`, поэтому `@Transactional`
    метода нельзя связать с interceptor из зависимости (CQ-026). Индексировать interceptor bindings
    (и `@Priority`) бинов зависимостей.
15f. [ ] Сгенерированные Quarkus классы (`*_ClientProxy`, `*_Synthetic_Bean` в
    `generated-bytecode.jar`) не индексируются (CQ-048, часть CQ-024).
15b. [x] Найдено реплеем и исправлено: `find_cycles` scope=class падал на вложенном классе с
    владельцем без рёбер; `change_session`/`plan_change`/`verify_change`/`find_impacted_tests`
    с целью `Class#method` требовали граф (CQ-050).
15c. [ ] Реплей: отделить внутренние ошибки (`TOOL_ERROR`) от ответов на неверный запрос
    (`CLASS_NOT_FOUND`, `AMBIGUOUS_METHOD`, неверные аргументы) в `failures`.

### Фаза 4. Платная проверка
15. [ ] Только после фаз 1–2 и реплея: повторить v3 (Quill-плечо, тот же baseline), отчёт по
    тем же метрикам + отдельно по «тяжёлым» задачам.

- [x] Общий офлайн-детектор Codex/Claude: `scripts/quill_response_reuse_audit.py`, [правило и ограничения](benchmarks/response-reuse-policy.md). Проверяет доставку и покрытие, включая отклонённые клиентом попытки; неоднозначные семантические повторы/чтения требуют разбора, а не объявляются автоматически лишними. `review_required` блокирует автоматическое разрешение; исходные ответы/решения не переписываются.
