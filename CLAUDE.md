# AudioBooks — правила для агентов

Android-приложение (Java 8, Gradle 8.13, JDK 17). Как устроен движок источников и как добавить сайт —
`docs/howto.md` («Adding an audiobook site»); план и статус — `tasks/plan.md` §12, `tasks/todo.md`,
`tasks/open-issues.md`. Сведения о машине и эмуляторе — `C:\work\audiobook\readme.md`.

## Сборка и тесты

Окружение загружать в той же команде PowerShell, что и сборку (переменные не сохраняются между вызовами):

```powershell
. "C:/Users/user/AppData/Local/audiobook-audit-tools/environment.ps1"; Set-Location C:/work2000/AudioBooks; .\gradlew.bat :app:testDebugUnitTest --console=plain -q
```

- Одна сборка Gradle за раз. Не запускать Gradle параллельно в разных копиях проекта (git worktree, копии во временных
  папках): каждая поднимает свой демон (`-Xmx1536m`) и свои процессы R8/lint. После работы в дополнительной копии —
  `.\gradlew.bat --stop` в ней.
- `assembleRelease` очищает `app/build/outputs/apk/release` целиком: нужные APK хранить в `test-apk/` (git их не видит).
- Release подписывается ключом из `keystore.properties` (не коммитится, пароли не читать и не выводить).

## Эмулятор: как не уронить систему

30.10.2026 в 18:11 машина упала в синий экран `0x3B SYSTEM_SERVICE_EXCEPTION` (`c0000005`, нарушение доступа в коде
ядра; дамп `C:\Windows\MEMORY.DMP`) во время работы с эмулятором, параллельно с несколькими сборками Gradle. Эмулятор
ускоряется драйвером ядра **AEHD 2.2** (`aehd.sys`) — единственным сторонним драйвером ядра в этой цепочке. Отсюда
правила; они обязательны для основной сессии и для всех субагентов и workflow.

1. **Только один эмулятор.** Перед любым действием проверить, что уже запущено:
   ```powershell
   . "C:/Users/user/AppData/Local/audiobook-audit-tools/environment.ps1"; adb devices -l; Get-Process qemu-system*, emulator* -ErrorAction SilentlyContinue
   ```
   Если `emulator-5554` есть — использовать его. Второй экземпляр, второй AVD, второй порт не запускать никогда.
2. **Запускает и останавливает эмулятор только основная сессия**, и только когда владелец попросил проверку на
   устройстве. Субагенты и workflow получают уже запущенный `emulator-5554` и работают через `adb -s emulator-5554`;
   запускать эмулятор, `emulator.exe`, `start-audit-emulator.ps1`, `avdmanager` им запрещено (писать это в бриф).
3. **Одним эмулятором управляет один агент.** Не давать двум агентам или двум параллельным шагам workflow работать с
   устройством одновременно.
4. **Запуск — только штатным скриптом** с проверенными параметрами (ускорение on, 2 ГБ, 2 ядра, без окна и звука):
   ```powershell
   & "C:/Users/user/AppData/Local/audiobook-audit-tools/start-audit-emulator.ps1"
   ```
   Затем ждать `adb -s emulator-5554 shell getprop sys.boot_completed` = `1` с ограничением по времени (3 мин), не
   бесконечным `wait-for-device`. Не менять `-memory`, `-cores`, `-gpu`, не использовать `-accel off`, `-wipe-data`,
   снапшоты, окно, звук.
5. **Пока работает эмулятор — никакой тяжёлой параллельной нагрузки:** не больше одной сборки Gradle, без `lintDebug` и
   `assembleRelease` одновременно с тестами на устройстве, без живых бенчмарков и массовых сетевых проверок в фоне.
   Перед сеансом на устройстве остановить лишние демоны (`.\gradlew.bat --stop`).
6. **Сеансы короткие:** проверка на устройстве — один агент, ограниченный список шагов, без бесконечных циклов
   скриншотов и `screenrecord`. Скриншоты — `adb shell screencap -p /sdcard/x.png` и `adb pull` (PowerShell 5.1 портит
   двоичный вывод через `>`).
7. **Завершение:** `adb -s emulator-5554 emu kill`, затем ждать, пока `emulator-5554` пропадёт из `adb devices`.
   Процесс `qemu-system-*` не убивать принудительно, если он не завис.
8. **Данные приложения на эмуляторе не трогать:** без `adb uninstall`, `pm clear`, `-wipe-data`. На эмуляторе стоит
   release-сборка с ключом владельца: ставить только release APK этим ключом (`adb install -r` сохраняет данные).
   Debug APK и `connectedDebugAndroidTest` требуют удаления приложения — только с разрешения владельца.
9. **Если машина упала:** не запускать эмулятор снова, сначала посмотреть причину
   (`Get-WinEvent -FilterHashtable @{LogName='System'; Id=1001,41} -MaxEvents 5`) и сообщить владельцу.

AEHD снимается с поддержки Google 31.12.2026; рекомендованная замена — Windows Hypervisor Platform (WHPX). Переход
требует прав администратора и перезагрузки — только по решению владельца.

## Git

Коммит и push — только по просьбе владельца. Основная ветка — `master`, слияния веток — `--no-ff` с сообщением
`Merge branch '<ветка>': <что вошло>`.
