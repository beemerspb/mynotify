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
    OPT_STREAM,
    async_get_streams,
    build_url,
    is_online,
    send_command,
    store,
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

    def current_stream(self) -> str | None:
        if self.stream in self.coord.streams or not self.coord.streams:
            return self.stream
        return self.coord.streams[0] if self.coord.streams else None

    async def show(self, stream: str | None = None, autohide: int = 0) -> None:
        s = stream or self.current_stream()
        if not s:
            _LOGGER.warning("mynotify: нет ни одной камеры go2rtc для показа")
            return
        self.stream = s
        send_command(
            self.hass,
            self.device_id,
            {"cmd": "show", "url": build_url(self.hass, s), "autohide": autohide},
        )
        self.on = True

    async def hide(self) -> None:
        send_command(self.hass, self.device_id, {"cmd": "hide"})
        self.on = False

    def set_stream(self, stream: str) -> None:
        self.stream = stream
        self.hass.config_entries.async_update_entry(
            self.entry, options={**(self.entry.options or {}), OPT_STREAM: stream}
        )

    def cmd(self, name: str) -> None:
        send_command(self.hass, self.device_id, {"cmd": name})


async def async_setup_device(
    hass: HomeAssistant, entry: ConfigEntry
) -> tuple[Device, MyNotifyCoordinator]:
    devices = store(hass).setdefault("devices", {})
    if entry.entry_id in devices:
        dev = devices[entry.entry_id]
        return dev, dev.coord
    coord = MyNotifyCoordinator(hass, entry)
    await coord.async_config_entry_first_refresh()
    dev = Device(hass, entry, coord)
    devices[entry.entry_id] = dev
    return dev, coord


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
