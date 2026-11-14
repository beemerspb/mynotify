"""Одна сущность «Камера поверх экрана» на каждое Android-устройство.

Реализована как humidifier: у него в more-info ровно то, что нужно —
тумблер вкл/выкл окна + выпадающий список режима (= камеры go2rtc).
Открываешь сущность: слева power, список камер, всё управление там же.
"""
from __future__ import annotations

import logging
from datetime import timedelta

from homeassistant.components.humidifier import HumidifierEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.restore_state import RestoreEntity

from . import (
    DOMAIN,
    OPT_STREAM,
    androidtv_entities,
    async_get_streams,
    build_hide_command,
    build_show_command,
    send_to_targets,
)

_LOGGER = logging.getLogger(__name__)

SCAN_INTERVAL = timedelta(minutes=5)


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities
) -> None:
    targets = androidtv_entities(hass)
    async_add_entities(MyNotifyOverlay(hass, entry, t) for t in targets)


class MyNotifyOverlay(HumidifierEntity, RestoreEntity):
    """Оверлей-камера одного устройства: in — показать, off — скрыть,
    режим — выбранная камера."""

    _attr_name = "Камера поверх экрана"
    _attr_icon = "mdi:cctv"
    _attr_should_poll = True

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry, target: str) -> None:
        self._hass = hass
        self._entry = entry
        self._target = target
        self._attr_unique_id = f"{entry.entry_id}-ovl-{target}"
        self._attr_is_on = False
        self._attr_mode = None

    # --- выбор камеры для этой сущности хранится в options записи ---
    @property
    def _mode_key(self) -> str:
        return f"mode:{self._target}"

    def _saved_mode(self) -> str | None:
        o = self._entry.options or {}
        return o.get(self._mode_key) or o.get(OPT_STREAM)

    def _store_mode(self, mode: str) -> None:
        self._hass.config_entries.async_update_entry(
            self._entry, options={**(self._entry.options or {}), self._mode_key: mode}
        )

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        last = await self.async_get_last_state()
        if last is not None:
            self._attr_is_on = last.state == "on"
            if last.attributes.get("mode"):
                self._attr_mode = last.attributes["mode"]
        await self.async_update()

    async def async_update(self) -> None:
        try:
            modes = await async_get_streams(self._hass)
        except Exception as err:  # noqa: BLE001
            _LOGGER.debug("mynotify: список камер недоступен: %s", err)
            modes = self._attr_available_modes or []
        if modes:
            self._attr_available_modes = modes
            if self._attr_mode not in modes:
                saved = self._saved_mode()
                self._attr_mode = saved if saved in modes else modes[0]

    async def async_turn_on(self, **kwargs) -> None:
        camera = self._attr_mode or (self._attr_available_modes or ["Cam1"])[0]
        await send_to_targets(
            self._hass, [self._target], build_show_command(self._hass, camera)
        )
        self._attr_is_on = True
        self.async_write_ha_state()

    async def async_turn_off(self, **kwargs) -> None:
        await send_to_targets(self._hass, [self._target], build_hide_command())
        self._attr_is_on = False
        self.async_write_ha_state()

    async def async_set_mode(self, mode: str) -> None:
        self._attr_mode = mode
        self._store_mode(mode)
        if self._attr_is_on:
            # окно уже открыто — переключаем поток на новый
            await send_to_targets(
                self._hass, [self._target], build_show_command(self._hass, mode)
            )
        self.async_write_ha_state()
