package com.fracturedhardcore.hcheart.gametest;

import java.util.Objects;

import com.fracturedhardcore.hcheart.HcHeartMod;
import com.fracturedhardcore.hcheart.Services;
import com.fracturedhardcore.hcheart.downed.DownedManager;
import com.fracturedhardcore.hcheart.downed.ReviveManager;
import com.fracturedhardcore.hcheart.state.HeartStateService;

final class Hc {
	private Hc() {}

	static Services services() { return Objects.requireNonNull(HcHeartMod.services(), "mod services not initialised"); }
	static HeartStateService state() { return services().state(); }
	static DownedManager downed() { return services().downed(); }
	static ReviveManager revive() { return services().revive(); }
}
