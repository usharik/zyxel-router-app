# Публикация в Google Play

## Состояние на 22.11.2026

Сделано:
- Приложение создано в Play Console; сборка 1.4.0 (versionCode 7) загружена во **внутреннее тестирование черновиком**.
- Карточка загружена через Fastlane на 5 языках: тексты, значок 512×512, обложка 1024×500, по 5 скриншотов.
- Разовая покупка `remove_ads` создана и активирована: базовая цена 1,99 USD, 174 региона.
- Firebase: `android/app/google-services.json` (проект `router-manager-for-zyxel`). AdMob: приложение `ca-app-pub-6420074467468909~9463913628`, баннер `…/8215953120`. Межстраничного блока нет: пустой ID в `ads.xml` выключает полноэкранную рекламу.
- Ключ загрузки: `~/dev/keys/routermanager-upload.jks`, пароль в `~/dev/keys/routermanager-upload.txt` (SHA-256 `27:8D:05:E0:…:CF:A0`). **Сделать резервную копию** вне этого Mac.
- Политика конфиденциальности опубликована: https://usharik.github.io/routermanager/privacy-policy.html (репозиторий `~/dev/usharik.github.io`).
- Ключ Play API: общий сервисный аккаунт `github-play@…` (`android/play-api-key.json`, копия `pc-api-key.json` из других проектов).

Осталось (только в консолях, руками):
1. **AdMob → Privacy & messaging:** создать и опубликовать сообщение GDPR для этого приложения. Сейчас UMP отвечает «no form(s) configured for the input app ID»: в ЕЭЗ не будет формы согласия, Firebase там не включится, реклама будет только неперсонализированной.
2. AdMob: зарегистрировать свои телефоны как тестовые устройства (Settings → Test devices).
3. Play Console → App content: анкеты (ответы ниже, раздел Data safety и п. 8), категория «Инструменты», контакты, сайт `https://usharik.github.io`.
4. Внутреннее тестирование: добавить тестировщиков и выпустить черновик (Release → Internal testing → Edit release → Review → Start rollout). License testing — свои аккаунты, проверить покупку и возврат.
5. Закрытое тестирование: 12 тестировщиков на 14 дней (`fastlane promote from:internal to:alpha`), затем заявка на продакшн.

## Как выпускать версию

```
cd android
bundle install                     # один раз (Ruby из mise.toml, гемы в vendor/bundle)
bundle exec fastlane check_access  # ключ Play API и доступ к приложению
bundle exec fastlane internal      # тесты, lint, подписанный AAB → внутреннее тестирование (черновик)
bundle exec fastlane closed        # то же в закрытое тестирование (alpha)
bundle exec fastlane promote from:internal to:production
bundle exec fastlane metadata      # тексты и графика карточки из fastlane/metadata
```
Перед новой версией поднять `versionCode`/`versionName` в `android/app/build.gradle.kts`. Пока приложение не опубликовано, Play принимает только черновики (по умолчанию `RELEASE_STATUS=draft`); после публикации — `RELEASE_STATUS=completed`.
Графика: `swift tools/store_graphics.swift` (значок и обложка), `ANDROID_SERIAL=emulator-5600 python3 tools/store_screenshots.py` (скриншоты демо-режима без рекламы; нужна debug-сборка на эмуляторе).

## Что сделать вручную (по порядку, исходный план)

1. **Название** выбрано: «Router Manager for Zyxel» (ru/uk «Менеджер роутера для Zyxel», cs «Správce routeru pro Zyxel», de «Router-Manager für Zyxel»). «Zyxel» стоит после «for», а в описании и в «О приложении» сказано, что приложение неофициальное, — так требуют правила Play о товарных знаках. Иконка своя, без логотипа Zyxel.
2. **Firebase.** Создать проект (или добавить Android-приложение `com.usharik.routermanager` в существующий), скачать `google-services.json` в `android/app/`. Файл в Git не попадает. Плагины Firebase и Crashlytics включатся сами. В Firebase → Analytics (GA4) → Data settings: выключить Google signals и персонализацию рекламы, проверить срок хранения данных (14 месяцев, как в политике).
3. **AdMob** (аккаунт `pub-6420074467468909`): добавить приложение, создать блоки «Баннер» и «Межстраничное объявление», вписать идентификаторы в `android/app/src/release/res/values/ads.xml`. Пока там тестовые ID, release не соберётся без `-PallowTestAds`. Сообщение GDPR (Privacy & messaging → European regulations) создать для этого приложения и **опубликовать**, иначе форма согласия в ЕЭЗ не появится, а Firebase там не включится; при желании — ещё сообщение для штатов США (US state regulations). **До установки release с настоящими ID** зарегистрировать свои телефоны в AdMob как тестовые устройства (Settings → Test devices), иначе свои показы и клики могут засчитать как недействительный трафик. `app-ads.txt` уже лежит на usharik.github.io; в карточке Play указать сайт разработчика `https://usharik.github.io`.
4. **Ключ загрузки:** `keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000`, затем `android/keystore.properties`:
   ```
   storeFile=../upload.jks
   storePassword=…
   keyAlias=upload
   keyPassword=…
   ```
   Ключ и пароли держать вне Git (уже в `.gitignore`), сделать резервную копию. Подпись приложения — Play App Signing.

   Release-сборка теперь **падает**, если нет `android/app/google-services.json`, `android/keystore.properties` или в `ads.xml` остались тестовые ID AdMob. Для локальной проверки без настоящих ID — флаг `-PallowTestAds` (такую сборку не загружать в Play).
