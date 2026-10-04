package com.castbridge;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Finds AirPlay receivers on the LAN via mDNS (`_airplay._tcp`). */
public class AirplayDiscovery {
    public interface Listener {
        void onDevice(String name, String host);
        void onError(String message);
    }

    private final NsdManager nsd;
    private final Listener listener;
    private final Set<String> emitted = new HashSet<>();
    // API 34+ resolve path: every registration must be unregistered again,
    // NSD keeps the callback (and its socket traffic) alive otherwise.
    private final List<NsdManager.ServiceInfoCallback> callbacks = new ArrayList<>();
    private NsdManager.DiscoveryListener discovery;
    private volatile boolean stopped = false;

    public AirplayDiscovery(Context ctx, Listener listener) {
        this.nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
    }

    public void start() {
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) {}

            @Override public void onServiceFound(NsdServiceInfo info) {
                if (info.getServiceType() == null
                        || !info.getServiceType().contains("_airplay")) return;
                resolve(info);
            }

            @Override public void onServiceLost(NsdServiceInfo info) {}

            @Override public void onDiscoveryStopped(String type) {}

            @Override public void onStartDiscoveryFailed(String type, int err) {
                listener.onError("discovery start failed (" + err + ")");
            }

            @Override public void onStopDiscoveryFailed(String type, int err) {}
        };
        nsd.discoverServices("_airplay._tcp", NsdManager.PROTOCOL_DNS_SD, discovery);
    }

    private void resolve(NsdServiceInfo info) {
        if (Build.VERSION.SDK_INT >= 34) {
            NsdManager.ServiceInfoCallback cb = new NsdManager.ServiceInfoCallback() {
                @Override public void onServiceInfoCallbackRegistrationFailed(int err) {}
                @Override public void onServiceUpdated(NsdServiceInfo updated) {
                    emit(updated);
                }
                @Override public void onServiceLost() {}
                @Override public void onServiceInfoCallbackUnregistered() {}
            };
            synchronized (callbacks) {
                if (stopped) return; // stop() ran while we were setting up
                callbacks.add(cb);
            }
            nsd.registerServiceInfoCallback(info, Runnable::run, cb);
        } else {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo si, int err) {}
                @Override public void onServiceResolved(NsdServiceInfo si) { emit(si); }
            });
        }
    }

    private void emit(NsdServiceInfo si) {
        InetAddress addr = si.getHost();
        if (addr == null) return;
        boolean isNew;
        synchronized (emitted) {
            isNew = emitted.add(si.getServiceName());
        }
        if (isNew && !stopped) {
            listener.onDevice(si.getServiceName(), addr.getHostAddress());
        }
    }

    public void stop() {
        synchronized (callbacks) {
            stopped = true;
            for (NsdManager.ServiceInfoCallback cb : callbacks) {
                try {
                    nsd.unregisterServiceInfoCallback(cb);
                } catch (Exception ignored) {}
            }
            callbacks.clear();
        }
        try {
            if (discovery != null) nsd.stopServiceDiscovery(discovery);
        } catch (Exception ignored) {}
        discovery = null;
    }
}
