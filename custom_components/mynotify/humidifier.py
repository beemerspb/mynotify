"""Одна сущность «Камера поверх экрана» на привязанное Android-устройство.

Реализована как humidifier: в more-info ровно нужное — тумблер показа окна
+ выпадающий список камер go2rtc. Команды уходят в очередь устройства,
приложение забирает их своим фоновым опросом (без ADB).
"""
from __future__ import annotations

import logging
import time
from datetime import timedelta

from homeassistant.components.humidifier import HumidifierEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.restore_state import RestoreEntity

from . import (
    OPT_STREAM,
    STALE_SEC,
    async_get_streams,
    build_url,
    is_online,
    send_command,
    store,
)

_LOGGER = logging.getLogger(__name__)

SCAN_INTERVAL = timedelta(seconds=15)


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities
) -> None:
    async_add_entities(
        [
            MyNotifyOverlay(
                hass,
                entry,
                entry.data["device_id"],
                entry.data.get("device_name", ""),
            )
        ]
    )


class MyNotifyOverlay(HumidifierEntity, RestoreEntity):
    """Оверлей-камера одного устройства: on — показать, off — скрыть,
    режим — выбранная камера."""

    _attr_name = "Камера поверх экрана"
    _attr_icon = "mdi:cctv"
    _attr_should_poll = True

    def __init__(
        self,
        hass: HomeAssistant,
        entry: ConfigEntry,
        device_id: str,
        device_name: str,
    ) -> None:
        self._hass = hass
        self._entry = entry
        self._device_id = device_id
        if device_name:
            self._attr_name = f"Камера поверх экрана — {device_name}"
        self._attr_unique_id = f"{entry.entry_id}-ovl-{device_id}"
        self._attr_is_on = False
        self._attr_mode = None

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        store(self._hass)["entities"][self.entity_id] = self
        last = await self.async_get_last_state()
        if last is not None:
            self._attr_is_on = last.state == "on"
            if last.attributes.get("mode"):
                self._attr_mode = last.attributes["mode"]
        await self.async_update()

    async def async_will_remove_from_hass(self) -> None:
        store(self._hass)["entities"].pop(self.entity_id, None)

    async def async_update(self) -> None:
        self._attr_available = is_online(self._hass, self._device_id)
        try:
            modes = await async_get_streams(self._hass)
        except Exception as err:  # noqa: BLE001
            _LOGGER.debug("mynotify: список камер недоступен: %s", err)
            modes = self._attr_available_modes or []
        if modes:
            self._attr_available_modes = modes
            if self._attr_mode not in modes:
                saved = (self._entry.options or {}).get(OPT_STREAM)
                self._attr_mode = saved if saved in modes else modes[0]

    # --- управление окном ---

    async def async_show_camera(self, stream: str, autohide: int = 0) -> None:
        send_command(
            self._hass,
            self._device_id,
            {"cmd": "show", "url": build_url(self._hass, stream), "autohide": autohide},
        )
        self._attr_is_on = True
        self.async_write_ha_state()

    async def async_hide_window(self) -> None:
        send_command(self._hass, self._device_id, {"cmd": "hide"})
        self._attr_is_on = False
        self.async_write_ha_state()

    # --- humidifier-интерфейс (more-info: power + список камер) ---

    async def async_turn_on(self, **kwargs) -> None:
        camera = self._attr_mode or (self._attr_available_modes or ["Cam1"])[0]
        await self.async_show_camera(camera)

    async def async_turn_off(self, **kwargs) -> None:
        await self.async_hide_window()

    async def async_set_mode(self, mode: str) -> None:
        self._attr_mode = mode
        self._hass.config_entries.async_update_entry(
            self._entry, options={**(self._entry.options or {}), OPT_STREAM: mode}
        )
        if self._attr_is_on:
            # окно уже открыто — переключаем поток на новый
            await self.async_show_camera(mode)
        self.async_write_ha_state()
