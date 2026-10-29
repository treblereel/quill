# TODO

- [ ] Поддержка Git submodules в Quill.
  - Обнаружение через `.gitmodules`, включая вложенные и неинициализированные submodules.
  - Корректная индексация и навигация с учётом границ репозиториев и их ревизий.
  - Проверки `init`, `update` и Git-инструментов в JVM и native-сборках, включая регрессию JGit reflection metadata.
