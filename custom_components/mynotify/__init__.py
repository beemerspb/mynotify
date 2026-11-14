"""MyNotify — показ камеры оверлей-окном на Android-устройствах.

Одна сущность «Камера поверх экрана» на каждое Android-устройство
(humidifier: тумблер окна + выпадающий список камер внутри more-info).

Сервисы mynotify.show / mynotify.hide — для автоматизаций.
Команды идут на устройства через androidtv_remote (ADB).
"""
from __future__ import annotations

import logging

import voluptuous as vol

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, ServiceCall
import homeassistant.helpers.config_validation as cv
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.aiohttp_client import async_get_clientsession

_LOGGER = logging.getLogger(__name__)

DOMAIN = "mynotify"
PLATFORMS = ["humidifier"]
DATA_ENTRIES = "entries"

CONF_GO2RTC_URL = "go2rtc_url"
CONF_TARGET = "target"
CONF_STREAM = "stream"
CONF_AUTOHIDE = "autohide"

OPT_STREAM = "stream"

DEFAULT_GO2RTC = "http://homeassistant.local:1984"

SCHEMA_SHOW = vol.Schema(
    {
        vol.Required(CONF_TARGET): cv.entity_ids,
        vol.Required(CONF_STREAM): cv.string,
        vol.Optional(CONF_AUTOHIDE, default=0): vol.All(
            vol.Coerce(int), vol.Range(min=0, max=3600)
        ),
    }
)

SCHEMA_HIDE = vol.Schema({vol.Required(CONF_TARGET): cv.entity_ids})


def _entries(hass: HomeAssistant) -> list[ConfigEntry]:
    return hass.data.get(DOMAIN, {}).get(DATA_ENTRIES, [])


def first_entry(hass: HomeAssistant) -> ConfigEntry | None:
    entries = _entries(hass)
    return entries[0] if entries else None


def base_url(hass: HomeAssistant) -> str:
    entry = first_entry(hass)
    if entry:
        return str(entry.data.get(CONF_GO2RTC_URL, DEFAULT_GO2RTC)).rstrip("/")
    return DEFAULT_GO2RTC


def build_url(hass: HomeAssistant, stream: str) -> str:
    """Имя потока go2rtc -> HLS URL; полный http(s)-URL проходит как есть."""
    if stream.startswith(("http://", "https://")):
        return stream
    return f"{base_url(hass)}/api/stream.m3u8?src={stream}"


def build_show_command(hass: HomeAssistant, stream: str, autohide: int = 0) -> str:
    """Запуск службы напрямую — без открытия активности (без всплывающего UI)."""
    cmd = (
        "am start-foreground-service -n com.mynotify/.OverlayService "
        f'-a com.mynotify.action.SHOW --es url "{build_url(hass, stream)}"'
    )
    if autohide:
        cmd += f" --ei autohide_sec {int(autohide)}"
    return cmd


def build_hide_command() -> str:
    return (
        "am start-foreground-service -n com.mynotify/.OverlayService "
        "-a com.mynotify.action.HIDE"
    )


async def send_to_targets(
    hass: HomeAssistant, target: list[str], command: str
) -> None:
    if not target:
        _LOGGER.warning("mynotify: не выбрано устройство показа")
        return
    await hass.services.async_call(
        "androidtv_remote",
        "send_command",
        {"entity_id": target, "command": command},
        blocking=False,
    )


async def async_get_streams(hass: HomeAssistant) -> list[str]:
    """Список имён потоков go2rtc (без служебных rtsp-ключей)."""
    session = async_get_clientsession(hass)
    async with session.get(f"{base_url(hass)}/api/streams", timeout=10) as resp:
        data = await resp.json()
    names = [k for k in data if not k.startswith("rtsp://")]
    mains = sorted(n for n in names if not n.endswith("_sub"))
    subs = sorted(n for n in names if n.endswith("_sub"))
    return mains + subs


def androidtv_entities(hass: HomeAssistant) -> list[str]:
    """media_player-сущности интеграции androidtv_remote."""
    registry = er.async_get(hass)
    return sorted(
        e.entity_id
        for e in registry.entities.values()
        if e.platform == "androidtv_remote" and e.entity_id.startswith("media_player.")
    )


def _register_services(hass: HomeAssistant) -> None:
    if hass.services.has_service(DOMAIN, "show"):
        return

    async def async_show(call: ServiceCall) -> None:
        cmd = build_show_command(hass, call.data[CONF_STREAM], call.data.get(CONF_AUTOHIDE, 0))
        _LOGGER.debug("mynotify.show -> %s: %s", call.data[CONF_TARGET], cmd)
        await send_to_targets(hass, call.data[CONF_TARGET], cmd)

    async def async_hide(call: ServiceCall) -> None:
        await send_to_targets(hass, call.data[CONF_TARGET], build_hide_command())

    hass.services.async_register(DOMAIN, "show", async_show, schema=SCHEMA_SHOW)
    hass.services.async_register(DOMAIN, "hide", async_hide, schema=SCHEMA_HIDE)


async def async_setup(hass: HomeAssistant, config: dict) -> bool:
    hass.data.setdefault(DOMAIN, {DATA_ENTRIES: []})
    return True


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    hass.data.setdefault(DOMAIN, {DATA_ENTRIES: []})
    if not any(e.entry_id == entry.entry_id for e in hass.data[DOMAIN][DATA_ENTRIES]):
        hass.data[DOMAIN][DATA_ENTRIES].append(entry)
    _register_services(hass)
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    entries = _entries(hass)
    for e in list(entries):
        if e.entry_id == entry.entry_id:
            entries.remove(e)
    if not entries and hass.services.has_service(DOMAIN, "show"):
        hass.services.async_remove(DOMAIN, "show")
        hass.services.async_remove(DOMAIN, "hide")
    return True
