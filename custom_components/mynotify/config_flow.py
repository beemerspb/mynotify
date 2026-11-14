"""Config flow для MyNotify: только адрес go2rtc. Всё остальное — сущностями."""
from __future__ import annotations

import voluptuous as vol

from homeassistant import config_entries

from . import CONF_GO2RTC_URL, DEFAULT_GO2RTC, DOMAIN


class MyNotifyConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    """Один шаг: адрес go2rtc."""

    VERSION = 1

    async def async_step_user(self, user_input=None):
        if user_input is not None:
            url = user_input[CONF_GO2RTC_URL].strip().rstrip("/")
            return self.async_create_entry(title="MyNotify", data={CONF_GO2RTC_URL: url})
        return self.async_show_form(
            step_id="user",
            data_schema=vol.Schema(
                {vol.Required(CONF_GO2RTC_URL, default=DEFAULT_GO2RTC): str}
            ),
        )
