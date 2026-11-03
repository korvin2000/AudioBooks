# Облачная синхронизация (Google Диск / Яндекс Диск) — проект v2

Статус: проект после ревью Fable (t6) и Codex (gpt-6-astra); кода нет. Пути — относительно `app/src/main/java/com/fanok/audiobooks/`.

## 1. Что синхронизируется

| Данные | Где живут | В облако |
|---|---|---|
| Избранные книги | Room `favorite` | да (членство; порядок локальный) |
| История | Room `history` (`opened_at`) | да, с `opened_at` |
| Избранные авторы/чтецы | Room `favorite_person` | да (с urls) |
| Позиции | prefs `STORAGE` `checkpoint:<url>` + Room `audio` (название главы, с) | да |
| Настройки | default prefs + `STORAGE.speed` | да, по типизированному белому списку |
| `saved`, `books_audio`, пути, прокси/маршруты/регион, cookies, PIN, кэши, `sync_*` | — | нет (свойства устройства или секреты) |

Белый список (тип задан таблицей, не угадывается из JSON):
- String: `pref_theme`, `books_adapter_layout_pref`, `seek_step`, `pref_sort_favorite`, `pref_start_screen`;
- boolean: `history_procent`, `toolbar_*` (12), `autoPlayPref`, `reproductionPref`, `seekButtonsPref`, `seekToPausePref`, `rewind_back`, `safeNavigationPref`, `scrollToSelectedPref`, `search_pref`, `pref_autoload_last_book`;
- float (`STORAGE`): `speed`.
По решению владельца добавлены источники (`pref_sources`, `pref_sources_max`, `sorce_books`, поисковые галочки
`search_<источник>`) и эквалайзер (`STORAGE.equalizer`, JSON). Прокси и маршруты источников — нет.

## 2. Формат файла

`audiobooks-sync.json.gz` (Google: `appDataFolder`; Яндекс: `app:/` = «Приложения/<имя>»). JSON в gzip (распаковка только при сигнатуре `1f 8b`), чтение ограничено 20 МБ.

```json
{
  "v": 1,
  "seq": {"<dev>": 12, "<dev2>": 7},        // векторные часы: сколько записей сделало каждое устройство
  "at": 1762156800000,                     // когда записано
  "books": {"<url>": {BookPOJO без desc}}, // метаданные книг из fav/hist, один раз
  "fav":  {"<url>": 0},
  "hist": {"<url>": 1762150000000},        // opened_at
  "people": {"0|<key>": {FavoritePersonPOJO}},
  "pos":  {"<url>": {"c": 3, "ms": 123000, "t": 1762…, "n": "Глава 3"}},
  "prefs": {"pref_theme": "dark", "autoPlayPref": "false", "speed": "1.25"}
}
```
- Незнакомые поля верхнего уровня сохраняются при перезаписи (старое приложение не стирает данные нового); `v` больше своего — только чтение, статус «обновите приложение».
- Невалидные элементы не роняют цикл: что нельзя применить (книга без имени, глава < 0, чужой kind), пропускается при применении, но ключ остаётся (иначе пропуск выглядел бы удалением).
- Тот же класс — локальная база (`noBackupFilesDir/sync/base.json.gz`, атомарная запись), рядом — случайный `dev` устройства.

## 3. Слияние

Трёхстороннее по ключам (base — после прошлой синхронизации этого устройства). Для ключа: `same(l,r)` → l; `same(l,b)` → r; `same(r,b)` → l; иначе: одно null → другое (правка сильнее удаления), иначе правило коллекции.

| Коллекция | `same` | Конфликт |
|---|---|---|
| fav | присутствие | — |
| hist | `opened_at` | больший |
| people | присутствие + множество urls | объединение urls, меньший addedAt |
| pos | (глава, мс) | больший `t`, при равенстве — облако |
| prefs | строка | облако (первая привязка: настройки облака сильнее умолчаний нового устройства) |

Доверие к base: `remote.seq[d] ≥ base.seq[d]` для всех `d` из base. Иначе (файл удалён, другой аккаунт, чужая параллельная запись поверх нашей) base пуста → объединение: удалённое может вернуться, добавленное не теряется. При записи `seq = max(remote, base)`, `seq[dev]++`. Перед записью — повторная проверка версии файла (Drive `version`, Яндекс `md5`); изменилась → цикл заново (≤ 3).

Загрузка нужна, только если merged семантически отличается от remote (сравнение по тем же `same`), — без бесконечных перезаписей из‑за порядка.

## 4. Позиции и плеер

- `Checkpoint` получает `at` (мс): формат `2|глава|мс|at|url`, `parse` читает и `1|…` (`at=0`); `equals` без `at`. `StorageUtil.storeCheckpoint` сохраняет прежний `at`, если место не изменилось. Откат на старую версию приложения теряет позиции (БД и так не переживает даунгрейд).
- `MediaPlayerService`: помнит последний записанный им checkpoint. Если место плеера не менялось, а сохранённое изменила синхронизация — `checkpointNow` не затирает его, а `play()` перечитывает книгу с сохранённого места (`restoreBook`). Пауза на телефоне → прослушано на планшете → Play на телефоне продолжает с места планшета.
- `checkpoint_last` синхронизация не трогает (холодный старт с гарнитуры остаётся локальным).

