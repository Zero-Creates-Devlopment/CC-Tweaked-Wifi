package net.mcreator.wifi;

import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

final class WifiRouterNetwork {
	interface Router {
		String getWanIP();
		Map<String, String> localDomains();
		boolean deliverWan(Map<String, Object> packet);
	}

	record Route(String wanIP, String lanIP) {}

	private static final Map<MinecraftServer, WifiRouterNetwork> NETWORKS = new WeakHashMap<>();
	private final Map<String, Router> routers = new LinkedHashMap<>();

	static synchronized WifiRouterNetwork forServer(MinecraftServer server) {
		return NETWORKS.computeIfAbsent(server, ignored -> new WifiRouterNetwork());
	}

	static synchronized void stopServer(MinecraftServer server) {
		NETWORKS.remove(server);
	}

	void register(Router router) {
		routers.put(router.getWanIP(), router);
	}

	void unregister(Router router) {
		routers.remove(router.getWanIP(), router);
	}

	boolean hasRouter(String wanIP) {
		return routers.containsKey(wanIP);
	}

	boolean hasOtherClaim(String domain, String ownerWan) {
		return routers.values().stream().anyMatch(router -> !router.getWanIP().equals(ownerWan)
				&& router.localDomains().containsKey(domain));
	}

	@Nullable
	Route resolve(String domain) {
		Route route = null;
		int claims = 0;
		for (Router router : routers.values()) {
			String address = router.localDomains().get(domain);
			if (address != null) {
				claims++;
				if (!address.isEmpty()) {
					route = new Route(router.getWanIP(), address);
				}
			}
		}
		return claims == 1 ? route : null;
	}

	ArrayList<Map<String, Object>> records() {
		ArrayList<Map<String, Object>> records = new ArrayList<>();
		for (Router router : routers.values()) {
			for (Map.Entry<String, String> domain : router.localDomains().entrySet()) {
				Map<String, Object> record = new LinkedHashMap<>();
				record.put("domain", domain.getKey());
				record.put("lanIP", domain.getValue());
				record.put("wanIP", router.getWanIP());
				record.put("online", !domain.getValue().isEmpty());
				records.add(record);
			}
		}
		return records;
	}

	boolean deliver(String destinationWan, Map<String, Object> packet) {
		Router router = routers.get(destinationWan);
		return router != null && router.deliverWan(packet);
	}
}
