"""Кнопки управления окном MyNotify: скриншот, полный экран, запись."""
from __future__ import annotations

from homeassistant.components.button import ButtonEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant

from .device import MyNotifyEntity, async_setup_device


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities
) -> None:
    dev, _coord = await async_setup_device(hass, entry)
    async_add_entities(
        [
            WindowButton(dev, "shot", "Скриншот", "mdi:camera", "screenshot"),
            WindowButton(dev, "fs", "На весь экран", "mdi:fullscreen", "fullscreen"),
            WindowButton(dev, "rec", "Запись видео", "mdi:record", "record"),
        ]
    )


class WindowButton(MyNotifyEntity, ButtonEntity):
    """Однократная команда окну на устройстве."""

    def __init__(self, dev, key: str, name: str, icon: str, cmd: str) -> None:
        super().__init__(dev, key, name, icon)
        self._cmd = cmd

    async def async_press(self) -> None:
        self._dev.cmd(self._cmd)