5. **Политика конфиденциальности:** e-mail для связи вписан (раздел 9). Опубликовать `store/privacy-policy.html` как `usharik.github.io/routermanager/privacy-policy.html` (ссылка зашита в приложение, `PRIVACY_POLICY_URL`). Репозиторий `usharik/zyxel-router-app` должен быть публичным, иначе ссылка на GitHub Issues в политике не откроется.
6. **Play Console:** создать приложение, заполнить платёжный профиль продавца (Settings → Payments profile) и налоговые сведения: без них разовый товар `remove_ads` создать нельзя. Первая загрузка AAB (`cd android && ./gradlew :app:bundleRelease`), затем разовый товар `remove_ads`: в Console или `tools/play-remove-ads-product.rb 1.99` (нужен ключ сервисного аккаунта `android/play-api-key.json`). Товар появляется только после первой загрузки AAB.
7. **Тестирование:** внутренний трек (`cd android && bundle exec fastlane internal`). Новым личным аккаунтам разработчика нужно закрытое тестирование: 12 тестировщиков в течение 14 дней до выхода в продакшн. В Settings → License testing добавить свои аккаунты и проверить покупку `remove_ads` тестовой картой и возврат денег (Order management → Refund с отзывом покупки: реклама должна вернуться).

   Тех, кто ставил APK с Google Drive, предупредить: там другая подпись, поэтому версия из Play поверх не встанет. Перед установкой из Play старую версию нужно удалить (сохранённый пароль и история устройств при этом пропадут).
8. Заполнить анкеты (App content): Data safety (ниже), Content rating (утилита, без пользовательского контента), Target audience (18+ или 13+, не для детей), Ads: «Да, есть реклама», Advertising ID: «Да», цели — реклама и аналитика, Financial features: нет, Government: нет, Health apps: нет, News apps: нет.
   **App access:** «Все или часть функций ограничены» и в инструкции для проверяющих написать, что роутер не нужен: на экране входа есть кнопка «Try without a router» («Попробовать без роутера»), она открывает демо-режим со встроенными данными без сети.
9. **После публикации:** в AdMob привязать приложение к карточке Play (Apps → App settings → Add app store details), затем проверить в AdMob статус `app-ads.txt` (Apps → app-ads.txt): он должен найти файл на сайте разработчика из карточки.

## Карточка магазина

Тексты на пяти языках (en-US, ru-RU, uk, cs-CZ, de-DE) лежат в формате Fastlane: `android/fastlane/metadata/android/<язык>/{title,short_description,full_description}.txt`. Загрузить: `cd android && bundle exec fastlane supply --skip_upload_apk --skip_upload_aab --skip_upload_images --skip_upload_screenshots`.

**Категория:** Инструменты. **Графика:** значок 512×512 PNG, обложка 1024×500, от 2 скриншотов телефона (лучше 4–6: Обзор, Устройства, карточка устройства, Контроль, Журнал), для каждого языка свои. Снимать на моке (`mock/mock_router.js`, в эмуляторе адрес `10.0.2.2:8443`), без реальных MAC и имён.

### Совместимые модели

Приложение говорит с роутером через тот же веб-API, что и веб-интерфейс Zyxel: вход `/UserLogin` с RSA + AES, данные `/cgi-bin/DAL?oid=cardpage_status`, `lanhosts` и т. д. Тот же протокол реализует библиотека `nr7101` (интеграция Home Assistant `ha-zyxel`). Её пользователи подтвердили работу на этих моделях:

| Модель | Тип | Откуда |
|---|---|---|
| **VMG8623-T50B** | VDSL | проверено нами на живом роутере |
| VMG8825-T50, VMG3625-T50B | VDSL | README ha-zyxel |
| VMG4005-B50A | VDSL | ha-zyxel #31 (после обновления прошивки V5.17) |
| AX7501-B0 | оптика / Ethernet | README ha-zyxel |
| EX5601-T1 | Ethernet | ha-zyxel #60, #66 (после исправления одновременных сессий; у нас запросы и так идут по одному) |
| NR5103E, NR5103 v2, NR5307, NR7101, NR7102, NR7302 | 5G | README ha-zyxel, #50 |
| FWA505, FWA510, FWA710 | 5G | README ha-zyxel |
| LTE3202-M437, LTE5398-M904, LTE7490-M904 | LTE | README ha-zyxel |

Под вопросом: DX3301-T0/T1, EX5601-T0, EX3301-T0 (у пользователей ha-zyxel были проблемы, частично исправленные), T56, EE5301 (не подключились).

