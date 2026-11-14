"""Переключатель «Показ окна» устройства MyNotify."""
from __future__ import annotations

from homeassistant.components.switch import SwitchEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.restore_state import RestoreEntity
from homeassistant.util import slugify

from . import store
from .device import MyNotifyEntity, async_setup_device


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities
) -> None:
    dev, _coord = await async_setup_device(hass, entry)
    async_add_entities([OverlaySwitch(dev)])


class OverlaySwitch(MyNotifyEntity, RestoreEntity, SwitchEntity):
    """on — показать окно с выбранной камерой, off — скрыть."""

    def __init__(self, dev) -> None:
        super().__init__(dev, "push", "Показ окна", "mdi:cctv")
        # стабильный короткий entity_id: switch.mynotify_<устройство>_push
        self._attr_suggested_object_id = f"{slugify(dev.entry.title)}_push"

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        store(self.hass)["entities"][self.entity_id] = self
        last = await self.async_get_last_state()
        if last is not None:
            self._dev.on = last.state == "on"

    async def async_will_remove_from_hass(self) -> None:
        store(self.hass)["entities"].pop(self.entity_id, None)

    @property
    def is_on(self) -> bool:
        return self._dev.on

    async def async_turn_on(self, **kwargs) -> None:
        await self._dev.show()
        self.async_write_ha_state()

    async def async_turn_off(self, **kwargs) -> None:
        await self._dev.hide()
        self.async_write_ha_state()

    # --- вызовы из сервисов mynotify.show / mynotify.hide ---

    async def async_show_camera(self, stream: str, autohide: int = 0) -> None:
        await self._dev.show(stream, autohide)
        self.async_write_ha_state()

    async def async_hide_window(self) -> None:
        await self._dev.hide()
        self.async_write_ha_state()
