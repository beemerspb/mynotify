"""Выпадающий список камер go2rtc для устройства MyNotify."""
from __future__ import annotations

from homeassistant.components.select import SelectEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant

from .device import MyNotifyEntity, async_setup_device


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities
) -> None:
    dev, _coord = await async_setup_device(hass, entry)
    async_add_entities([CameraSelect(dev)])


class CameraSelect(MyNotifyEntity, SelectEntity):
    """Камера, которую показывает окно."""

    def __init__(self, dev) -> None:
        super().__init__(dev, "cam", "Камера", "mdi:video-switch")

    @property
    def options(self) -> list[str]:
        return self.coordinator.streams

    @property
    def current_option(self) -> str | None:
        return self._dev.current_stream()

    async def async_select_option(self, option: str) -> None:
        self._dev.set_stream(option)
        if self._dev.on:
            # окно открыто — переключаем поток сразу
            await self._dev.show(option)
        self.async_write_ha_state()
