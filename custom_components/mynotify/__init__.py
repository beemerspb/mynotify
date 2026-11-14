"""MyNotify — показ камеры оверлей-окном на Android-устройствах.

Устройство (планшет/ТВ с приложением MyNotify) ПОДКЛЮЧАЕТСЯ К ИНТЕГРАЦИИ
САМО: фоновая служба в приложении регистрируется по 6-значному коду и
опрашивает очередь команд. Никакого ADB/androidtv_remote не нужно.

Каждое устройство = одна запись конфигурации и один блок устройств
(HA-устройство) с сущностями: выключатель показа окна, список камер.

Сервисы mynotify.show / mynotify.hide — для автоматизаций.

Если устройство вне домашней сети, оно обращается к HA по внешнему
адресу; поток для него проксируется через HA (/api/mynotify/stream) —
наружу не отдаётся ничего, кроме того, что видно в самом HA.
"""
from __future__ import annotations

import logging
import time
from urllib.parse import quote, urljoin

import voluptuous as vol
from aiohttp import web

from homeassistant.components.http import HomeAssistantView
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, ServiceCall
import homeassistant.helpers.config_validation as cv
from homeassistant.helpers.aiohttp_client import async_get_clientsession

_LOGGER = logging.getLogger(__name__)

DOMAIN = "mynotify"
PLATFORMS = ["switch", "select"]

CONF_GO2RTC_URL = "go2rtc_url"
CONF_TARGET = "target"
CONF_STREAM = "stream"
CONF_AUTOHIDE = "autohide"

OPT_STREAM = "stream"
OPT_MUTE = "mute"
CONF_MUTE = "mute"

DEFAULT_GO2RTC = "http://homeassistant.local:1984"

STALE_SEC = 30  # устройство считается offline, если не опрашивалось дольше

