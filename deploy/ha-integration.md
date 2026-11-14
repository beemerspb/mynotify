# Интеграция MyNotify с Home Assistant

Приложение на устройстве — фоновая служба, которая САМА подключается к HA:
регистрируется по 6-значному коду и опрашивает очередь команд по HTTP.
ADB, беспроводная отладка и androidtv_remote НЕ нужны.

Каждое привязанное устройство = одна карточка интеграции и ОДНА сущность
«Камера поверх экрана» — открываешь её, внутри:

- тумблер вкл/выкл окна (показать / скрыть оверлей),
- выпадающий список камер — тянется живьём из go2rtc; смена камеры при
  открытом окне переключает поток сразу.

Скрытое окно службу не останавливает: HA может показать окно в любой момент.

## Установка

1. Marketplace (HA 2026.11+): Настройки → Marketplace → «Добавить по
   ссылке» → https://github.com/beemerspb/mynotify → установить.
   HACS (HA ≤2026.10): пользовательские репозитории → та же ссылка.
   Либо руками: `./deploy.sh` (кладёт в /config/custom_components/mynotify).
2. Рестарт HA Core (только по явному согласию пользователя; при установке
   из Marketplace на 2026.11+ рестарт обычно не нужен).
3. На устройство (ТВ/планшет) поставить APK с релизов репозитория, выдать
   разрешение «Поверх приложений», нажать «Запустить службу». В приложении
   проверить адрес HA и запомнить 6-значный код.
4. HA: Настройки → Устройства и сервисы → Добавить интеграцию → MyNotify →
   адрес go2rtc (обычно http://<адрес HA>:1984) → ввести код из приложения.

## Сервисы

`mynotify.show` — показать окно:

```yaml
action: mynotify.show
data:
  target:
    - humidifier.mynotify_camera_poverh_ekrana   # сущность устройства
  stream: Cam1        # имя потока go2rtc ИЛИ полный http-URL
  autohide: 0         # сек, 0 = не скрывать
```

`mynotify.hide` — закрыть окно (служба остаётся подключённой):

```yaml
action: mynotify.hide
data:
  target:
    - humidifier.mynotify_camera_poverh_ekrana
```

Имена потоков смотрятся в API go2rtc: `http://<адрес HA>:1984/api/streams`
(там же видно субпотоки вида `*_sub` — легче для слабых сетей).

ВАЖНО: fragment-параметры вида `src=Cam1#audio=no` API HLS не понимает
("stream not found") — использовать только чистое имя потока.

## Пример автоматизации: триггер → нужная камера

Например, сработал датчик на входе — показать входную камеру на ТВ 30 секунд:

```yaml
automation:
  - alias: "Вход: показать камеру на ТВ"
    triggers:
      - trigger: state
        entity_id: binary_sensor.door_open
        to: "on"
    actions:
      - action: mynotify.show
        data:
          target:
            - humidifier.mynotify_camera_poverh_ekrana
          stream: Cam1
          autohide: 30
```

Другой датчик — другая камера на другом устройстве:

```yaml
      - action: mynotify.show
        data:
          target:
            - humidifier.mynotify_camera_poverh_ekrana_tv
          stream: Cam2
          autohide: 120
```

## Как это работает под капотом

- Приложение: `POST /api/mynotify/register {device_id, code, name}` раз в
  10 с; `GET /api/mynotify/poll?device=..&code=..` раз в 2 с.
- Интеграция кладёт команды в очередь устройства; poll отдаёт
  `{"cmd":"show","url":"http://<go2rtc>/api/stream.m3u8?src=...","autohide":N}`
  или `{"cmd":"hide"}`.
- Endpoints без авторизации (устройство ещё не привязано); доступ ограничен
  LAN и 6-значным кодом — не ставьте HA наружу без reverse-proxy.
- Сущность offline, если устройство не опрашивало HA дольше 30 с.

## Голосовые команды

Фразу («покажи камеру у ворот на телевизоре») мапит автоматизация с
`yandex_station_intents` или Assisted → вызывает mynotify.show.
ВАЖНО: первую команду конкретному ТВ отправлять только вручную/с отмашки.
