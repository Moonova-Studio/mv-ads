package com.ads.control.admob;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class NavigationTracker {

    private static volatile String currentRoute;

    private static final Set<String> blockedRoutes =
            Collections.synchronizedSet(new HashSet<>());

    private NavigationTracker() {
    }

    public static void setCurrentRoute(String route) {
        currentRoute = route;
    }

    public static String getCurrentRoute() {
        return currentRoute;
    }

    public static void addBlockedRoute(String route) {
        if (route != null) {
            blockedRoutes.add(route);
        }
    }

    public static void addBlockedRoutes(String... routes) {
        if (routes != null) {
            blockedRoutes.addAll(Arrays.asList(routes));
        }
    }

    public static void removeBlockedRoute(String route) {
        blockedRoutes.remove(route);
    }

    public static void clearBlockedRoutes() {
        blockedRoutes.clear();
    }

    public static boolean canShowAppOpen() {
        return currentRoute == null
                || !blockedRoutes.contains(currentRoute);
    }
}