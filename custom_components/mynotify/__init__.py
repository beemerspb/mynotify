"""MyNotify — показ камеры оверлей-окном на Android-устройствах.

Устройство (планшет/ТВ с приложением MyNotify) ПОДКЛЮЧАЕТСЯ К ИНТЕГРАЦИИ
САМО: фоновая служба в приложении регистрируется по 6-значному коду и
опрашивает очередь команд. Никакого ADB/androidtv_remote не нужно.

Каждое устройство = одна запись конфигурации и один блок устройств
(HA-устройство) с сущностями: выключатель показа окна, список камер,
кнопки скриншота / полного экрана / записи.

Сервисы mynotify.show / mynotify.hide — для автоматизаций.
"""
from __future__ import annotations

import logging
import time

import voluptuous as vol
from aiohttp import web

from homeassistant.components.http import HomeAssistantView
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, ServiceCall
import homeassistant.helpers.config_validation as cv
from homeassistant.helpers.aiohttp_client import async_get_clientsession

_LOGGER = logging.getLogger(__name__)

DOMAIN = "mynotify"
PLATFORMS = ["switch", "select", "button"]

CONF_GO2RTC_URL = "go2rtc_url"
CONF_TARGET = "target"
CONF_STREAM = "stream"
CONF_AUTOHIDE = "autohide"

OPT_STREAM = "stream"

DEFAULT_GO2RTC = "http://homeassistant.local:1984"

STALE_SEC = 30  # устройство считается offline, если не опрашивалось дольше

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


def store(hass: HomeAssistant) -> dict:
    return hass.data.setdefault(
        DOMAIN,
        {"entries": {}, "pending": {}, "seen": {}, "queues": {}, "entities": {}},
    )


def base_url(hass: HomeAssistant) -> str:
    for entry in store(hass)["entries"].values():
        return str(entry.data.get(CONF_GO2RTC_URL, DEFAULT_GO2RTC)).rstrip("/")
    return DEFAULT_GO2RTC


def build_url(hass: HomeAssistant, stream: str) -> str:
    """Имя потока go2rtc -> HLS URL; полный http(s)-URL проходит как есть."""
    if stream.startswith(("http://", "https://")):
        return stream
    return f"{base_url(hass)}/api/stream.m3u8?src={stream}"


def send_command(hass: HomeAssistant, device_id: str, cmd: dict) -> None:
    store(hass)["queues"].setdefault(device_id, []).append(cmd)


def is_online(hass: HomeAssistant, device_id: str) -> bool:
    return store(hass)["seen"].get(device_id, 0) > time.time() - STALE_SEC


async def async_get_streams(hass: HomeAssistant) -> list[str]:
    """Список имён потоков go2rtc (без служебных rtsp-ключей)."""
    session = async_get_clientsession(hass)
    async with session.get(f"{base_url(hass)}/api/streams", timeout=10) as resp:
        data = await resp.json()
    names = [k for k in data if not k.startswith("rtsp://")]
    mains = sorted(n for n in names if not n.endswith("_sub"))
    subs = sorted(n for n in names if n.endswith("_sub"))
    return mains + subs


class RegisterView(HomeAssistantView):
    """POST /api/mynotify/register — приложение объявляет себя (code+id).

    Без авторизации: устройство ещё не привязано. Защита — 6-значный код,
    который пользователь вводит в настройках интеграции, и LAN-доступ.
    """

    url = "/api/mynotify/register"
    url_path = "/api/mynotify/register"
    name = "api:mynotify:register"
    requires_auth = False

    async def post(self, request: web.Request) -> web.Response:
        hass = request.app["hass"]
        data = await request.json()
        code = str(data.get("code", ""))
        device_id = str(data.get("device_id", ""))
        name = str(data.get("name", "Android"))
        if len(code) != 6 or not device_id:
            return self.json({"ok": False, "error": "bad_request"})
        st = store(hass)
        now = time.time()
        # чистим протухшие незакрытые заявки (сутки)
        for k in [k for k, v in st["pending"].items() if now - v["ts"] > 86400]:
            st["pending"].pop(k, None)
        st["pending"][code] = {"device_id": device_id, "name": name, "ts": now}
        bound = any(
            e.data.get("device_id") == device_id for e in st["entries"].values()
        )
        if bound:
            st["seen"][device_id] = time.time()
        return self.json({"ok": True, "bound": bound})


class PollView(HomeAssistantView):
    """GET /api/mynotify/poll — heartbeat устройства + выдача команды."""

    url = "/api/mynotify/poll"
    url_path = "/api/mynotify/poll"
    name = "api:mynotify:poll"
    requires_auth = False

    async def get(self, request: web.Request) -> web.Response:
        hass = request.app["hass"]
        device_id = request.query.get("device", "")
        code = request.query.get("code", "")
        st = store(hass)
        bound = any(
            e.data.get("device_id") == device_id and e.data.get("device_code") == code
            for e in st["entries"].values()
        )
        if not bound and st["pending"].get(code, {}).get("device_id") != device_id:
            return self.json({"ok": False})
        st["seen"][device_id] = time.time()
        queue = st["queues"].get(device_id)
        cmd = queue.pop(0) if queue else {}
        return self.json({"ok": True, "bound": bound, **cmd})


def ensure_views(hass: HomeAssistant) -> None:
    """Регистрирует HTTP-эндпоинты приложения.

    Вызывается и из async_setup, и из config flow: до появления первой
    записи домен интеграции HA не загружает, а приложение должно иметь
    возможность зарегистрироваться ДО создания записи (по коду).
    """
    st = store(hass)
    if st.get("views"):
        return
    hass.http.register_view(RegisterView())
    hass.http.register_view(PollView())
    st["views"] = True


def _register_services(hass: HomeAssistant) -> None:
    if hass.services.has_service(DOMAIN, "show"):
        return

    async def async_show(call: ServiceCall) -> None:
        entities = store(hass)["entities"]
        for eid in call.data[CONF_TARGET]:
            ent = entities.get(eid)
            if ent is None:
                _LOGGER.warning("mynotify: неизвестная цель %s", eid)
                continue
            await ent.async_show_camera(
                call.data[CONF_STREAM], call.data.get(CONF_AUTOHIDE, 0)
            )

    async def async_hide(call: ServiceCall) -> None:
        entities = store(hass)["entities"]
        for eid in call.data[CONF_TARGET]:
            ent = entities.get(eid)
            if ent is not None:
                await ent.async_hide_window()

    hass.services.async_register(DOMAIN, "show", async_show, schema=SCHEMA_SHOW)
    hass.services.async_register(DOMAIN, "hide", async_hide, schema=SCHEMA_HIDE)


async def async_setup(hass: HomeAssistant, config: dict) -> bool:
    store(hass)
    ensure_views(hass)
    _register_services(hass)
    return True


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    if "device_id" not in entry.data:
        _LOGGER.error(
            "mynotify: устаревшая запись конфигурации (до v0.6) — "
            "удали интеграцию и добавь заново с кодом из приложения"
        )
        return False
    store(hass)["entries"][entry.entry_id] = entry
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    unloaded = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    store(hass)["entries"].pop(entry.entry_id, None)
    store(hass).get("devices", {}).pop(entry.entry_id, None)
    return unloaded
