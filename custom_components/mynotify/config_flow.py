"""Config flow MyNotify: адрес go2rtc + код привязки устройства.

Устройство привязывается кодом, который показывает приложение MyNotify
на планшете/ТВ (служба должна быть запущена). Один планшет = одна запись.
"""
from __future__ import annotations

import voluptuous as vol

from homeassistant import config_entries

from . import CONF_GO2RTC_URL, DEFAULT_GO2RTC, DOMAIN, ensure_views, store


class MyNotifyConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    VERSION = 1

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
