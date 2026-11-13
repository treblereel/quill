"""Apollo questions (public). Each AQ-NNN mirrors Cryostat CQ-NNN with a Spring counterpart.

The prompt text is what the tested model sees; oracles live in oracles.py (private).
"""

CONTEXT = (
    "Context: Enumerations are bounded to application-owned production/test source of the tracked "
    "Apollo modules unless dependencies or generated mechanisms are explicitly requested. Packaged "
    "framework evidence supplements those questions. No ambient env/secret values are assumed.\n"
    "Profile: apollo-portal runs with spring.profiles.active=github,auth (github also activates the "
    "mysql profile group); apollo-adminservice and apollo-configservice run with github.\n"
    "Read-only except AQ-049, whose patch must be applied only in a disposable checkout. No permission "
    "to start external services or read process secrets.")

P = 'com.ctrip.framework.apollo.portal'
ROUTE = 'POST /openapi/v1/envs/{env}/apps/{appId}/clusters/{clusterName}/namespaces/{namespaceName}/releases'

# id -> (track, [prompts]); track as in Cryostat: independent | patch | linked.
QUESTIONS = {
    'AQ-001': ('independent', [
        'Какие модули образуют Apollo, где точка сборки и какие зависимости подключают Spring Boot и Spring Cloud?']),
    'AQ-002': ('independent', [
        f'Какие beans объявлены в production-пакете {P}.component, только непосредственно в этом пакете, без '
        'subpackages? Раздели component-классы, @Bean-методы и beans, которые производит FactoryBean, укажи их '
        'имена и scope.']),
    'AQ-003': ('independent', [
        f'Почему {P}.component.UnifiedPermissionValidator попадает в Spring-контекст apollo-portal: какие '
        'аннотации, stereotypes или правила component scanning это обеспечивают?']),
    'AQ-004': ('independent', [
        'Откуда берётся зависимость типа org.springframework.context.ApplicationEventPublisher, которую получает '
        'com.ctrip.framework.apollo.openapi.v1.controller.ReleaseController: какой dependency или механизм Spring '
        'её предоставляет и есть ли для неё bean definition?']),
    'AQ-005': ('independent', [
        f'Может ли Spring не зарегистрировать bean {P}.spi.configuration.AuthConfiguration.SpringSecurityAuthAutoConfiguration'
        '#springSecurityUserInfoHolder(org.springframework.beans.factory.ObjectProvider) при github,auth? Покажи '
        'основания регистрации или пропуска и ограничения такого вывода.']),
    'AQ-006': ('independent', [
        'Что будет внедрено в параметр userInfoHolder конструктора '
        'com.ctrip.framework.apollo.openapi.v1.controller.ReleaseController при github,auth? Покажи кандидатов и '
        'почему выбран именно этот bean.']),
    'AQ-007': ('independent', [
        f'Как Spring создаёт {P}.component.UserTokenPermissionValidator#<init>({P}.component.UserPermissionValidator,'
        f'{P}.service.UserTokenService,{P}.util.UserTokenAuthUtil,{P}.service.RolePermissionService,'
        f'{P}.spi.UserInfoHolder) и какие beans удовлетворяют каждому параметру конструктора?']),
    'AQ-009': ('independent', [
        f'Какие зависимости {P}.component.emailbuilder.NormalPublishEmailBuilder получает от своего superclass, а какие '
        'объявлены непосредственно? Учти переопределения.']),
    'AQ-010': ('independent', [
        f'Какие реализации реально подходят для {P}.component.PermissionValidator#hasReleaseNamespacePermission'
        '(java.lang.String,java.lang.String,java.lang.String,java.lang.String)? Отдели Java-наследование от bean '
        'types Spring-контекста apollo-portal.']),
    'AQ-011': ('independent', [
        'Где внедряется org.springframework.security.crypto.password.PasswordEncoder, создаваемый '
        f'{P}.spi.configuration.AuthConfiguration.SpringSecurityAuthAutoConfiguration#passwordEncoder(), и через '
        'какие qualifiers? Отдельно покажи прямые injection sites и динамические обращения.']),
    'AQ-012': ('independent', [
        f'Почему qualifier у параметра fileTextResolver конструктора {P}.service.ItemService совпадает или не '
        'совпадает с qualifiers кандидатов типа ConfigTextResolver? Учти имена beans и значение @Qualifier.']),
    'AQ-013': ('independent', [
        'Какие явные и неявные qualifiers действуют у параметра userInfoHolder конструктора '
        'com.ctrip.framework.apollo.openapi.v1.controller.ReleaseController и его кандидатов? Объясни влияние имени '
        'bean, имени параметра, @Primary и @Qualifier.']),
    'AQ-014': ('independent', [
        f'Какие beans может вернуть {P}.spi.springsecurity.SpringSecurityUserInfoHolder через свой '
        'ObjectProvider<UserService>? Проследи lookup и условия, из-за которых результат зависит от runtime.']),
    'AQ-015': ('independent', [
        f'Как выбирается реализация для {P}.listener.ConfigPublishListener#emailService при github,auth: '
        '@Primary, условия или fallback/default bean?']),
    'AQ-016': ('independent', [
        f'Что создаёт {P}.spi.configuration.AuthConfiguration.SpringSecurityAuthAutoConfiguration#jdbcUserDetailsManager'
        '(javax.sql.DataSource,jakarta.persistence.EntityManagerFactory), какие у результата type, имя, qualifiers и '
        'scope? Не смешивай их со свойствами declaring class.']),
    'AQ-018': ('independent', [
        'Какие зависимости нужны '
        f'{P}.spi.configuration.AuthConfiguration.SpringSecurityAuthAutoConfiguration#springSecurityUserService('
        'org.springframework.security.crypto.password.PasswordEncoder,'
        f'{P}.repository.UserRepository,{P}.repository.AuthorityRepository), чтобы создать результат? Покажи '
        'параметры @Bean-метода, явные depends-on и зависимости его declaring class.']),
    'AQ-020': ('independent', [
        f'Когда создаётся и уничтожается {P}.listener.ConfigPublishListener, какие lifecycle callbacks объявлены '
        'или наследуются? Где статического анализа недостаточно?']),
    'AQ-023': ('independent', [
        f'Как runtime-конфигурация влияет на то, какой UserService вернёт {P}.spi.springsecurity.SpringSecurityUserInfoHolder'
        '#getUser()? Что известно заранее, а что определяется только при запуске?']),
    'AQ-024': ('independent', [
        'Кто регистрирует bean com.ctrip.framework.apollo.biz.registry.configuration.support.ApolloServiceDiscoveryProperties, '
        'какие у него имя, типы и зависимости, и при каких условиях он появляется?']),
    'AQ-025': ('independent', [
        f'Может ли {P}.listener.ConfigPublishListener#onConfigPublish({P}.listener.ConfigPublishEvent) в своём '
        'асинхронном пути опираться на контекст текущего запроса (тип аутентификации, текущего пользователя)? '
        'Покажи границы контекста и что требует runtime-проверки.']),
    'AQ-026': ('independent', [
        'Какие aspects применимы к com.ctrip.framework.apollo.adminservice.controller.ItemController#create('
        'java.lang.String,java.lang.String,java.lang.String,com.ctrip.framework.apollo.common.dto.ItemDTO), в каком '
        'порядке и за счёт каких pointcuts или annotations?']),
    'AQ-027': ('independent', [
        'Где начинается и заканчивается транзакция на пути через '
        'com.ctrip.framework.apollo.adminservice.controller.ReleaseController#publish(java.lang.String,java.lang.String,'
        'java.lang.String,java.lang.String,java.lang.String,java.lang.String,boolean), и какие вызовы могут изменить '
        'propagation?']),
    'AQ-028': ('independent', [
        'Какие listeners могут получить событие, опубликованное в '
        'com.ctrip.framework.apollo.openapi.v1.controller.ReleaseController#createRelease? Учти тип события, '
        'sync/async и транзакционную фазу.']),
    'AQ-029': ('independent', [
        'Какие decorators применяются к com.ctrip.framework.apollo.biz.registry.DatabaseDiscoveryClient, как выбран '
        'delegate и что определяет порядок?']),
    'AQ-030': ('independent', [
        'Есть ли циклы в зависимостях от com.ctrip.framework.apollo.biz.service.NamespaceService? Отдели цикл графа '
        'зависимостей от реально недопустимого создания объектов.']),
    'AQ-031': ('independent', [
        f'Как запрос {ROUTE} в apollo-portal доходит до бизнес-метода и его Spring-зависимости? Покажи один прямой '
        'путь, не добавляя все транзитивные зависимости.']),
    'AQ-032': ('independent', [
        f'Какие проверки доступа действуют для {ROUTE} в apollo-portal при github,auth, откуда берутся роли или '
        'permissions и где меняется поведение по конфигурации?']),
    'AQ-033': ('independent', [
        'Что запускает сканирование в com.ctrip.framework.apollo.biz.message.ReleaseMessageScanner, какие '
        'Spring-зависимости используются и какие настройки управляют расписанием?']),
    'AQ-035': ('independent', [
        'Что делает com.ctrip.framework.apollo.biz.registry.configuration.support.'
        'ApolloServiceRegistryHeartbeatApplicationRunner#run(org.springframework.boot.ApplicationArguments) при '
        'старте или остановке и какие beans затрагивает? Укажи доказанный порядок и неизвестные отношения.']),
    'AQ-036': ('independent', [
        'Где задаётся и читается настройка apollo-portal emergencyPublish.supported.envs? Покажи default, источники '
        'значения при github,auth и места использования.']),
    'AQ-037': ('independent', [
        'Какие ключи описывает com.ctrip.framework.apollo.biz.registry.configuration.support.'
        'ApolloServiceRegistryProperties, включая вложенные значения, коллекции и значения по умолчанию?']),
    'AQ-038': ('independent', [
        'AQ-038 uses only explicit sources: classpath application.properties of apollo-portal sets '
        'emergencyPublish.supported.envs=dev, and the portal database table ServerConfig holds '
        'emergencyPublish.supported.envs=pro.\n'
        'Какое значение emergencyPublish.supported.envs увидит PortalConfig при github,auth, без ambient env/system '
        'properties и аргументов командной строки? Покажи приоритет источников и как он устанавливается.']),
    'AQ-039': ('independent', [
        'Можно ли изменить emergencyPublish.supported.envs у работающего apollo-portal без пересборки и '
        'перезапуска? Покажи доказательства поведения для этой версии.']),
    'AQ-040': ('independent', [
        'Что надо изменить при переименовании настройки emergencyPublish.supported.envs в '
        'emergencyPublish.allowed.envs? Отдели код, конфигурацию и SQL-скрипты, документацию и тесты.']),
    'AQ-041': ('independent', [
        f'Каков прямой радиус изменения сигнатуры {P}.component.UnifiedPermissionValidator#hasReleaseNamespacePermission'
        '(java.lang.String,java.lang.String,java.lang.String,java.lang.String)? Отдели Java callers, ссылки вне '
        'Java-кода, overrides и интерфейсы, тесты и косвенные зависимости.']),
    'AQ-042': ('independent', [
        f'Если переименовать bean {P}.component.txtresolver.FileTextResolver из "fileTextResolver" в '
        '"fileTextResolverV2", какие injection sites и объявления придётся проверить или изменить?']),
    'AQ-043': ('independent', [
        f'Что затронет изменение контракта {P}.spi.UserInfoHolder: реализации, bean-объявления, injection sites и '
        'прямые пользователи методов?']),
    'AQ-044': ('independent', [
        f'Как заменить component-класс {P}.component.UnifiedPermissionValidator @Bean-методом, не потеряв имя bean, '
        'scope, lifecycle и совместимость потребителей?']),
    'AQ-045': ('independent', [
        f'Что изменится, если перевести {P}.component.UserPermissionValidator в request scope? Проверь состояние, '
        'proxy, потребителей-singletons и контекст вызова вне HTTP-запроса.']),
    'AQ-046': ('independent', [
        f'Какие тесты следует запустить после изменения {P}.component.UnifiedPermissionValidator'
        '#hasReleaseNamespacePermission(java.lang.String,java.lang.String,java.lang.String,java.lang.String) и '
        'почему? Отдели прямое покрытие от эвристически релевантных тестов.']),
    'AQ-047': ('independent', [
        f'Как {P}.controller.ItemControllerAuthIntegrationTest подменяет Spring-зависимости и конфигурацию? Что '
        'отличается от production и что такой тест не проверяет?']),
    'AQ-048': ('independent', [
        'Откуда появился com.ctrip.framework.apollo.openapi.api.ReleaseManagementApi, какой handwritten input или '
        'plugin им управляет и что надо менять вместо generated-файла?']),
    'AQ-049': ('patch', [
        f'Переименуй {P}.component.UnifiedPermissionValidator#getDelegate() в resolveDelegate, обнови все '
        'необходимые места и добавь или адаптируй узкий тест. Сохрани поведение и покажи проверку.\n'
        'Тесты apollo-portal работают на H2 без внешних сервисов; запускай только узкие тесты, а не весь reactor.']),
    'AQ-050': ('linked', [
        f'Разбери одну пользовательскую операцию {ROUTE} в apollo-portal: точку входа, DI-выбор, настройки, радиус '
        'изменения и подходящие тесты. Работай по шагам в одной сессии.\n'
        f'Найди точку входа операции {ROUTE} и её непосредственный обработчик.',
        'Проследи выбор Spring-зависимостей в этой операции при github,auth.',
        'Найди связанные настройки и их определения для этого профиля.',
        'Раздели прямые вызовы, DI-связи и условные runtime-переходы на этом пути.',
        f'Составь минимальный план изменения сигнатуры {P}.service.ReleaseService#publish({P}.entity.model.'
        'NamespaceReleaseModel) и прямой радиус проверки для этой операции. Только план, без изменения кода.',
        'Выбери узкие тесты для плана; отметь пробелы доказательств и не повторяй весь предыдущий разбор.']),
}

# Cryostat CQ cases whose Spring counterpart has no application-owned instance in Apollo.
NOT_APPLICABLE_IDS = ['AQ-008', 'AQ-017', 'AQ-019', 'AQ-021', 'AQ-022', 'AQ-034']
