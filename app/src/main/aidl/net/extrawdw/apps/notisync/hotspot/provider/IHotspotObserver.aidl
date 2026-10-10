package net.extrawdw.apps.notisync.hotspot.provider;

/** Invalidation only; credentials never travel through an unsolicited Binder callback. */
oneway interface IHotspotObserver {
    void onChanged();
}
