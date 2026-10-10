package net.extrawdw.apps.notisync.hotspot.provider;

import android.os.Bundle;
import net.extrawdw.apps.notisync.hotspot.provider.IHotspotObserver;

/** Local app-to-shell boundary. No network identities, command strings, or configuration setters. */
interface IHotspotUserService {
    void destroy() = 16777114;
    Bundle query() = 1;
    Bundle setEnabled(boolean enabled) = 2;
    void observe(IHotspotObserver observer) = 3;
}