Оговорки для описания: на 4G/5G-моделях нет DSL-скорости линии, а состав родительского контроля и журнала может отличаться. Поэтому в описании стоит «проверено на VMG8623-T50B, должно работать с…» без обещания полной совместимости. Список моделей в описании одной строкой, без повторов, иначе Play может счесть это спамом ключевыми словами. Источники: github.com/zulufoxtrot/ha-zyxel (README и issues), github.com/zulufoxtrot/nr7101.

## Товарные знаки и авторские права

Что уже сделано:
- Название по схеме «… for Zyxel», бренд не в начале; в описании, «О приложении» и в экране лицензий написано, что приложение неофициальное и Zyxel его не одобряла.
- Иконка своя (векторный роутер), без логотипа и фирменных цветов Zyxel. В скриншотах и обложке не использовать логотип Zyxel, фото роутеров с сайта Zyxel и вид веб-интерфейса роутера.
- Протокол входа реализован для совместимости по обмену с роутером; из `nr7101`/`ha-zyxel` взят только список совместимых моделей. Код прошивки и веб-интерфейса Zyxel (JS, картинки) в приложение не входит. Если когда-нибудь переносить код из `nr7101` — сначала проверить его лицензию и указать в `open_source_licenses.txt`.
- Справочник производителей по MAC (`assets/oui.dat`) собран из открытых реестров IEEE; источник указан в экране лицензий.
- Фикстуры в репозитории обезличены (MAC, IP из 198.51.100.0/24, имена `device-N`, SSID `Net-N`).
- «О приложении» → «Лицензии открытого ПО»: текст `assets/open_source_licenses.txt` (Apache 2.0 и MIT требуют прикладывать лицензии к сборке). При добавлении библиотеки сверить список: `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`.

Остающиеся риски и решения:
- Имя пакета `com.usharik.routermanager` выбрано без товарного знака (до 1.4 было `com.usharik.zyxelrouter`): после первой загрузки в Play его не сменить, а при жалобе Zyxel пришлось бы выпускать новое приложение. Установленные с Google Drive версии 1.x со старым именем обновиться до версии из Play не могут, их нужно удалить.
- **Список моделей в описании** — номинативное использование, но 18 названий подряд Play может счесть спамом ключевыми словами. Если карточку отклонят — оставить 3–4 модели и «и другие роутеры Zyxel с тем же веб-интерфейсом».
- Если Zyxel пришлёт жалобу через Play, ответ: приложение неофициальное, бренд используется только для указания совместимости, логотипов нет.

## Data safety (ответы для анкеты)

| Вопрос | Ответ |
|---|---|
| Собирает или передаёт данные? | Да |
| Шифрование при передаче | Да |
| Можно запросить удаление | Нет (данные на сервере только у Google, без привязки к аккаунту) |
| Device or other IDs | Собирается (AdMob, Firebase): реклама, аналитика; передаётся (AdMob) |
| App interactions (App activity) | Собирается: аналитика (Firebase Analytics, в том числе события показа рекламы и покупки `remove_ads`); реклама (AdMob: показы и клики по объявлениям); передаётся (AdMob) |
| Crash logs | Собирается: аналитика (Crashlytics) |
| Diagnostics | Собирается: аналитика (Crashlytics); реклама (AdMob: сведения об устройстве и производительности); передаётся (AdMob) |
| Approximate location | AdMob может определять по IP: реклама |
| Purchase history | Собирается: функциональность приложения (Google Play Billing), аналитика (события покупки в Firebase Analytics, без платёжных данных) |
| Личная информация, контакты, фото, файлы, точное местоположение | Нет |

Пароль роутера, сведения об устройствах в сети и журнал роутера с устройства не уходят (только на сам роутер), поэтому в анкету не входят.

Пароль Wi-Fi читается с роутера только когда пользователь открывает «Поделиться сетью», обрабатывается на устройстве и нигде не сохраняется. Наружу он уходит только по явному действию пользователя — в приложение, выбранное в системном меню «Поделиться». По правилам Play это не сбор и не передача данных, поэтому в анкету не входит. В аналитику идут только `wifi_qr_shown` и `wifi_qr_shared` без имени сети и пароля.

## Что проверить перед выпуском

- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:bundleRelease` без `-PallowTestAds`.
- На реальном телефоне с Play Store: покупка `remove_ads` тестовым аккаунтом (License testing в Play Console), баннер исчезает, после переустановки покупка восстанавливается.
- Форма согласия в ЕЭЗ и пункт меню «Настройки рекламы и конфиденциальности». В ЕЭЗ без согласия (цель 1) события в DebugView не приходят; после согласия приходят. С купленным `remove_ads` формы нет.
- Демо-режим: кнопка на экране входа открывает приложение без сети и без роутера.
- В Firebase DebugView приходят события (`adb shell setprop debug.firebase.analytics.app com.usharik.routermanager`; в debug-сборке сбор выключен, проверять release или временно включить).