## 5. Применение

Сравнение `merged` с `local`, снятым в начале; перед каждой записью — проверка, что значение не изменилось за время синхронизации (иначе ключ пропускается — локальная правка новее и уйдёт в следующий раз):
- Room, одна транзакция: fav — `addFavorite`/`removeFavorite`; hist — `addHistory(book, t)`/`removeHistory`. `HistoryDao` сортирует по `opened_at, id` (порядок истории больше не зависит от id).
- people — одной пакетной операцией на потоке `FavoritePeople` (вставка с urls/addedAt, объединение urls, удаление), с ожиданием результата; затем `reload()`.
- pos — `checkpoint:<url>` (сырая строка с `at` из облака) и строка `audio` (`add(url,n)` + `setTime`).
- prefs — запись по типу; при смене `pref_theme` — `AppThemes.applyNightMode` в главном потоке.
- Затем broadcast `UpdateAdapter`; `FavoriteFragment` по нему перечитывает список.

## 6. Цикл и триггеры

Однопоточный фоновый исполнитель, одна синхронизация за раз, без сети — пропуск. Сразу: запуск приложения, уход в фон (счётчик видимых активностей в `App`), кнопка. Отложенно (склейка, самый ранний срок): после записи позиции — 30 с на паузе, 5 мин во время воспроизведения. Отдельный OkHttp-клиент: `Proxy.NO_PROXY` (токены не идут через прокси приложения), без cookies, таймауты 15/30 с. Статус (аккаунт, время последней удачной синхронизации или ошибка) — в summary пункта настроек.

## 7. Транспорты — `read() → {bytes, tag}|null`, `tag()`, `write(bytes)`

**Google Диск** (`play-services-auth:22.0.0`). `Identity.getAuthorizationClient(ctx).authorize(drive.appdata)`; первый раз — UI через `StartIntentSenderForResult`, дальше в фоне тот же вызов без UI (`Tasks.await`). Email — `GET drive/v3/about?fields=user(emailAddress)`, далее `setAccount`. 401 → `clearToken`, повтор один раз; `hasResolution()` в фоне → «переподключите». Файл: `GET /drive/v3/files?spaces=appDataFolder&q=name='…'&orderBy=createdTime&fields=files(id,version)` — всегда берётся старейший (дубликаты от одновременного создания сходятся к нему, не удаляются); `GET files/{id}?alt=media`; создание `POST /upload/drive/v3/files?uploadType=multipart` (`multipart/related`); запись `PATCH /upload/drive/v3/files/{id}?uploadType=media`. Без Google Play services пункт скрыт. Идентификаторов в коде нет — Android-клиент OAuth сопоставляется по пакету и SHA-1.

**Яндекс Диск** (REST, `Authorization: OAuth <token>`). Вход: внешний браузер `https://oauth.yandex.ru/authorize?response_type=code&client_id=<CID>&code_challenge=<S256>&code_challenge_method=S256` → страница показывает 7-значный код → пользователь вводит его в диалоге → `POST https://oauth.yandex.ru/token` (`grant_type=authorization_code`, `code`, `client_id`, `code_verifier`; секрет не нужен — PKCE, задокументировано). Логин для статуса — `GET https://login.yandex.ru/info?format=json` (необязательно). Файл: `GET /v1/disk/resources?path=app:/…&fields=md5` (тег; 404 — нет файла); `GET /resources/download?path=…` → `href` → `GET`; `GET /resources/upload?path=…&overwrite=true` → `href` → `PUT` без `Authorization`, 201/202 — успех. 401 → «войдите снова» (токен ≥ 1 года). `client_id` — `YANDEX_CLIENT_ID` в `gradle.properties` → `BuildConfig`; пусто — пункт скрыт. WebDAV не подходит: с 22.06.2026 только для Яндекс 360.

## 8. Интерфейс

`preferences.xml`, группа «Резервное копирование»: `sync_service` (ListPreference: Выключена / Google Диск / Яндекс Диск; значение ставится только после успешного входа; смена сервиса или выключение сбрасывает base) и `sync_now` («Синхронизировать сейчас», summary — статус).

## 9. Файлы

Новые: `sync/CloudSync.java` (цикл, планировщик, статус, локальные чтение/применение), `sync/Snapshot.java` (формат, слияние — чистая Java, JVM-тесты), `sync/GoogleDrive.java`, `sync/YandexDisk.java`. Изменения: `service/Checkpoint.java`, `pojo/StorageUtil.java`, `service/MediaPlayerService.java`, `room/HistoryDao.java`, `model/FavoritePeople.java`, `fragment/SettingsFragment.java`, `fragment/FavoriteFragment.java`, `App.java`, `activity/MainActivity.java`, `res/xml/preferences.xml`, `res/values/strings.xml`, `app/build.gradle`, `gradle.properties`, `proguard-rules.pro`. Тесты: слияние и доверие к base, формат/валидация, `Checkpoint` v1/v2. Документация владельцу: `docs/cloud-sync.md` (настройка Google Cloud и Яндекс OAuth).
