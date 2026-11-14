# Интеграция MyNotify с Home Assistant

Приложение на устройстве — только служба: выдал разрешение один раз, дальше
всем управляет HA. На каждое Android-устройство интеграция создаёт ОДНУ
сущность «Камера поверх экрана» — открываешь её, внутри:

- тумблер вкл/выкл окна (показать / скрыть оверлей),
- выпадающий список камер — тянется живьём из go2rtc; смена камеры при
  открытом окне переключает поток сразу.

Команды идут `am start-foreground-service` напрямую в службу — активность
приложения не открывается, всплывающего UI нет.

Камера для автоматизаций выбирается сервисами (ниже).
Интеграция `mynotify` лежит в custom_components/mynotify (готовый путь для
Marketplace/HACS), деплой ./deploy.sh или по ссылке из README.

## Установка

1. Marketplace (HA 2026.11+): Настройки → Marketplace → «Добавить по
   ссылке» → https://github.com/beemerspb/mynotify → установить.
   HACS (HA ≤2026.10): пользовательские репозитории → та же ссылка.
   Либо руками: `./deploy.sh` (кладёт в /config/custom_components/mynotify).
2. Рестарт HA Core (только по явному согласию пользователя; при установке
   из Marketplace на 2026.11+ рестарт обычно не нужен).
- Настройки → Устройства и сервисы → Добавить интеграцию → MyNotify →
  указать адрес go2rtc (обычно http://<адрес HA>:1984).
4. На устройство (ТВ/планшет) поставлен APK MyNotify, выдано разрешение,
   устройство подключено к HA через androidtv_remote (ADB).

## Сервисы

`mynotify.show` — показать окно:

```yaml
action: mynotify.show
data:
  target:
    - media_player.tv_kitchen     # сущность androidtv_remote
  stream: Cam1        # имя потока go2rtc ИЛИ полный http-URL
  autohide: 0         # сек, 0 = не скрывать
```

`mynotify.hide` — закрыть окно:

```yaml
action: mynotify.hide
data:
  target:
    - media_player.tv_kitchen
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
            - media_player.tv_kitchen
          stream: Cam1
          autohide: 30
```

Другой датчик — другая камера на другом устройстве:

```yaml
      - action: mynotify.show
        data:
          target:
            - media_player.tv_bedroom
          stream: Cam2
          autohide: 120
```

## Как это работает под капотом

`mynotify.show` собирает `http://<go2rtc>/api/stream.m3u8?src=<stream>`
и шлёт на устройство командой ADB:
`am start -a com.mynotify.SHOW --es url "<url>" [--ei autohide_sec N]`.
Приложение открывает HLS через ExoPlayer в окне поверх всех приложений.

## Проверка без HA (с консоли)

```bash
adb connect <IP>:5555
adb -s <IP>:5555 shell am start -a com.mynotify.SHOW --es url \
  "http://<адрес HA>:1984/api/stream.m3u8?src=Cam1"
```

## Голосовые команды

Фразу («покажи камеру у ворот на телевизоре») мапит автоматизация с
`yandex_station_intents` или Assisted → вызывает mynotify.show.
ВАЖНО: первую команду конкретному ТВ отправлять только вручную/с отмашки.