SCHEMA_SHOW = vol.Schema(
    {
        vol.Required(CONF_TARGET): cv.entity_ids,
        vol.Required(CONF_STREAM): cv.string,
        vol.Optional(CONF_AUTOHIDE, default=0): vol.All(
            vol.Coerce(int), vol.Range(min=0, max=3600)
        ),
        vol.Optional(CONF_MUTE): cv.boolean,
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
    """Имя потока go2rtc -> HLS URL; полный http(s)-URL проходит как есть.

    video=all&audio=aac включает в go2rtc fMP4-ветку HLS: TS-ветка (без
    фильтров) теряет AAC-пакеты (0-1 на сегмент) — звук прерывался;
    fMP4+audio=aac отдаёт ровные 21.3 мс без дыр (замерено ffprobe).
    """
    if stream.startswith(("http://", "https://")):
        return stream
    return f"{base_url(hass)}/api/stream.m3u8?src={stream}&video=all&audio=aac"


def _proxied(abs_url: str, device: str, code: str) -> str:
    """Ссылка на ресурс go2rtc через прокси HA (относительная — работает
    и из локальной сети, и по внешнему адресу HA)."""
    return (
        "/api/mynotify/stream"
        f"?device={quote(device, safe='')}"
        f"&code={quote(code, safe='')}"
        f"&u={quote(abs_url, safe='')}"
    )


def stream_path(hass: HomeAssistant, device_id: str, code: str, stream: str) -> str:
    """Относительный путь прокси для HLS-манифеста потока (пусто, если
    stream — уже готовый внешний URL: его проксировать нельзя)."""
    if stream.startswith(("http://", "https://")):
        return ""
    return _proxied(build_url(hass, stream), device_id, code)


def rewrite_manifest(
    text: str, manifest_url: str, device: str, code: str, allowed_prefix: str
) -> str:
    """Переписывает ссылки манифеста HLS на прокси HA.

    allowed_prefix — базовый URL локального go2rtc: проксируются только
    ресурсы из него (защита от SSRF), остальное остаётся как есть.
    """
    out = []
    for line in text.splitlines():
        s = line.strip()
        if s and not s.startswith("#"):
            absu = urljoin(manifest_url, s)
            out.append(
                _proxied(absu, device, code)
                if absu.startswith(allowed_prefix + "/")
                else line
            )
        elif s.startswith('#EXT-X-MAP:URI="'):
            head, _, rest = s.partition('"')
            uri, _, tail = rest.partition('"')
            absu = urljoin(manifest_url, uri)
            out.append(
                f'{head}"{_proxied(absu, device, code)}"{tail}'
                if absu.startswith(allowed_prefix + "/")
                else line
            )
        else:
            out.append(line)
    return "\n".join(out) + "\n"


def send_command(hass: HomeAssistant, device_id: str, cmd: dict) -> None:
    queue = store(hass)["queues"].setdefault(device_id, [])
    now = time.time()
    cmd = {**cmd, "ts": now}
    # Окну имеет смысл только ПОСЛЕДНЯЯ команда показа/скрытия и только свежая:
    # планшет мог спать и проснуться через N минут — старые камеры доедать
    # уже не надо, иначе окно показывает устаревший выбор пользователя.
    queue[:] = [
        c for c in queue
        if c.get("cmd") not in ("show", "hide") and now - c.get("ts", 0) < 120
    ]
    queue.append(cmd)


def is_online(hass: HomeAssistant, device_id: str) -> bool:
    return store(hass)["seen"].get(device_id, 0) > time.time() - STALE_SEC


def resolve_mute(entry_option: bool | None, service_value: bool | None) -> bool:
    """Итоговый mute для команды: явный параметр службы важнее настройки
    устройства; если и там пусто — без звука по умолчанию."""
    if service_value is not None:
        return bool(service_value)
    if entry_option is not None:
        return bool(entry_option)
    return True


def apply_state_report(st: dict, device_id: str, code: str, state: str) -> bool:
    """Синхронизация состояния окна по репорту устройства (параметр poll).

    Приложение шлёт фактическое состояние окна (1/0) в каждом опросе —
    так переключение/закрытие окна на самом устройстве (кнопка «X»,
    автоскрытие, жест) отражается в switch-сущности HA.
    """
    for e in st["entries"].values():
        if e.data.get("device_id") == device_id and e.data.get("device_code") == code:
            dev = st.get("devices", {}).get(e.entry_id)
            if dev is None:
                return False
            new_on = state == "1"
            if dev.on != new_on:
                dev.on = new_on
                dev.notify()
            return True
    return False


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
        ver = str(data.get("ver", ""))
        if len(code) != 6 or not device_id:
            return self.json({"ok": False, "error": "bad_request"})
        st = store(hass)
        # версия приложения на устройстве — меняться редко, логируем смену
        app_ver = st.setdefault("app_ver", {})
        if ver and app_ver.get(device_id) != ver:
            _LOGGER.info("mynotify: устройство %s, приложение v%s", name, ver)
        app_ver[device_id] = ver
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
        rep = request.query.get("state")
        if rep is not None:
            apply_state_report(st, device_id, code, rep)
        queue = st["queues"].get(device_id) or []
        cmd = {}
        while queue:
            c = queue.pop(0)
            # просроченные show/hide не отдаём: планшет спал — показывать
            # камеру, выбранную полчаса назад, уже не надо
            if c.get("cmd") not in ("show", "hide") or time.time() - c.get("ts", 0) < 120:
                cmd = c
                break
        return self.json({"ok": True, "bound": bound, **cmd})


class StreamView(HomeAssistantView):
    """GET /api/mynotify/stream — прокси go2rtc через HA.

    Устройство вне домашней сети видит только HA, поэтому HLS-сегменты
    идут тем же каналом, что и API. Доступ — по той же паре device+code,
    что и для poll; наружу проксируются только URL локального go2rtc.
    """

    url = "/api/mynotify/stream"
    url_path = "/api/mynotify/stream"
    name = "api:mynotify:stream"
    requires_auth = False

    async def get(self, request: web.Request) -> web.StreamResponse:
        hass = request.app["hass"]
        device = request.query.get("device", "")
        code = request.query.get("code", "")
        st = store(hass)
        if not any(
            e.data.get("device_id") == device and e.data.get("device_code") == code
            for e in st["entries"].values()
        ):
            return web.json_response({"ok": False, "error": "unauthorized"}, status=401)
        u = request.query.get("u", "")
        base = base_url(hass)
        if not u.startswith(base + "/"):
            return web.json_response({"ok": False, "error": "forbidden"}, status=403)
        headers = {}
        if "Range" in request.headers:
            headers["Range"] = request.headers["Range"]
        session = async_get_clientsession(hass)
        try:
            async with session.get(u, headers=headers) as up:
                ctype = up.headers.get("Content-Type", "")
                if "mpegurl" in ctype or u.split("?", 1)[0].endswith(".m3u8"):
                    text = await up.text()
                    body = rewrite_manifest(text, u, device, code, base)
                    # Content-Type без charset: ExoPlayer не распознаёт HLS,
                    # если у MIME есть параметр "; charset=..."
                    return web.Response(
                        body=body.encode("utf-8"),
                        headers={"Content-Type": "application/vnd.apple.mpegurl"},
                        status=up.status,
                    )
                resp_headers = {"Accept-Ranges": "bytes"}
                for h in ("Content-Type", "Content-Length", "Content-Range"):
                    if h in up.headers:
                        resp_headers[h] = up.headers[h]
                resp = web.StreamResponse(status=up.status, headers=resp_headers)
                await resp.prepare(request)
                async for chunk in up.content.iter_any():
                    await resp.write(chunk)
                await resp.write_eof()
                return resp
        except Exception as err:  # noqa: BLE001 — клиент ушёл/go2rtc лёг
            _LOGGER.debug("mynotify: стрим-прокси %s: %s", u, err)
            return web.json_response({"ok": False, "error": "upstream"}, status=502)


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
    hass.http.register_view(StreamView())
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
                call.data[CONF_STREAM],
                call.data.get(CONF_AUTOHIDE, 0),
                call.data.get(CONF_MUTE),
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
