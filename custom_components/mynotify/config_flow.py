"""Config flow MyNotify: адрес go2rtc + код привязки устройства.

Устройство привязывается кодом, который показывает приложение MyNotify
на планшете/ТВ (служба должна быть запущена). Один планшет = одна запись.
"""
from __future__ import annotations

import voluptuous as vol

from homeassistant import config_entries
from homeassistant.core import callback

from . import (
    CONF_GO2RTC_URL,
    DEFAULT_GO2RTC,
    DOMAIN,
    OPT_MUTE,
    ensure_views,
    store,
)


class MyNotifyOptionsFlow(config_entries.OptionsFlow):
    """Настройки устройства: звук по умолчанию."""

    def __init__(self, entry: config_entries.ConfigEntry) -> None:
        self._entry = entry

    async def async_step_init(self, user_input=None):
        if user_input is not None:
            return self.async_create_entry(title="", data=user_input)
        cur = (self._entry.options or {}).get(OPT_MUTE, True)
        return self.async_show_form(
            step_id="init",
            data_schema=vol.Schema(
                {vol.Required(OPT_MUTE, default=cur): bool}
            ),
        )


class MyNotifyConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    VERSION = 1
    supports_reconfigure_flow = True

    @staticmethod
    @callback
    def async_get_options_flow(
        config_entry: config_entries.ConfigEntry,
    ) -> MyNotifyOptionsFlow:
        return MyNotifyOptionsFlow(config_entry)

    def __init__(self) -> None:
        self._go2rtc = DEFAULT_GO2RTC

    async def async_step_user(self, user_input=None):
        # эндпоинты приложения нужны уже на шаге кода — до создания записи
        ensure_views(self.hass)
        if user_input is not None:
            self._go2rtc = user_input[CONF_GO2RTC_URL].strip().rstrip("/")
            return await self.async_step_device()
        return self.async_show_form(
            step_id="user",
            data_schema=vol.Schema(
                {vol.Required(CONF_GO2RTC_URL, default=DEFAULT_GO2RTC): str}
            ),
        )

    async def async_step_device(self, user_input=None):
        errors = {}
        if user_input is not None:
            code = user_input["code"].strip()
            st = store(self.hass)
            pending = st["pending"].get(code)
            if not pending:
                errors["base"] = "code_not_found"
            else:
                device_id = pending["device_id"]
                for e in self._async_current_entries():
                    if e.data.get("device_id") == device_id:
                        errors["base"] = "already_configured"
                        break
                if not errors:
                    name = pending["name"]
                    return self.async_create_entry(
                        title=f"MyNotify: {name}",
                        data={
                            CONF_GO2RTC_URL: self._go2rtc,
                            "device_id": device_id,
                            "device_code": code,
                            "device_name": name,
                        },
                    )
        return self.async_show_form(
            step_id="device",
            data_schema=vol.Schema({vol.Required("code"): str}),
            errors=errors,
        )

    async def async_step_reconfigure(self, user_input=None):
        entry = self.hass.config_entries.async_get_entry(self.context["entry_id"])
        errors = {}
        if user_input is not None:
            url = user_input[CONF_GO2RTC_URL].strip().rstrip("/")
            if not url.startswith(("http://", "https://")):
                errors["base"] = "bad_url"
            else:
                self.hass.config_entries.async_update_entry(
                    entry, data={**entry.data, CONF_GO2RTC_URL: url}
                )
                self.hass.config_entries.async_schedule_reload(entry.entry_id)
                return self.async_abort(reason="reconfigure_successful")
        return self.async_show_form(
            step_id="reconfigure",
            data_schema=vol.Schema(
                {vol.Required(CONF_GO2RTC_URL, default=entry.data[CONF_GO2RTC_URL]): str}
            ),
            errors=errors,
        )
