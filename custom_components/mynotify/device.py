"""Контроллер устройства MyNotify + координатор опроса.

Один экземпляр на запись конфигурации: состояние окна, выбранная камера,
отправка команд в очередь устройства и общий опрос (онлайн + список
потоков go2rtc) для всех сущностей устройства.
"""
from __future__ import annotations

import logging
from datetime import timedelta

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity import DeviceInfo
from homeassistant.helpers.update_coordinator import (
    CoordinatorEntity,
    DataUpdateCoordinator,
)

from . import (
    CONF_GO2RTC_URL,
    DOMAIN,
    OPT_MUTE,
    OPT_STREAM,
    async_get_streams,
    build_url,
    is_online,
    resolve_mute,
    send_command,
    store,
    stream_path,
)

_LOGGER = logging.getLogger(__name__)

SCAN_INTERVAL = timedelta(seconds=10)


class MyNotifyCoordinator(DataUpdateCoordinator):
    """Опрос: онлайн ли устройство, какие потоки отдаёт go2rtc."""

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry) -> None:
        super().__init__(
            hass,
            _LOGGER,
            name=f"{DOMAIN}:{entry.entry_id}",
            update_interval=SCAN_INTERVAL,
        )
        self.entry = entry
        self.device_id: str = entry.data["device_id"]
        self.online = False
        self.streams: list[str] = []

    async def _async_update_data(self) -> bool:
        self.online = is_online(self.hass, self.device_id)
        try:
            streams = await async_get_streams(self.hass)
            if streams:
                self.streams = streams
        except Exception as err:  # noqa: BLE001
            _LOGGER.debug("mynotify: список камер недоступен: %s", err)
        return self.online


class Device:
    """Управление окном одного Android-устройства."""

    def __init__(
        self, hass: HomeAssistant, entry: ConfigEntry, coord: MyNotifyCoordinator
    ) -> None:
        self.hass = hass
        self.entry = entry
        self.coord = coord
        self.device_id = coord.device_id
        self.on = False
        self.stream: str | None = (entry.options or {}).get(OPT_STREAM)
        self._watchers: list = []

    def watch(self, cb) -> None:
        self._watchers.append(cb)

    def unwatch(self, cb) -> None:
        if cb in self._watchers:
            self._watchers.remove(cb)

    def notify(self) -> None:
        for cb in list(self._watchers):
            try:
                cb()
            except Exception:  # noqa: BLE001
                pass

    def current_stream(self) -> str | None:
        if self.stream in self.coord.streams or not self.coord.streams:
            return self.stream
        return self.coord.streams[0] if self.coord.streams else None

    async def show(
        self, stream: str | None = None, autohide: int = 0, mute: bool | None = None
    ) -> None:
        s = stream or self.current_stream()
        if not s:
            _LOGGER.warning("mynotify: нет ни одной камеры go2rtc для показа")
            return
        if stream:
            # показ конкретной камеры (сервис/автоматизация) — запоминаем её
            # как выбранную, чтобы список «Камера» в UI показывал правду
            self.set_stream(s)
        m = resolve_mute((self.entry.options or {}).get(OPT_MUTE), mute)
        if mute is not None and (self.entry.options or {}).get(OPT_MUTE) != m:
            # параметр из службы синхронизируется с настройкой устройства
            self.hass.config_entries.async_update_entry(
                self.entry, options={**(self.entry.options or {}), OPT_MUTE: m}
            )
        cmd = {
            "cmd": "show",
            "url": build_url(self.hass, s),
            "autohide": autohide,
            "mute": 1 if m else 0,
        }
        # относительный путь через прокси HA: работает и из локальной сети,
        # и снаружи — приложение подставит активный адрес HA
        path = stream_path(
            self.hass, self.device_id, self.entry.data["device_code"], s
        )
        if path:
            cmd["path"] = path
        send_command(self.hass, self.device_id, cmd)
        self.on = True
        self.notify()

    async def hide(self) -> None:
        send_command(self.hass, self.device_id, {"cmd": "hide"})
        self.on = False
        self.notify()

    def set_stream(self, stream: str) -> None:
        self.stream = stream
        self.hass.config_entries.async_update_entry(
            self.entry, options={**(self.entry.options or {}), OPT_STREAM: stream}
        )
        self.notify()

    def cmd(self, name: str) -> None:
        send_command(self.hass, self.device_id, {"cmd": name})


async def async_setup_device(
    hass: HomeAssistant, entry: ConfigEntry
) -> tuple[Device, MyNotifyCoordinator]:
    devices = store(hass).setdefault("devices", {})
    dev = devices.get(entry.entry_id)
    if dev is None:
        coord = MyNotifyCoordinator(hass, entry)
        dev = Device(hass, entry, coord)
        # ВАЖНО: вставляем в кэш ДО первого await. Платформы (switch/select/
        # button) ставятся параллельно: без этого каждая создавала свою
        # копию Device — переключение камеры дёргало один экземпляр, а
        # сущность «Камера» показывала другой (расхождение списка и показа).
        devices[entry.entry_id] = dev
        await coord.async_config_entry_first_refresh()
    return dev, dev.coord


async def async_unload_device(hass: HomeAssistant, entry: ConfigEntry) -> None:
    store(hass).get("devices", {}).pop(entry.entry_id, None)


class MyNotifyEntity(CoordinatorEntity):
    """База всех сущностей устройства: один девайс-блок на карточке."""

    _attr_has_entity_name = True

    def __init__(self, dev: Device, key: str, name: str | None, icon: str) -> None:
        super().__init__(dev.coord)
        self._dev = dev
        self._attr_unique_id = f"{dev.entry.entry_id}-{key}"
        if name:
            self._attr_name = name
        self._attr_icon = icon
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, dev.device_id)},
            name=dev.entry.title,
            manufacturer="MyNotify",
            model="Android overlay",
        )

    @property
    def available(self) -> bool:
        return self.coordinator.online and self.coordinator.last_update_success

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        self._dev.watch(self.async_write_ha_state)

    async def async_will_remove_from_hass(self) -> None:
        self._dev.unwatch(self.async_write_ha_state)
